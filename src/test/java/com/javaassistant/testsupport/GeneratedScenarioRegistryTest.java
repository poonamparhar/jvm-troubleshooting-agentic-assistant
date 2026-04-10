package com.javaassistant.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeneratedScenarioRegistryTest {

    private final GeneratedScenarioRegistry registry = GeneratedScenarioRegistry.defaultRegistry();

    @Test
    void resolvesLegacyAndScenarioLabArtifactReferences() throws Exception {
        Path hotPathJfr = registry.resolveArtifactPath("generated-jfr:hot-path");
        Path healthyGc = registry.resolveArtifactPath("generated-scenario:control-healthy-g1-baseline:gc");
        Path g1EvacuationFailureGc = registry.resolveArtifactPath("generated-scenario:gc-g1-evacuation-failure-to-space-exhaustion:gc");
        Path g1HumongousPressureGc = registry.resolveArtifactPath("generated-scenario:gc-g1-humongous-allocation-pressure:gc");
        Path g1HumongousPressureJfr = registry.resolveArtifactPath("generated-correlation:gc-g1-humongous-allocation-pressure:jfr");
        Path g1HumongousPressureHeap = registry.resolveArtifactPath("generated-correlation:gc-g1-humongous-allocation-pressure:heap");
        Path gcSequenceBaseline = registry.resolveArtifactPath("generated-scenario:sequence-gc-pressure-worsening:gc-baseline");
        Path gcSequenceCurrent = registry.resolveArtifactPath("generated-scenario:sequence-gc-pressure-worsening:gc-current");
        Path healthyNativeMemory = registry.resolveArtifactPath("generated-scenario:control-healthy-native-memory-baseline:nmt");
        Path lowSignalSnapshot = registry.resolveArtifactPath("generated-scenario:ambiguity-low-signal-single-snapshot:nmt");
        Path ambiguityThreadDump = registry.resolveArtifactPath(
            "generated-scenario:ambiguity-time-skewed-or-conflicting-correlation:thread-dump"
        );
        Path executorPoolStallCurrent = registry.resolveArtifactPath("generated-scenario:executor-pool-stall:current");
        Path executorPoolStallBaseline = registry.resolveArtifactPath("generated-scenario:executor-pool-stall:baseline");
        Path heapExhaustionJfr = registry.resolveArtifactPath("generated-correlation:oome-java-heap-space-or-gc-overhead-limit-exceeded:jfr");
        Path javaHeapSpaceJfr = registry.resolveArtifactPath("generated-correlation:oome-java-heap-space-terminal:jfr");
        Path containerBudgetSnapshot = registry.resolveArtifactPath("generated-correlation:container-limit-below-jvm-budget:container");
        Path directBufferJfr = registry.resolveArtifactPath("generated-correlation:direct-buffer-native-leak:jfr");
        Path directBufferNativeOomHsErr = registry.resolveArtifactPath("generated-correlation:correlate-direct-buffer-leak-and-native-oom:hs-err");
        Path nativeThreadHsErr = registry.resolveArtifactPath("generated-correlation:native-thread-exhaustion:hs-err");
        Path compressedClassSpaceHsErr = registry.resolveArtifactPath("generated-correlation:compressed-class-space-oom:hs-err");
        Path codeCacheHsErr = registry.resolveArtifactPath("generated-correlation:code-cache-full:hs-err");
        Path internalArenaDiff = registry.resolveArtifactPath("generated-scenario:nmt-internal-or-arena-growth:diff");
        Path internalArenaCurrent = registry.resolveArtifactPath("generated-scenario:nmt-internal-or-arena-growth:current");
        Path activeNativeGrowthNmt = registry.resolveArtifactPath("generated-scenario:active-native-growth-or-off-heap-pressure:nmt");
        Path activeNativeGrowthPmap = registry.resolveArtifactPath("generated-scenario:active-native-growth-or-off-heap-pressure:pmap");
        Path activeNativeGrowthCorrelationNmt = registry.resolveArtifactPath("generated-correlation:active-native-growth-or-off-heap-pressure:nmt");
        Path nativeMemorySequenceNmtBaseline = registry.resolveArtifactPath("generated-scenario:sequence-native-memory-growth:nmt-baseline");
        Path nativeMemorySequenceNmtMid = registry.resolveArtifactPath("generated-scenario:sequence-native-memory-growth:nmt-mid");
        Path nativeMemorySequencePmapCurrent = registry.resolveArtifactPath("generated-scenario:sequence-native-memory-growth:pmap-current");
        Path reservedMismatchNmt = registry.resolveArtifactPath("generated-scenario:reserved-vs-committed-native-mismatch:nmt");
        Path reservedMismatchPmap = registry.resolveArtifactPath("generated-scenario:reserved-vs-committed-native-mismatch:pmap");
        Path reservedMismatchCorrelationNmt = registry.resolveArtifactPath("generated-correlation:reserved-vs-committed-native-mismatch:nmt");

        assertTrue(Files.exists(hotPathJfr), "legacy generated-jfr reference should resolve");
        assertTrue(Files.exists(healthyGc), "scenario-lab single-artifact reference should resolve");
        assertTrue(Files.exists(g1EvacuationFailureGc), "scenario-lab G1 evacuation-failure reference should resolve");
        assertTrue(Files.exists(g1HumongousPressureGc), "scenario-lab G1 humongous-pressure GC reference should resolve");
        assertTrue(Files.exists(g1HumongousPressureJfr), "scenario-lab G1 humongous-pressure JFR reference should resolve");
        assertTrue(Files.exists(g1HumongousPressureHeap), "scenario-lab G1 humongous-pressure heap reference should resolve");
        assertTrue(Files.exists(gcSequenceBaseline), "scenario-lab GC-sequence baseline reference should resolve");
        assertTrue(Files.exists(gcSequenceCurrent), "scenario-lab GC-sequence current reference should resolve");
        assertTrue(Files.exists(healthyNativeMemory), "scenario-lab native-memory reference should resolve");
        assertTrue(Files.exists(lowSignalSnapshot), "scenario-lab ambiguity reference should resolve");
        assertTrue(Files.exists(ambiguityThreadDump), "scenario-lab multi-artifact reference should resolve");
        assertTrue(Files.exists(executorPoolStallCurrent), "scenario-lab executor-pool-stall current reference should resolve");
        assertTrue(Files.exists(executorPoolStallBaseline), "scenario-lab executor-pool-stall baseline reference should resolve");
        assertTrue(Files.exists(heapExhaustionJfr), "scenario-lab heap-exhaustion correlation reference should resolve");
        assertTrue(Files.exists(javaHeapSpaceJfr), "scenario-lab Java-heap-space correlation reference should resolve");
        assertTrue(Files.exists(containerBudgetSnapshot), "scenario-lab container-budget correlation reference should resolve");
        assertTrue(Files.exists(directBufferJfr), "scenario-lab direct-buffer correlation reference should resolve");
        assertTrue(Files.exists(directBufferNativeOomHsErr), "scenario-lab direct-buffer native-OOM hs_err reference should resolve");
        assertTrue(Files.exists(nativeThreadHsErr), "scenario-lab native-thread-exhaustion correlation reference should resolve");
        assertTrue(Files.exists(compressedClassSpaceHsErr), "scenario-lab compressed-class-space correlation reference should resolve");
        assertTrue(Files.exists(codeCacheHsErr), "scenario-lab code-cache correlation reference should resolve");
        assertTrue(Files.exists(internalArenaDiff), "scenario-lab internal-arena diff reference should resolve");
        assertTrue(Files.exists(internalArenaCurrent), "scenario-lab internal-arena current reference should resolve");
        assertTrue(Files.exists(activeNativeGrowthNmt), "scenario-lab active-native-growth NMT reference should resolve");
        assertTrue(Files.exists(activeNativeGrowthPmap), "scenario-lab active-native-growth pmap reference should resolve");
        assertTrue(Files.exists(activeNativeGrowthCorrelationNmt), "scenario-lab active-native-growth correlation reference should resolve");
        assertTrue(Files.exists(nativeMemorySequenceNmtBaseline), "scenario-lab native-memory-sequence baseline NMT reference should resolve");
        assertTrue(Files.exists(nativeMemorySequenceNmtMid), "scenario-lab native-memory-sequence mid NMT reference should resolve");
        assertTrue(Files.exists(nativeMemorySequencePmapCurrent), "scenario-lab native-memory-sequence current pmap reference should resolve");
        assertTrue(Files.exists(reservedMismatchNmt), "scenario-lab reserved-versus-committed NMT reference should resolve");
        assertTrue(Files.exists(reservedMismatchPmap), "scenario-lab reserved-versus-committed pmap reference should resolve");
        assertTrue(Files.exists(reservedMismatchCorrelationNmt), "scenario-lab reserved-versus-committed correlation reference should resolve");
    }

    @Test
    void cachesScenarioArtifactsByScenarioId() throws Exception {
        Path first = registry.resolveArtifactPath("generated-scenario:control-healthy-jfr-baseline:jfr");
        Path second = registry.resolveArtifactPath("generated-scenario:control-healthy-jfr-baseline:jfr");

        assertEquals(first, second, "scenario artifacts should be cached per scenario id");
    }

    @Test
    void rejectsDuplicateScenarioOwnership() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new GeneratedScenarioRegistry(List.of(
                new FixedScenarioLab(Set.of("duplicate-scenario")),
                new FixedScenarioLab(Set.of("duplicate-scenario"))
            ))
        );

        assertTrue(exception.getMessage().contains("duplicate-scenario"));
    }

    private record FixedScenarioLab(Set<String> supportedScenarioIds) implements ScenarioLab {

        @Override
        public Map<String, Path> generate(String scenarioId, Path tempDirectory) {
            return Map.of();
        }
    }
}
