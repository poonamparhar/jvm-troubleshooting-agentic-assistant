package com.javaassistant.testsupport;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class NativeMemoryScenarioLab implements ScenarioLab {

    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "nmt-internal-or-arena-growth",
        "sequence-native-memory-growth"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        return switch (scenarioId) {
            case "nmt-internal-or-arena-growth" -> MemoryPressureFixtureFactory.createInternalArenaGrowthBundle(tempDirectory);
            case "sequence-native-memory-growth" -> MemoryPressureFixtureFactory.createSequenceNativeMemoryGrowthBundle(tempDirectory);
            default -> throw new IllegalStateException("Unsupported native-memory scenario: " + scenarioId);
        };
    }
}
