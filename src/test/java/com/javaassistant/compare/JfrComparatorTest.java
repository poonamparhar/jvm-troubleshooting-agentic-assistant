package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final JfrArtifactParser parser = new JfrArtifactParser();
    private final JfrComparator comparator = new JfrComparator();

    @TempDir
    Path tempDir;

    @Test
    void emitsComparisonFindingsForWorseningJfrSignals() throws Exception {
        var baseline = loader.load(JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("baseline.jfr")));
        var current = loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("current.jfr")));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-lock-contention-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-gc-pause-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-execution-hot-path-shift")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-allocation-regression")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-old-object-growth")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-jfr-old-object-depth-regression")));
    }

    @Test
    void ignoresStableJfrRecordings() throws Exception {
        var baseline = loader.load(JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("stable-baseline.jfr")));
        var current = loader.load(JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("stable-current.jfr")));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().startsWith("compare-jfr-")));
    }
}
