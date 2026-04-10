package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
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

    @Test
    void emitsRestartLoopFindingWithoutExplicitOomKilledMarker() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/pod_crashloopbackoff_describe.txt",
            """
                Name:           checkout-api-7b8897b9c9-6l2q9
                Namespace:      production
                Start Time:     Sun, 30 Mar 2026 18:32:01 +0000
                Containers:
                  checkout:
                    Container ID:   containerd://abc123
                    Image:          checkout-api:1.4.7
                    State:          Waiting
                      Reason:       CrashLoopBackOff
                    Last State:     Terminated
                      Reason:       Error
                      Exit Code:    1
                      Started:      Sun, 30 Mar 2026 18:41:14 +0000
                      Finished:     Sun, 30 Mar 2026 18:42:13 +0000
                    Restart Count:  6
                """
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("oom-signal-restart-loop")));
        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("oom-signal-pod-oomkilled")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-oom-signal-restart-loop")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.OOM_SIGNAL,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
