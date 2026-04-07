package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThreadDumpComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();
    private final ThreadDumpComparator comparator = new ThreadDumpComparator();

    @Test
    void emitsDeadlockAndStallRegressionFindings() throws Exception {
        var baseline = loader.load(Path.of("samples/thread_dump_baseline.txt"));
        var current = loader.load(Path.of("samples/thread_dump_deadlock.txt"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-deadlock-appeared")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-contention-hotspot")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-pool-stall")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("compare-thread-dump-blocked-growth") && finding.evidenceIds().contains("thread-dump-blocked-threads")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-compare-thread-dump-deadlock-appeared")));
    }

    @Test
    void emitsResolvedDeadlockFindingWhenCurrentDumpClearsIt() throws Exception {
        var baseline = loader.load(Path.of("samples/thread_dump_deadlock.txt"));
        var current = loader.load(Path.of("samples/thread_dump_baseline.txt"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-deadlock-resolved")));
        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-deadlock-appeared")));
        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-thread-dump-blocked-growth")));
    }
}
