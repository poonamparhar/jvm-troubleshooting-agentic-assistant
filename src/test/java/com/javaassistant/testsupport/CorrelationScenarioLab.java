package com.javaassistant.testsupport;

import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CorrelationScenarioLab implements ScenarioLab {

    private static final ArtifactLoader ARTIFACT_LOADER = new ArtifactLoader();
    private static final JfrArtifactParser JFR_PARSER = new JfrArtifactParser();
    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "jfr-gc-heap",
        "jfr-thread-dump",
        "metaspace-gc-nmt-pmap",
        "oome-java-heap-space-or-gc-overhead-limit-exceeded",
        "oome-java-heap-space-terminal",
        "container-limit-below-jvm-budget",
        "thread-growth-nmt",
        "native-thread-exhaustion",
        "compressed-class-space-oom",
        "classloading-metaspace",
        "code-cache-full",
        "direct-buffer-native-leak",
        "correlate-direct-buffer-leak-and-native-oom",
        "active-native-growth-or-off-heap-pressure",
        "reserved-vs-committed-native-mismatch"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        return switch (scenarioId) {
            case "jfr-gc-heap" -> createJfrGcHeapScenario(tempDirectory);
            case "jfr-thread-dump" -> createJfrThreadDumpScenario(tempDirectory);
            case "metaspace-gc-nmt-pmap" -> MemoryPressureFixtureFactory.createMetaspacePressureBundle(tempDirectory);
            case "oome-java-heap-space-or-gc-overhead-limit-exceeded" -> MemoryPressureFixtureFactory.createHeapExhaustionBundle(tempDirectory);
            case "oome-java-heap-space-terminal" -> MemoryPressureFixtureFactory.createJavaHeapSpaceExhaustionBundle(tempDirectory);
            case "container-limit-below-jvm-budget" -> MemoryPressureFixtureFactory.createContainerBudgetJvmBundle(tempDirectory);
            case "thread-growth-nmt" -> MemoryPressureFixtureFactory.createThreadGrowthBundle(tempDirectory);
            case "native-thread-exhaustion" -> MemoryPressureFixtureFactory.createNativeThreadExhaustionBundle(tempDirectory);
            case "compressed-class-space-oom" -> MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDirectory);
            case "classloading-metaspace" -> MemoryPressureFixtureFactory.createClassLoadingMetaspaceBundle(tempDirectory);
            case "code-cache-full" -> MemoryPressureFixtureFactory.createCodeCacheFullBundle(tempDirectory);
            case "direct-buffer-native-leak" -> MemoryPressureFixtureFactory.createDirectBufferNativeLeakBundle(tempDirectory);
            case "correlate-direct-buffer-leak-and-native-oom" -> MemoryPressureFixtureFactory.createDirectBufferNativeOomBundle(tempDirectory);
            case "active-native-growth-or-off-heap-pressure" -> MemoryPressureFixtureFactory.createActiveNativeGrowthBundle(tempDirectory);
            case "reserved-vs-committed-native-mismatch" -> MemoryPressureFixtureFactory.createReservedCommittedMismatchBundle(tempDirectory);
            default -> throw new IllegalStateException("Unsupported correlation scenario: " + scenarioId);
        };
    }

    private Map<String, Path> createJfrGcHeapScenario(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createIncidentWindowRecording(tempDirectory.resolve("correlate-jfr-gc-heap-recording.jfr"));
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        Path gcPath = createGcLogOverlappingIncidentWindow(tempDirectory.resolve("correlate-jfr-gc-heap.log"), jfrParsed);
        Path heapPath = createMatchingHeapHistogram(tempDirectory.resolve("correlate-jfr-gc-heap.txt"));
        generated.put("primary", jfrPath);
        generated.put("jfr", jfrPath);
        generated.put("gc", gcPath);
        generated.put("heap", heapPath);
        return Map.copyOf(generated);
    }

    private Map<String, Path> createJfrThreadDumpScenario(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(
            tempDirectory.resolve("correlate-jfr-thread-contention-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        Path threadDumpPath = createTimedThreadDump(
            tempDirectory.resolve("correlate-jfr-thread-contention.txt"),
            firstIncidentWindowMidpoint(jfrParsed)
        );
        generated.put("primary", jfrPath);
        generated.put("jfr", jfrPath);
        generated.put("thread-dump", threadDumpPath);
        return Map.copyOf(generated);
    }

    private Path createMatchingHeapHistogram(Path path) throws IOException {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         42000       16800000  java.util.LinkedHashMap
               2:         90000        9600000  [B
               3:         80000        6400000  java.lang.String
               4:         30000        4800000  java.util.LinkedHashMap$Entry
            Total        242000       36800000
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path createGcLogOverlappingIncidentWindow(Path path, ParsedArtifact jfrParsed) throws IOException {
        Instant incidentStart = incidentWindowStart(jfrParsed);
        Instant gcFirst = incidentStart;
        Instant gcSecond = incidentStart.plusMillis(350L);
        Instant bootstrap = gcFirst.minusSeconds(1L);

        String content = ""
            + "[" + bootstrap + "][0.100s][info][gc] Using G1\n"
            + "[" + gcFirst + "][1.100s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 1020M->1018M(1024M) 220.000ms\n"
            + "[" + gcSecond + "][1.450s][info][gc] GC(2) Pause Full (G1 Compaction Pause) 1022M->1020M(1024M) 260.000ms\n";
        Files.writeString(path, content);
        return path;
    }

    private Path createTimedThreadDump(Path path, Instant captureTime) throws IOException {
        String sample = Files.readString(Path.of("samples/thread_dump_deadlock.txt"));
        int firstNewline = sample.indexOf('\n');
        String threadDumpBody = firstNewline >= 0 ? sample.substring(firstNewline + 1).stripLeading() : sample;
        Files.writeString(path, "Capture time: " + captureTime + "\n" + threadDumpBody);
        return path;
    }

    private Instant incidentWindowStart(ParsedArtifact jfrParsed) {
        return Instant.parse(firstIncidentWindow(jfrParsed).get("startTime").toString());
    }

    private Instant firstIncidentWindowMidpoint(ParsedArtifact jfrParsed) {
        Map<String, Object> incidentWindow = firstIncidentWindow(jfrParsed);
        Instant start = Instant.parse(incidentWindow.get("startTime").toString());
        Instant end = Instant.parse(incidentWindow.get("endTime").toString());
        if (!end.isAfter(start)) {
            return start;
        }
        return start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstIncidentWindow(ParsedArtifact jfrParsed) {
        List<Map<String, Object>> incidentWindows = (List<Map<String, Object>>) jfrParsed.extractedData().get("incidentWindows");
        return incidentWindows.getFirst();
    }
}
