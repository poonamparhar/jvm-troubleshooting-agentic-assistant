package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeapHistogramArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HeapHistogramArtifactParser parser = new HeapHistogramArtifactParser();
    private final HeapHistogramArtifactAssessor engine = new HeapHistogramArtifactAssessor();

    @Test
    void emitsRetentionFindingForCacheLikeEntries() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/heap_histogram_1.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("histogram-cache-retention")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("histogram-collection-retention") && finding.evidenceIds().contains("histogram-collection-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("histogram-payload-retention") && finding.evidenceIds().contains("histogram-payload-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-histogram-cache-retention")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-histogram-payload-retention")));
    }
}
