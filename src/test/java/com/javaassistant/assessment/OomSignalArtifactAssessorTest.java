package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.OomSignalArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OomSignalArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final OomSignalArtifactParser parser = new OomSignalArtifactParser();
    private final OomSignalArtifactAssessor engine = new OomSignalArtifactAssessor();

    @Test
    void emitsKernelOomKillFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/kernel_oom_kill.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("oom-signal-kernel-oom-kill") && finding.evidenceIds().contains("oom-signal-kernel-event")
        ));
        assertFalse(evaluation.recommendedActions().isEmpty());
        assertTrue(evaluation.missingData().isEmpty());
    }

    @Test
    void emitsPodOomKilledAndRestartLoopFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/pod_oomkilled_describe.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("oom-signal-pod-oomkilled")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("oom-signal-restart-loop")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-oom-signal-restart-loop")));
    }
}
