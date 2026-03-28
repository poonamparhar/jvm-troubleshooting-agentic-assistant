package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.HeapHistogramArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeapHistogramArtifactRuleEngineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HeapHistogramArtifactParser parser = new HeapHistogramArtifactParser();
    private final HeapHistogramArtifactRuleEngine engine = new HeapHistogramArtifactRuleEngine();

    @Test
    void emitsRetentionFindingForCacheLikeEntries() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/heap_histogram_1.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("histogram-cache-retention")));
    }
}
