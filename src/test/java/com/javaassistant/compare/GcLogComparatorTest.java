package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GcLogComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser parser = new GcLogArtifactParser();
    private final GcLogComparator comparator = new GcLogComparator();

    @Test
    void emitsComparisonFindingsForWorseningGcSignals() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_current_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-g1-distress-regression")));
    }

    @Test
    void ignoresStableGcLogs() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().startsWith("compare-gc-")));
    }

    @Test
    void emitsCmsComparisonFindingsForWorseningFallbackSignals() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-cms-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-cms-regression/gc_current_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-cms-fallback-regression")));
    }

    @Test
    void emitsSerialComparisonFindingsForWorseningStopTheWorldPressure() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-serial-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-serial-regression/gc_current_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-stop-the-world-pressure-regression")));
    }

    @Test
    void emitsParallelComparisonFindingsForWorseningStopTheWorldPressure() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-parallel-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-parallel-regression/gc_current_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-stop-the-world-pressure-regression")));
    }

    @Test
    void emitsZgcComparisonFindingsForWorseningAllocationStalls() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-zgc-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-zgc-regression/gc_current_small.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-zgc-stall-regression")));
    }

    @Test
    void emitsG1ComparisonFindingsAcrossUnifiedBaselineAndLegacyCurrentLogs() throws Exception {
        var baseline = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var current = loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-full-gc-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-headroom-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-gc-g1-distress-regression")));
    }
}
