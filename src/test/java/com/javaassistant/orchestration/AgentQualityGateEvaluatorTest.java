package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.context.ContextCoverage;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AgentToolInvocation;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentQualityGateEvaluatorTest {

    private final AgentQualityGateEvaluator evaluator = new AgentQualityGateEvaluator();
    private static final ModelExecutionTraceability TEST_MODEL_EXECUTION = new ModelExecutionTraceability(
        "TEST",
        "Test Chat Model",
        "RoutingStubChatModel",
        "RoutingStubChatModel",
        "JfrAgent.analyze",
        "v1"
    );

    @Test
    void failsWhenCoverageIsIncompleteAndNoRetrievalOccurred() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap saturation may be the issue, but more data may still help.",
            List.of("Heap saturation"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(new ContextCoverage("/tmp/gc.log", List.of("summary"), List.of("raw-tail"), List.of(), List.of(), true)),
            List.of(),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.FAILED
        ));
    }

    @Test
    void passesCoverageAwareConfidenceGateAfterRetrievalWhenCoverageLooksResolved() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap saturation remains the most likely issue after reviewing additional GC context.",
            List.of("Heap saturation"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(new ContextCoverage("/tmp/gc.log", List.of("summary"), List.of("raw-tail"), List.of(), List.of(), true)),
            List.of(new AgentToolInvocation(
                "fetchGcContext",
                "RETRIEVAL",
                ArtifactType.GC_LOG,
                "/tmp/gc.log",
                "gcId=45",
                "raw-search-gc-45",
                "GC event 45",
                "/tmp/gc.log lines 640-648",
                false,
                false
            )),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    @Test
    void failsHighlyCertainNarrativeWhenRetrievedContextStillReportsMoreAvailable() {
        List<?> gates = evaluator.evaluate(
            "Summary: This clearly proves the root cause is heap saturation.",
            List.of("Heap saturation"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(new ContextCoverage("/tmp/gc.log", List.of("summary"), List.of("raw-tail"), List.of(), List.of(), true)),
            List.of(new AgentToolInvocation(
                "fetchGcContext",
                "RETRIEVAL",
                ArtifactType.GC_LOG,
                "/tmp/gc.log",
                "",
                "raw-chunk-013",
                "Raw file chunk 13",
                "/tmp/gc.log lines 289-312",
                false,
                true
            )),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.FAILED
        ));
    }

    @Test
    void passesWhenRetrievedContextStillHasMoreAvailableButNarrativeStatesUncertainty() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap saturation is likely, but more GC context may still change the picture.",
            List.of("Heap saturation"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(new ContextCoverage("/tmp/gc.log", List.of("summary"), List.of("raw-tail"), List.of(), List.of(), true)),
            List.of(new AgentToolInvocation(
                "fetchGcContext",
                "RETRIEVAL",
                ArtifactType.GC_LOG,
                "/tmp/gc.log",
                "",
                "raw-chunk-013",
                "Raw file chunk 13",
                "/tmp/gc.log lines 289-312",
                false,
                true
            )),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    @Test
    void passesWhenALaterRetrievalResolvesAnEarlierIncompleteOne() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap saturation remains the most likely issue after reviewing the additional GC context.",
            List.of("Heap saturation"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(new ContextCoverage("/tmp/gc.log", List.of("summary"), List.of("raw-tail"), List.of(), List.of(), true)),
            List.of(
                new AgentToolInvocation(
                    "fetchGcContext",
                    "RETRIEVAL",
                    ArtifactType.GC_LOG,
                    "/tmp/gc.log",
                    "",
                    "raw-chunk-013",
                    "Raw file chunk 13",
                    "/tmp/gc.log lines 289-312",
                    false,
                    true
                ),
                new AgentToolInvocation(
                    "fetchGcContext",
                    "RETRIEVAL",
                    ArtifactType.GC_LOG,
                    "/tmp/gc.log",
                    "gcId=45",
                    "raw-search-gc-45",
                    "GC event 45",
                    "/tmp/gc.log lines 640-648",
                    false,
                    false
                )
            ),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    @Test
    void failsWhenCoverageWasIncompleteForOneArtifactButOnlyAnotherArtifactWasRetrieved() {
        List<?> gates = evaluator.evaluate(
            "Summary: The current heap histogram looks most suspicious.",
            List.of("Heap growth"),
            List.of("histogram-growth"),
            List.of(),
            List.of(
                new ContextCoverage("/tmp/baseline.histo", List.of("summary"), List.of(), List.of(), List.of(), true),
                new ContextCoverage("/tmp/current.histo", List.of("summary"), List.of(), List.of(), List.of(), true)
            ),
            List.of(new AgentToolInvocation(
                "fetchHistogramContext",
                "RETRIEVAL",
                ArtifactType.HEAP_HISTOGRAM,
                "/tmp/current.histo",
                "",
                "section13",
                "Additional structured context: Section13",
                "extractedData.section13",
                false,
                false
            )),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("coverage-aware-confidence")
                && result.status() == AgentQualityGateStatus.FAILED
        ));
    }

    @Test
    void failsWhenModelExecutionTraceabilityIsMissing() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap pressure remains elevated.",
            List.of("Heap pressure"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("model-execution-traceability")
                && result.status() == AgentQualityGateStatus.FAILED
        ));
    }
}
