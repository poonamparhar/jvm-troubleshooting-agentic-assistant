package com.javaassistant.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jdk.jfr.EventSettings;
import jdk.jfr.Recording;

public final class ControlAndUncertaintyScenarioLab implements ScenarioLab {

    private static final DateTimeFormatter GC_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx").withZone(ZoneOffset.UTC);
    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "control-healthy-g1-baseline",
        "control-healthy-jfr-baseline",
        "control-healthy-thread-dump-idle",
        "control-healthy-native-memory-baseline",
        "ambiguity-low-signal-single-snapshot",
        "ambiguity-time-skewed-or-conflicting-correlation"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        return switch (scenarioId) {
            case "control-healthy-g1-baseline" -> singleArtifact("gc", writeHealthyGcLog(tempDirectory.resolve("control-healthy-g1-baseline.log")));
            case "control-healthy-jfr-baseline" -> singleArtifact("jfr", writeHealthyJfrRecording(tempDirectory.resolve("control-healthy-baseline.jfr")));
            case "control-healthy-thread-dump-idle" -> singleArtifact(
                "thread-dump",
                writeHealthyThreadDump(tempDirectory.resolve("control-healthy-thread-dump.txt"), Instant.parse("2026-04-08T17:10:00Z"))
            );
            case "control-healthy-native-memory-baseline" -> createHealthyNativeMemoryBundle(tempDirectory);
            case "ambiguity-low-signal-single-snapshot" ->
                singleArtifact("nmt", writeLowSignalNmtSummary(tempDirectory.resolve("ambiguity-low-signal-nmt.txt")));
            case "ambiguity-time-skewed-or-conflicting-correlation" -> createTimeSkewedCorrelationBundle(tempDirectory);
            default -> throw new IllegalStateException("Unsupported control or uncertainty scenario: " + scenarioId);
        };
    }

    private Map<String, Path> singleArtifact(String artifactKey, Path path) {
        LinkedHashMap<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("primary", path);
        artifacts.put(artifactKey, path);
        return Map.copyOf(artifacts);
    }

    private Map<String, Path> createTimeSkewedCorrelationBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("jfr", writeHealthyJfrRecording(tempDirectory.resolve("ambiguity-healthy-runtime.jfr")));
        artifacts.put("gc", writeHealthyGcLogAt(tempDirectory.resolve("ambiguity-healthy-gc.log"), Instant.parse("2026-04-09T17:00:00Z")));
        artifacts.put(
            "thread-dump",
            writeHealthyThreadDump(tempDirectory.resolve("ambiguity-healthy-thread-dump.txt"), Instant.parse("2026-04-10T17:05:00Z"))
        );
        artifacts.put("nmt", writeHealthyNmtSummary(tempDirectory.resolve("ambiguity-healthy-nmt.txt")));
        artifacts.put("pmap", writeHealthyPmap(tempDirectory.resolve("ambiguity-healthy-pmap.txt")));
        artifacts.put("primary", artifacts.get("jfr"));
        return Map.copyOf(artifacts);
    }

    private Map<String, Path> createHealthyNativeMemoryBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("nmt", writeHealthyNmtSummary(tempDirectory.resolve("control-healthy-native-memory.txt")));
        artifacts.put("pmap", writeHealthyPmap(tempDirectory.resolve("control-healthy-native-memory.pmap")));
        artifacts.put("primary", artifacts.get("nmt"));
        return Map.copyOf(artifacts);
    }

    private Path writeHealthyGcLog(Path path) throws IOException {
        return writeHealthyGcLogAt(path, Instant.parse("2026-04-08T17:00:00Z"));
    }

    private Path writeHealthyGcLogAt(Path path, Instant baseTime) throws IOException {
        String content = """
            [%s][0.100s][info][gc] Using G1
            [%s][5.200s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 128M->44M(512M) 11.200ms
            [%s][18.400s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 176M->58M(512M) 13.800ms
            [%s][37.900s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Evacuation Pause) 212M->74M(512M) 15.400ms
            [%s][38.000s][info][gc,heap] GC(2) Eden regions: 18->0(24)
            [%s][38.000s][info][gc,heap] GC(2) Survivor regions: 4->4(4)
            [%s][38.000s][info][gc,heap] GC(2) Old regions: 70->74
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(5L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusSeconds(13L)),
            gcTimestamp(baseTime.plusSeconds(32L)),
            gcTimestamp(baseTime.plusSeconds(32L).plusMillis(100L)),
            gcTimestamp(baseTime.plusSeconds(32L).plusMillis(100L)),
            gcTimestamp(baseTime.plusSeconds(32L).plusMillis(100L))
        );
        Files.writeString(path, content);
        return path;
    }

    private Path writeHealthyJfrRecording(Path path) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, JfrTestRecordingFactory.TestExecutionSampleEvent.class);
            enable(recording, JfrTestRecordingFactory.TestObjectAllocationInNewTLABEvent.class);

            recording.start();

            for (int index = 0; index < 3; index++) {
                JfrTestRecordingFactory.TestExecutionSampleEvent executionSampleEvent =
                    new JfrTestRecordingFactory.TestExecutionSampleEvent();
                executionSampleEvent.commit();
                Thread.sleep(45L);
            }

            JfrTestRecordingFactory.TestObjectAllocationInNewTLABEvent allocationEvent =
                new JfrTestRecordingFactory.TestObjectAllocationInNewTLABEvent();
            allocationEvent.objectClass = String.class;
            allocationEvent.allocationSize = 256_000L;
            allocationEvent.tlabSize = 384_000L;
            allocationEvent.allocator = "baseline-request";
            allocationEvent.commit();

            recording.stop();
            recording.dump(path);
        }
        return path;
    }

    private Path writeHealthyThreadDump(Path path, Instant captureTime) throws IOException {
        String content = """
            Capture time: %s
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "main" #1 prio=5 os_prio=31 cpu=42.10ms elapsed=181.25s tid=0x0000000102800000 nid=0x1003 waiting on condition [0x000000016f9d3000]
               java.lang.Thread.State: TIMED_WAITING (sleeping)
                    at java.lang.Thread.sleep(java.base@25/Native Method)
                    at com.acme.bootstrap.MainLoop.awaitNextCheck(MainLoop.java:48)

            "http-worker-1" #21 daemon prio=5 os_prio=31 cpu=12.11ms elapsed=180.91s tid=0x0000000103800000 nid=0x1101 waiting on condition [0x000000016fad3000]
               java.lang.Thread.State: WAITING (parking)
                    at jdk.internal.misc.Unsafe.park(java.base@25/Native Method)
                    - parking to wait for  <0x0000000700001000> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                    at java.util.concurrent.locks.LockSupport.park(java.base@25/LockSupport.java:369)
                    at java.util.concurrent.LinkedBlockingQueue.take(java.base@25/LinkedBlockingQueue.java:435)
                    at java.util.concurrent.ThreadPoolExecutor.getTask(java.base@25/ThreadPoolExecutor.java:1070)
                    at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@25/ThreadPoolExecutor.java:1130)
                    at java.util.concurrent.ThreadPoolExecutor$Worker.run(java.base@25/ThreadPoolExecutor.java:642)

            "http-worker-2" #22 daemon prio=5 os_prio=31 cpu=10.84ms elapsed=180.90s tid=0x0000000103900000 nid=0x1102 waiting on condition [0x000000016fbd3000]
               java.lang.Thread.State: WAITING (parking)
                    at jdk.internal.misc.Unsafe.park(java.base@25/Native Method)
                    - parking to wait for  <0x0000000700001000> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                    at java.util.concurrent.locks.LockSupport.park(java.base@25/LockSupport.java:369)
                    at java.util.concurrent.LinkedBlockingQueue.take(java.base@25/LinkedBlockingQueue.java:435)
                    at java.util.concurrent.ThreadPoolExecutor.getTask(java.base@25/ThreadPoolExecutor.java:1070)
                    at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@25/ThreadPoolExecutor.java:1130)
                    at java.util.concurrent.ThreadPoolExecutor$Worker.run(java.base@25/ThreadPoolExecutor.java:642)

            "Reference Handler" #2 daemon prio=10 os_prio=31 cpu=0.50ms elapsed=181.23s tid=0x0000000102811000 nid=0x1004 waiting on condition [0x000000016f8d3000]
               java.lang.Thread.State: RUNNABLE
                    at java.lang.ref.Reference.waitForReferencePendingList(java.base@25/Native Method)
                    at java.lang.ref.Reference.processPendingReferences(java.base@25/Reference.java:246)
            """.formatted(captureTime);
        Files.writeString(path, content);
        return path;
    }

    private Path writeHealthyNmtSummary(Path path) throws IOException {
        String content = """
            606060:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4378624KB, committed=146432KB
                   malloc: 18120KB #181200
                   mmap:   reserved=4360504KB, committed=128312KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=32768KB, committed=8192KB)
                                        (classes #15220)
                                        (  instance classes #15084, array classes #136)
                                        (malloc=1088KB #30112)
                                        (mmap: reserved=31680KB, committed=7104KB)
                                        (  Metadata:   )
                                        (    reserved=24576KB, committed=6144KB)
                                        (    used=5120KB)
                                        (    waste=1024KB =16.67%)

            -                    Thread (reserved=14336KB, committed=960KB)
                                        (thread #14)
                                        (stack: reserved=14336KB, committed=896KB)

            -                      Code (reserved=184320KB, committed=9216KB)
                                        (malloc=192KB #2144)
                                        (mmap: reserved=184128KB, committed=9024KB)

            -                 Metaspace (reserved=25600KB, committed=7168KB)
                                        (malloc=256KB #412)
                                        (mmap: reserved=25344KB, committed=6912KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path writeHealthyPmap(Path path) throws IOException {
        String content = """
            606060:   java -Xlog:gc*=healthy-g1-baseline.log -XX:NativeMemoryTracking=summary HealthyCheckoutService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   32768    12288   12288 rw---   [ anon ]
            0000000702000000  262144        0       0 -----   [ anon ]
            0000000712000000  524288        0       0 -----   [ anon ]
            00007f8000000000 1048576   84480   84480 rw---   [ anon ]
            00007f8040000000  262144       0       0 -----   [ anon ]
            00007f8080000000   65536    2048    2048 rw--- libjvm.so
            total kB 2195456  98816  98816
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path writeLowSignalNmtSummary(Path path) throws IOException {
        String content = """
            818181:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=537600KB, committed=173824KB
                   malloc: 20480KB #192240
                   mmap:   reserved=517120KB, committed=153344KB

            -                 Java Heap (reserved=262144KB, committed=131072KB)
                                        (mmap: reserved=262144KB, committed=131072KB)

            -                     Class (reserved=36864KB, committed=10240KB)
                                        (classes #18240)
                                        (  instance classes #18092, array classes #148)
                                        (malloc=1216KB #34410)
                                        (mmap: reserved=35648KB, committed=9024KB)
                                        (  Metadata:   )
                                        (    reserved=26624KB, committed=8192KB)
                                        (    used=6656KB)
                                        (    waste=1536KB =18.75%)

            -                    Thread (reserved=22528KB, committed=1408KB)
                                        (thread #22)
                                        (stack: reserved=22528KB, committed=1344KB)

            -                      Code (reserved=188416KB, committed=11264KB)
                                        (malloc=224KB #2201)
                                        (mmap: reserved=188192KB, committed=11040KB)

            -                 Metaspace (reserved=27648KB, committed=9216KB)
                                        (malloc=320KB #488)
                                        (mmap: reserved=27328KB, committed=8896KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private void enable(Recording recording, Class<? extends jdk.jfr.Event> eventType) {
        EventSettings eventSettings = recording.enable(eventType);
        eventSettings.withThreshold(java.time.Duration.ZERO);
    }

    private String gcTimestamp(Instant instant) {
        return GC_TIMESTAMP_FORMAT.format(instant);
    }
}
