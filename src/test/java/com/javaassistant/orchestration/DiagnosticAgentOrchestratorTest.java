package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.ai.ConfiguredChatModel;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.CorrelationToolCallingStubChatModel;
import com.javaassistant.testsupport.GcComparisonToolCallingStubChatModel;
import com.javaassistant.testsupport.GcToolCallingStubChatModel;
import com.javaassistant.testsupport.GcWindowStreakToolCallingStubChatModel;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import com.javaassistant.testsupport.JfrToolCallingStubChatModel;
import com.javaassistant.testsupport.LegacyGcToolCallingStubChatModel;
import com.javaassistant.testsupport.LegacyGcWindowStreakToolCallingStubChatModel;
import com.javaassistant.testsupport.RoutingStubChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticAgentOrchestratorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();

    @TempDir
    Path tempDir;

    @Test
    void acceptsJfrSpecialistNarrativeWhenTheStartingContextAlreadyCarriesUsefulCoverage() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("contention-and-gc-recording.jfr")))
        );

        assertNotNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("JfrAgent", report.agentTraceability().getFirst().agentName());
        assertEquals(AgentNarrativeSource.SPECIALIST_AGENT, report.agentTraceability().getFirst().narrativeSource());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.PASSED
                && result.gateId().equals("coverage-aware-confidence")));
        assertEquals("TEST", report.agentTraceability().getFirst().modelExecutionTraceability().providerId());
        assertEquals("RoutingStubChatModel", report.agentTraceability().getFirst().modelExecutionTraceability().modelName());
        assertEquals("JfrAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertEquals(OrchestrationWorkflowType.SINGLE_ARTIFACT, report.supervisorTrace().workflowType());
        assertEquals(2, report.supervisorTrace().steps().size());
        assertEquals(SupervisorTraceStepType.ARTIFACT_GROUNDING, report.supervisorTrace().steps().getFirst().stepType());
        assertEquals("JfrAgent", report.supervisorTrace().steps().getLast().agentName());
        assertTrue(report.supervisorTrace().steps().getLast().selectedForUserNarrative());
        assertEquals("JfrAgent.analyze", report.supervisorTrace().steps().getLast().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: SINGLE_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("STRUCTURED_CONTEXT_SLICES")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("REPRESENTATIVE_CONTEXT_SLICES")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("CONTEXT_COVERAGE")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("starting context above is intentionally bounded")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("sliceId=<id>")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("offset=<charOffset>, chars=<charCount>")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Display Name:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Content Length:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Parser Version:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactAttributes")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Traceability:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Kind:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("extractedData.")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Never refer to the diagnostic data as a packet")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("DETERMINISTIC_FINDINGS")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("RECOMMENDED_ACTIONS")));
    }

    @Test
    void frontLoadsJfrStartingSummaryInSingleArtifactPrompts() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("prompt-jfr-current-recording.jfr")))
        );

        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("JFR_STARTING_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("summaryLines")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Recording window:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Execution hotspot:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Runtime pressure:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Allocation pressure:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Retained-object signals:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominantHotspot")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("runtimeSignals")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("memorySignals")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("If MODE: SINGLE_ARTIFACT and JFR_STARTING_SUMMARY are present")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("If JFR_STARTING_SUMMARY is present, read it before the detailed structured facts, highlights, and slices")));
    }

    @Test
    void doesNotFrontLoadPmapProcessCommandNamesIntoTheFirstPassPrompt() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        orchestrator.analyze(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        String prompt = chatModel.prompts().stream()
            .filter(value -> value.contains("Analyze the following pmap diagnostic data:"))
            .findFirst()
            .orElseThrow();

        assertFalse(prompt.contains("MetaspaceMemoryLeak"));
        assertTrue(prompt.contains("Do not infer root cause from a process name"));
        assertTrue(prompt.contains("Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses"));
    }

    @Test
    void usesArtifactSpecificAgentForComparisonWhenTheNarrativeAcknowledgesResidualUncertainty() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(Path.of("samples/heap_histogram_1.txt")),
            loader.load(Path.of("samples/heap_histogram_2.txt"))
        );

        assertNotNull(report.userNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("HeapHistogramAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .anyMatch(result -> result.status() == AgentQualityGateStatus.PASSED
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
    void routesJfrComparisonsThroughJfrSpecialistAndFrontLoadsJfrComparisonSummary() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("jfr-baseline-routing.jfr"))),
            loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("jfr-current-routing.jfr")))
        );

        assertEquals("JfrAgent", report.agentTraceability().getFirst().agentName());
        assertEquals(OrchestrationWorkflowType.COMPARE, report.supervisorTrace().workflowType());
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-lock-contention-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-gc-pause-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-execution-hot-path-shift")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-allocation-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-old-object-growth")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-old-object-depth-regression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("JFR_COMPARISON_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("regressionSynopsis")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("summaryLines")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Recording window:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Incident window:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Hotspot shift:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Event-family trend:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Thread trend:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Runtime pressure:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Allocation pressure:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Retained-object signals:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("recordingComparison")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominantIncidentPair")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominantHotspotPair")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("eventFamilyRegression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("threadRegression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("runtimePressureDelta")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("allocationDelta")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("retentionDelta")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Read regressionSynopsis first when it is present")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Then compare dominantIncidentPair, dominantHotspotPair, eventFamilyRegression, threadRegression, runtimePressureDelta, allocationDelta, and retentionDelta before spending tool calls")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("read regressionSynopsis first, then dominantIncidentPair, dominantHotspotPair, eventFamilyRegression, threadRegression, runtimePressureDelta, allocationDelta, and retentionDelta before deciding whether more context is needed")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=current")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=baseline")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("thread=checkout-worker")));
    }

    @Test
    void routesGcComparisonsThroughGcSpecialistAndFrontLoadsGcComparisonSummary() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log")),
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_current_small.log"))
        );

        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertEquals(OrchestrationWorkflowType.COMPARE, report.supervisorTrace().workflowType());
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-g1-distress-regression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("GC_COMPARISON_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("pressureAndRecoveryDelta")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorSpecificDelta")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("causeMixPair")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("regressionSynopsis")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("summaryLines")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Pause profile:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Recovery shape:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("baselineCauseMix")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("currentCauseMix")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("topPauseCausesByTotalPauseMs")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("recoveryShapePair")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("baselineRecoveryShape")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("currentRecoveryShape")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("maxNearCapacityPauseStreak")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominantIncidentPair")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Read regressionSynopsis first when it is present")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Then compare any causeMixPair and recoveryShapePair entries")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("read regressionSynopsis first, then causeMixPair, recoveryShapePair, and finally dominantIncidentPair before deciding whether more context is needed")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("baselineIncident")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("currentIncident")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("toSpaceExhaustedCountDelta")));
    }

    @Test
    void autoOrderedComparisonUsesOlderSnapshotAsBaseline() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        Path olderPath = createTimedThreadDump(
            tempDir.resolve("thread-dump-older.txt"),
            Instant.parse("2026-04-06T17:10:05Z")
        );
        Path newerPath = createTimedThreadDump(
            tempDir.resolve("thread-dump-newer.txt"),
            Instant.parse("2026-04-06T17:12:05Z")
        );

        var report = orchestrator.compareAutoOrdered(List.of(
            loader.load(newerPath),
            loader.load(olderPath)
        ));

        assertEquals(olderPath.toString(), report.supervisorTrace().steps().get(0).artifactPaths().getFirst());
        assertEquals(newerPath.toString(), report.supervisorTrace().steps().get(1).artifactPaths().getFirst());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("BASELINE_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains(olderPath.toString())));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains(newerPath.toString())));
    }

    @Test
    void routesGcSequencesThroughGcSpecialistAndFrontLoadsSequenceSummary() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.sequence(List.of(
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log")),
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_current_small.log")),
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-g1-cycle-pressure/gc_g1_cycle_pressure_small.log"))
        ));

        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertEquals(OrchestrationWorkflowType.SEQUENCE, report.supervisorTrace().workflowType());
        assertEquals(5, report.supervisorTrace().steps().size());
        assertEquals("sequence-evaluation", report.supervisorTrace().steps().get(3).stepId());
        assertEquals(SupervisorTraceStepType.SEQUENCE_EVALUATION, report.supervisorTrace().steps().get(3).stepType());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_SEQUENCE")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("ARTIFACT_SEQUENCE_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("firstToLastProgression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("pairwiseProgression")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("SNAPSHOT_1_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("SNAPSHOT_2_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("SNAPSHOT_3_ARTIFACT")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=snapshot-2")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("snapshotOverview")));
    }

    @Test
    void autoOrderedSequenceUsesTimestampedSnapshotNameOrder() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        Path latest = copySample(
            Path.of("samples/heap_histogram_2.txt"),
            tempDir.resolve("heap-20260406-102500.txt")
        );
        Path earliest = copySample(
            Path.of("samples/heap_histogram_1.txt"),
            tempDir.resolve("heap-20260406-101000.txt")
        );
        Path middle = copySample(
            Path.of("samples/heap_histogram_2.txt"),
            tempDir.resolve("heap-20260406-101500.txt")
        );

        var report = orchestrator.sequenceAutoOrdered(List.of(
            loader.load(latest),
            loader.load(earliest),
            loader.load(middle)
        ));

        assertEquals(
            List.of(earliest.toString(), middle.toString(), latest.toString()),
            report.supervisorTrace().artifactPaths()
        );
        assertEquals(earliest.toString(), report.supervisorTrace().steps().get(0).artifactPaths().getFirst());
        assertEquals(middle.toString(), report.supervisorTrace().steps().get(1).artifactPaths().getFirst());
        assertEquals(latest.toString(), report.supervisorTrace().steps().get(2).artifactPaths().getFirst());
    }

    @Test
    void acceptsGcComparisonNarrativeAfterTargetedBaselineAndCurrentTooling() throws Exception {
        GcComparisonToolCallingStubChatModel chatModel = new GcComparisonToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log")),
            loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("incident=dominant-pressure", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(0).artifactPath().endsWith("samples/g1_21_smallheap_fullgcs.log"));
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(1).artifactPath().endsWith("samples/g1_21_smallheap_fullgcs.log"));
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(2).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getFirst().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(2).artifactPath().endsWith("gc_baseline_small.log"));
        assertTrue(report.userNarrative().contains("Compared with the baseline"));
        assertTrue(report.userNarrative().contains("current GC log has regressed"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("GC_COMPARISON_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=current")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("incident=dominant-pressure")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominant-window-summary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacyG1ComparisonNarrativeAfterTargetedBaselineAndCurrentTooling() throws Exception {
        GcComparisonToolCallingStubChatModel chatModel = new GcComparisonToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.compare(
            loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log")),
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("incident=dominant-pressure", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(0).artifactPath().endsWith("gc_legacy_g1_pressure_large.log"));
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(1).artifactPath().endsWith("gc_legacy_g1_pressure_large.log"));
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(2).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getFirst().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getFirst().toolInvocations().get(2).artifactPath().endsWith("gc_baseline_small.log"));
        assertTrue(report.userNarrative().contains("Compared with the baseline"));
        assertTrue(report.userNarrative().contains("current GC log has regressed"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("GC_COMPARISON_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=current")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("incident=dominant-pressure")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominant-window-summary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("gc_legacy_g1_pressure_large.log")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsJfrComparisonNarrativeAfterTargetedBaselineAndCurrentTooling() throws Exception {
        JfrToolCallingStubChatModel chatModel = new JfrToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);
        var baselineArtifact = loader.load(JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("jfr-baseline-accept.jfr")));
        var currentArtifact = loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("jfr-current-accept.jfr")));

        var report = orchestrator.compare(baselineArtifact, currentArtifact);

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("JfrAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchJfrContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("hotspot=checkoutService", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals(currentArtifact.metadata().sourcePath(), report.agentTraceability().getFirst().toolInvocations().get(0).artifactPath());
        assertEquals("computeJfrView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("execution-hotspots", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertEquals(currentArtifact.metadata().sourcePath(), report.agentTraceability().getFirst().toolInvocations().get(1).artifactPath());
        assertEquals("computeJfrView", report.agentTraceability().getFirst().toolInvocations().get(2).toolName());
        assertEquals("execution-hotspots", report.agentTraceability().getFirst().toolInvocations().get(2).request());
        assertEquals(baselineArtifact.metadata().sourcePath(), report.agentTraceability().getFirst().toolInvocations().get(2).artifactPath());
        assertTrue(report.userNarrative().contains("Compared with the baseline"));
        assertTrue(report.userNarrative().contains("checkout"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("MODE: ARTIFACT_COMPARISON")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("JFR_COMPARISON_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=current")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("artifactRef=baseline")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchJfrContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeJfrView]:")));
    }

    @Test
    void acceptsThreadDumpNarrativeWhenTheDeadlockSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(loader.load(Path.of("samples/thread_dump_deadlock.txt")));

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("ThreadDumpAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("ThreadDumpAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following thread dump diagnostic data:")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallFullCompactionSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-full-compaction/gc_full_compaction_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(report.userNarrative().contains("Summary:"));
        assertTrue(report.userNarrative().contains("Key metrics:"));
        assertTrue(report.userNarrative().contains("Likely issues:"));
        assertTrue(report.userNarrative().contains("Recommended actions:"));
        assertFalse(report.userNarrative().contains("Next steps:"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following GC log diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("GC_STARTING_SUMMARY")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("summaryLines")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Pause profile:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Full-GC profile:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Recovery shape:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("dominantIncident")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("If MODE: SINGLE_ARTIFACT and GC_STARTING_SUMMARY are present")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("If GC_STARTING_SUMMARY is present, read it before the detailed structured facts, highlights, and slices")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: G1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorPressureSummary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorFocusAreas")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorInterpretationHint")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Evacuation Failure")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallMetaspacePressureSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-metaspace/gc_metaspace_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(report.userNarrative().contains("metaspace"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Metadata GC Threshold")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallG1MixedToFullCycleSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-g1-cycle-pressure/gc_g1_cycle_pressure_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: G1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("g1CycleProgressionSummary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("lowReclaimHighRetentionFullGcCount")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallZgcAllocationStallSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-zgc-allocation-stall/gc_zgc_allocation_stall_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: ZGC")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("allocationStallCount")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorFocusAreas")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallParallelFullGcSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-parallel-full-gc/gc_parallel_full_gc_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: Parallel")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorInterpretationHint")));
        assertTrue(chatModel.prompts().stream().noneMatch(prompt -> prompt.contains("collector: Shenandoah")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallLegacyG1DistressSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-legacy-g1-distress/gc_legacy_g1_distress_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: G1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorPressureSummary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("toSpaceExhaustedCount")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("gc-incident-dominant-g1-distress-window")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("peakHumongousAfterRegions")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallLegacyCmsFailureSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-cms-concurrent-failure/gc_cms_concurrent_failure_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: CMS")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorPressureSummary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("concurrentModeFailureCount")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("longestConcurrentPhaseMs")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("gc-incident-dominant-cms-fallback-window")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorInterpretationHint")));
    }

    @Test
    void acceptsGcNarrativeWhenSmallLegacySerialFullGcSampleFitsInFirstPassContext() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-serial-full-gc/gc_serial_full_gc_small.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals("GCLogAgent.analyze", report.agentTraceability().getFirst().modelExecutionTraceability().templateId());
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: Serial")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorPressureSummary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collectorFocusAreas")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("gc-incident-dominant-full-gc-window")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("peakHeapOccupancyRatio")));
    }

    @Test
    void acceptsGcNarrativeAfterToolRetrievalWhenLargeGcLogExceedsFirstPassContext() throws Exception {
        GcToolCallingStubChatModel chatModel = new GcToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals(1, report.agentTraceability().size());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("RETRIEVAL", report.agentTraceability().getFirst().toolInvocations().get(0).toolFamily());
        assertEquals("gcId=45", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("COMPUTATION", report.agentTraceability().getFirst().toolInvocations().get(1).toolFamily());
        assertEquals("failure-summary", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("fullGcCount: 19"));
        assertTrue(report.userNarrative().contains("full compactions"));
        assertTrue(chatModel.prompts().size() >= 2);
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following GC log diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Display Name:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Parser Version:")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Traceability:")));
    }

    @Test
    void acceptsLegacyG1NarrativeAfterToolRetrievalWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcToolCallingStubChatModel chatModel = new LegacyGcToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("signalType=TO_SPACE_EXHAUSTED", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("cause=G1 Compaction Pause", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("to-space exhaustion"));
        assertTrue(report.userNarrative().contains("G1 compaction pauses"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: G1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacyCmsNarrativeAfterToolRetrievalWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcToolCallingStubChatModel chatModel = new LegacyGcToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-cms-pressure/gc_legacy_cms_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("signalType=CONCURRENT_MODE_FAILURE", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("phaseKind=CONCURRENT", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("concurrent-mode failures"));
        assertTrue(report.userNarrative().contains("CMS"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: CMS")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacySerialNarrativeAfterToolRetrievalWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcToolCallingStubChatModel chatModel = new LegacyGcToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-serial-pressure/gc_legacy_serial_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("cause=Allocation Failure", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("recovery-summary", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("Serial"));
        assertTrue(report.userNarrative().contains("allocation failure"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: Serial")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacyG1NarrativeAfterWindowAndStreakToolingWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcWindowStreakToolCallingStubChatModel chatModel = new LegacyGcWindowStreakToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("start=24s,end=32.6s", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("streak=distress", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("24.0s to 32.6s"));
        assertTrue(report.userNarrative().contains("to-space exhaustion"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: G1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacyCmsNarrativeAfterWindowAndStreakToolingWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcWindowStreakToolCallingStubChatModel chatModel = new LegacyGcWindowStreakToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-cms-pressure/gc_legacy_cms_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("start=24.0s,end=24.9s", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("streak=failure", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("failure cluster"));
        assertTrue(report.userNarrative().contains("CMS"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: CMS")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsLegacySerialNarrativeAfterWindowAndStreakToolingWhenLargeJdk8LogExceedsFirstPassContext() throws Exception {
        LegacyGcWindowStreakToolCallingStubChatModel chatModel = new LegacyGcWindowStreakToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-serial-pressure/gc_legacy_serial_pressure_large.log"))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("start=155s,end=161s", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("streak=full-gc", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("continuous full-GC streak"));
        assertTrue(report.userNarrative().contains("Serial"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("collector: Serial")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsGcNarrativeAfterTimeWindowAndStreakToolingWhenLargeGcLogExceedsFirstPassContext() throws Exception {
        GcWindowStreakToolCallingStubChatModel chatModel = new GcWindowStreakToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("GCLogAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchGcContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("start=6.6s,end=7.35s", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeGcView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("streak=distress", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("distress interval"));
        assertTrue(report.userNarrative().contains("6.6s to 7.35s"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchGcContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeGcView]:")));
    }

    @Test
    void acceptsJfrNarrativeAfterToolRetrievalWhenBoundedContextNeedsExpansion() throws Exception {
        JfrToolCallingStubChatModel chatModel = new JfrToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(
            loader.load(JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("tool-hot-path-recording.jfr")))
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("JfrAgent", report.agentTraceability().getFirst().agentName());
        assertTrue(report.agentTraceability().getFirst().selectedForUserNarrative());
        assertEquals(2, report.agentTraceability().getFirst().toolInvocations().size());
        assertEquals("fetchJfrContext", report.agentTraceability().getFirst().toolInvocations().get(0).toolName());
        assertEquals("hotspot=checkoutService", report.agentTraceability().getFirst().toolInvocations().get(0).request());
        assertEquals("computeJfrView", report.agentTraceability().getFirst().toolInvocations().get(1).toolName());
        assertEquals("execution-hotspots", report.agentTraceability().getFirst().toolInvocations().get(1).request());
        assertTrue(report.userNarrative().contains("checkout"));
        assertTrue(report.userNarrative().contains("Summary:"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchJfrContext]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeJfrView]:")));
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

        assertNotNull(report.userNarrative());
        assertTrue(report.userNarrative().contains("Summary:"));
        assertTrue(report.userNarrative().contains("Recommended actions:"));
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
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("computeRelevantArtifactView first when a focused artifact summary is likely enough")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("fetchRelevantArtifactContext when the exact lines, neighboring section, or omitted slice matters")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Do not infer root cause from a process name")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses")));
        assertFalse(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("DETERMINISTIC_CORRELATION_FINDINGS")));
    }

    @Test
    void acceptsCorrelationSynthesisWhenEachArtifactAlreadyHasAnAcceptedSpecialistObservation() throws Exception {
        SingleProcessCorrelationStubChatModel chatModel = new SingleProcessCorrelationStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.correlate(
            List.of(
                loader.load(Path.of("samples/single_process_data/classes_leak/gclog_metaspace.log")),
                loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")),
                loader.load(Path.of("samples/single_process_data/java_nmt_diff_3391237.txt")),
                loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")),
                loader.load(Path.of("samples/single_process_data/pmap_3391237.txt"))
            )
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertTrue(report.agentTraceability().subList(0, report.agentTraceability().size() - 1).stream()
            .allMatch(traceability -> !traceability.selectedForUserNarrative()));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("SPECIALIST_AGENT_OBSERVATIONS")));
    }

    @Test
    void doesNotInjectDeterministicFallbackObservationsIntoCorrelationContexts() throws Exception {
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
    void frontLoadsCrossArtifactSignalSummaryForJfrGcHeapAndMemoryCorrelation() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);
        var jfrArtifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("correlation-summary-recording.jfr")));
        var jfrParsed = jfrParser.parse(jfrArtifact);
        var gcArtifact = loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("correlation-summary-gc.log"), jfrParsed), ArtifactType.GC_LOG);

        orchestrator.correlate(
            List.of(
                jfrArtifact,
                gcArtifact,
                loader.load(createMatchingHeapHistogram(tempDir.resolve("correlation-summary-heap.txt"))),
                loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"))
            )
        );

        String correlationPrompt = chatModel.prompts().stream()
            .filter(prompt -> prompt.contains("Analyze the following multi-artifact JVM diagnostic data:"))
            .findFirst()
            .orElseThrow();

        assertTrue(correlationPrompt.contains("CROSS_ARTIFACT_SIGNAL_SUMMARY"));
        assertTrue(correlationPrompt.contains("sharedSignalFamilies"));
        assertTrue(correlationPrompt.contains("crossArtifactTiming"));
        assertTrue(correlationPrompt.contains("timingCoverage"));
        assertTrue(correlationPrompt.contains("timingAlignments"));
        assertTrue(correlationPrompt.contains("strongestAlignments"));
        assertTrue(correlationPrompt.contains("perArtifactSignals"));
        assertTrue(correlationPrompt.contains("jfr-gc-heap-pressure-alignment"));
        assertTrue(correlationPrompt.contains("jfr-native-pressure-alignment"));
        assertTrue(correlationPrompt.contains("java.util.LinkedHashMap"));
        assertTrue(correlationPrompt.contains("sharedClassFamilies"));
        assertTrue(correlationPrompt.contains("UNTIMED_COMPANION"));
        assertTrue(correlationPrompt.contains("When crossArtifactTiming is present"));
    }

    @Test
    void acceptsCorrelationNarrativeAfterTargetedArtifactTooling() throws Exception {
        CorrelationToolCallingStubChatModel chatModel = new CorrelationToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.correlate(
            List.of(
                loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")),
                loader.load(Path.of("samples/kernel_oom_kill.log")),
                loader.load(Path.of("samples/pod_oomkilled_describe.txt"))
            )
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getLast().toolInvocations().size());
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(0).toolName());
        assertEquals("pressure-summary", report.agentTraceability().getLast().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(0).artifactPath().endsWith("container_memory_pressure_snapshot.txt"));
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(1).toolName());
        assertEquals("kernel-summary", report.agentTraceability().getLast().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(1).artifactPath().endsWith("kernel_oom_kill.log"));
        assertEquals("fetchRelevantArtifactContext", report.agentTraceability().getLast().toolInvocations().get(2).toolName());
        assertEquals("pattern=Killed process", report.agentTraceability().getLast().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(2).artifactPath().endsWith("kernel_oom_kill.log"));
        assertTrue(report.userNarrative().contains("container memory pressure"));
        assertTrue(report.userNarrative().contains("kernel OOM kill"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeRelevantArtifactView]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchRelevantArtifactContext]:")));
    }

    @Test
    void acceptsCorrelationNarrativeForGcMemoryPressureAfterTargetedTooling() throws Exception {
        CorrelationToolCallingStubChatModel chatModel = new CorrelationToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.correlate(
            List.of(
                loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")),
                loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")),
                loader.load(Path.of("samples/single_process_data/pmap_3391237.txt"))
            )
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getLast().toolInvocations().size());
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(0).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getLast().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(0).artifactPath().endsWith("g1_21_smallheap_fullgcs.log"));
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(1).toolName());
        assertEquals("resident-summary", report.agentTraceability().getLast().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(1).artifactPath().endsWith("pmap_3391237.txt"));
        assertEquals("fetchRelevantArtifactContext", report.agentTraceability().getLast().toolInvocations().get(2).toolName());
        assertEquals("pattern=Java Heap", report.agentTraceability().getLast().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(2).artifactPath().endsWith("java_nmt_summary_3391237.txt"));
        assertTrue(report.userNarrative().contains("broad JVM memory pressure"));
        assertTrue(report.userNarrative().contains("resident-memory pressure"));
    }

    @Test
    void acceptsCorrelationNarrativeForNativeOomAfterTargetedTooling() throws Exception {
        CorrelationToolCallingStubChatModel chatModel = new CorrelationToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.correlate(
            List.of(
                loader.load(Path.of("samples/hs_err_pid2866366.log")),
                loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")),
                loader.load(Path.of("samples/single_process_data/pmap_3391237.txt"))
            )
        );

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getLast().toolInvocations().size());
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(0).toolName());
        assertEquals("crash-summary", report.agentTraceability().getLast().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(0).artifactPath().endsWith("hs_err_pid2866366.log"));
        assertEquals("fetchRelevantArtifactContext", report.agentTraceability().getLast().toolInvocations().get(1).toolName());
        assertEquals("pattern=Internal", report.agentTraceability().getLast().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(1).artifactPath().endsWith("java_nmt_summary_3391237.txt"));
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(2).toolName());
        assertEquals("resident-summary", report.agentTraceability().getLast().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(2).artifactPath().endsWith("pmap_3391237.txt"));
        assertTrue(report.userNarrative().contains("native memory exhaustion"));
        assertTrue(report.userNarrative().contains("fatal JVM crash"));
    }

    @Test
    void acceptsCorrelationNarrativeForJfrGcHeapAfterTargetedTooling() throws Exception {
        CorrelationToolCallingStubChatModel chatModel = new CorrelationToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);
        var jfrArtifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("correlate-jfr-gc-heap-recording.jfr")));
        var jfrParsed = jfrParser.parse(jfrArtifact);
        var gcArtifact = loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("correlate-jfr-gc-heap.log"), jfrParsed), ArtifactType.GC_LOG);
        var heapArtifact = loader.load(createMatchingHeapHistogram(tempDir.resolve("correlate-jfr-gc-heap.txt")));

        var report = orchestrator.correlate(List.of(jfrArtifact, gcArtifact, heapArtifact));

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getLast().toolInvocations().size());
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(0).toolName());
        assertEquals("runtime-incident-summary", report.agentTraceability().getLast().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(0).artifactPath().endsWith("correlate-jfr-gc-heap-recording.jfr"));
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(1).toolName());
        assertEquals("dominant-window-summary", report.agentTraceability().getLast().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(1).artifactPath().endsWith("correlate-jfr-gc-heap.log"));
        assertEquals("fetchRelevantArtifactContext", report.agentTraceability().getLast().toolInvocations().get(2).toolName());
        assertEquals("class=java.util.LinkedHashMap", report.agentTraceability().getLast().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(2).artifactPath().endsWith("correlate-jfr-gc-heap.txt"));
        assertTrue(report.userNarrative().contains("LinkedHashMap"));
        assertTrue(report.userNarrative().contains("retained-heap pressure"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("runtime-incident-summary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("class=java.util.LinkedHashMap")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeRelevantArtifactView]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchRelevantArtifactContext]:")));
    }

    @Test
    void acceptsCorrelationNarrativeForJfrThreadDumpAfterTargetedTooling() throws Exception {
        CorrelationToolCallingStubChatModel chatModel = new CorrelationToolCallingStubChatModel();
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);
        var jfrArtifact = loader.load(
            JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(tempDir.resolve("correlate-jfr-thread-contention-recording.jfr"))
        );
        var jfrParsed = jfrParser.parse(jfrArtifact);
        var threadDumpArtifact = loader.load(
            createTimedThreadDump(tempDir.resolve("correlate-jfr-thread-contention.txt"), firstIncidentWindowMidpoint(jfrParsed))
        );

        var report = orchestrator.correlate(List.of(jfrArtifact, threadDumpArtifact));

        assertNotNull(report.userNarrative());
        assertTrue(report.hasAiAgentBackedUserNarrative());
        assertEquals("CorrelationAgent", report.agentTraceability().getLast().agentName());
        assertTrue(report.agentTraceability().getLast().selectedForUserNarrative());
        assertTrue(report.agentTraceability().getLast().qualityGates().stream()
            .noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED));
        assertEquals(3, report.agentTraceability().getLast().toolInvocations().size());
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(0).toolName());
        assertEquals("runtime-incident-summary", report.agentTraceability().getLast().toolInvocations().get(0).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(0).artifactPath().endsWith("correlate-jfr-thread-contention-recording.jfr"));
        assertEquals("computeRelevantArtifactView", report.agentTraceability().getLast().toolInvocations().get(1).toolName());
        assertEquals("deadlock-summary", report.agentTraceability().getLast().toolInvocations().get(1).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(1).artifactPath().endsWith("correlate-jfr-thread-contention.txt"));
        assertEquals("fetchRelevantArtifactContext", report.agentTraceability().getLast().toolInvocations().get(2).toolName());
        assertEquals("thread=Deadlock-Worker-1", report.agentTraceability().getLast().toolInvocations().get(2).request());
        assertTrue(report.agentTraceability().getLast().toolInvocations().get(2).artifactPath().endsWith("correlate-jfr-thread-contention.txt"));
        assertTrue(report.userNarrative().contains("deadlock"));
        assertTrue(report.userNarrative().contains("http-nio-8080-exec"));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("deadlock-summary")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("thread=Deadlock-Worker-1")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[computeRelevantArtifactView]:")));
        assertTrue(chatModel.prompts().stream().anyMatch(prompt -> prompt.contains("TOOL_RESULT[fetchRelevantArtifactContext]:")));
    }

    @Test
    void leavesUserNarrativeEmptyWhenSpecialistResponseIsBlank() throws Exception {
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
    void recordsAgentInvocationFailureDetailsWhenTheModelCallThrows() throws Exception {
        RoutingStubChatModel chatModel = new RoutingStubChatModel(false, false, true);
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModel);

        var report = orchestrator.analyze(loader.load(Path.of("samples/thread_dump_deadlock.txt")));

        assertNull(report.userNarrative());
        assertTrue(report.agentTraceability().getFirst().qualityGates().stream()
            .anyMatch(result ->
                result.status() == AgentQualityGateStatus.FAILED
                    && result.gateId().equals("response-not-empty")
                    && result.detail().contains("Simulated thread-dump model failure: OCI 401 unauthorized")
            ));
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

    @Test
    void usesSmallerStartingContextForCompactLocalModelsThanForStrongerModels() throws Exception {
        RoutingStubChatModel compactLocalChatModel = new RoutingStubChatModel();
        RoutingStubChatModel strongerChatModel = new RoutingStubChatModel();
        DiagnosticAgentOrchestrator compactOrchestrator = OrchestratorTestSupport.createOrchestrator(
            configuredChatModel(compactLocalChatModel, "OLLAMA", "Ollama", "llama3.2", 16384)
        );
        DiagnosticAgentOrchestrator strongerOrchestrator = OrchestratorTestSupport.createOrchestrator(
            configuredChatModel(strongerChatModel, "OCI", "OCI Generative AI", "xai.grok-4", 32768)
        );

        compactOrchestrator.analyze(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        strongerOrchestrator.analyze(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        String compactPrompt = compactLocalChatModel.prompts().stream()
            .filter(prompt -> prompt.contains("Analyze the following GC log diagnostic data:"))
            .findFirst()
            .orElseThrow();
        String strongerPrompt = strongerChatModel.prompts().stream()
            .filter(prompt -> prompt.contains("Analyze the following GC log diagnostic data:"))
            .findFirst()
            .orElseThrow();

        assertTrue(countOccurrences(compactPrompt, "- Slice ") < countOccurrences(strongerPrompt, "- Slice "));
        assertTrue(compactPrompt.length() < strongerPrompt.length());
    }

    private ConfiguredChatModel configuredChatModel(
        RoutingStubChatModel chatModel,
        String providerId,
        String providerLabel,
        String modelName,
        int approximateContextWindowTokens
    ) {
        return new ConfiguredChatModel(
            chatModel,
            providerId,
            providerLabel,
            modelName,
            ConfiguredChatModel.inferModelFamily(modelName),
            approximateContextWindowTokens
        );
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while (text != null && token != null && !token.isEmpty() && (index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static final class SingleProcessCorrelationStubChatModel implements ChatModel {
        private final RoutingStubChatModel delegate = new RoutingStubChatModel();
        private final List<String> prompts = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            String prompt = chatRequest.messages().stream()
                .map(Object::toString)
                .reduce("", (left, right) -> left + "\n" + right);
            prompts.add(prompt);

            if (prompt.contains("Analyze the following multi-artifact JVM diagnostic data:")
                && prompt.contains("gclog_metaspace.log")
                && prompt.contains("java_nmt_summary_3391237.txt")
                && prompt.contains("pmap_3391237.txt")) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage("""
                        Summary:
                        The combined diagnostics show two aligned pressures: Metaspace pressure is corroborated across GC and NMT, and native memory pressure is supported by both pmap and NMT.
                        Key metrics:
                        - metaspaceTriggeredFullGcCount: elevated
                        - metaspaceCommittedHeadroom: low
                        - nativeMemoryPressure: present
                        - anonymousResidentPressure: elevated
                        Likely issues:
                        - Metaspace pressure is corroborated across GC and NMT, which points to class metadata growth rather than a heap-only issue.
                        - Native memory pressure is supported by both pmap and NMT, so the process is carrying meaningful non-heap pressure at the same time.
                        Recommended actions:
                        1. Review dynamic class generation, classloader churn, and recent deployment behavior that could increase class metadata growth.
                        2. Reconcile the dominant NMT categories with the largest resident anonymous mappings so you can separate metaspace, thread stacks, and other native contributors.
                        """))
                    .build();
            }

            return delegate.doChat(chatRequest);
        }

        List<String> prompts() {
            return prompts;
        }
    }

    private Path createMatchingHeapHistogram(Path path) throws Exception {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         42000       16800000  java.util.LinkedHashMap
               2:         90000        9600000  [B
               3:         80000        6400000  java.lang.String
               4:         30000        4800000  java.util.LinkedHashMap$Entry
            Total        242000       36800000
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path createGcLogOverlappingIncidentWindow(Path path, ParsedArtifact jfrParsed) throws Exception {
        Instant incidentStart = incidentWindowStart(jfrParsed);
        Instant gcFirst = incidentStart;
        Instant gcSecond = incidentStart.plusMillis(350L);
        Instant bootstrap = gcFirst.minusSeconds(1L);

        String content = ""
            + "[" + bootstrap + "][0.100s][info][gc] Using G1\n"
            + "[" + gcFirst + "][1.100s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 1020M->1018M(1024M) 220.000ms\n"
            + "[" + gcSecond + "][1.450s][info][gc] GC(2) Pause Full (G1 Compaction Pause) 1022M->1020M(1024M) 260.000ms\n";
        Files.writeString(path, content);
        return path;
    }

    private Path createTimedThreadDump(Path path, Instant captureTime) throws Exception {
        String sample = Files.readString(Path.of("samples/thread_dump_deadlock.txt"));
        int firstNewline = sample.indexOf('\n');
        String threadDumpBody = firstNewline >= 0 ? sample.substring(firstNewline + 1).stripLeading() : sample;
        Files.writeString(path, "Capture time: " + captureTime + "\n" + threadDumpBody);
        return path;
    }

    private Path copySample(Path source, Path target) throws Exception {
        Files.copy(source, target);
        return target;
    }

    private Instant incidentWindowStart(ParsedArtifact jfrParsed) {
        return Instant.parse(firstIncidentWindow(jfrParsed).get("startTime").toString());
    }

    private Instant firstIncidentWindowMidpoint(ParsedArtifact jfrParsed) {
        Map<String, Object> incidentWindow = firstIncidentWindow(jfrParsed);
        Instant start = Instant.parse(incidentWindow.get("startTime").toString());
        Instant end = Instant.parse(incidentWindow.get("endTime").toString());
        if (!end.isAfter(start)) {
            return start;
        }
        return start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstIncidentWindow(ParsedArtifact jfrParsed) {
        List<Map<String, Object>> incidentWindows = (List<Map<String, Object>>) jfrParsed.extractedData().get("incidentWindows");
        return incidentWindows.getFirst();
    }
}
