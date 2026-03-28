package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.PmapArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PmapArtifactRuleEngineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final PmapArtifactParser parser = new PmapArtifactParser();
    private final PmapArtifactRuleEngine engine = new PmapArtifactRuleEngine();

    @Test
    void emitsAnonymousMemoryPressureFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-anon-pressure")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-pmap-anon-pressure")));
    }
}
