package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.HsErrArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HsErrArtifactRuleEngineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HsErrArtifactParser parser = new HsErrArtifactParser();
    private final HsErrArtifactRuleEngine engine = new HsErrArtifactRuleEngine();

    @Test
    void emitsFatalCrashFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-g1-fullgc-crash")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-g1-fullgc-crash")));
    }
}
