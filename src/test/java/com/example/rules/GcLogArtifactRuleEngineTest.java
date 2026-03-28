package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.GcLogArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GcLogArtifactRuleEngineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser parser = new GcLogArtifactParser();
    private final GcLogArtifactRuleEngine engine = new GcLogArtifactRuleEngine();

    @Test
    void emitsFullGcAndHeapPressureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-repeated-full-gcs")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-heap-saturation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-repeated-full-gcs")));
    }
}
