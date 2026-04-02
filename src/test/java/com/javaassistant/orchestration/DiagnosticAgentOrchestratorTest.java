package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import com.javaassistant.testsupport.RoutingStubChatModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticAgentOrchestratorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());

    @TempDir
    Path tempDir;

    @Test
    void rejectsJfrSpecialistNarrativeWhenStubDoesNotExpandBoundedContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("contention-and-gc-recording.jfr")))
        );

        assertNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("JfrAgent", report.agentTraceability().getFirst().agentName());
        assertEquals(AgentNarrativeSource.SPECIALIST_AGENT, report.agentTraceability().getFirst().narrativeSource());
        assertFalse(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.FAILED
                && result.gateId().equals("coverage-aware-confidence")));
        assertEquals("TEST", report.agentTraceability().getFirst().modelExecutionTraceability().providerId());
        assertEquals("RoutingStubChatModel", report.agentTraceability().getFirst().modelExecutionTraceability().modelName());
        assertEquals("JfrAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertEquals(OrchestrationWorkflowType.SINGLE_ARTIFACT, report.supervisorTrace().workflowType());
        assertEquals(2, report.supervisorTrace().steps().size());
        assertEquals(SupervisorTraceStepType.ARTIFACT_GROUNDING, report.supervisorTrace().steps().getFirst().stepType());
        assertNull(report.supervisorTrace().steps().getLast().agentName());
        assertFalse(report.supervisorTrace().steps().getLast().selectedForUserNarrative());
        assertNull(report.supervisorTrace().steps().getLast().modelExecutionTraceability());
        assertEquals(
            "Supervisor did not accept a narrative candidate for single-artifact analysis.",
            report.supervisorTrace().steps().getLast().decision()
        );
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: SINGLE_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("STRUCTURED_CONTEXT_SLICES")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("REPRESENTATIVE_CONTEXT_SLICES")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("CONTEXT_COVERAGE")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("starting context above is intentionally bounded")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("sliceId=<id>")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("offset=<charOffset>, chars=<charCount>")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Never refer to the diagnostic data as a packet")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("DETERMINISTIC_FINDINGS")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("RECOMMENDED_ACTIONS")));
    }

    @Test
    void usesArtifactSpecificAgentForComparison() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(Path.of("samples/heap_histogram_1.txt")),
            loader.load(Path.of("samples/heap_histogram_2.txt"))
        );

        assertNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("HeapHistogramAgent", report.agentTraceability().getFirst().agentName());
        assertFalse(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.FAILED
                && result.gateId().equals("coverage-aware-confidence")));
        assertEquals("HeapHistogramAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertEquals(OrchestrationWorkflowType.COMPARE, report.supervisorTrace().workflowType());
        assertEquals(4, report.supervisorTrace().steps().size());
        assertEquals("baseline-grounding", report.supervisorTrace().steps().get(0).stepId());
        assertEquals("comparison-evaluation", report.supervisorTrace().steps().get(2).stepId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following heap histogram diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("BASELINE_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("CURRENT_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("STRUCTURED_CONTEXT_SLICES")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("REPRESENTATIVE_CONTEXT_SLICES")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("DETERMINISTIC_COMPARISON_FINDINGS")));
    }

    @Test
    void usesCorrelationAgentWithSpecialistObservationsForMultiArtifactAnalysis() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.correlate(
            List.of(
                loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")),
                loader.load(Path.of("samples/kernel_oom_kill.log")),
                loader.load(Path.of("samples/pod_oomkilled_describe.txt"))
            )
        );

        assertEquals("Correlation synthesis narrative", report.userNarrative());
        assertEquals(4, report.agentTraceability().size());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertEquals(AgentNarrativeSource.SYNTHESIS_AGENT, report.agentTraceability().getLast().narrativeSource());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertEquals("CorrelationAgent.analyze", report.agentTraceability().getLast().modelExecutionTraceability().templateId());
        assertEquals(
            3,
            report.agentTraceability().stream().filter(traceability -> !traceability.selectedForUserNarrative()).count()
        );
        assertEquals(OrchestrationWorkflowType.CORRELATE, report.supervisorTrace().workflowType());
        assertEquals(8, report.supervisorTrace().steps().size());
        assertEquals("correlation-evaluation", report.supervisorTrace().steps().get(3).stepId());
        assertEquals(
            3,
            report.supervisorTrace().steps().stream()
                .filter(step -> step.stepType() == SupervisorTraceStepType.SPECIALIST_SELECTION)
                .filter(step -> "correlation-specialist-observation".equals(step.stageId()))
                .count()
        );
        assertEquals("CorrelationAgent", report.supervisorTrace().steps().getLast().agentName());
        assertTrue(report.supervisorTrace().steps().getLast().selectedForUserNarrative());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following container-memory diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following OOM or restart-signal diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following multi-artifact JVM diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("SPECIALIST_AGENT_OBSERVATIONS")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("ARTIFACT_CONTEXT_1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("CONTEXT_COVERAGE")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("DETERMINISTIC_CORRELATION_FINDINGS")));
    }

    @Test
    void doesNotInjectDeterministicFallbackObservationsIntoCorrelationPackets() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel(true);
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        orchestrator.correlate(
            List.of(
                loader.load(JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("missing-observation.jfr"))),
                loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"))
            )
        );

        String correlationPrompt = chatModel.prompts().stream()
            .filter(prompt -> prompt.contains("Analyze the following multi-artifact JVM diagnostic data:"))
            .findFirst()
            .orElseThrow();

        assertFalse(correlationPrompt.contains("DeterministicAssessmentFallback"));
        assertFalse(correlationPrompt.contains("specialist evidence points to"));
    }

    @Test
    void leavesOperatorNarrativeEmptyWhenSpecialistResponseIsBlank() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel(true);
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("blank-primary-recording.jfr")))
        );

        assertNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("JfrAgent", report.agentTraceability().get(0).agentName());
        assertFalse(report.agentTraceability().get(0).selectedForUserNarrative());
        assertEquals("JfrAgent.analyze", report.agentTraceability().get(0).modelExecutionTraceability().templateId());
        assertTrue(report.agentTraceability().get(0).qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.FAILED && result.gateId().equals("response-not-empty")));
        assertEquals("single-artifact-specialist-analysis", report.supervisorTrace().steps().getLast().stepId());
        assertFalse(report.supervisorTrace().steps().getLast().selectedForUserNarrative());
        assertEquals("Supervisor did not accept a narrative candidate for single-artifact analysis.", report.supervisorTrace().steps().getLast().decision());
    }

    @Test
    void rejectsNarrativesThatUseInternalPipelineTerms() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel(false, true);
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("internal-term-recording.jfr")))
        );

        assertNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("JfrAgent", report.agentTraceability().get(0).agentName());
        assertFalse(report.agentTraceability().get(0).selectedForUserNarrative());
        assertEquals("JfrAgent.analyze", report.agentTraceability().get(0).modelExecutionTraceability().templateId());
        assertTrue(report.agentTraceability().get(0).qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.FAILED && result.gateId().equals("user-language-only")));
        assertFalse(report.supervisorTrace().steps().getLast().selectedForUserNarrative());
    }
}
