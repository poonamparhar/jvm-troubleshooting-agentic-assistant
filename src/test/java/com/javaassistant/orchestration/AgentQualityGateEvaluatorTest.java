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
    void passesWhenCoverageIsIncompleteButNarrativeClearlyStatesUncertainty() {
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
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    @Test
    void warnsWhenCoverageIsIncompleteAndNoRetrievalOccurredWithoutClearUncertainty() {
        List<?> gates = evaluator.evaluate(
            "Summary: Heap saturation remains the main issue in this GC log.",
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
                && result.status() == AgentQualityGateStatus.WARNING
        ));
    }

    @Test
    void failsWhenCoverageIsIncompleteAndNarrativeStaysHighlyCertainWithoutRetrieval() {
        List<?> gates = evaluator.evaluate(
            "Summary: This clearly proves the root cause is heap saturation.",
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
    void passesCoverageAwareConfidenceGateAfterComputationWhenCoverageLooksResolved() {
        List<?> gates = evaluator.evaluate(
            "Summary: The checkout hotspot remains the clearest regression lead after comparing focused execution summaries.",
            List.of("checkout hotspot"),
            List.of("jfr-execution-hotspot"),
            List.of(),
            List.of(new ContextCoverage("/tmp/current.jfr", List.of("executionHotspotSummary"), List.of(), List.of(), List.of(), true)),
            List.of(new AgentToolInvocation(
                "computeJfrView",
                "COMPUTATION",
                ArtifactType.JFR,
                "/tmp/current.jfr",
                "execution-hotspots",
                "jfr-execution-hotspots",
                "JFR execution hotspot computation view",
                "extractedData.executionHotspotSummary",
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
            "Summary: This clearly proves the current heap histogram is the root cause.",
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

    @Test
    void includesInvocationFailureDetailWhenTheModelCallThrowsBeforeResponding() {
        List<?> gates = evaluator.evaluate(
            null,
            List.of("Deadlock"),
            List.of("thread-dump-deadlock"),
            List.of(),
            List.of(),
            List.of(),
            TEST_MODEL_EXECUTION,
            "OCI 401 unauthorized"
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("response-not-empty")
                && result.status() == AgentQualityGateStatus.FAILED
                && result.detail().contains("OCI 401 unauthorized")
        ));
    }

    @Test
    void failsWhenRequiredTroubleshootingSectionsAreMissing() {
        List<?> gates = evaluator.evaluate(
            """
                Summary:
                Heap pressure remains elevated.
                Likely issues:
                - Full GC activity suggests the heap is saturated.
                """,
            List.of("Heap pressure"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(),
            List.of(),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("troubleshooting-response-structure")
                && result.status() == AgentQualityGateStatus.FAILED
                && result.detail().contains("Key metrics:")
                && result.detail().contains("Recommended actions:")
        ));
    }

    @Test
    void passesWhenRequiredTroubleshootingSectionsArePresent() {
        List<?> gates = evaluator.evaluate(
            structuredNarrative(),
            List.of("Heap pressure"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(),
            List.of(),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("troubleshooting-response-structure")
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    @Test
    void acceptsEmphasizedSectionHeadingsWhenTheyRemainParseable() {
        List<?> gates = evaluator.evaluate(
            """
                **Summary:** Heap pressure remains elevated.
                **Key metrics:**
                - fullGcCount: 3
                - maxPauseMs: 681.585
                **Likely issues:**
                - Repeated full GCs indicate the heap is close to saturation.
                **Recommended actions:**
                1. Capture a heap histogram.
                2. Review allocation spikes.
                """,
            List.of("Heap pressure"),
            List.of("gc-heap-occupancy-peak"),
            List.of(),
            List.of(),
            List.of(),
            TEST_MODEL_EXECUTION
        );

        assertTrue(gates.stream().anyMatch(item ->
            item instanceof com.javaassistant.diagnostics.AgentQualityGateResult result
                && result.gateId().equals("troubleshooting-response-structure")
                && result.status() == AgentQualityGateStatus.PASSED
        ));
    }

    private String structuredNarrative() {
        return """
            Summary:
            Heap pressure remains elevated after GC.
            Key metrics:
            - fullGcCount: 3
            - maxPauseMs: 681.585
            Likely issues:
            - Repeated full GCs indicate the heap is saturated.
            Recommended actions:
            1. Capture a heap histogram.
            2. Review recent allocation spikes.
            """;
    }
}
