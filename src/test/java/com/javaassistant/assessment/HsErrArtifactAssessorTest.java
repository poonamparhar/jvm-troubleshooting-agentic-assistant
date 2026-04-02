package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.HsErrArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HsErrArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HsErrArtifactParser parser = new HsErrArtifactParser();
    private final HsErrArtifactAssessor engine = new HsErrArtifactAssessor();

    @Test
    void emitsFatalCrashFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-g1-fullgc-crash")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-g1-fullgc-crash")));
    }

    @Test
    void emitsNativeAllocationFailureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-native-allocation-failure") && finding.evidenceIds().contains("hs-err-native-allocation-failure")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-compiler-thread-native-oom")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-native-allocation-failure")));
    }
}
