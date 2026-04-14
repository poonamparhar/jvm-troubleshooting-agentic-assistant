package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserConsoleReportRendererTest {

    private final UserConsoleReportRenderer renderer = new UserConsoleReportRenderer();

    @Test
    void rendersUserFriendlyFullFidelityOutput() {
        AnalysisReport report = reportWithSelectedAiNarrative();

        String rendered = renderer.render(report);

        assertTrue(rendered.contains("Troubleshooting Assessment:"));
        assertTrue(rendered.contains("Key Metrics:"));
        assertTrue(rendered.contains("Issues Identified:"));
        assertTrue(rendered.contains("Recommended Actions:"));
        assertFalse(rendered.contains("Next Steps:"));
        assertFalse(rendered.contains("Next Useful Commands:"));
        assertTrue(rendered.contains("672.4 ms"));
        assertTrue(rendered.contains("99.8%"));
        assertTrue(rendered.contains("The current heap is too small for the live set or allocation pressure is overwhelming G1's ability to recover space."));
        assertFalse(rendered.contains("Capture a heap histogram or dump if safe, then correlate with NMT or pmap to separate heap pressure from mixed native pressure."));
        assertFalse(rendered.contains("Analysis ID:"));
        assertFalse(rendered.contains("Created At:"));
        assertFalse(rendered.contains("Severity:"));
        assertFalse(rendered.contains("Confidence:"));
        assertFalse(rendered.contains("Artifact:"));
        assertFalse(rendered.contains("Repeated long full GCs detected ["));
        assertFalse(rendered.contains("Agent Traceability:"));
        assertFalse(rendered.contains("Supervisor Trace:"));
        assertFalse(rendered.contains("Sharing:"));
        assertFalse(rendered.contains("[redacted-"));
    }

    @Test
    void refusesToRenderFallbackOnlyReports() {
        AnalysisReport report = reportWithDeterministicFallback();

        String rendered = renderer.render(report);

        assertTrue(rendered.contains("AI agent-backed troubleshooting analysis is unavailable for this report."));
        assertFalse(rendered.contains("Troubleshooting Assessment:"));
        assertFalse(rendered.contains("Repeated long full GCs detected"));
    }

    @Test
    void parsesMarkdownStyledSectionsWithoutDuplicatingKeyMetrics() {
        AnalysisReport report = reportWithMarkdownStyledNarrative();

        String rendered = renderer.render(report);

        assertEquals(1, occurrences(rendered, "Key Metrics:"));
        assertFalse(rendered.contains("**Summary:**"));
        assertFalse(rendered.contains("**Key metrics:**"));
        assertFalse(rendered.contains("Full GC summary:"));
        assertTrue(rendered.contains("pauseEventCount: 47"));
        assertTrue(rendered.contains("fullGcCount: 19"));
        assertTrue(rendered.contains("Issues Identified:"));
        assertTrue(rendered.contains("Recommended Actions:"));
        assertFalse(rendered.contains("Next Steps:"));
        assertFalse(rendered.contains("Correlate this with NMT or pmap to rule out mixed native pressure."));
    }

    @Test
    void foldsCorrelationSummaryIntoTheAssessmentWithoutASpecialHeading() {
        AnalysisReport report = reportWithCorrelationSummary();

        String rendered = renderer.render(report);

        assertFalse(rendered.contains("Cross-Artifact Context:"));
        assertTrue(rendered.contains("Across the provided diagnostics, the strongest shared signal is Metaspace pressure is corroborated across GC and NMT."));
    }

    private AnalysisReport reportWithSelectedAiNarrative() {
        InputArtifact artifact = artifact();
        Evidence fullGcSummary = evidence(
            "gc-full-gc-summary",
            "Full GC summary",
            Map.of(
                "fullGcCount", 19,
                "maxFullGcPauseMs", 672.4,
                "metaspaceTriggeredFullGcCount", 0
            )
        );
        Evidence heapOccupancy = evidence(
            "gc-heap-occupancy-peak",
            "Peak post-GC occupancy",
            Map.of(
                "afterHeapMb", 1023,
                "heapCapacityMb", 1024,
                "peakHeapOccupancyRatio", 0.998
            )
        );
        Finding fullGcFinding = finding(
            "gc-repeated-full-gcs",
            "Repeated long full GCs detected",
            "The GC log shows repeated full collections with multi-hundred-millisecond pauses.",
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            List.of(fullGcSummary.id())
        );
        Finding occupancyFinding = finding(
            "gc-heap-saturation",
            "Heap occupancy is near capacity after GC",
            "The heap remains close to full even after collection, leaving very little recovery headroom.",
            SeverityLevel.HIGH,
            ConfidenceLevel.HIGH,
            List.of(heapOccupancy.id())
        );
        RecommendedAction action = action();

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-user-1",
            LocalDateTime.of(2026, 3, 31, 15, 5),
            "GC pressure is driving long stop-the-world pauses and the heap is effectively saturated.",
            """
                Summary: The JVM is spending significant time in full GC because the live heap stays almost completely full after collection.
                Key metrics: 19 full GCs were observed, the longest full-GC pause reached 672.4 ms, and peak post-GC occupancy was 99.8% of the heap.
                Likely issues: The current heap is too small for the live set or allocation pressure is overwhelming G1's ability to recover space.
                Recommended actions: Treat this as a memory-pressure incident, capture heap evidence if safe, and review allocation spikes or cache growth.
                """.strip(),
            List.of(new AgentTraceability(
                "single-artifact-specialist-analysis",
                "GCLogAgent",
                AgentNarrativeSource.SPECIALIST_AGENT,
                ArtifactType.GC_LOG,
                List.of(artifact.metadata().sourcePath()),
                List.of(fullGcSummary.id(), heapOccupancy.id()),
                true,
                List.of(new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.PASSED, "Narrative present."))
            )),
            null,
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            List.of(artifact),
            List.of(),
            List.of(fullGcSummary, heapOccupancy),
            List.of(fullGcFinding, occupancyFinding),
            List.of(action),
            List.of(),
            List.of("jcmd <pid> GC.heap_info", "jmap -histo:live <pid>"),
            List.of(),
            null
        );
    }

    private AnalysisReport reportWithDeterministicFallback() {
        InputArtifact artifact = artifact();
        Evidence fullGcSummary = evidence(
            "gc-full-gc-summary",
            "Full GC summary",
            Map.of(
                "fullGcCount", 19,
                "maxFullGcPauseMs", 672.4
            )
        );
        Finding fullGcFinding = finding(
            "gc-repeated-full-gcs",
            "Repeated long full GCs detected",
            "The GC log shows repeated full collections with multi-hundred-millisecond pauses.",
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            List.of(fullGcSummary.id())
        );

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-user-2",
            LocalDateTime.of(2026, 3, 31, 15, 12),
            "Full GC pressure is likely the primary incident driver.",
            "GC specialist evidence points to repeated long full GCs and severe memory pressure.",
            List.of(
                new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "GCLogAgent",
                    AgentNarrativeSource.SPECIALIST_AGENT,
                    ArtifactType.GC_LOG,
                    List.of(artifact.metadata().sourcePath()),
                    List.of(fullGcSummary.id()),
                    false,
                    List.of(new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.FAILED, "Narrative missing evidence."))
                ),
                new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "DeterministicAssessmentFallback",
                    AgentNarrativeSource.DETERMINISTIC_FALLBACK,
                    ArtifactType.GC_LOG,
                    List.of(artifact.metadata().sourcePath()),
                    List.of(fullGcSummary.id()),
                    true,
                    List.of(new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.PASSED, "Narrative present."))
                )
            ),
            null,
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            List.of(artifact),
            List.of(),
            List.of(fullGcSummary),
            List.of(fullGcFinding),
            List.of(action()),
            List.of("A fresh heap histogram would confirm whether the live set is growing."),
            List.of("jcmd <pid> GC.heap_info"),
            List.of(),
            null
        );
    }

    private AnalysisReport reportWithMarkdownStyledNarrative() {
        AnalysisReport baseReport = reportWithSelectedAiNarrative();
        return baseReport.withUserNarrative("""
            **Summary:**
            The GC log indicates severe memory pressure because the live heap remains nearly full after collection.

            **Key metrics:**
            - pauseEventCount: 47
            - p50PauseMs: 26.806
            - p95PauseMs: 600.889
            - p99PauseMs: 681.585
            - maxPauseMs: 681.585
            - fullGcCount: 19

            **Likely issues:**
            The heap appears undersized for the live set or allocation pressure is overwhelming G1 recovery.

            **Recommended actions:**
            1. Increase the heap size or reduce retained data.
            2. Capture a histogram or heap dump if safe.

            **Next steps:**
            Correlate this with NMT or pmap to rule out mixed native pressure.
            """.strip());
    }

    private AnalysisReport reportWithCorrelationSummary() {
        AnalysisReport baseReport = reportWithSelectedAiNarrative();
        return new AnalysisReport(
            baseReport.schemaVersion(),
            baseReport.analysisId(),
            baseReport.createdAt(),
            baseReport.incidentSummary(),
            baseReport.userNarrative(),
            baseReport.agentTraceability(),
            baseReport.supervisorTrace(),
            baseReport.overallSeverity(),
            baseReport.confidence(),
            baseReport.inputArtifacts(),
            baseReport.parsedArtifacts(),
            baseReport.evidence(),
            baseReport.findings(),
            baseReport.recommendedActions(),
            baseReport.missingData(),
            baseReport.followUpCommands(),
            baseReport.artifactInventory(),
            new CorrelationResult(
                "Across the provided diagnostics, the strongest shared signal is Metaspace pressure is corroborated across GC and NMT.",
                ConfidenceLevel.HIGH,
                List.of(
                    finding(
                        "correlation-metaspace-class-pressure",
                        "Metaspace pressure is corroborated across GC and NMT",
                        "The GC and NMT artifacts both point to class-metadata pressure.",
                        SeverityLevel.HIGH,
                        ConfidenceLevel.HIGH,
                        List.of()
                    )
                ),
                List.of(),
                List.of(artifact().metadata().sourcePath(), "samples/single_process_data/java_nmt_summary_3391237.txt")
            )
        );
    }

    private InputArtifact artifact() {
        return new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/srv/prod/apps/orders/current/g1_21_smallheap_fullgcs.log",
                "g1_21_smallheap_fullgcs.log",
                4096L,
                LocalDateTime.of(2026, 3, 31, 15, 0),
                Map.of()
            ),
            "sample gc log"
        );
    }

    private Evidence evidence(String id, String label, Map<String, Object> metrics) {
        Map<String, Object> orderedMetrics = new LinkedHashMap<>(metrics);
        return new Evidence(
            id,
            "/srv/prod/apps/orders/current/g1_21_smallheap_fullgcs.log",
            label,
            label + " detail",
            null,
            List.of(42),
            orderedMetrics
        );
    }

    private Finding finding(
        String id,
        String title,
        String summary,
        SeverityLevel severity,
        ConfidenceLevel confidence,
        List<String> evidenceIds
    ) {
        return new Finding(
            id,
            title,
            summary,
            "memory",
            severity,
            confidence,
            FindingStatus.CONFIRMED,
            List.of("/srv/prod/apps/orders/current/g1_21_smallheap_fullgcs.log"),
            evidenceIds,
            "The evidence indicates incident-grade heap pressure."
        );
    }

    private RecommendedAction action() {
        return new RecommendedAction(
            "action-1",
            "Treat the heap as saturated and gather immediate supporting evidence.",
            "Repeated long full GCs and near-full post-GC occupancy indicate severe memory pressure.",
            ActionType.DATA_COLLECTION,
            ActionPriority.URGENT,
            List.of(
                "Capture a heap histogram or heap dump if safe to do so.",
                "Review recent allocation spikes and cache growth."
            ),
            List.of("gc-repeated-full-gcs")
        );
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
