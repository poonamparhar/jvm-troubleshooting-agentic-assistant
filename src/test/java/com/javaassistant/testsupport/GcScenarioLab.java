package com.javaassistant.testsupport;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class GcScenarioLab implements ScenarioLab {

    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "sequence-gc-pressure-worsening",
        "gc-g1-evacuation-failure-to-space-exhaustion",
        "gc-g1-humongous-allocation-pressure"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        return switch (scenarioId) {
            case "sequence-gc-pressure-worsening" -> MemoryPressureFixtureFactory.createGcPressureWorseningSequenceBundle(tempDirectory);
            case "gc-g1-evacuation-failure-to-space-exhaustion" -> MemoryPressureFixtureFactory.createG1EvacuationFailureBundle(tempDirectory);
            case "gc-g1-humongous-allocation-pressure" -> MemoryPressureFixtureFactory.createG1HumongousAllocationPressureBundle(tempDirectory);
            default -> throw new IllegalStateException("Unsupported GC scenario: " + scenarioId);
        };
    }
}
