package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThreadDumpArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();
    private final ThreadDumpArtifactAssessor engine = new ThreadDumpArtifactAssessor();

    @Test
    void emitsDeadlockContentionAndPoolStallFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("thread-dump-java-deadlock") && finding.evidenceIds().contains("thread-dump-deadlock")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-lock-contention-hotspot")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("thread-dump-stuck-thread-pool") && finding.evidenceIds().contains("thread-dump-pool-http-nio-8080-exec")
        ));
        assertFalse(evaluation.recommendedActions().isEmpty());
        assertTrue(evaluation.missingData().isEmpty());
    }
}
