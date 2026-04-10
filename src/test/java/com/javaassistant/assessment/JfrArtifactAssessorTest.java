package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
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
    void emitsMonitorWaitBacklogFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createMonitorWaitRecording(tempDir.resolve("monitor-wait-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        var finding = evaluation.findings().stream()
            .filter(candidate -> candidate.id().equals("jfr-monitor-wait-events"))
            .findFirst()
            .orElseThrow();

        assertTrue(finding.evidenceIds().contains("jfr-monitor-wait-summary"));
        assertTrue(finding.summary().contains("monitor-wait"));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-monitor-wait-events")));
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

    @Test
    void emitsClassLoadingPressureFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createClassLoadingPressureRecording(tempDir.resolve("class-loading-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-class-loading-pressure") && finding.evidenceIds().contains("jfr-class-loading-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-class-loading-pressure")));
    }

    @Test
    void emitsCodeCachePressureFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createCodeCachePressureRecording(tempDir.resolve("code-cache-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-code-cache-pressure") && finding.evidenceIds().contains("jfr-code-cache-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-code-cache-pressure")));
    }

    @Test
    void emitsCpuLoadSaturationFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createCpuLoadSaturationRecording(tempDir.resolve("cpu-load-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        var finding = evaluation.findings().stream()
            .filter(candidate -> candidate.id().equals("jfr-cpu-load-saturation"))
            .findFirst()
            .orElseThrow();

        assertTrue(finding.evidenceIds().contains("jfr-cpu-load-summary"));
        assertTrue(finding.summary().contains("CPU-load"));
        assertTrue(finding.summary().contains("checkout-cpu-hot-thread"));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-cpu-load-saturation")));
    }

    @Test
    void emitsVirtualThreadPinningFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createVirtualThreadPinningRecording(tempDir.resolve("pinning-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("jfr-virtual-thread-pinning") && finding.evidenceIds().contains("jfr-recording-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-virtual-thread-pinning")));
    }

    @Test
    void emitsVirtualThreadSubmitFailedFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createVirtualThreadSubmitFailedRecording(tempDir.resolve("submit-failed-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        var finding = evaluation.findings().stream()
            .filter(candidate -> candidate.id().equals("jfr-virtual-thread-submit-failed"))
            .findFirst()
            .orElseThrow();

        assertTrue(finding.evidenceIds().contains("jfr-recording-summary"));
        assertTrue(finding.summary().contains("submit-failed"));
        assertTrue(finding.summary().contains("carrier scheduler saturated"));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-jfr-virtual-thread-submit-failed")));
    }

    @Test
    void surfacesThresholdBlindSpotAsMissingDataRatherThanWaitFinding() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createThresholdBlindSpotRecording(tempDir.resolve("threshold-blind-spot-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().noneMatch(finding ->
            finding.id().equals("jfr-thread-park-events") || finding.id().equals("jfr-monitor-wait-events")
        ));
        assertTrue(evaluation.missingData().stream().anyMatch(item -> item.contains("thresholded")));
    }

    @Test
    void reportsCorruptedRecordingAsMissingDataInsteadOfHotspotFindings() throws Exception {
        Path corruptedRecording = tempDir.resolve("corrupted-recording.jfr");
        Files.write(corruptedRecording, new byte[] {0x01, 0x23, 0x45, 0x67});

        var parsed = parser.parse(loader.load(corruptedRecording));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().isEmpty());
        assertTrue(evaluation.missingData().stream().anyMatch(item -> item.contains("could not be parsed")));
    }
}
