package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.GeneratedScenarioRegistry;
import com.javaassistant.testsupport.ReferenceIncidentBundle;
import com.javaassistant.testsupport.ReferenceIncidentBundleLoader;
import com.javaassistant.testsupport.ReferenceIncidentChatModelFactory;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Tag;

@Tag("reference")
class ReferenceIncidentBundleEvaluationTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GeneratedScenarioRegistry generatedScenarioRegistry = GeneratedScenarioRegistry.defaultRegistry();
    private final ReferenceIncidentBundleLoader bundleLoader = new ReferenceIncidentBundleLoader();

    @TestFactory
    Stream<DynamicTest> referenceIncidentBundlesPreserveAgentWorkflowExpectations() throws Exception {
        return bundleLoader.loadBundles().stream()
            .map(bundle -> DynamicTest.dynamicTest(bundle.bundleId(), () -> evaluateBundle(bundle)));
    }

    @org.junit.jupiter.api.Test
    void generatedMetaspacePressureBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-metaspace-pressure-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedThreadGrowthBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-thread-growth-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedExecutorPoolStallAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-executor-pool-stall")));
    }

    @org.junit.jupiter.api.Test
    void generatedExecutorPoolStallCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-executor-pool-stall")));
    }

    @org.junit.jupiter.api.Test
    void generatedNativeThreadExhaustionBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-native-thread-exhaustion-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedCompressedClassSpaceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-compressed-class-space-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedClassLoadingMetaspaceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-classloading-metaspace-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedCodeCacheFullBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-code-cache-full-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedDirectBufferNativePressureBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-direct-buffer-native-pressure-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedDirectBufferNativeOomBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-direct-buffer-native-oom-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedInternalArenaAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-nmt-internal-arena-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedInternalArenaCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-nmt-internal-arena-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedInternalArenaSequenceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/sequence-generated-nmt-internal-arena-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedNativeMemoryGrowthNmtSequenceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/sequence-generated-nmt-native-memory-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedNativeMemoryGrowthPmapSequenceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/sequence-generated-pmap-native-memory-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedReservedCommittedNmtAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-nmt-reserved-committed-mismatch")));
    }

    @org.junit.jupiter.api.Test
    void generatedActiveNativeGrowthNmtAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-nmt-active-native-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedReservedCommittedNmtCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-nmt-reserved-committed-mismatch")));
    }

    @org.junit.jupiter.api.Test
    void generatedActiveNativeGrowthNmtCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-nmt-active-native-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedReservedCommittedPmapAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-pmap-reserved-committed-mismatch")));
    }

    @org.junit.jupiter.api.Test
    void generatedActiveNativeGrowthPmapAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-pmap-active-native-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedReservedCommittedPmapCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-pmap-reserved-committed-mismatch")));
    }

    @org.junit.jupiter.api.Test
    void generatedActiveNativeGrowthPmapCompareBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/compare-generated-pmap-active-native-growth")));
    }

    @org.junit.jupiter.api.Test
    void generatedReservedCommittedCorrelationBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-reserved-committed-native-mismatch-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedActiveNativeGrowthCorrelationBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-active-native-growth-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedContainerBudgetJvmBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-container-budget-jvm-pressure-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedHeapExhaustionBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-heap-exhaustion-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedJavaHeapSpaceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-java-heap-space-tooling")));
    }

    @org.junit.jupiter.api.Test
    void generatedGcPressureWorseningSequenceBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/sequence-generated-gc-pressure-worsening")));
    }

    @org.junit.jupiter.api.Test
    void generatedG1EvacuationFailureAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-gc-g1-evacuation-failure")));
    }

    @org.junit.jupiter.api.Test
    void generatedG1HumongousPressureAnalyzeBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-generated-gc-g1-humongous-pressure")));
    }

    @org.junit.jupiter.api.Test
    void generatedG1HumongousPressureCorrelationBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-generated-gc-g1-humongous-pressure-tooling")));
    }

    @org.junit.jupiter.api.Test
    void healthyGcBaselineBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-control-healthy-g1")));
    }

    @org.junit.jupiter.api.Test
    void healthyJfrBaselineBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-control-healthy-jfr")));
    }

    @org.junit.jupiter.api.Test
    void healthyThreadDumpBaselineBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-control-healthy-thread-dump")));
    }

    @org.junit.jupiter.api.Test
    void healthyNativeMemoryBaselineBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-control-healthy-native-memory")));
    }

    @org.junit.jupiter.api.Test
    void lowSignalSingleSnapshotBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-ambiguity-low-signal-single-snapshot")));
    }

    @org.junit.jupiter.api.Test
    void timeSkewedAmbiguityBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/correlate-control-time-skewed-ambiguity")));
    }

    @org.junit.jupiter.api.Test
    void hsErrNativeAllocationFailureBundlePreservesAgentWorkflowExpectations() throws Exception {
        evaluateBundle(bundleLoader.loadBundle(Path.of("src/test/resources/reference-incidents/analyze-hs-err-native-allocation-failure-bounded")));
    }

    private void evaluateBundle(ReferenceIncidentBundle bundle) throws Exception {
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(
            ReferenceIncidentChatModelFactory.create(bundle)
        );
        AnalysisReport report = switch (bundle.workflowType()) {
            case COMPARE -> orchestrator.compare(loadArtifact(bundle.artifactPaths().get(0)), loadArtifact(bundle.artifactPaths().get(1)));
            case SEQUENCE -> orchestrator.sequence(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case CORRELATE -> orchestrator.correlate(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case SINGLE_ARTIFACT -> orchestrator.analyze(loadArtifact(bundle.artifactPaths().getFirst()));
        };

        assertNotNull(report.supervisorTrace(), "reference bundles should persist supervisor trace data");
        assertEquals(bundle.workflowType(), report.supervisorTrace().workflowType(), "workflow type should round-trip into the report");
        assertTrue(
            report.supervisorTrace().steps().size() >= bundle.minSupervisorTraceSteps(),
            "supervisor trace should include the expected minimum number of steps"
        );
        if (bundle.expectUserNarrative()) {
            assertTrue(report.hasAiAgentBackedUserNarrative(), "reference bundles should preserve an AI-agent-backed user narrative");
            assertNotNull(report.userNarrative(), "reference bundles should preserve a user narrative");
            assertFalse(report.userNarrative().isBlank(), "reference bundles should preserve a non-empty user narrative");
            assertHasTroubleshootingSections(report.userNarrative());
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
            assertTrue(
                selectedTraceability.toolInvocations().size() >= bundle.minSelectedToolInvocations(),
                "selected narrative used fewer tool invocations than the reference minimum"
            );
            for (String requiredToolName : bundle.requiredSelectedToolNames()) {
                assertTrue(
                    selectedTraceability.toolInvocations().stream().anyMatch(invocation -> requiredToolName.equals(invocation.toolName())),
                    "missing required selected-tool invocation: " + requiredToolName
                );
            }

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

    private boolean isHighSeverity(Finding finding) {
        return finding.severity() == SeverityLevel.HIGH || finding.severity() == SeverityLevel.CRITICAL;
    }

    private void assertHasTroubleshootingSections(String narrative) {
        for (String sectionLabel : List.of("Summary:", "Key metrics:", "Likely issues:", "Recommended actions:")) {
            assertTrue(
                narrative.contains(sectionLabel),
                "accepted reference narratives must preserve the troubleshooting section " + sectionLabel
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private com.javaassistant.diagnostics.InputArtifact loadArtifact(String artifactPath) {
        try {
            return loader.load(generatedScenarioRegistry.resolveArtifactPath(artifactPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load reference incident artifact: " + artifactPath, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to materialize reference incident artifact: " + artifactPath, exception);
        }
    }
}
