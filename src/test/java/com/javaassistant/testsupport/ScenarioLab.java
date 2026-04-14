package com.javaassistant.testsupport;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Generates deterministic scenario artifacts for end-to-end testing.
 */
public interface ScenarioLab {

    Set<String> supportedScenarioIds();

    default boolean supports(String scenarioId) {
        return supportedScenarioIds().contains(scenarioId);
    }

    Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception;
}
