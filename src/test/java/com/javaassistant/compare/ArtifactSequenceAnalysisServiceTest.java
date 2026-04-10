package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSequenceAnalysisServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final NmtArtifactParser nmtParser = new NmtArtifactParser();
    private final PmapArtifactParser pmapParser = new PmapArtifactParser();
    private final ArtifactSequenceAnalysisService sequenceAnalysisService = new ArtifactSequenceAnalysisService(
        new ArtifactComparisonService(List.of(new GcLogComparator(), new NmtComparator(), new PmapComparator()))
    );

    @TempDir
    Path tempDir;

    @Test
    void aggregatesPairwiseGrowthAcrossGeneratedNativeMemoryNmtSequence() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createSequenceNativeMemoryGrowthBundle(tempDir);
        var baseline = loader.load(bundle.get("nmt-baseline"));
        var mid = loader.load(bundle.get("nmt-mid"));
        var current = loader.load(bundle.get("nmt-current"));

        var analysis = sequenceAnalysisService.analyze(
            List.of(baseline, mid, current),
            List.of(nmtParser.parse(baseline), nmtParser.parse(mid), nmtParser.parse(current))
        );

        assertEquals(2, analysis.pairwiseComparisons().size());
        assertTrue(analysis.firstToLastEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-nmt-native-growth")
        ));
        assertTrue(analysis.pairwiseComparisons().stream().allMatch(pairwise ->
            pairwise.evaluation().findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-native-growth"))
        ));
        assertFalse(analysis.aggregateEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-nmt-reserved-expansion")
        ));
    }

    @Test
    void aggregatesPairwiseGrowthAcrossGeneratedNativeMemoryPmapSequence() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createSequenceNativeMemoryGrowthBundle(tempDir);
        var baseline = loader.load(bundle.get("pmap-baseline"));
        var mid = loader.load(bundle.get("pmap-mid"));
        var current = loader.load(bundle.get("pmap-current"));

        var analysis = sequenceAnalysisService.analyze(
            List.of(baseline, mid, current),
            List.of(pmapParser.parse(baseline), pmapParser.parse(mid), pmapParser.parse(current))
        );

        assertEquals(2, analysis.pairwiseComparisons().size());
        assertTrue(analysis.firstToLastEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-pmap-growth")
        ));
        assertTrue(analysis.pairwiseComparisons().stream().allMatch(pairwise ->
            pairwise.evaluation().findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-growth"))
        ));
        assertFalse(analysis.aggregateEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-pmap-reserved-expansion")
        ));
    }

    @Test
    void aggregatesWorseningSignalsAcrossGeneratedGcPressureSequence() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createGcPressureWorseningSequenceBundle(tempDir);
        var baseline = loader.load(bundle.get("gc-baseline"));
        var mid = loader.load(bundle.get("gc-mid"));
        var current = loader.load(bundle.get("gc-current"));

        var analysis = sequenceAnalysisService.analyze(
            List.of(baseline, mid, current),
            List.of(gcParser.parse(baseline), gcParser.parse(mid), gcParser.parse(current))
        );

        assertEquals(2, analysis.pairwiseComparisons().size());
        assertTrue(analysis.firstToLastEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-gc-pause-regression")
        ));
        assertTrue(analysis.aggregateEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-gc-full-gc-regression")
        ));
        assertTrue(analysis.aggregateEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-gc-headroom-regression")
        ));
        assertTrue(analysis.aggregateEvaluation().findings().stream().anyMatch(finding ->
            finding.id().equals("compare-gc-g1-distress-regression")
        ));
    }
}
