package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeapHistogramComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final HeapHistogramArtifactParser parser = new HeapHistogramArtifactParser();
    private final HeapHistogramComparator comparator = new HeapHistogramComparator();

    @Test
    void emitsHeapGrowthFinding() throws Exception {
        var baseline = loader.load(Path.of("samples/heap_histogram_1.txt"));
        var current = loader.load(Path.of("samples/heap_histogram_2.txt"));
        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-heap-growth")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("compare-heap-retention-pattern") && finding.evidenceIds().contains("histogram-cache-like-entry")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-compare-heap-retention-pattern")));
    }
}
