package com.javaassistant.testsupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class JfrScenarioLab implements ScenarioLab {

    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "contention-and-gc",
        "deeper-analytics",
        "hot-path",
        "allocation-path",
        "retained-objects",
        "comparison-baseline",
        "comparison-current"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        Path recordingPath = tempDirectory.resolve(scenarioId + ".jfr");
        Path generatedPath = switch (scenarioId) {
            case "contention-and-gc" -> JfrTestRecordingFactory.createContentionAndGcRecording(recordingPath);
            case "deeper-analytics" -> JfrTestRecordingFactory.createDeeperAnalyticsRecording(recordingPath);
            case "hot-path" -> JfrTestRecordingFactory.createHotPathRecording(recordingPath);
            case "allocation-path" -> JfrTestRecordingFactory.createAllocationPathRecording(recordingPath);
            case "retained-objects" -> JfrTestRecordingFactory.createRetainedObjectRecording(recordingPath);
            case "comparison-baseline" -> JfrTestRecordingFactory.createComparisonBaselineRecording(recordingPath);
            case "comparison-current" -> JfrTestRecordingFactory.createComparisonCurrentRecording(recordingPath);
            default -> throw new IllegalStateException("Unsupported JFR scenario: " + scenarioId);
        };

        LinkedHashMap<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("primary", generatedPath);
        artifacts.put("jfr", generatedPath);
        artifacts.put("recording", generatedPath);
        return Map.copyOf(artifacts);
    }
}
