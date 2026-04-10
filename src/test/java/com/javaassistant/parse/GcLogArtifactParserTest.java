package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcLogArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser parser = new GcLogArtifactParser();

    @Test
    void parsesG1GcLogSummary() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> failureSummary = (Map<String, Object>) parsed.extractedData().get("failureSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> phaseSummary = (Map<String, Object>) parsed.extractedData().get("phaseSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuSummary = (Map<String, Object>) parsed.extractedData().get("cpuSummary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("eventCount")).longValue() > 0);
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() > 0.0);
        assertTrue(((Number) summary.get("fullGcCount")).longValue() >= 3L);
        assertTrue(((Number) pauses.getFirst().get("lineNumber")).longValue() > 0L);
        assertTrue(String.valueOf(pauses.getFirst().get("absoluteTimestamp")).startsWith("2024-04-16T08:32"));
        assertTrue(pauses.stream().anyMatch(pause -> "Evacuation Failure".equals(String.valueOf(pause.get("cause")))));
        assertTrue(((Number) failureSummary.get("evacuationFailurePauseCount")).longValue() > 0L);
        assertTrue(((Number) failureSummary.get("concurrentMarkAbortCount")).longValue() > 0L);
        assertTrue(((Number) failureSummary.get("fullCompactionAttemptCount")).longValue() > 0L);
        assertTrue(((Number) phaseSummary.get("phaseSampleCount")).longValue() > 0L);
        assertTrue(((Number) cpuSummary.get("maxRealSeconds")).doubleValue() > 0.0d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-full-gc-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-heap-occupancy-peak")));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void detectsZgcCollector() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_fullgc.log")));
        @SuppressWarnings("unchecked")
        Map<String, Object> phaseSummary = (Map<String, Object>) parsed.extractedData().get("phaseSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> workerSummary = (Map<String, Object>) parsed.extractedData().get("workerSummary");

        assertEquals("ZGC", parsed.extractedData().get("collector"));
        assertTrue(((Number) phaseSummary.get("phaseSampleCount")).longValue() > 0L);
        assertTrue(((Number) workerSummary.get("sampleCount")).longValue() > 0L);
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void parsesZgcAllocationStallSummary() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_allocation_stall.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> concurrentSummary = (Map<String, Object>) parsed.extractedData().get("concurrentSummary");

        assertEquals("ZGC", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("allocationStallCount")).longValue() >= 3L);
        assertTrue(((Number) summary.get("gcCycleCount")).longValue() > 0L);
        assertTrue(((Number) concurrentSummary.get("longestConcurrentPhaseMs")).doubleValue() > 0.0d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-allocation-stall-summary")));
    }

    @Test
    void parsesMetaspaceTriggeredFullGcSignals() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");
        @SuppressWarnings("unchecked")
        Map<String, Object> failureSummary = (Map<String, Object>) parsed.extractedData().get("failureSummary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("metaspaceTriggeredFullGcCount")).longValue() >= 2L);
        assertTrue(((Number) metaspace.get("peakUsageRatio")).doubleValue() >= 0.80d);
        assertTrue(((Number) failureSummary.get("metadataTriggeredPauseCount")).longValue() >= 1L);
        assertTrue(((Number) failureSummary.get("metadataClearSoftReferencesFullGcCount")).longValue() >= 1L);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-metaspace-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-full-gc-summary")));
    }

    @Test
    void parsesUnifiedG1PauseLinesWithGigabyteUnits() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_unified_gc_gigabyte_units.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(5) Pause Init Mark (unload classes) 1.250ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(6) Pause Full (G1 Compaction Pause) 1.50G->1.10G(2.00G) 12.750ms
                [2026-04-03T10:00:02.010-0700][2.110s][info][gc] GC(6) Metaspace: 768M(800M)->760M(800M)
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(2L, ((Number) summary.get("pauseEventCount")).longValue());
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() >= 12.75d);
        assertTrue(pauses.stream().anyMatch(pause -> "INIT_MARK".equals(String.valueOf(pause.get("pauseType")))));
        assertEquals(1L, ((Number) metaspace.get("snapshotCount")).longValue());
        assertTrue(((Number) metaspace.get("peakUsageRatio")).doubleValue() >= 0.90d);
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void summarizesRecoveryAndPauseCauseSeverityAcrossYoungMixedAndFullPauses() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_recovery_summary.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 900M->700M(1024M) 25.000ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(2) Pause Young (Mixed) (G1 Evacuation Pause) 950M->880M(1024M) 35.000ms
                [2026-04-03T10:00:03.000-0700][3.100s][info][gc] GC(3) Pause Full (G1 Compaction Pause) 1023M->1010M(1024M) 120.000ms
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> pauseBreakdown = (Map<String, Object>) parsed.extractedData().get("pauseBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Object> recoverySummary = (Map<String, Object>) parsed.extractedData().get("recoverySummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> causeTotalPauseMs = (Map<String, Object>) pauseBreakdown.get("causeTotalPauseMs");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals("G1 Evacuation Pause", pauseBreakdown.get("dominantPauseCauseByCount"));
        assertEquals("G1 Compaction Pause", pauseBreakdown.get("dominantPauseCauseByTotalPauseMs"));
        assertEquals(60.0d, ((Number) causeTotalPauseMs.get("G1 Evacuation Pause")).doubleValue());
        assertEquals(120.0d, ((Number) causeTotalPauseMs.get("G1 Compaction Pause")).doubleValue());
        assertEquals(1L, ((Number) recoverySummary.get("youngPauseCount")).longValue());
        assertEquals(1L, ((Number) recoverySummary.get("mixedPauseCount")).longValue());
        assertEquals(1L, ((Number) recoverySummary.get("fullPauseCount")).longValue());
        assertEquals(2L, ((Number) recoverySummary.get("highRetainedOccupancyPauseCount")).longValue());
        assertEquals(1L, ((Number) recoverySummary.get("nearCapacityAfterGcCount")).longValue());
        assertEquals(1L, ((Number) recoverySummary.get("maxNearCapacityPauseStreak")).longValue());
        assertTrue(((Number) recoverySummary.get("averageReclaimedMb")).doubleValue() > 90.0d);
        assertTrue(((Number) recoverySummary.get("averageMixedPostGcOccupancyRatio")).doubleValue() > 0.85d);
        assertTrue(((Number) recoverySummary.get("averageFullPostGcOccupancyRatio")).doubleValue() > 0.98d);
    }

    @Test
    void summarizesG1MixedToFullProgressionAndWeakFullGcRecovery() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_cycle_progression.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 900M->780M(1024M) 20.000ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(2) Pause Young (Mixed) (G1 Evacuation Pause) 980M->940M(1024M) 30.000ms
                [2026-04-03T10:00:03.000-0700][3.100s][info][gc] GC(3) Pause Young (Mixed) (G1 Evacuation Pause) 1000M->955M(1024M) 35.000ms
                [2026-04-03T10:00:04.000-0700][4.100s][info][gc] GC(4) Pause Full (G1 Compaction Pause) 1020M->1002M(1024M) 120.000ms
                [2026-04-03T10:00:05.000-0700][5.100s][info][gc] GC(5) Pause Full (G1 Compaction Pause) 1022M->1006M(1024M) 140.000ms
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> g1CycleProgressionSummary = (Map<String, Object>) parsed.extractedData().get("g1CycleProgressionSummary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(1L, ((Number) g1CycleProgressionSummary.get("youngPausesBeforeFirstMixedPause")).longValue());
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("mixedPausesBeforeFirstFullGc")).longValue());
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("fullGcCountAfterMixedPhase")).longValue());
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("highRetainedMixedPauseCount")).longValue());
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("lowReclaimHighRetentionFullGcCount")).longValue());
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("maxLowReclaimHighRetentionFullGcStreak")).longValue());
        assertEquals(17.0d, ((Number) g1CycleProgressionSummary.get("averageFullGcReclaimedMb")).doubleValue(), 0.0001d);
        assertEquals(0.0166015625d, ((Number) g1CycleProgressionSummary.get("averageFullGcReclaimedPctOfHeap")).doubleValue(), 0.0000001d);
        assertEquals(2L, ((Number) g1CycleProgressionSummary.get("firstMixedGcId")).longValue());
        assertEquals(4L, ((Number) g1CycleProgressionSummary.get("firstFullGcAfterMixedGcId")).longValue());
        assertEquals(4L, ((Number) g1CycleProgressionSummary.get("firstLowReclaimHighRetentionFullGcId")).longValue());
    }

    @Test
    void summarizesCollectorPressureForG1MixedToFullPressure() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_collector_pressure.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(1) Pause Young (Mixed) (G1 Evacuation Pause) 980M->940M(1024M) 30.000ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(2) Pause Young (Mixed) (G1 Evacuation Pause) 1000M->955M(1024M) 35.000ms
                [2026-04-03T10:00:03.000-0700][3.100s][info][gc] GC(3) Pause Full (G1 Compaction Pause) 1020M->1002M(1024M) 120.000ms
                [2026-04-03T10:00:04.000-0700][4.100s][info][gc] GC(4) Pause Full (G1 Compaction Pause) 1022M->1006M(1024M) 140.000ms
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> collectorPressureSummary = (Map<String, Object>) parsed.extractedData().get("collectorPressureSummary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals("G1", collectorPressureSummary.get("collector"));
        assertEquals(2L, ((Number) collectorPressureSummary.get("fullGcCount")).longValue());
        assertEquals(2L, ((Number) collectorPressureSummary.get("maxFullGcStreak")).longValue());
        assertEquals(2L, ((Number) collectorPressureSummary.get("mixedPausesBeforeFirstFullGc")).longValue());
        assertEquals(2L, ((Number) collectorPressureSummary.get("lowReclaimHighRetentionFullGcCount")).longValue());
        assertEquals(2L, ((Number) collectorPressureSummary.get("maxLowReclaimHighRetentionFullGcStreak")).longValue());
        assertEquals("G1 Compaction Pause", collectorPressureSummary.get("dominantPauseCauseByTotalPauseMs"));
    }

    @Test
    void parsesInterleavedOrRotatedGcLogFragments() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_interleaved_rotated.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(11) Pause Young (Normal) (G1 Evacuation Pause) 900M->700M(1024M) 25.000ms
                [2026-04-03T09:59:59.500-0700][0.950s][info][gc] GC(10) Pause Young (Normal) (G1 Evacuation Pause) 880M->690M(1024M) 24.000ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(12) Pause Full (G1 Compaction Pause) 1010M->998M(1024M) 140.000ms
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(3L, ((Number) summary.get("pauseEventCount")).longValue());
        assertEquals(1L, ((Number) summary.get("fullGcCount")).longValue());
    }

    @Test
    void parsesTruncatedGcLogWithoutDroppingEarlierEvents() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_truncated.log",
            """
                [2026-04-03T10:00:00.000-0700][0.100s][info][gc] Using G1
                [2026-04-03T10:00:01.000-0700][1.100s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 900M->700M(1024M) 25.000ms
                [2026-04-03T10:00:02.000-0700][2.100s][info][gc] GC(6) Pause Full (G1 Compaction
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(1L, ((Number) summary.get("pauseEventCount")).longValue());
        assertEquals(0L, ((Number) summary.get("fullGcCount")).longValue());
    }

    @Test
    void parsesLegacyParallelGcLinesWithKilobyteUnits() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/parallel_legacy_gc.log",
            """
                2026-04-03T10:00:00.000+0000: 12.345: [GC (Allocation Failure) [PSYoungGen: 65536K->10744K(76288K)] 65536K->10752K(251392K), 0.0131945 secs] [Times: user=0.04 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:10.000+0000: 22.345: [Full GC (Metadata GC Threshold) [PSYoungGen: 10752K->0K(76288K)] [ParOldGen: 44016K->44010K(175104K)] 54768K->44010K(251392K), [Metaspace: 21234K->21234K(1067008K)], 0.0889058 secs] [Times: user=0.19 sys=0.00, real=0.09 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuSummary = (Map<String, Object>) parsed.extractedData().get("cpuSummary");

        assertEquals("Parallel", parsed.extractedData().get("collector"));
        assertEquals(2L, ((Number) summary.get("pauseEventCount")).longValue());
        assertEquals(1L, ((Number) summary.get("fullGcCount")).longValue());
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() >= 80.0d);
        assertTrue(pauses.stream().anyMatch(pause -> "Allocation Failure".equals(String.valueOf(pause.get("cause")))));
        assertTrue(pauses.getFirst().containsKey("elapsedSeconds"));
        assertEquals(1L, ((Number) metaspace.get("snapshotCount")).longValue());
        assertEquals(2L, ((Number) cpuSummary.get("sampleCount")).longValue());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void detectsCmsAndSerialCollectorsFromLegacyMarkers() {
        var cmsParsed = parser.parse(syntheticArtifact(
            "samples/cms_legacy_gc.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC (Allocation Failure) [ParNew: 65536K->4096K(76032K), 0.0056789 secs] 350000K->289000K(524288K), 0.0156789 secs] [Times: user=0.02 sys=0.00, real=0.02 secs]
                """
        ));
        var serialParsed = parser.parse(syntheticArtifact(
            "samples/serial_legacy_gc.log",
            """
                2026-04-03T10:00:00.000+0000: 0.123: [GC (Allocation Failure) [DefNew: 4416K->512K(4928K), 0.0020000 secs] 4416K->2048K(15872K), 0.0030000 secs] [Times: user=0.00 sys=0.00, real=0.00 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> cmsSummary = (Map<String, Object>) cmsParsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> serialSummary = (Map<String, Object>) serialParsed.extractedData().get("summary");

        assertEquals("CMS", cmsParsed.extractedData().get("collector"));
        assertEquals("Serial", serialParsed.extractedData().get("collector"));
        assertEquals(1L, ((Number) cmsSummary.get("pauseEventCount")).longValue());
        assertEquals(1L, ((Number) serialSummary.get("pauseEventCount")).longValue());
    }

    @Test
    void parsesLegacyCmsInitialMarkRemarkAndConcurrentPhaseSignals() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/cms_legacy_distress.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC (CMS Initial Mark) [1 CMS-initial-mark: 350000K(524288K)] 360000K(713280K), 0.0123456 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:00.500+0000: 1.734: [CMS-concurrent-mark: 0.120/0.450 secs] [Times: user=0.40 sys=0.00, real=0.45 secs]
                2026-04-03T10:00:01.000+0000: 2.234: [GC (CMS Final Remark) [YG occupancy: 18083 K (188992 K)]2026-04-03T10:00:01.000+0000: 2.234: [Rescan (parallel) , 0.0004028 secs]2026-04-03T10:00:01.001+0000: 2.235: [weak refs processing, 0.0000118 secs] [1 CMS-remark: 349932K(524288K)] 368016K(713280K), 0.0044493 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:02.000+0000: 3.234: [Full GC (concurrent mode failure) [CMS: 500000K->498000K(524288K)] 530000K->500000K(713280K), 0.2500000 secs] [Times: user=0.30 sys=0.00, real=0.25 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> phaseSummary = (Map<String, Object>) parsed.extractedData().get("phaseSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> concurrentSummary = (Map<String, Object>) parsed.extractedData().get("concurrentSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> failureSummary = (Map<String, Object>) parsed.extractedData().get("failureSummary");

        assertEquals("CMS", parsed.extractedData().get("collector"));
        assertEquals(3L, ((Number) summary.get("pauseEventCount")).longValue());
        assertEquals(1L, ((Number) summary.get("fullGcCount")).longValue());
        assertTrue(pauses.stream().anyMatch(pause -> "INIT_MARK".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "REMARK".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "FULL".equals(String.valueOf(pause.get("pauseType")))));
        assertEquals(1L, ((Number) phaseSummary.get("phaseSampleCount")).longValue());
        assertEquals(1L, ((Number) concurrentSummary.get("concurrentPhaseCount")).longValue());
        assertTrue(((Number) concurrentSummary.get("longestConcurrentPhaseMs")).doubleValue() >= 450.0d);
        assertEquals(1L, ((Number) failureSummary.get("concurrentModeFailureCount")).longValue());
        assertTrue(pauses.stream().allMatch(pause -> pause.containsKey("elapsedSeconds")));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void parsesCmsPromotionFailureSignals() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/cms_legacy_promotion_failure.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC (CMS Initial Mark) [1 CMS-initial-mark: 350000K(524288K)] 360000K(713280K), 0.0123456 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:01.000+0000: 2.234: [Full GC (promotion failed) [CMS: 500000K->498000K(524288K)] 530000K->500000K(713280K), 0.2500000 secs] [Times: user=0.30 sys=0.00, real=0.25 secs]
                2026-04-03T10:00:02.000+0000: 3.234: [Full GC (promotion failed) [CMS: 501000K->499000K(524288K)] 531000K->501000K(713280K), 0.2600000 secs] [Times: user=0.30 sys=0.00, real=0.26 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> failureSummary = (Map<String, Object>) parsed.extractedData().get("failureSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("CMS", parsed.extractedData().get("collector"));
        assertEquals(2L, ((Number) failureSummary.get("promotionFailedCount")).longValue());
        assertEquals(2L, ((Number) summary.get("fullGcCount")).longValue());
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-full-gc-summary")));
    }

    @Test
    void summarizesCollectorPressureForLegacyCmsFallback() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/cms_legacy_pressure.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC (CMS Initial Mark) [1 CMS-initial-mark: 350000K(524288K)] 360000K(713280K), 0.0123456 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:00.500+0000: 1.734: [CMS-concurrent-mark: 0.120/0.450 secs] [Times: user=0.40 sys=0.00, real=0.45 secs]
                2026-04-03T10:00:01.000+0000: 2.234: [Full GC (concurrent mode failure) [CMS: 500000K->498000K(524288K)] 530000K->500000K(713280K), 0.2500000 secs] [Times: user=0.30 sys=0.00, real=0.25 secs]
                2026-04-03T10:00:02.000+0000: 3.234: [GC (CMS Initial Mark) [1 CMS-initial-mark: 350000K(524288K)] 360000K(713280K), 0.0123456 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:02.500+0000: 3.734: [CMS-concurrent-mark: 0.120/0.450 secs] [Times: user=0.40 sys=0.00, real=0.45 secs]
                2026-04-03T10:00:03.000+0000: 4.234: [Full GC (concurrent mode failure) [CMS: 501000K->499000K(524288K)] 531000K->501000K(713280K), 0.2600000 secs] [Times: user=0.30 sys=0.00, real=0.26 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> collectorPressureSummary = (Map<String, Object>) parsed.extractedData().get("collectorPressureSummary");

        assertEquals("CMS", parsed.extractedData().get("collector"));
        assertEquals("CMS", collectorPressureSummary.get("collector"));
        assertEquals(2L, ((Number) collectorPressureSummary.get("concurrentModeFailureCount")).longValue());
        assertEquals(2L, ((Number) collectorPressureSummary.get("maxConcurrentModeFailureStreak")).longValue());
        assertTrue(((Number) collectorPressureSummary.get("longestConcurrentPhaseMs")).doubleValue() >= 450.0d);
        assertTrue(((Number) collectorPressureSummary.get("averageFullPostGcOccupancyRatio")).doubleValue() >= 0.69d);
    }

    @Test
    void detectsSerialCollectorFromTenuredOnlyLegacyFullGcLine() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/serial_tenured_only_gc.log",
            """
                2026-04-03T10:00:00.000+0000: 12.345: [Full GC (Allocation Failure) [Tenured: 12000K->10000K(15872K)] 13000K->10000K(20000K), 0.1230000 secs] [Times: user=0.20 sys=0.00, real=0.12 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("Serial", parsed.extractedData().get("collector"));
        assertEquals(1L, ((Number) summary.get("pauseEventCount")).longValue());
        assertEquals(1L, ((Number) summary.get("fullGcCount")).longValue());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void summarizesCollectorPressureForLegacySerialFullGcStreak() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/serial_pressure.log",
            """
                2026-04-03T10:00:00.000+0000: 1.000: [Full GC (Allocation Failure) [Tenured: 19870K->19670K(20000K)] 19870K->19670K(20000K), 0.2400000 secs] [Times: user=0.24 sys=0.00, real=0.24 secs]
                2026-04-03T10:00:01.000+0000: 2.000: [Full GC (Allocation Failure) [Tenured: 19890K->19690K(20000K)] 19890K->19690K(20000K), 0.2600000 secs] [Times: user=0.24 sys=0.00, real=0.26 secs]
                2026-04-03T10:00:02.000+0000: 3.000: [Full GC (Allocation Failure) [Tenured: 19910K->19710K(20000K)] 19910K->19710K(20000K), 0.2800000 secs] [Times: user=0.24 sys=0.00, real=0.28 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> collectorPressureSummary = (Map<String, Object>) parsed.extractedData().get("collectorPressureSummary");

        assertEquals("Serial", parsed.extractedData().get("collector"));
        assertEquals("Serial", collectorPressureSummary.get("collector"));
        assertEquals(3L, ((Number) collectorPressureSummary.get("fullGcCount")).longValue());
        assertEquals(3L, ((Number) collectorPressureSummary.get("maxFullGcStreak")).longValue());
        assertEquals("Allocation Failure", collectorPressureSummary.get("dominantPauseCauseByTotalPauseMs"));
        assertTrue(((Number) collectorPressureSummary.get("averageFullPostGcOccupancyRatio")).doubleValue() >= 0.98d);
    }

    @Test
    void parsesLegacyG1PauseBlocksWithAttachedHeapSignalsAndToSpaceDistress() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_legacy_pause_blocks.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC pause (G1 Evacuation Pause) (young) (initial-mark), 0.0123456 secs]
                   [Parallel Time: 11.0 ms, GC Workers: 8]
                   [Humongous regions: 2->1]
                   [Eden: 64.0M(64.0M)->0.0B(50.0M) Survivors: 0.0B->8192.0K Heap: 180.0M(256.0M)->120.0M(256.0M)]
                   [Times: user=0.04 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:01.000+0000: 2.234: [GC pause (G1 Evacuation Pause) (mixed) (to-space exhausted), 0.1957310 secs]
                   [Parallel Time: 195.0 ms, GC Workers: 8]
                   [Humongous regions: 10->14]
                   [Eden: 32.0M(64.0M)->0.0B(40.0M) Survivors: 8192.0K->8192.0K Heap: 250.0M(256.0M)->248.0M(256.0M)]
                   [Metaspace: 21234K->21234K(1067008K)]
                   [Times: user=1.52 sys=0.01, real=0.20 secs]
                2026-04-03T10:00:02.000+0000: 3.234: [GC pause (G1 Evacuation Pause) (young) (to-space overflow), 0.0300000 secs]
                   [Humongous regions: 14->16]
                   [Eden: 32.0M(40.0M)->0.0B(30.0M) Survivors: 8192.0K->8192.0K Heap: 252.0M(256.0M)->249.0M(256.0M)]
                   [Times: user=0.06 sys=0.00, real=0.03 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> failureSummary = (Map<String, Object>) parsed.extractedData().get("failureSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> workerSummary = (Map<String, Object>) parsed.extractedData().get("workerSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuSummary = (Map<String, Object>) parsed.extractedData().get("cpuSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> humongousSummary = (Map<String, Object>) parsed.extractedData().get("humongousSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(3L, ((Number) summary.get("pauseEventCount")).longValue());
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() >= 195.731d);
        assertTrue(pauses.stream().anyMatch(pause -> "INIT_MARK".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "MIXED".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "To-space exhausted".equals(String.valueOf(pause.get("cause")))));
        assertTrue(pauses.stream().anyMatch(pause -> "To-space overflow".equals(String.valueOf(pause.get("cause")))));
        assertTrue(pauses.stream().anyMatch(pause ->
            "INIT_MARK".equals(String.valueOf(pause.get("pauseType")))
                && ((Number) pause.get("beforeHeapMb")).longValue() == 180L
                && ((Number) pause.get("afterHeapMb")).longValue() == 120L
        ));
        assertEquals(2L, ((Number) failureSummary.get("toSpaceExhaustedCount")).longValue());
        assertEquals(2L, ((Number) workerSummary.get("sampleCount")).longValue());
        assertEquals(3L, ((Number) cpuSummary.get("sampleCount")).longValue());
        assertEquals(3L, ((Number) humongousSummary.get("sampleCount")).longValue());
        assertEquals(16L, ((Number) humongousSummary.get("peakAfterRegions")).longValue());
        assertEquals(1L, ((Number) metaspace.get("snapshotCount")).longValue());
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-humongous-summary")));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void parsesLegacyG1ConcurrentPhaseLinesAndCleanupRemarkPauses() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/g1_legacy_concurrent_phases.log",
            """
                2026-04-03T10:00:00.000+0000: 0.900: [GC pause (G1 Evacuation Pause) (young), 0.0020000 secs]
                   [Eden: 20.0M(20.0M)->0.0B(18.0M) Survivors: 4096.0K->4096.0K Heap: 120.0M(256.0M)->100.0M(256.0M)]
                2026-04-03T10:00:01.000+0000: 1.000: [GC concurrent-root-region-scan-start]
                2026-04-03T10:00:01.010+0000: 1.010: [GC concurrent-root-region-scan-end, 0.0100000 secs]
                2026-04-03T10:00:01.011+0000: 1.011: [GC concurrent-mark-start]
                2026-04-03T10:00:01.200+0000: 1.200: [GC concurrent-mark-end, 0.1890000 secs]
                2026-04-03T10:00:01.201+0000: 1.201: [GC cleanup 200M->180M(256M), 0.0030000 secs]
                2026-04-03T10:00:01.205+0000: 1.205: [GC remark 180M->170M(256M), 0.0040000 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> phaseSummary = (Map<String, Object>) parsed.extractedData().get("phaseSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> concurrentSummary = (Map<String, Object>) parsed.extractedData().get("concurrentSummary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertEquals(3L, ((Number) summary.get("pauseEventCount")).longValue());
        assertTrue(pauses.stream().anyMatch(pause -> "YOUNG".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "CLEANUP".equals(String.valueOf(pause.get("pauseType")))));
        assertTrue(pauses.stream().anyMatch(pause -> "REMARK".equals(String.valueOf(pause.get("pauseType")))));
        assertEquals(2L, ((Number) phaseSummary.get("phaseSampleCount")).longValue());
        assertEquals(2L, ((Number) concurrentSummary.get("concurrentPhaseCount")).longValue());
        assertTrue(((Number) concurrentSummary.get("longestConcurrentPhaseMs")).doubleValue() >= 189.0d);
        assertEquals("Concurrent Mark", concurrentSummary.get("longestConcurrentPhaseName"));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void parsesLegacyParallelFullGcWithUnwrappedMetaspaceSection() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/parallel_legacy_gc_unwrapped_metaspace.log",
            """
                2026-04-03T10:00:10.000+0000: 22.345: [Full GC (Metadata GC Threshold) [PSYoungGen: 10752K->0K(76288K)] [ParOldGen: 44016K->44010K(175104K)] 54768K->44010K(251392K), Metaspace: 21234K->21234K(1067008K), 0.0889058 secs] [Times: user=0.19 sys=0.00, real=0.09 secs]
                """
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = (List<Map<String, Object>>) parsed.extractedData().get("pauses");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");

        assertEquals("Parallel", parsed.extractedData().get("collector"));
        assertEquals(1L, pauses.size());
        assertEquals(53L, ((Number) pauses.getFirst().get("beforeHeapMb")).longValue());
        assertEquals(43L, ((Number) pauses.getFirst().get("afterHeapMb")).longValue());
        assertEquals(246L, ((Number) pauses.getFirst().get("heapCapacityMb")).longValue());
        assertEquals(1L, ((Number) metaspace.get("snapshotCount")).longValue());
        assertTrue(parsed.warnings().isEmpty());
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
