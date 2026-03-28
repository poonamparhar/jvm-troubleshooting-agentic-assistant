package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.NmtArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NmtArtifactRuleEngineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactRuleEngine engine = new NmtArtifactRuleEngine();

    @Test
    void emitsMetaspacePressureFindingForSample() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("nmt-metaspace-pressure")));
        assertFalse(evaluation.recommendedActions().isEmpty());
    }
}
