package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javaassistant.ai.ConfiguredChatModel;
import com.javaassistant.ai.OCIChatModelProvider;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.report.ReportBundleService;
import com.javaassistant.testsupport.GeneratedScenarioRegistry;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import com.javaassistant.testsupport.ReferenceIncidentBundle;
import com.javaassistant.testsupport.ReferenceIncidentBundleLoader;
import com.javaassistant.testsupport.ScenarioCatalogSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Tag("oci-live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfSystemProperty(named = "jtroubleshoot.liveOci", matches = "true")
class OciReferenceIncidentBundleLiveTest {

    private static final DateTimeFormatter RUN_DIRECTORY_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final String LIVE_OCI_PROBE_ARTIFACT = "generated-scenario:control-healthy-g1-baseline:gc";

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GeneratedScenarioRegistry generatedScenarioRegistry = GeneratedScenarioRegistry.defaultRegistry();
    private final ReferenceIncidentBundleLoader bundleLoader = new ReferenceIncidentBundleLoader();
    private final ScenarioCatalogSupport.ScenarioCatalog scenarioCatalog = ScenarioCatalogSupport.loadDefaultCatalog();
    private final List<ScenarioRunSummary> summaries = new ArrayList<>();

    private ConfiguredChatModel configuredChatModel;
    private Path runDirectory;

    @TestFactory
    Stream<DynamicTest> referenceIncidentBundlesPreserveScenarioIntentWithLiveOciProvider() throws Exception {
        initializeIfNeeded();
        return bundleLoader.loadBundles().stream()
            .map(bundle -> DynamicTest.dynamicTest(bundle.bundleId(), () -> evaluateBundle(bundle)));
    }

    @AfterAll
    void writeRunSummaryAndCloseModel() throws Exception {
        try {
            if (runDirectory != null) {
                writeSummary();
            }
        } finally {
            if (configuredChatModel != null) {
                configuredChatModel.closeQuietly();
            }
        }
    }

    private void initializeIfNeeded() throws Exception {
        if (configuredChatModel != null) {
            return;
        }

        assumeTrue(
            OCIChatModelProvider.INSTANCE.setupStatus(null).ready(),
            "OCI provider is not ready. Run ./bin/jtroubleshoot --provider oci status to inspect setup."
        );

        configuredChatModel = OCIChatModelProvider.INSTANCE.createChatModel(null);
        assertLiveOciRuntimeReady();
        runDirectory = Path.of("target", "analysis-reports", "oci-reference-bundles", RUN_DIRECTORY_FORMAT.format(LocalDateTime.now()));
        Files.createDirectories(runDirectory);
    }

    private void assertLiveOciRuntimeReady() {
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(configuredChatModel);
        AnalysisReport probeReport = orchestrator.analyze(loadArtifact(LIVE_OCI_PROBE_ARTIFACT));
        String invocationFailureDetail = responseFailureDetail(probeReport);
        assumeTrue(
            !looksLikeProviderAccessFailure(invocationFailureDetail),
            "OCI provider passed static setup but rejected the live probe request: "
                + invocationFailureDetail
                + ". Verify OCI auth, profile, and compartment access before rerunning."
        );
    }

    private void evaluateBundle(ReferenceIncidentBundle bundle) throws Exception {
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(configuredChatModel);
        AnalysisReport report = switch (bundle.workflowType()) {
            case COMPARE -> orchestrator.compare(loadArtifact(bundle.artifactPaths().get(0)), loadArtifact(bundle.artifactPaths().get(1)));
            case SEQUENCE -> orchestrator.sequence(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case CORRELATE -> orchestrator.correlate(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case SINGLE_ARTIFACT -> orchestrator.analyze(loadArtifact(bundle.artifactPaths().getFirst()));
        };

        Path savedReportDirectory = new ReportBundleService(runDirectory.resolve(bundle.bundleId())).save(report);
        recordSummary(bundle, report, savedReportDirectory);

        assertNotNull(report.supervisorTrace(), "live OCI runs should preserve supervisor trace data");
        assertEquals(bundle.workflowType(), report.supervisorTrace().workflowType(), "workflow type should round-trip into the report");
        assertTrue(
            report.supervisorTrace().steps().size() >= bundle.minSupervisorTraceSteps(),
            "supervisor trace should include the expected minimum number of steps"
        );

        boolean hasAiNarrative = report.hasAiAgentBackedUserNarrative();
        if (hasAiNarrative) {
            assertNotNull(report.userNarrative(), "live OCI runs should preserve a user narrative");
            assertFalse(report.userNarrative().isBlank(), "live OCI runs should preserve a non-empty user narrative");
            assertHasTroubleshootingSections(report.userNarrative());
            assertScenarioSpecificFraming(bundle.primaryScenarioId(), report);
            assertEquals(
                1,
                report.supervisorTrace().steps().stream().filter(SupervisorTraceStep::selectedForUserNarrative).count(),
                "exactly one supervisor trace step should be marked as the user-facing selection"
            );

            AgentTraceability selectedTraceability = report.agentTraceability().stream()
                .filter(AgentTraceability::selectedForUserNarrative)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a selected narrative traceability entry"));
            assertHasModelExecutionTraceability(selectedTraceability);
            assertEquals(bundle.expectedSelectedAgent(), selectedTraceability.agentName(), "selected narrative agent changed");
            assertEquals(bundle.expectedSelectedSource(), selectedTraceability.narrativeSource(), "selected narrative source changed");
            assertTrue(
                selectedTraceability.qualityGates().stream().noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED),
                "selected narrative should not fail blocking quality gates"
            );
            assertDeterministicSignalOverlap(bundle, selectedTraceability);

            SupervisorTraceStep selectedTraceStep = report.supervisorTrace().steps().stream()
                .filter(SupervisorTraceStep::selectedForUserNarrative)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a selected supervisor trace step"));
            assertNotNull(selectedTraceStep.modelExecutionTraceability(), "selected supervisor trace step must carry model execution traceability");
            assertEquals(
                selectedTraceability.modelExecutionTraceability().templateId(),
                selectedTraceStep.modelExecutionTraceability().templateId(),
                "selected supervisor trace step must align with selected narrative template traceability"
            );
        } else {
            assertAcceptableDeterministicFallback(bundle, report);
        }

        for (SupervisorTraceStepType requiredType : bundle.requiredStepTypes()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> step.stepType() == requiredType),
                "missing required supervisor step type: " + requiredType
            );
        }
        for (String requiredStepId : bundle.requiredStepIds()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> requiredStepId.equals(step.stepId())),
                "missing required supervisor step id: " + requiredStepId
            );
        }
        for (String requiredAgent : bundle.requiredTraceabilityAgents()) {
            assertTrue(
                report.agentTraceability().stream().anyMatch(traceability -> requiredAgent.equals(traceability.agentName())),
                "missing required traceability agent: " + requiredAgent
            );
        }
        if (hasAiNarrative) {
            for (String requiredAgent : bundle.requiredTraceAgents()) {
                assertTrue(
                    report.supervisorTrace().steps().stream().anyMatch(step -> requiredAgent.equals(step.agentName())),
                    "missing required supervisor trace agent: " + requiredAgent
                );
            }
        }

        assertTrue(report.findings().size() >= bundle.minFindings(), "finding count dropped below the reference minimum");
        for (String expectedFindingId : bundle.expectedFindingIds()) {
            assertTrue(
                report.findings().stream().anyMatch(finding -> expectedFindingId.equals(finding.id())),
                "missing expected finding id: " + expectedFindingId
            );
        }
        assertTrue(
            report.findings().stream()
                .filter(this::isHighSeverity)
                .allMatch(finding -> !finding.evidenceIds().isEmpty()),
            "high-severity findings must stay supported by evidence"
        );
    }

    private void recordSummary(ReferenceIncidentBundle bundle, AnalysisReport report, Path savedReportDirectory) {
        AgentTraceability selectedTraceability = report.selectedNarrativeTraceability();
        ScenarioCatalogSupport.ScenarioCatalogEntry catalogEntry = scenarioCatalog.entriesById().get(bundle.primaryScenarioId());
        summaries.add(new ScenarioRunSummary(
            bundle.bundleId(),
            bundle.primaryScenarioId(),
            catalogEntry != null ? catalogEntry.title() : null,
            report.analysisId(),
            selectedTraceability != null ? selectedTraceability.agentName() : null,
            selectedTraceability != null ? selectedTraceability.narrativeSource().name() : null,
            report.analysisPathLabel(),
            report.incidentSummary(),
            report.overallSeverity(),
            report.confidence(),
            report.findings().stream().map(Finding::id).toList(),
            selectedTraceability != null ? gateStatus(selectedTraceability, "deterministic-signal-overlap") : null,
            savedReportDirectory
        ));
    }

    private void writeSummary() throws IOException {
        List<ScenarioRunSummary> orderedSummaries = summaries.stream()
            .sorted(Comparator.comparing(ScenarioRunSummary::bundleId))
            .toList();

        StringBuilder markdown = new StringBuilder();
        markdown.append("# OCI Reference Scenario Summary\n\n");
        markdown.append("- Run directory: ").append(runDirectory).append('\n');
        markdown.append("- Provider: ").append(configuredChatModel.providerId()).append('\n');
        markdown.append("- Model: ").append(configuredChatModel.modelName()).append("\n\n");

        for (ScenarioRunSummary summary : orderedSummaries) {
            markdown.append("## ").append(summary.bundleId()).append('\n');
            markdown.append("- Scenario: ").append(summary.primaryScenarioId());
            if (summary.scenarioTitle() != null && !summary.scenarioTitle().isBlank()) {
                markdown.append(" - ").append(summary.scenarioTitle());
            }
            markdown.append('\n');
            String selectedPath = summary.selectedAgent() != null
                ? summary.selectedAgent() + " / " + summary.selectedSource()
                : summary.analysisPathLabel();
            markdown.append("- Selected path: ").append(selectedPath).append('\n');
            markdown.append("- Incident summary: ").append(summary.incidentSummary()).append('\n');
            markdown.append("- Severity / confidence: ").append(summary.overallSeverity()).append(" / ").append(summary.confidence()).append('\n');
            markdown.append("- Findings: ").append(String.join(", ", summary.findingIds())).append('\n');
            markdown.append("- Deterministic signal overlap: ").append(summary.deterministicSignalGateStatus()).append('\n');
            markdown.append("- Report: ").append(summary.savedReportDirectory()).append("\n\n");
        }

        Files.writeString(runDirectory.resolve("summary.md"), markdown.toString());
    }

    private void assertDeterministicSignalOverlap(ReferenceIncidentBundle bundle, AgentTraceability selectedTraceability) {
        if (bundle.minFindings() <= 0 && bundle.expectedFindingIds().isEmpty()) {
            return;
        }

        AgentQualityGateStatus status = gateStatus(selectedTraceability, "deterministic-signal-overlap");
        assertTrue(
            status == AgentQualityGateStatus.PASSED || status == AgentQualityGateStatus.WARNING,
            "selected narrative should preserve deterministic-signal coverage without a hard failure"
        );
    }

    private AgentQualityGateStatus gateStatus(AgentTraceability traceability, String gateId) {
        return traceability.qualityGates().stream()
            .filter(result -> gateId.equals(result.gateId()))
            .map(AgentQualityGateResult::status)
            .findFirst()
            .orElse(null);
    }

    private String responseFailureDetail(AnalysisReport report) {
        return report.agentTraceability().stream()
            .flatMap(traceability -> traceability.qualityGates().stream())
            .filter(result -> "response-not-empty".equals(result.gateId()))
            .filter(result -> result.status() == AgentQualityGateStatus.FAILED)
            .map(AgentQualityGateResult::detail)
            .filter(this::hasText)
            .findFirst()
            .orElse(null);
    }

    private boolean looksLikeProviderAccessFailure(String detail) {
        String normalized = normalize(detail);
        return normalized.contains("authentication")
            || normalized.contains("unauthorized")
            || normalized.contains("invalid_authentication_info")
            || normalized.contains("not authorized")
            || normalized.contains("forbidden")
            || normalized.contains("permission")
            || normalized.contains("401")
            || normalized.contains("403");
    }

    private boolean isHighSeverity(Finding finding) {
        return finding.severity() == SeverityLevel.HIGH || finding.severity() == SeverityLevel.CRITICAL;
    }

    private void assertHasTroubleshootingSections(String narrative) {
        for (String sectionLabel : List.of("Summary:", "Key metrics:", "Likely issues:", "Recommended actions:")) {
            assertTrue(
                narrative.contains(sectionLabel),
                "accepted live OCI narratives must preserve the troubleshooting section " + sectionLabel
            );
        }
    }

    private void assertHasModelExecutionTraceability(AgentTraceability traceability) {
        assertNotNull(traceability.modelExecutionTraceability(), "selected narrative must carry model execution traceability");
        assertTrue(hasText(traceability.modelExecutionTraceability().providerId()), "selected narrative provider traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelName()), "selected narrative model traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelFamily()), "selected narrative model-family traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateId()), "selected narrative template traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateVersion()), "selected narrative template version missing");
    }

    private void assertAcceptableDeterministicFallback(ReferenceIncidentBundle bundle, AnalysisReport report) {
        assertTrue(report.userNarrative() == null || report.userNarrative().isBlank(), "rejected live narratives should not leave a selected user narrative");
        assertEquals(
            0,
            report.supervisorTrace().steps().stream().filter(SupervisorTraceStep::selectedForUserNarrative).count(),
            "no supervisor trace step should be selected when the live narrative is rejected"
        );

        List<String> failedGateIds = report.agentTraceability().stream()
            .flatMap(traceability -> traceability.qualityGates().stream())
            .filter(result -> result.status() == AgentQualityGateStatus.FAILED)
            .map(AgentQualityGateResult::gateId)
            .distinct()
            .toList();
        assertTrue(
            !failedGateIds.isEmpty() && failedGateIds.stream().allMatch("troubleshooting-response-structure"::equals),
            "live deterministic fallback should only happen for structure-only narrative rejections"
        );
        assertFalse(report.recommendedActions().isEmpty(), "deterministic fallback should still preserve recommended actions");
        assertTrue(report.findings().size() >= bundle.minFindings(), "deterministic fallback should still preserve the core issue findings");
    }

    private void assertScenarioSpecificFraming(String scenarioId, AnalysisReport report) {
        String normalizedNarrative = normalize(report.incidentSummary() + "\n" + report.userNarrative());
        if (scenarioId.startsWith("control-healthy-")) {
            assertEquals(SeverityLevel.LOW, report.overallSeverity(), "healthy-control reports should stay low severity");
            assertTrue(
                containsAny(normalizedNarrative, List.of("healthy", "baseline", "no clear", "no obvious", "no active", "no significant", "stable")),
                "healthy-control narratives should stay baseline-oriented"
            );
        }
        if (scenarioId.startsWith("ambiguity-")) {
            assertEquals(SeverityLevel.LOW, report.overallSeverity(), "ambiguity reports should stay low severity");
            assertTrue(
                containsAny(
                    normalizedNarrative,
                    List.of(
                        "uncertain",
                        "uncertainty",
                        "limited",
                        "not enough",
                        "insufficient",
                        "single snapshot",
                        "time context",
                        "time-series",
                        "cannot tell",
                        "cannot determine",
                        "impossible to detect",
                        "different times",
                        "do not line up",
                        "do not overlap",
                        "same window",
                        "shared timing",
                        "baseline capture"
                    )
                ),
                "ambiguity narratives should call out uncertainty instead of overcommitting"
            );
        }
        if (scenarioId.contains("single-snapshot")) {
            assertTrue(
                containsAny(
                    normalizedNarrative,
                    List.of(
                        "second snapshot",
                        "nmt diff",
                        "repeated nmt",
                        "growth can be measured",
                        "single snapshot",
                        "time context",
                        "time-series",
                        "baseline capture"
                    )
                ),
                "single-snapshot ambiguity narratives should recommend time-based follow-up"
            );
        }
        if (scenarioId.contains("time-skewed")) {
            assertTrue(
                containsAny(
                    normalizedNarrative,
                    List.of("same symptom window", "same window", "time aligned", "aligned", "different times", "do not line up", "do not overlap")
                ),
                "time-skewed ambiguity narratives should highlight timing misalignment"
            );
        }
    }

    private boolean containsAny(String text, List<String> candidates) {
        return candidates.stream().anyMatch(text::contains);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private InputArtifact loadArtifact(String artifactPath) {
        try {
            return loader.load(generatedScenarioRegistry.resolveArtifactPath(artifactPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load reference incident artifact: " + artifactPath, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to materialize reference incident artifact: " + artifactPath, exception);
        }
    }

    private record ScenarioRunSummary(
        String bundleId,
        String primaryScenarioId,
        String scenarioTitle,
        String analysisId,
        String selectedAgent,
        String selectedSource,
        String analysisPathLabel,
        String incidentSummary,
        SeverityLevel overallSeverity,
        ConfidenceLevel confidence,
        List<String> findingIds,
        AgentQualityGateStatus deterministicSignalGateStatus,
        Path savedReportDirectory
    ) {
    }
}
