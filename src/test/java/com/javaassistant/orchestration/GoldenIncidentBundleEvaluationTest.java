package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import com.javaassistant.testsupport.RoutingStubChatModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class GoldenIncidentBundleEvaluationTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());

    @TestFactory
    Stream<DynamicTest> goldenIncidentBundlesPreserveAgentWorkflowExpectations() throws Exception {
        return loadBundles().stream()
            .map(bundle -> DynamicTest.dynamicTest(bundle.bundleId(), () -> evaluateBundle(bundle)));
    }

    private void evaluateBundle(GoldenIncidentBundle bundle) throws Exception {
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(new RoutingStubChatModel());
        AnalysisReport report = switch (bundle.workflowType()) {
            case COMPARE -> orchestrator.compare(loadArtifact(bundle.artifactPaths().get(0)), loadArtifact(bundle.artifactPaths().get(1)));
            case CORRELATE -> orchestrator.correlate(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case SINGLE_ARTIFACT -> orchestrator.analyze(loadArtifact(bundle.artifactPaths().getFirst()));
        };

        assertNotNull(report.supervisorTrace(), "golden bundles should persist supervisor trace data");
        assertEquals(bundle.workflowType(), report.supervisorTrace().workflowType(), "workflow type should round-trip into the report");
        assertTrue(
            report.supervisorTrace().steps().size() >= bundle.minSupervisorTraceSteps(),
            "supervisor trace should include the expected minimum number of steps"
        );
        if (bundle.expectUserNarrative()) {
            assertTrue(report.hasAiAgentBackedUserNarrative(), "golden bundles should preserve an AI-agent-backed user narrative");
            assertNotNull(report.userNarrative(), "golden bundles should preserve a user narrative");
            assertFalse(report.userNarrative().isBlank(), "golden bundles should preserve a non-empty user narrative");
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
            assertFalse(report.hasAiAgentBackedUserNarrative(), "bundles marked as retrieval-dependent should not preserve an accepted user narrative under the non-tooling stub");
            assertTrue(report.userNarrative() == null || report.userNarrative().isBlank(), "no user narrative should be accepted when the stub never expands bounded context");
            assertEquals(
                0,
                report.supervisorTrace().steps().stream().filter(SupervisorTraceStep::selectedForUserNarrative).count(),
                "no supervisor trace step should be marked as selected when the bounded-context gate rejects the narrative"
            );
            assertTrue(
                report.agentTraceability().stream().noneMatch(AgentTraceability::selectedForUserNarrative),
                "no traceability entry should be selected when the bounded-context gate rejects the narrative"
            );
            assertTrue(
                report.agentTraceability().stream().anyMatch(traceability ->
                    traceability.qualityGates().stream().anyMatch(result ->
                        result.gateId().equals("coverage-aware-confidence")
                            && result.status() == AgentQualityGateStatus.FAILED
                    )
                ),
                "retrieval-dependent bundles should fail the coverage-aware confidence gate under the non-tooling stub"
            );
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
        for (String requiredAgent : bundle.requiredTraceAgents()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> requiredAgent.equals(step.agentName())),
                "missing required supervisor trace agent: " + requiredAgent
            );
        }

        assertTrue(report.findings().size() >= bundle.minFindings(), "finding count dropped below the golden minimum");
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

    private boolean isHighSeverity(Finding finding) {
        return finding.severity() == SeverityLevel.HIGH || finding.severity() == SeverityLevel.CRITICAL;
    }

    private void assertHasModelExecutionTraceability(AgentTraceability traceability) {
        assertNotNull(traceability.modelExecutionTraceability(), "selected narrative must carry model execution traceability");
        assertTrue(hasText(traceability.modelExecutionTraceability().providerId()), "selected narrative provider traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelName()), "selected narrative model traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelFamily()), "selected narrative model-family traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateId()), "selected narrative template traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateVersion()), "selected narrative template version missing");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private com.javaassistant.diagnostics.InputArtifact loadArtifact(String artifactPath) {
        try {
            return loader.load(Path.of(artifactPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load golden incident artifact: " + artifactPath, exception);
        }
    }

    private List<GoldenIncidentBundle> loadBundles() throws IOException {
        Path root = Path.of("src/test/resources/golden-incidents");
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                .filter(Files::isDirectory)
                .sorted()
                .map(this::loadBundle)
                .toList();
        }
    }

    private GoldenIncidentBundle loadBundle(Path bundleDir) {
        Properties properties = new Properties();
        Path manifestPath = bundleDir.resolve("manifest.properties");
        try (InputStream inputStream = Files.newInputStream(manifestPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load golden incident manifest: " + manifestPath, exception);
        }

        return new GoldenIncidentBundle(
            bundleDir.getFileName().toString(),
            OrchestrationWorkflowType.valueOf(required(properties, "workflowType")),
            split(properties.getProperty("artifactPaths")),
            bool(properties.getProperty("expectUserNarrative"), true),
            required(properties, "expectedSelectedAgent"),
            AgentNarrativeSource.valueOf(required(properties, "expectedSelectedSource")),
            split(properties.getProperty("requiredTraceabilityAgents")),
            split(properties.getProperty("requiredTraceAgents")),
            split(properties.getProperty("requiredStepIds")),
            split(properties.getProperty("expectedFindingIds")),
            split(properties.getProperty("requiredStepTypes")).stream()
                .map(SupervisorTraceStepType::valueOf)
                .toList(),
            integer(properties.getProperty("minFindings"), 1),
            integer(properties.getProperty("minSupervisorTraceSteps"), 1)
        );
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required golden incident property: " + key);
        }
        return value.strip();
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private int integer(String value, int defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.strip());
    }

    private boolean bool(String value, boolean defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.strip());
    }

    private record GoldenIncidentBundle(
        String bundleId,
        OrchestrationWorkflowType workflowType,
        List<String> artifactPaths,
        boolean expectUserNarrative,
        String expectedSelectedAgent,
        AgentNarrativeSource expectedSelectedSource,
        List<String> requiredTraceabilityAgents,
        List<String> requiredTraceAgents,
        List<String> requiredStepIds,
        List<String> expectedFindingIds,
        List<SupervisorTraceStepType> requiredStepTypes,
        int minFindings,
        int minSupervisorTraceSteps
    ) { }
}
