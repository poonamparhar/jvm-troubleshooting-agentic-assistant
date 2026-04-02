package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final JfrArtifactParser parser = new JfrArtifactParser();
    private final JfrArtifactAssessor engine = new JfrArtifactAssessor();

    @TempDir
    Path tempDir;

    @Test
    void emitsLockAndGcFindingsFromRecordingSignals() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-lock-contention-events") && finding.evidenceIds().contains("jfr-lock-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-gc-pause-events") && finding.evidenceIds().contains("jfr-gc-summary")
        ));
        assertTrue(evaluation.missingData().stream().anyMatch(item -> item.contains("duration is only")));
        assertFalse(evaluation.recommendedActions().isEmpty());
    }

    @Test
    void emitsDeeperRuntimeFindingsFromRecordingSignals() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("deeper-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-thread-park-events") && finding.evidenceIds().contains("jfr-thread-park-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-io-latency-events") && finding.evidenceIds().contains("jfr-io-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-exception-burst") && finding.evidenceIds().contains("jfr-exception-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-safepoint-pause-events") && finding.evidenceIds().contains("jfr-safepoint-summary")
        ));
        assertTrue(evaluation.missingData().stream().anyMatch(item -> item.contains("duration is only")));
        assertFalse(evaluation.recommendedActions().isEmpty());
    }

    @Test
    void emitsExecutionAndRuntimeHotPathFindings() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("hot-path-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-execution-hot-path") && finding.evidenceIds().contains("jfr-execution-hotspots")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-runtime-hot-path") && finding.evidenceIds().contains("jfr-runtime-hotspots")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-execution-hot-path")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-runtime-hot-path")));
    }

    @Test
    void emitsAllocationFieldAndHotPathFindings() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createAllocationPathRecording(tempDir.resolve("allocation-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-allocation-churn") && finding.evidenceIds().contains("jfr-allocation-field-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-dominant-allocation-class") && finding.evidenceIds().contains("jfr-allocation-field-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-allocation-hot-path") && finding.evidenceIds().contains("jfr-allocation-hotspots")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-allocation-churn")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-dominant-allocation-class")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-allocation-hot-path")));
    }

    @Test
    void emitsOldObjectRetentionAndDepthFindings() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createRetainedObjectRecording(tempDir.resolve("old-object-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-old-object-retention-candidates") && finding.evidenceIds().contains("jfr-old-object-field-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-dominant-old-object-class") && finding.evidenceIds().contains("jfr-old-object-field-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-old-object-reference-depth") && finding.evidenceIds().contains("jfr-old-object-field-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-old-object-retention-candidates")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-dominant-old-object-class")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-old-object-reference-depth")));
    }
}
