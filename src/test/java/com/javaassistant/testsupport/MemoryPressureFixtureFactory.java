package com.javaassistant.testsupport;

import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic multi-artifact fixture generator for memory-pressure scenarios.
 * These fixtures stay intentionally small and coherent so they are stable in the
 * reference-incident harness while still exercising real parser and correlator logic.
 */
public final class MemoryPressureFixtureFactory {

    private static final ArtifactLoader ARTIFACT_LOADER = new ArtifactLoader();
    private static final JfrArtifactParser JFR_PARSER = new JfrArtifactParser();
    private static final DateTimeFormatter GC_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx").withZone(ZoneOffset.UTC);

    private MemoryPressureFixtureFactory() {
    }

    public static Map<String, Path> createMetaspacePressureBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("gc", writeMetaspacePressureGcLog(tempDirectory.resolve("generated-metaspace-pressure-gc.log")));
        generated.put("nmt", writeMetaspacePressureNmtSummary(tempDirectory.resolve("generated-metaspace-pressure-nmt.txt")));
        generated.put("pmap", writeMetaspacePressurePmap(tempDirectory.resolve("generated-metaspace-pressure-pmap.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createMixedHeapNativePressureBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("gc", writeRetainedHeapPressureGcLog(tempDirectory.resolve("generated-mixed-memory-pressure-gc.log")));
        generated.put("heap", writeRetainedHeapHistogram(tempDirectory.resolve("generated-mixed-memory-pressure-heap.txt")));
        generated.put("nmt", writeMixedPressureNmtSummary(tempDirectory.resolve("generated-mixed-memory-pressure-nmt.txt")));
        generated.put("pmap", writeMixedPressurePmap(tempDirectory.resolve("generated-mixed-memory-pressure-pmap.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createContainerBudgetJvmBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("container", writeContainerBudgetPressureSnapshot(tempDirectory.resolve("generated-container-budget-pressure-snapshot.txt")));
        generated.put("gc", writeContainerBudgetPressureGcLog(tempDirectory.resolve("generated-container-budget-pressure-gc.log")));
        generated.put("nmt", writeContainerBudgetPressureNmtSummary(tempDirectory.resolve("generated-container-budget-pressure-nmt.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createHeapExhaustionBundle(Path tempDirectory) throws Exception {
        return createHeapExhaustionBundle(tempDirectory, "generated-heap-exhaustion", "GC overhead limit exceeded");
    }

    public static Map<String, Path> createJavaHeapSpaceExhaustionBundle(Path tempDirectory) throws Exception {
        return createHeapExhaustionBundle(tempDirectory, "generated-java-heap-space", "Java heap space");
    }

    public static Map<String, Path> createThreadGrowthBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("thread-dump", writeThreadGrowthThreadDump(tempDirectory.resolve("generated-thread-growth-thread-dump.txt")));
        generated.put("nmt", writeThreadGrowthNmtDiff(tempDirectory.resolve("generated-thread-growth-nmt.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createNativeThreadExhaustionBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("thread-dump", writeThreadGrowthThreadDump(tempDirectory.resolve("generated-native-thread-exhaustion-thread-dump.txt")));
        generated.put("nmt", writeThreadGrowthNmtDiff(tempDirectory.resolve("generated-native-thread-exhaustion-nmt.txt")));
        generated.put("hs-err", writeNativeThreadExhaustionHsErr(tempDirectory.resolve("generated-native-thread-exhaustion-hs-err.log")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createClassLoadingMetaspaceBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createClassLoadingPressureRecording(
            tempDirectory.resolve("generated-classloading-pressure-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        generated.put("jfr", jfrPath);
        generated.put("gc", writeClassLoadingMetaspaceGcLog(tempDirectory.resolve("generated-classloading-pressure-gc.log"), jfrParsed));
        generated.put("nmt", writeClassLoadingMetaspaceNmtDiff(tempDirectory.resolve("generated-classloading-pressure-nmt.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createCodeCacheFullBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createCodeCachePressureRecording(
            tempDirectory.resolve("generated-code-cache-pressure-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        generated.put("jfr", jfrPath);
        generated.put("nmt", writeCodeCachePressureNmtDiff(tempDirectory.resolve("generated-code-cache-pressure-nmt.txt")));
        generated.put("hs-err", writeCodeCachePressureHsErr(tempDirectory.resolve("generated-code-cache-pressure-hs-err.log"), jfrParsed));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createCompressedClassSpaceOomBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("nmt", writeCompressedClassSpaceNmtSummary(tempDirectory.resolve("generated-compressed-class-space-pressure-nmt.txt")));
        generated.put("hs-err", writeCompressedClassSpaceHsErr(tempDirectory.resolve("generated-compressed-class-space-pressure-hs-err.log")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createInternalArenaGrowthBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("baseline", writeInternalArenaGrowthBaselineNmt(tempDirectory.resolve("generated-internal-arena-growth-baseline-nmt.txt")));
        generated.put("mid", writeInternalArenaGrowthMidNmt(tempDirectory.resolve("generated-internal-arena-growth-mid-nmt.txt")));
        generated.put("current", writeInternalArenaGrowthCurrentNmt(tempDirectory.resolve("generated-internal-arena-growth-current-nmt.txt")));
        generated.put("diff", writeInternalArenaGrowthDiffNmt(tempDirectory.resolve("generated-internal-arena-growth-diff-nmt.txt")));
        generated.put("primary", generated.get("diff"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createReservedCommittedMismatchBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("nmt-baseline", writeReservedCommittedMismatchBaselineNmt(tempDirectory.resolve("generated-reserved-committed-mismatch-baseline-nmt.txt")));
        generated.put("nmt-current", writeReservedCommittedMismatchCurrentNmt(tempDirectory.resolve("generated-reserved-committed-mismatch-current-nmt.txt")));
        generated.put("nmt", generated.get("nmt-current"));
        generated.put("pmap-baseline", writeReservedCommittedMismatchBaselinePmap(tempDirectory.resolve("generated-reserved-committed-mismatch-baseline-pmap.txt")));
        generated.put("pmap-current", writeReservedCommittedMismatchCurrentPmap(tempDirectory.resolve("generated-reserved-committed-mismatch-current-pmap.txt")));
        generated.put("pmap", generated.get("pmap-current"));
        generated.put("primary", generated.get("nmt-current"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createActiveNativeGrowthBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("nmt-baseline", writeActiveNativeGrowthBaselineNmt(tempDirectory.resolve("generated-active-native-growth-baseline-nmt.txt")));
        generated.put("nmt-current", writeActiveNativeGrowthCurrentNmt(tempDirectory.resolve("generated-active-native-growth-current-nmt.txt")));
        generated.put("nmt-diff", writeActiveNativeGrowthDiffNmt(tempDirectory.resolve("generated-active-native-growth-diff-nmt.txt")));
        generated.put("nmt", generated.get("nmt-diff"));
        generated.put("pmap-baseline", writeActiveNativeGrowthBaselinePmap(tempDirectory.resolve("generated-active-native-growth-baseline-pmap.txt")));
        generated.put("pmap-current", writeActiveNativeGrowthCurrentPmap(tempDirectory.resolve("generated-active-native-growth-current-pmap.txt")));
        generated.put("pmap", generated.get("pmap-current"));
        generated.put("primary", generated.get("nmt-diff"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createSequenceNativeMemoryGrowthBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("nmt-baseline", writeActiveNativeGrowthBaselineNmt(tempDirectory.resolve("generated-sequence-native-memory-growth-baseline-nmt.txt")));
        generated.put("nmt-mid", writeActiveNativeGrowthMidNmt(tempDirectory.resolve("generated-sequence-native-memory-growth-mid-nmt.txt")));
        generated.put("nmt-current", writeActiveNativeGrowthCurrentNmt(tempDirectory.resolve("generated-sequence-native-memory-growth-current-nmt.txt")));
        generated.put("nmt", generated.get("nmt-current"));
        generated.put("pmap-baseline", writeActiveNativeGrowthBaselinePmap(tempDirectory.resolve("generated-sequence-native-memory-growth-baseline-pmap.txt")));
        generated.put("pmap-mid", writeActiveNativeGrowthMidPmap(tempDirectory.resolve("generated-sequence-native-memory-growth-mid-pmap.txt")));
        generated.put("pmap-current", writeActiveNativeGrowthCurrentPmap(tempDirectory.resolve("generated-sequence-native-memory-growth-current-pmap.txt")));
        generated.put("pmap", generated.get("pmap-current"));
        generated.put("primary", generated.get("nmt-current"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createGcPressureWorseningSequenceBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("gc-baseline", writeGcPressureWorseningBaselineLog(tempDirectory.resolve("generated-sequence-gc-pressure-worsening-baseline.log")));
        generated.put("gc-mid", writeGcPressureWorseningMidLog(tempDirectory.resolve("generated-sequence-gc-pressure-worsening-mid.log")));
        generated.put("gc-current", writeGcPressureWorseningCurrentLog(tempDirectory.resolve("generated-sequence-gc-pressure-worsening-current.log")));
        generated.put("gc", generated.get("gc-current"));
        generated.put("primary", generated.get("gc-current"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createG1EvacuationFailureBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        generated.put("gc", writeGcPressureWorseningCurrentLog(tempDirectory.resolve("generated-g1-evacuation-failure.log")));
        generated.put("primary", generated.get("gc"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createG1HumongousAllocationPressureBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createHumongousAllocationPressureRecording(
            tempDirectory.resolve("generated-g1-humongous-pressure-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        generated.put("jfr", jfrPath);
        generated.put("gc", writeG1HumongousAllocationPressureGcLog(
            tempDirectory.resolve("generated-g1-humongous-pressure-gc.log"),
            jfrParsed
        ));
        generated.put("heap", writeG1HumongousAllocationPressureHistogram(
            tempDirectory.resolve("generated-g1-humongous-pressure-heap.txt")
        ));
        generated.put("primary", generated.get("gc"));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createDirectBufferNativeLeakBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createDirectBufferPressureRecording(
            tempDirectory.resolve("generated-direct-buffer-pressure-recording.jfr")
        );
        generated.put("jfr", jfrPath);
        generated.put("nmt", writeDirectBufferNativeLeakNmtDiff(tempDirectory.resolve("generated-direct-buffer-pressure-nmt.txt")));
        generated.put("pmap", writeDirectBufferNativeLeakPmap(tempDirectory.resolve("generated-direct-buffer-pressure-pmap.txt")));
        return Map.copyOf(generated);
    }

    public static Map<String, Path> createDirectBufferNativeOomBundle(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createDirectBufferPressureRecording(
            tempDirectory.resolve("generated-direct-buffer-pressure-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        generated.put("jfr", jfrPath);
        generated.put("nmt", writeDirectBufferNativeLeakNmtDiff(tempDirectory.resolve("generated-direct-buffer-pressure-nmt.txt")));
        generated.put("pmap", writeDirectBufferNativeLeakPmap(tempDirectory.resolve("generated-direct-buffer-pressure-pmap.txt")));
        generated.put("hs-err", writeDirectBufferNativeLeakHsErr(
            tempDirectory.resolve("generated-direct-buffer-pressure-hs-err.log"),
            jfrParsed
        ));
        generated.put("primary", generated.get("hs-err"));
        return Map.copyOf(generated);
    }

    private static Path writeMetaspacePressureGcLog(Path path) throws IOException {
        String content = """
            [2026-04-07T09:14:00.000-0700][120.000s][info][gc] GC(32) Pause Young (Normal) (G1 Evacuation Pause) 182M->171M(256M) 38.000ms
            [2026-04-07T09:14:04.000-0700][124.000s][info][gc] GC(33) Pause Full (Metadata GC Threshold) 208M->201M(256M) 221.000ms
            [2026-04-07T09:14:04.000-0700][124.000s][info][gc,metaspace] GC(33) Metaspace: 57344K(61440K)->57344K(61440K)
            [2026-04-07T09:14:08.000-0700][128.000s][info][gc] GC(34) Pause Full (Metadata GC Threshold) 214M->206M(256M) 247.000ms
            [2026-04-07T09:14:08.000-0700][128.000s][info][gc,metaspace] GC(34) Metaspace: 58368K(61440K)->58368K(61440K)
            [2026-04-07T09:14:12.000-0700][132.000s][info][gc] GC(35) Pause Full (Metadata GC Threshold) 219M->210M(256M) 263.000ms
            [2026-04-07T09:14:12.000-0700][132.000s][info][gc,metaspace] GC(35) Metaspace: 59392K(61440K)->59392K(61440K)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeMetaspacePressureNmtSummary(Path path) throws IOException {
        String content = """
            424242:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4499233KB, committed=214487KB
                   malloc: 23041KB #201144
                   mmap:   reserved=4476192KB, committed=191446KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=85504KB, committed=62464KB)
                                        (classes #48210)
                                        (  instance classes #48012, array classes #198)
                                        (malloc=3168KB #99120)
                                        (mmap: reserved=82336KB, committed=59296KB)
                                        (  Metadata:   )
                                        (    reserved=65536KB, committed=59392KB)
                                        (    used=57472KB)
                                        (    waste=1920KB =3.23%)
                                        (  Class space:)
                                        (    reserved=65536KB, committed=3072KB)
                                        (    used=2688KB)
                                        (    waste=384KB =12.50%)

            -                    Thread (reserved=25680KB, committed=1488KB)
                                        (thread #25)
                                        (stack: reserved=25600KB, committed=1408KB)
                                        (malloc=48KB #154)
                                        (arena=32KB #41)

            -                      Code (reserved=251132KB, committed=10284KB)
                                        (malloc=188KB #2214)
                                        (mmap: reserved=250944KB, committed=10096KB)

            -                        GC (reserved=126944KB, committed=54272KB)
                                        (malloc=8192KB #1804)
                                        (mmap: reserved=118752KB, committed=46080KB)

            -                  Internal (reserved=2656KB, committed=2656KB)
                                        (malloc=2620KB #50124)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=66560KB, committed=59904KB)
                                        (malloc=512KB #644)
                                        (mmap: reserved=66048KB, committed=59392KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeMetaspacePressurePmap(Path path) throws IOException {
        String content = """
            424242:   java -Xlog:gc*=generated-metaspace-pressure-gc.log -XX:NativeMemoryTracking=summary -XX:MaxMetaspaceSize=64m ClassLoaderStormService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000  32768    18432   18432 rw---   [ anon ]
            0000000702000000 262144        0       0 -----   [ anon ]
            0000000712000000 524288        0       0 -----   [ anon ]
            0000000722000000 2097152       0       0 -----   [ anon ]
            00000007ff800000   4096     4096    4096 rw---   [ anon ]
            00000007ffdf0000   1024      896     896 rw--- classes.jsa
            00000007fff00000   2048      256     256 rw---   [ anon ]
            00007f6000000000 1572864  142336  142336 rw---   [ anon ]
            00007f6060000000  524288       0       0 -----   [ anon ]
            00007f60a0000000  131072    6144    6144 rw---   [ anon ]
            00007f60b0000000   65536    1024    1024 rw--- libjvm.so
            total kB 5132280 173184 173184
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeCompressedClassSpaceNmtSummary(Path path) throws IOException {
        String content = """
            515151:

            Native Memory Tracking:

            Total: reserved=4521984KB, committed=231424KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=135168KB, committed=101376KB)
                                        (classes #68120)
                                        (  instance classes #67612, array classes #508)
                                        (malloc=4096KB #110210)
                                        (mmap: reserved=131072KB, committed=97280KB)
                                        (  Metadata:   )
                                        (    reserved=65536KB, committed=32768KB)
                                        (    used=28672KB)
                                        (    waste=4096KB =12.50%)
                                        (  Class space:)
                                        (    reserved=65536KB, committed=63488KB)
                                        (    used=62976KB)
                                        (    waste=512KB =0.81%)

            -                    Thread (reserved=26624KB, committed=1728KB)
                                        (thread #29)
                                        (stack: reserved=26496KB, committed=1600KB)
                                        (malloc=72KB #214)
                                        (arena=56KB #48)

            -                      Code (reserved=252004KB, committed=11264KB)
                                        (malloc=220KB #2310)
                                        (mmap: reserved=251784KB, committed=11044KB)

            -                        GC (reserved=126944KB, committed=54272KB)
                                        (malloc=8192KB #1804)
                                        (mmap: reserved=118752KB, committed=46080KB)

            -                 Metaspace (reserved=98304KB, committed=96256KB)
                                        (malloc=1024KB #844)
                                        (mmap: reserved=97280KB, committed=95232KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeCompressedClassSpaceHsErr(Path path) throws IOException {
        String content = """
            #
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  SIGABRT (0x6) at pc=0x00007ff80abc1234, pid=515151, tid=271828
            #
            # Out of Memory Error (Compressed class space), pid=515151, tid=271828
            # There is insufficient memory for the Java Runtime Environment to continue.
            # Native memory allocation (mmap) failed to allocate 1048576 bytes for Compressed class space
            # Compressed class space is full.
            #
            # JRE version: OpenJDK Runtime Environment (21.0.4+8) (build 21.0.4+8)
            # Java VM: OpenJDK 64-Bit Server VM (21.0.4+8, mixed mode, sharing, compressed class ptrs, compressed oops, g1 gc, bsd-amd64)
            Command Line: -XX:NativeMemoryTracking=summary -XX:CompressedClassSpaceSize=64m CompressedClassSpaceStorm
            Current thread (0x0000000123456789):  JavaThread "class-define-worker-1" [_thread_in_vm, id=271828, stack(0x000000016f000000,0x000000016f100000)]
            Time: Wed Apr 8 09:42:31 2026 UTC elapsed time: 188.200 seconds
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeContainerBudgetPressureSnapshot(Path path) throws IOException {
        String content = """
            # Container memory snapshot v1
            # Captured from /sys/fs/cgroup on 2026-04-08T10:20:15Z

            [memory.current]
            1015021568

            [memory.max]
            1073741824

            [memory.high]
            943718400

            [memory.events]
            low 0
            high 96
            max 11
            oom 0
            oom_kill 0
            oom_group_kill 0

            [memory.stat]
            anon 847249408
            file 104857600
            kernel 41943040
            kernel_stack 3145728
            pagetables 9437184
            percpu 1572864
            sock 0
            shmem 0
            file_mapped 33554432
            inactive_anon 721420288
            active_anon 125829120
            inactive_file 62914560
            active_file 41943040
            slab_reclaimable 12582912
            slab_unreclaimable 6291456
            slab 18874368

            [memory.pressure]
            some avg10=5.40 avg60=3.10 avg300=1.20 total=76123
            full avg10=1.05 avg60=0.45 avg300=0.10 total=10244
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeContainerBudgetPressureGcLog(Path path) throws IOException {
        String content = """
            [2026-04-08T10:19:58.000-0700][118.000s][info][gc] Using G1
            [2026-04-08T10:20:00.000-0700][120.000s][info][gc] GC(41) Pause Young (Normal) (G1 Evacuation Pause) 724M->708M(768M) 44.000ms
            [2026-04-08T10:20:04.000-0700][124.000s][info][gc] GC(42) Pause Full (G1 Compaction Pause) 760M->744M(768M) 288.000ms
            [2026-04-08T10:20:08.000-0700][128.000s][info][gc] GC(43) Pause Young (Concurrent Start) (G1 Evacuation Pause) 748M->736M(768M) 63.000ms
            [2026-04-08T10:20:12.000-0700][132.000s][info][gc] GC(44) Pause Full (G1 Compaction Pause) 764M->746M(768M) 301.000ms
            [2026-04-08T10:20:16.000-0700][136.000s][info][gc] GC(45) Pause Full (G1 Compaction Pause) 767M->748M(768M) 329.000ms
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeContainerBudgetPressureNmtSummary(Path path) throws IOException {
        String content = """
            616161:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4747264KB, committed=1002496KB
                   malloc: 28672KB #209884
                   mmap:   reserved=4718592KB, committed=973824KB

            -                 Java Heap (reserved=786432KB, committed=786432KB)
                                        (mmap: reserved=786432KB, committed=786432KB)

            -                     Class (reserved=65536KB, committed=30720KB)
                                        (classes #39214)
                                        (  instance classes #39008, array classes #206)
                                        (malloc=2304KB #70214)
                                        (mmap: reserved=63232KB, committed=28416KB)
                                        (  Metadata:   )
                                        (    reserved=49152KB, committed=28672KB)
                                        (    used=26624KB)
                                        (    waste=2048KB =7.14%)
                                        (  Class space:)
                                        (    reserved=16384KB, committed=2048KB)
                                        (    used=1792KB)
                                        (    waste=256KB =12.50%)

            -                    Thread (reserved=132096KB, committed=9984KB)
                                        (thread #128)
                                        (stack: reserved=131072KB, committed=8960KB)
                                        (malloc=640KB #1424)
                                        (arena=384KB #128)

            -                      Code (reserved=196608KB, committed=16384KB)
                                        (malloc=256KB #2148)
                                        (mmap: reserved=196352KB, committed=16128KB)

            -                        GC (reserved=98304KB, committed=65536KB)
                                        (malloc=10240KB #2144)
                                        (mmap: reserved=88064KB, committed=55296KB)

            -                  Internal (reserved=4096KB, committed=4096KB)
                                        (malloc=4060KB #52114)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=51200KB, committed=29696KB)
                                        (malloc=512KB #644)
                                        (mmap: reserved=50688KB, committed=29184KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeRetainedHeapPressureGcLog(Path path) throws IOException {
        String content = """
            [2026-04-07T09:40:00.000-0700][300.000s][info][gc] GC(71) Pause Young (Normal) (G1 Evacuation Pause) 612M->574M(768M) 44.000ms
            [2026-04-07T09:40:05.000-0700][305.000s][info][gc] GC(72) Pause Young (Concurrent Start) (G1 Evacuation Pause) 684M->640M(768M) 57.000ms
            [2026-04-07T09:40:09.000-0700][309.000s][info][gc] GC(73) Pause Full (G1 Compaction Pause) 742M->718M(768M) 312.000ms
            [2026-04-07T09:40:13.000-0700][313.000s][info][gc] GC(74) Pause Full (G1 Compaction Pause) 748M->722M(768M) 338.000ms
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeRetainedHeapHistogram(Path path) throws IOException {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         52000       20800000  java.util.LinkedHashMap
               2:        104000       16640000  java.util.LinkedHashMap$Entry
               3:        180000       14400000  java.lang.String
               4:        120000       12000000  [B
               5:         64000        8192000  java.util.HashMap$Node
               6:         42000        6720000  java.util.ArrayList
               7:         18000        2304000  com.acme.cache.SessionCacheEntry
               8:          9000         720000  java.lang.Object[]
            Total        589000       83456000
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeMixedPressureNmtSummary(Path path) throws IOException {
        String content = """
            515151:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4688896KB, committed=302112KB
                   malloc: 42048KB #241921
                   mmap:   reserved=4646848KB, committed=260064KB

            -                 Java Heap (reserved=4194304KB, committed=786432KB)
                                        (mmap: reserved=4194304KB, committed=786432KB)

            -                     Class (reserved=49152KB, committed=22528KB)
                                        (classes #33128)
                                        (  instance classes #32984, array classes #144)
                                        (malloc=1984KB #60114)
                                        (mmap: reserved=47168KB, committed=20544KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21504KB)
                                        (    used=16256KB)
                                        (    waste=5248KB =24.41%)

            -                    Thread (reserved=246784KB, committed=21504KB)
                                        (thread #240)
                                        (stack: reserved=245760KB, committed=20480KB)
                                        (malloc=672KB #1596)
                                        (arena=352KB #244)

            -                      Code (reserved=194560KB, committed=16384KB)
                                        (malloc=256KB #2231)
                                        (mmap: reserved=194304KB, committed=16128KB)

            -                        GC (reserved=94208KB, committed=65536KB)
                                        (malloc=12288KB #3012)
                                        (mmap: reserved=81920KB, committed=53248KB)

            -                  Internal (reserved=6144KB, committed=6144KB)
                                        (malloc=6108KB #78214)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=43008KB, committed=22528KB)
                                        (malloc=1024KB #612)
                                        (mmap: reserved=41984KB, committed=21504KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeMixedPressurePmap(Path path) throws IOException {
        String content = """
            515151:   java -Xlog:gc*=generated-mixed-memory-pressure-gc.log -XX:NativeMemoryTracking=summary CheckoutService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   65536    36864   36864 rw---   [ anon ]
            0000000705000000 1048576       0       0 -----   [ anon ]
            0000000745000000 1572864       0       0 -----   [ anon ]
            00000007ff600000    8192    4096    4096 rw---   [ anon ]
            00000007ffd00000    1024     896     896 rw--- classes.jsa
            00007f7000000000 2097152  238592  238592 rw---   [ anon ]
            00007f7080000000  524288       0       0 -----   [ anon ]
            00007f70a0000000  131072   12288   12288 rw---   [ anon ]
            00007f70b0000000   65536    2048    2048 rw--- libjvm.so
            total kB 5403640 294784 294784
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Map<String, Path> createHeapExhaustionBundle(Path tempDirectory, String prefix, String terminalMessage) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createIncidentWindowRecording(
            tempDirectory.resolve(prefix + "-recording.jfr")
        );
        ParsedArtifact jfrParsed = JFR_PARSER.parse(ARTIFACT_LOADER.load(jfrPath));
        generated.put("jfr", jfrPath);
        generated.put("gc", writeHeapExhaustionGcLog(tempDirectory.resolve(prefix + "-gc.log"), jfrParsed, terminalMessage));
        generated.put("heap", writeHeapExhaustionHistogram(tempDirectory.resolve(prefix + "-heap.txt")));
        return Map.copyOf(generated);
    }

    private static Path writeHeapExhaustionGcLog(Path path, ParsedArtifact jfrParsed, String terminalMessage) throws IOException {
        Instant baseTime = heapExhaustionWindowStart(jfrParsed);
        String content = """
            [%s][210.000s][info][gc] Using G1
            [%s][211.000s][info][gc] GC(61) Pause Full (G1 Compaction Pause) 1020M->1018M(1024M) 220.000ms
            [%s][211.350s][info][gc] GC(62) Pause Full (G1 Compaction Pause) 1022M->1020M(1024M) 260.000ms
            [%s][211.650s][info][gc] GC(63) Pause Full (G1 Compaction Pause) 1023M->1021M(1024M) 295.000ms
            [%s][211.980s][info][gc] GC(64) Pause Full (G1 Compaction Pause) 1024M->1022M(1024M) 328.000ms
            Exception in thread "main" java.lang.OutOfMemoryError: %s
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(1L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusMillis(350L)),
            gcTimestamp(baseTime.plusMillis(650L)),
            gcTimestamp(baseTime.plusMillis(980L)),
            terminalMessage
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeGcPressureWorseningBaselineLog(Path path) throws IOException {
        Instant baseTime = Instant.parse("2026-04-08T17:00:00Z");
        String content = """
            [%s][0.100s][info][gc] Using G1
            [%s][1.100s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 720M->420M(1024M) 18.000ms
            [%s][2.100s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Evacuation Pause) 810M->500M(1024M) 22.000ms
            [%s][2.220s][info][gc] GC(2) Concurrent Mark Cycle 120.000ms
            [%s][3.100s][info][gc] GC(3) Pause Young (Mixed) (G1 Evacuation Pause) 860M->540M(1024M) 26.000ms
            [%s][4.100s][info][gc] GC(4) Pause Young (Mixed) (G1 Evacuation Pause) 880M->560M(1024M) 30.000ms
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(1L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusSeconds(1L)),
            gcTimestamp(baseTime.plusSeconds(1L).plusMillis(120L)),
            gcTimestamp(baseTime.plusSeconds(2L)),
            gcTimestamp(baseTime.plusSeconds(3L))
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeGcPressureWorseningMidLog(Path path) throws IOException {
        Instant baseTime = Instant.parse("2026-04-08T18:00:00Z");
        String content = """
            [%s][0.100s][info][gc] Using G1
            [%s][10.000s][info][gc] GC(12) Pause Young (Concurrent Start) (G1 Evacuation Pause) 1004M->968M(1024M) 162.000ms
            [%s][11.000s][info][gc] GC(13) Pause Young (Concurrent Start) (G1 Evacuation Pause) (Evacuation Failure) 1023M->1018M(1024M) 188.000ms
            [%s][11.000s][info][gc] GC(13) To-space exhausted
            [%s][11.100s][info][gc] GC(13) Attempting full compaction
            [%s][11.500s][info][gc] GC(14) Pause Full (G1 Compaction Pause) 1023M->952M(1024M) 265.000ms
            [%s][21.000s][info][gc] GC(15) Pause Full (G1 Compaction Pause) 1019M->944M(1024M) 318.000ms
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(1L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusSeconds(1L)),
            gcTimestamp(baseTime.plusSeconds(1L)),
            gcTimestamp(baseTime.plusSeconds(1L).plusMillis(100L)),
            gcTimestamp(baseTime.plusSeconds(1L).plusMillis(500L)),
            gcTimestamp(baseTime.plusSeconds(11L))
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeGcPressureWorseningCurrentLog(Path path) throws IOException {
        Instant baseTime = Instant.parse("2026-04-08T19:00:00Z");
        String content = """
            [%s][0.100s][info][gc] Using G1
            [%s][30.000s][info][gc] GC(20) Pause Young (Concurrent Start) (G1 Evacuation Pause) (Evacuation Failure) 1023M->1023M(1024M) 245.111ms
            [%s][30.000s][info][gc] GC(20) To-space exhausted
            [%s][30.100s][info][gc] GC(20) Attempting full compaction
            [%s][30.500s][info][gc] GC(21) Pause Full (G1 Compaction Pause) 1023M->1023M(1024M) 410.222ms
            [%s][40.000s][info][gc] GC(22) Pause Full (G1 Compaction Pause) 1023M->1022M(1024M) 512.333ms
            [%s][50.000s][info][gc] GC(23) Pause Young (Concurrent Start) (G1 Evacuation Pause) (Evacuation Failure) 1024M->1024M(1024M) 388.444ms
            [%s][50.000s][info][gc] GC(23) To-space exhausted
            [%s][50.050s][info][gc] GC(23) Attempting full compaction
            [%s][50.600s][info][gc] GC(24) Pause Full (G1 Compaction Pause) 1024M->1023M(1024M) 681.585ms
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(1L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusMillis(100L)),
            gcTimestamp(baseTime.plusMillis(500L)),
            gcTimestamp(baseTime.plusSeconds(10L)),
            gcTimestamp(baseTime.plusSeconds(20L)),
            gcTimestamp(baseTime.plusSeconds(20L)),
            gcTimestamp(baseTime.plusSeconds(20L).plusMillis(50L)),
            gcTimestamp(baseTime.plusSeconds(20L).plusMillis(600L))
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeG1HumongousAllocationPressureGcLog(Path path, ParsedArtifact jfrParsed) throws IOException {
        Instant baseTime = heapExhaustionWindowStart(jfrParsed);
        String content = """
            [%s][420.000s][info][gc] Using G1
            [%s][421.000s][info][gc] GC(71) Pause Young (Concurrent Start) (G1 Evacuation Pause) 918M->884M(1024M) 84.000ms
            [%s][421.000s][info][gc] GC(71) Humongous regions: 96->124
            [%s][422.100s][info][gc] GC(72) Pause Young (Mixed) (G1 Evacuation Pause) 976M->958M(1024M) 121.000ms
            [%s][422.100s][info][gc] GC(72) Humongous regions: 124->156
            [%s][423.300s][info][gc] GC(73) Pause Young (Mixed) (G1 Evacuation Pause) 1006M->989M(1024M) 166.000ms
            [%s][423.300s][info][gc] GC(73) Humongous regions: 156->194
            [%s][424.600s][info][gc] GC(74) Pause Young (Concurrent Start) (G1 Evacuation Pause) 1018M->1002M(1024M) 198.000ms
            [%s][424.600s][info][gc] GC(74) Humongous regions: 194->226
            """.formatted(
            gcTimestamp(baseTime.minusSeconds(1L)),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusMillis(1_100L)),
            gcTimestamp(baseTime.plusMillis(1_100L)),
            gcTimestamp(baseTime.plusMillis(2_300L)),
            gcTimestamp(baseTime.plusMillis(2_300L)),
            gcTimestamp(baseTime.plusMillis(3_600L)),
            gcTimestamp(baseTime.plusMillis(3_600L))
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeG1HumongousAllocationPressureHistogram(Path path) throws IOException {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         7200      301989888  [B
               2:        84000       95200000  java.lang.String
               3:         1800       57600000  [Ljava.lang.Object;
               4:        36000       43200000  java.util.LinkedHashMap$Entry
               5:         4200       20160000  java.util.LinkedHashMap
            Total        133200      518949888
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeHeapExhaustionHistogram(Path path) throws IOException {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         98000      392000000  java.util.LinkedHashMap
               2:        320000      204800000  java.lang.String
               3:        180000      201600000  [B
               4:        460000      184000000  java.util.LinkedHashMap$Entry
               5:         18000       92160000  com.acme.checkout.CartSnapshot
            Total       1076000     1074560000
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeThreadGrowthThreadDump(Path path) throws IOException {
        String content = """
            Capture time: 2026-04-07T17:02:15Z
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "main" #1 prio=5 os_prio=31 cpu=48.15ms elapsed=240.35s tid=0x0000000102800000 nid=0x7703 runnable [0x000000016f9d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.cli.CommandLoop.read(CommandLoop.java:118)
                at com.acme.cli.CommandLoop.run(CommandLoop.java:77)

            "RequestLockOwner" #41 daemon prio=5 os_prio=31 cpu=512.10ms elapsed=42.01s tid=0x0000000102a23000 nid=0x7803 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.gateway.Dispatcher.dispatch(Dispatcher.java:144)
                - locked <0x0000000700200001> (a java.lang.Object)
                at com.acme.gateway.WorkDispatcher.run(WorkDispatcher.java:61)

            "http-worker-101" #51 daemon prio=5 os_prio=31 cpu=121.44ms elapsed=18.20s tid=0x0000000102a54000 nid=0x7903 waiting for monitor entry [0x000000016f5d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.gateway.Dispatcher.dispatch(Dispatcher.java:144)
                - waiting to lock <0x0000000700200001> (a java.lang.Object)
                at com.acme.http.HttpWorker.run(HttpWorker.java:88)

            "http-worker-102" #52 daemon prio=5 os_prio=31 cpu=119.03ms elapsed=18.18s tid=0x0000000102a86000 nid=0x7a03 waiting for monitor entry [0x000000016f4d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.gateway.Dispatcher.dispatch(Dispatcher.java:144)
                - waiting to lock <0x0000000700200001> (a java.lang.Object)
                at com.acme.http.HttpWorker.run(HttpWorker.java:88)

            "http-worker-103" #53 daemon prio=5 os_prio=31 cpu=118.14ms elapsed=18.17s tid=0x0000000102ab7000 nid=0x7b03 waiting for monitor entry [0x000000016f3d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.gateway.Dispatcher.dispatch(Dispatcher.java:144)
                - waiting to lock <0x0000000700200001> (a java.lang.Object)
                at com.acme.http.HttpWorker.run(HttpWorker.java:88)

            "http-worker-104" #54 daemon prio=5 os_prio=31 cpu=11.14ms elapsed=18.17s tid=0x0000000102ac7000 nid=0x7c03 waiting on condition [0x000000016f2d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700209999> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1062)
                at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1122)

            "async-exec-201" #61 daemon prio=5 os_prio=31 cpu=82.45ms elapsed=12.01s tid=0x0000000102ae8000 nid=0x7d03 waiting on condition [0x000000016f1d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700200011> (a java.util.concurrent.CountDownLatch$Sync)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:211)
                at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:230)
                at com.acme.gateway.DownstreamCall.await(DownstreamCall.java:77)
                at com.acme.gateway.AsyncTask.run(AsyncTask.java:41)

            "Reference Handler" #2 daemon prio=10 os_prio=31 cpu=0.24ms elapsed=240.31s tid=0x0000000102819000 nid=0x7e03 runnable [0x000000016f0d3000]
               java.lang.Thread.State: RUNNABLE
                at java.lang.ref.Reference.waitForReferencePendingList(Native Method)
                at java.lang.ref.Reference.processPendingReferences(Reference.java:246)

            JNI global refs: 36, weak refs: 0
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeThreadGrowthNmtDiff(Path path) throws IOException {
        String content = """
            626262:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4808704KB +131072KB, committed=278528KB +49152KB
                   malloc: 38912KB #244112
                   mmap:   reserved=4769792KB, committed=239616KB

            -                 Java Heap (reserved=4194304KB, committed=786432KB)
                                        (mmap: reserved=4194304KB, committed=786432KB)

            -                     Class (reserved=49152KB +2048KB, committed=22528KB +1024KB)
                                        (classes #33244 +116)
                                        (  instance classes #33102, array classes #142)
                                        (malloc=1984KB #60222)
                                        (mmap: reserved=47168KB +2048KB, committed=20544KB +1024KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB +1024KB, committed=21504KB +512KB)
                                        (    used=16384KB +256KB)
                                        (    waste=5120KB =23.81%)

            -                    Thread (reserved=198656KB +98304KB, committed=8192KB +4096KB)
                                        (thread #192 +96)
                                        (stack: reserved=196608KB +98304KB, committed=7168KB +3584KB)
                                        (malloc=704KB +128KB #1752)
                                        (arena=320KB +64KB #262)

            -                      Code (reserved=194560KB +2048KB, committed=17408KB +1024KB)
                                        (malloc=256KB #2255)
                                        (mmap: reserved=194304KB +2048KB, committed=17152KB +1024KB)

            -                        GC (reserved=98304KB +4096KB, committed=69632KB +2048KB)
                                        (malloc=12288KB #3044)
                                        (mmap: reserved=86016KB +4096KB, committed=57344KB +2048KB)

            -                  Internal (reserved=6144KB +1024KB, committed=6144KB +1024KB)
                                        (malloc=6108KB #78440)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=43008KB +1024KB, committed=22528KB +512KB)
                                        (malloc=1024KB #618)
                                        (mmap: reserved=41984KB +1024KB, committed=21504KB +512KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeNativeThreadExhaustionHsErr(Path path) throws IOException {
        String content = """
            #
            # There is insufficient memory for the Java Runtime Environment to continue.
            # Cannot create worker GC thread. Out of system resources.
            # An exception java.lang.OutOfMemoryError occurred while allocating the next thread:
            # unable to create new native thread
            #
            # Possible reasons:
            #   The process is close to the operating-system thread or memory limit.
            #   The thread stack size is too large for the available native headroom.
            #
            # Possible solutions:
            #   Reduce the number of Java threads.
            #   Reduce Java thread stack sizes (-Xss).
            #
            #  Out of Memory Error (pthread_create failed (EAGAIN) for attributes: stacksize: 1024k, guardsize: 0k, detached.), pid=626262, tid=424999
            #
            # JRE version: OpenJDK Runtime Environment (25+36) (build 25+36)
            # Java VM: OpenJDK 64-Bit Server VM (25+36, mixed mode, sharing, tiered, compressed oops, compressed class ptrs, g1 gc, linux-amd64)
            # Core dump will be written. Default location: Core dumps may be processed with "/usr/lib/systemd/systemd-coredump %%P %%u %%g %%s %%t %%e"
            #
            Command Line: -Xss1m -XX:NativeMemoryTracking=detail ThreadPressureService
            Host: ci-thread-stress, 8 cores, 16G, Linux 6.8.0
            Time: Tue Apr 7 17:02:18 2026 UTC elapsed time: 242.381224 seconds (0d 0h 4m 2s)

            Current thread (0x00007f9d1c100000): JavaThread "http-worker-193" daemon [_thread_in_vm, id=424999, stack(0x00007f9caaffd000,0x00007f9cab0fe000)]
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeClassLoadingMetaspaceGcLog(Path path, ParsedArtifact jfrParsed) throws IOException {
        Instant baseTime = classLoadingWindowStart(jfrParsed);
        String content = """
            [%s][140.000s][info][gc] GC(41) Pause Young (Concurrent Start) (G1 Evacuation Pause) 228M->221M(256M) 34.000ms
            [%s][140.040s][info][gc] GC(42) Pause Full (Metadata GC Threshold) 246M->240M(256M) 182.000ms
            [%s][140.040s][info][gc,metaspace] GC(42) Metaspace: 55296K(57344K)->55296K(57344K)
            [%s][140.120s][info][gc] GC(43) Pause Full (Metadata GC Threshold) 249M->243M(256M) 201.000ms
            [%s][140.120s][info][gc,metaspace] GC(43) Metaspace: 56320K(57344K)->56320K(57344K)
            [%s][140.220s][info][gc] GC(44) Pause Full (Metadata GC Threshold) 251M->244M(256M) 219.000ms
            [%s][140.220s][info][gc,metaspace] GC(44) Metaspace: 56832K(57344K)->56832K(57344K)
            """.formatted(
            gcTimestamp(baseTime),
            gcTimestamp(baseTime.plusMillis(40L)),
            gcTimestamp(baseTime.plusMillis(40L)),
            gcTimestamp(baseTime.plusMillis(120L)),
            gcTimestamp(baseTime.plusMillis(120L)),
            gcTimestamp(baseTime.plusMillis(220L)),
            gcTimestamp(baseTime.plusMillis(220L))
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeClassLoadingMetaspaceNmtDiff(Path path) throws IOException {
        String content = """
            737373:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4521984KB +28672KB, committed=236544KB +20480KB
                   malloc: 24896KB #224118
                   mmap:   reserved=4497088KB, committed=211648KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=83968KB +16384KB, committed=62464KB +12288KB)
                                        (classes #54820 +16240)
                                        (  instance classes #54628 +16120, array classes #192 +120)
                                        (malloc=3072KB +640KB #99212)
                                        (mmap: reserved=80896KB +15744KB, committed=59392KB +11648KB)
                                        (  Metadata:   )
                                        (    reserved=58368KB +12288KB, committed=57344KB +11264KB)
                                        (    used=55296KB +10752KB)
                                        (    waste=2048KB =3.57%)
                                        (  Class space:)
                                        (    reserved=65536KB +4096KB, committed=5120KB +1024KB)
                                        (    used=4096KB +896KB)
                                        (    waste=1024KB =20.00%)

            -                    Thread (reserved=26624KB +1024KB, committed=1728KB +128KB)
                                        (thread #27 +2)
                                        (stack: reserved=25600KB +1024KB, committed=1536KB +128KB)
                                        (malloc=64KB #172)
                                        (arena=32KB #44)

            -                      Code (reserved=251132KB +1024KB, committed=11264KB +1024KB)
                                        (malloc=192KB #2240)
                                        (mmap: reserved=250940KB +1024KB, committed=11072KB +1024KB)

            -                        GC (reserved=126944KB +1024KB, committed=55296KB +1024KB)
                                        (malloc=8192KB #1810)
                                        (mmap: reserved=118752KB +1024KB, committed=47104KB +1024KB)

            -                  Internal (reserved=2720KB +64KB, committed=2720KB +64KB)
                                        (malloc=2684KB #50220)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=70656KB +12288KB, committed=58368KB +11264KB)
                                        (malloc=512KB #650)
                                        (mmap: reserved=70144KB +12288KB, committed=57856KB +11264KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeCodeCachePressureNmtDiff(Path path) throws IOException {
        String content = """
            919191:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4587520KB +49152KB, committed=244736KB +28672KB
                   malloc: 26112KB #225612
                   mmap:   reserved=4561408KB, committed=218624KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=53248KB +1024KB, committed=25600KB +512KB)
                                        (classes #38420 +220)
                                        (malloc=2112KB +64KB #68812)
                                        (mmap: reserved=51136KB +960KB, committed=23488KB +448KB)

            -                    Thread (reserved=28672KB +1024KB, committed=2048KB +128KB)
                                        (thread #28 +1)
                                        (stack: reserved=27648KB +1024KB, committed=1920KB +128KB)
                                        (malloc=64KB #194)
                                        (arena=32KB #27)

            -                      Code (reserved=262144KB +32768KB, committed=61440KB +24576KB)
                                        (malloc=768KB +128KB #4310)
                                        (mmap: reserved=261376KB +32640KB, committed=60672KB +24448KB)

            -                        GC (reserved=98304KB +2048KB, committed=49152KB +1024KB)
                                        (malloc=8192KB #1832)
                                        (mmap: reserved=90112KB +2048KB, committed=40960KB +1024KB)

            -                  Internal (reserved=4096KB +512KB, committed=4096KB +512KB)
                                        (malloc=4060KB +512KB #50111)
                                        (mmap: reserved=36KB, committed=36KB)

            -                 Metaspace (reserved=45056KB +512KB, committed=23552KB +256KB)
                                        (malloc=1024KB #612)
                                        (mmap: reserved=44032KB +512KB, committed=22528KB +256KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeCodeCachePressureHsErr(Path path, ParsedArtifact jfrParsed) throws IOException {
        Instant crashTime = codeCacheCrashTime(jfrParsed);
        String content = """
            #
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  SIGTRAP (0x5) at pc=0x00007f9dca123456, pid=919191, tid=730011
            #
            # JRE version: OpenJDK Runtime Environment (25+36) (build 25+36)
            # Java VM: OpenJDK 64-Bit Server VM (25+36, mixed mode, sharing, tiered, compressed oops, compressed class ptrs, g1 gc, linux-amd64)
            # Problematic frame:
            # V  [libjvm.so+0x123456]  CodeCache::report_codemem_full+0x2f
            #
            Command Line: -XX:ReservedCodeCacheSize=240m -XX:NativeMemoryTracking=detail CodeCacheStressService
            Host: ci-codecache-stress, 8 cores, 16G, Linux 6.8.0
            Time: %s elapsed time: 184.221000 seconds (0d 0h 3m 4s)

            Current thread (0x00007f9d1c100000): JavaThread "C2 CompilerThread0" daemon [_thread_in_vm, id=730011, stack(0x00007f9caaffd000,0x00007f9cab0fe000)]

            CodeCache is full. Compiler has been disabled.
            CodeCache: size=245760Kb used=245120Kb max_used=245456Kb free=640Kb
            compilation: disabled (not enough contiguous free space left)
            """.formatted(
            crashTime.atOffset(ZoneOffset.UTC).format(
                DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy 'UTC'", Locale.ENGLISH)
            )
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeDirectBufferNativeLeakNmtDiff(Path path) throws IOException {
        String content = """
            848484:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4620288KB +98304KB, committed=198656KB +49152KB
                   malloc: 28928KB #231441
                   mmap:   reserved=4591360KB, committed=169728KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=49152KB +512KB, committed=22528KB +256KB)
                                        (classes #33120 +44)
                                        (malloc=1984KB +64KB #60110)
                                        (mmap: reserved=47168KB +448KB, committed=20544KB +192KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB +256KB, committed=21504KB +128KB)
                                        (    used=16256KB +96KB)
                                        (    waste=5248KB =24.41%)

            -                    Thread (reserved=20480KB +1024KB, committed=1600KB +128KB)
                                        (thread #20 +1)
                                        (stack: reserved=19456KB +1024KB, committed=1472KB +128KB)
                                        (malloc=96KB #188)
                                        (arena=32KB #24)

            -                      Code (reserved=194560KB +1024KB, committed=12288KB +1024KB)
                                        (malloc=256KB #2218)
                                        (mmap: reserved=194304KB +1024KB, committed=12032KB +1024KB)

            -                        GC (reserved=98304KB +2048KB, committed=49152KB +1024KB)
                                        (malloc=8192KB #1822)
                                        (mmap: reserved=90112KB +2048KB, committed=40960KB +1024KB)

            -                  Internal (reserved=65536KB +65536KB, committed=16384KB +32768KB)
                                        (malloc=4096KB +8192KB #78214)
                                        (mmap: reserved=61440KB +57344KB, committed=12288KB +24576KB)

            -                 Metaspace (reserved=43008KB +512KB, committed=22528KB +256KB)
                                        (malloc=1024KB #612)
                                        (mmap: reserved=41984KB +512KB, committed=21504KB +256KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeDirectBufferNativeLeakPmap(Path path) throws IOException {
        String content = """
            848484:   java -XX:NativeMemoryTracking=detail BufferGatewayService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   65536    24576   24576 rw---   [ anon ]
            0000000704000000 1572864        0       0 -----   [ anon ]
            0000000764000000 2097152       0       0 -----   [ anon ]
            00000007ff700000    8192     4096    4096 rw---   [ anon ]
            00007f8000000000  786432   196608  196608 rw---   [ anon ]
            00007f8030000000  524288        0       0 -----   [ anon ]
            00007f8050000000  262144    12288   12288 rw---   [ anon ]
            00007f8060000000   65536     2048    2048 rw--- libjvm.so
            total kB 5382144 239616 239616
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeDirectBufferNativeLeakHsErr(Path path, ParsedArtifact jfrParsed) throws IOException {
        Instant crashTime = directBufferCrashTime(jfrParsed);
        String content = """
            #
            # There is insufficient memory for the Java Runtime Environment to continue.
            # Native memory allocation (malloc) failed to allocate 4194304 bytes for DirectByteBuffer::reserveMemory
            # Possible reasons:
            #   The system is out of physical RAM or swap space
            #   The process is carrying too much off-heap or native memory for the available headroom
            # Possible solutions:
            #   Reduce direct-buffer load or release native buffers more aggressively
            #   Increase available native headroom or lower other JVM memory commitments
            #
            #  Out of Memory Error (bits.cpp:205), pid=848484, tid=848512
            #
            # JRE version: OpenJDK Runtime Environment (25+36) (build 25+36)
            # Java VM: OpenJDK 64-Bit Server VM (25+36, mixed mode, sharing, compressed oops, compressed class ptrs, g1 gc, linux-amd64)
            #

            ---------------  S U M M A R Y ------------

            Command Line: -XX:NativeMemoryTracking=detail -XX:MaxDirectMemorySize=256m BufferGatewayService

            Host: ci-direct-buffer-stress, 8 cores, 16G, Linux 6.8.0
            Time: %s elapsed time: 214.442000 seconds (0d 0h 3m 34s)

            ---------------  T H R E A D  ---------------

            Current thread (0x00007f6300240000):  JavaThread "nio-direct-buffer-worker" daemon [_thread_in_native, id=848512, stack(0x00007f62ed4fd000,0x00007f62ed5fe000)]
            """.formatted(
            crashTime.atOffset(ZoneOffset.UTC).format(
                DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy 'UTC'", Locale.ENGLISH)
            )
        );
        Files.writeString(path, content);
        return path;
    }

    private static Path writeInternalArenaGrowthBaselineNmt(Path path) throws IOException {
        String content = """
            929292:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4513792KB, committed=163840KB
                   malloc: 19456KB #198112
                   mmap:   reserved=4494336KB, committed=144384KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=50176KB, committed=23040KB)
                                        (classes #34210)
                                        (malloc=1984KB #62104)
                                        (mmap: reserved=48192KB, committed=21056KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21504KB)
                                        (    used=16896KB)
                                        (    waste=4608KB =21.43%)
                                        (  Class space:)
                                        (    reserved=32768KB, committed=1536KB)
                                        (    used=1216KB)
                                        (    waste=320KB =20.83%)

            -                    Thread (reserved=21504KB, committed=1600KB)
                                        (thread #21)
                                        (stack: reserved=20480KB, committed=1472KB)
                                        (malloc=96KB #188)
                                        (arena=32KB #24)

            -                      Code (reserved=196608KB, committed=12288KB)
                                        (malloc=256KB #2250)
                                        (mmap: reserved=196352KB, committed=12032KB)

            -                        GC (reserved=98304KB, committed=49152KB)
                                        (malloc=8192KB #1828)
                                        (mmap: reserved=90112KB, committed=40960KB)

            -                  Internal (reserved=6144KB, committed=4096KB)
                                        (malloc=4060KB #48120)
                                        (mmap: reserved=2084KB, committed=36KB)

            -                   Unknown (reserved=2048KB, committed=2048KB)
                                        (malloc=2048KB #16144)

            -               Arena Chunk (reserved=1024KB, committed=1024KB)
                                        (malloc=1024KB #128)

            -                 Metaspace (reserved=43008KB, committed=22528KB)
                                        (malloc=1024KB #612)
                                        (mmap: reserved=41984KB, committed=21504KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeInternalArenaGrowthMidNmt(Path path) throws IOException {
        String content = """
            929292:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4538368KB, committed=187392KB
                   malloc: 22528KB #214640
                   mmap:   reserved=4515840KB, committed=164864KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=50688KB, committed=23552KB)
                                        (classes #34310)
                                        (malloc=2016KB #62640)
                                        (mmap: reserved=48672KB, committed=21536KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21760KB)
                                        (    used=17024KB)
                                        (    waste=4736KB =21.76%)
                                        (  Class space:)
                                        (    reserved=32768KB, committed=1536KB)
                                        (    used=1216KB)
                                        (    waste=320KB =20.83%)

            -                    Thread (reserved=22528KB, committed=1728KB)
                                        (thread #22)
                                        (stack: reserved=21504KB, committed=1600KB)
                                        (malloc=96KB #192)
                                        (arena=32KB #25)

            -                      Code (reserved=197120KB, committed=12544KB)
                                        (malloc=288KB #2270)
                                        (mmap: reserved=196832KB, committed=12256KB)

            -                        GC (reserved=98304KB, committed=49408KB)
                                        (malloc=8192KB #1830)
                                        (mmap: reserved=90112KB, committed=41216KB)

            -                  Internal (reserved=18432KB, committed=16384KB)
                                        (malloc=12288KB #62210)
                                        (mmap: reserved=6144KB, committed=4096KB)

            -                   Unknown (reserved=7168KB, committed=7168KB)
                                        (malloc=7168KB #31220)

            -               Arena Chunk (reserved=4096KB, committed=4096KB)
                                        (malloc=4096KB #256)

            -                 Metaspace (reserved=43264KB, committed=22784KB)
                                        (malloc=1024KB #616)
                                        (mmap: reserved=42240KB, committed=21760KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeInternalArenaGrowthCurrentNmt(Path path) throws IOException {
        String content = """
            929292:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4562944KB, committed=210944KB
                   malloc: 26112KB #231880
                   mmap:   reserved=4536832KB, committed=184832KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=51200KB, committed=24064KB)
                                        (classes #34430)
                                        (malloc=2048KB #63200)
                                        (mmap: reserved=49152KB, committed=22016KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=22016KB)
                                        (    used=17152KB)
                                        (    waste=4864KB =22.09%)
                                        (  Class space:)
                                        (    reserved=32768KB, committed=1536KB)
                                        (    used=1216KB)
                                        (    waste=320KB =20.83%)

            -                    Thread (reserved=22528KB, committed=1728KB)
                                        (thread #22)
                                        (stack: reserved=21504KB, committed=1600KB)
                                        (malloc=96KB #194)
                                        (arena=32KB #25)

            -                      Code (reserved=197632KB, committed=12800KB)
                                        (malloc=320KB #2296)
                                        (mmap: reserved=197312KB, committed=12480KB)

            -                        GC (reserved=98304KB, committed=49408KB)
                                        (malloc=8192KB #1834)
                                        (mmap: reserved=90112KB, committed=41216KB)

            -                  Internal (reserved=30720KB, committed=28672KB)
                                        (malloc=21504KB #78120)
                                        (mmap: reserved=9216KB, committed=7168KB)

            -                   Unknown (reserved=12288KB, committed=12288KB)
                                        (malloc=12288KB #45240)

            -               Arena Chunk (reserved=9216KB, committed=9216KB)
                                        (malloc=9216KB #512)

            -                 Metaspace (reserved=43520KB, committed=23040KB)
                                        (malloc=1024KB #620)
                                        (mmap: reserved=42496KB, committed=22016KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeInternalArenaGrowthDiffNmt(Path path) throws IOException {
        String content = """
            929292:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4562944KB +49152KB, committed=210944KB +47104KB
                   malloc: 26112KB #231880
                   mmap:   reserved=4536832KB, committed=184832KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=51200KB +1024KB, committed=24064KB +1024KB)
                                        (classes #34430 +220)
                                        (malloc=2048KB +64KB #63200)
                                        (mmap: reserved=49152KB +960KB, committed=22016KB +960KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=22016KB +512KB)
                                        (    used=17152KB +256KB)
                                        (    waste=4864KB =22.09%)
                                        (  Class space:)
                                        (    reserved=32768KB, committed=1536KB)
                                        (    used=1216KB)
                                        (    waste=320KB =20.83%)

            -                    Thread (reserved=22528KB +1024KB, committed=1728KB +128KB)
                                        (thread #22 +1)
                                        (stack: reserved=21504KB +1024KB, committed=1600KB +128KB)
                                        (malloc=96KB #194)
                                        (arena=32KB #25)

            -                      Code (reserved=197632KB +1024KB, committed=12800KB +512KB)
                                        (malloc=320KB +64KB #2296)
                                        (mmap: reserved=197312KB +960KB, committed=12480KB +448KB)

            -                        GC (reserved=98304KB, committed=49408KB +256KB)
                                        (malloc=8192KB #1834)
                                        (mmap: reserved=90112KB, committed=41216KB +256KB)

            -                  Internal (reserved=30720KB +24576KB, committed=28672KB +24576KB)
                                        (malloc=21504KB +17444KB #78120)
                                        (mmap: reserved=9216KB +7132KB, committed=7168KB +7132KB)

            -                   Unknown (reserved=12288KB +10240KB, committed=12288KB +10240KB)
                                        (malloc=12288KB +10240KB #45240)

            -               Arena Chunk (reserved=9216KB +8192KB, committed=9216KB +8192KB)
                                        (malloc=9216KB +8192KB #512)

            -                 Metaspace (reserved=43520KB +512KB, committed=23040KB +512KB)
                                        (malloc=1024KB #620)
                                        (mmap: reserved=42496KB +512KB, committed=22016KB +512KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeReservedCommittedMismatchBaselineNmt(Path path) throws IOException {
        String content = """
            939393:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4947968KB, committed=242688KB
                   malloc: 23680KB #221144
                   mmap:   reserved=4924288KB, committed=219008KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=196608KB, committed=20480KB)
                                        (classes #28420)
                                        (malloc=2048KB #55120)
                                        (mmap: reserved=194560KB, committed=18432KB)
                                        (  Metadata:   )
                                        (    reserved=65536KB, committed=19456KB)
                                        (    used=16256KB)
                                        (    waste=3200KB =16.45%)
                                        (  Class space:)
                                        (    reserved=131072KB, committed=1024KB)
                                        (    used=768KB)
                                        (    waste=256KB =25.00%)

            -                    Thread (reserved=32768KB, committed=2048KB)
                                        (thread #32)
                                        (stack: reserved=31744KB, committed=1920KB)
                                        (malloc=96KB #256)
                                        (arena=32KB #28)

            -                      Code (reserved=262144KB, committed=12288KB)
                                        (malloc=320KB #2408)
                                        (mmap: reserved=261824KB, committed=11968KB)

            -                        GC (reserved=131072KB, committed=49152KB)
                                        (malloc=8192KB #1860)
                                        (mmap: reserved=122880KB, committed=40960KB)

            -                  Internal (reserved=65536KB, committed=4096KB)
                                        (malloc=4060KB #48210)
                                        (mmap: reserved=61476KB, committed=36KB)

            -                 Metaspace (reserved=57344KB, committed=23552KB)
                                        (malloc=1024KB #644)
                                        (mmap: reserved=56320KB, committed=22528KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeReservedCommittedMismatchCurrentNmt(Path path) throws IOException {
        String content = """
            939393:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=5423104KB, committed=249344KB
                   malloc: 24192KB #224812
                   mmap:   reserved=5398912KB, committed=225152KB

            -                 Java Heap (reserved=4194304KB, committed=131072KB)
                                        (mmap: reserved=4194304KB, committed=131072KB)

            -                     Class (reserved=327680KB, committed=21504KB)
                                        (classes #28510)
                                        (malloc=2112KB #55880)
                                        (mmap: reserved=325568KB, committed=19392KB)
                                        (  Metadata:   )
                                        (    reserved=65536KB, committed=19968KB)
                                        (    used=16384KB)
                                        (    waste=3584KB =17.95%)
                                        (  Class space:)
                                        (    reserved=262144KB, committed=1536KB)
                                        (    used=1024KB)
                                        (    waste=512KB =33.33%)

            -                    Thread (reserved=32768KB, committed=2176KB)
                                        (thread #33)
                                        (stack: reserved=31744KB, committed=2048KB)
                                        (malloc=96KB #260)
                                        (arena=32KB #29)

            -                      Code (reserved=524288KB, committed=13312KB)
                                        (malloc=384KB #2520)
                                        (mmap: reserved=523904KB, committed=12928KB)

            -                        GC (reserved=131072KB, committed=49408KB)
                                        (malloc=8192KB #1864)
                                        (mmap: reserved=122880KB, committed=41216KB)

            -                  Internal (reserved=131072KB, committed=5120KB)
                                        (malloc=5084KB #50210)
                                        (mmap: reserved=125988KB, committed=36KB)

            -                 Metaspace (reserved=57344KB, committed=24064KB)
                                        (malloc=1024KB #648)
                                        (mmap: reserved=56320KB, committed=23040KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeReservedCommittedMismatchBaselinePmap(Path path) throws IOException {
        String content = """
            939393:   java -XX:NativeMemoryTracking=summary ReservationHeavyService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   32768     8192    8192 rw---   [ anon ]
            0000000702000000  262144        0       0 -----   [ anon ]
            0000000712000000  524288        0       0 -----   [ anon ]
            0000000732000000  262144        0       0 -----   [ anon ]
            00007f8000000000 1048576    65536   65536 rw---   [ anon ]
            00007f8040000000  524288        0       0 -----   [ anon ]
            00007f8080000000  262144     4096    4096 rw--- libjvm.so
            total kB 2916352 77824 77824
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeReservedCommittedMismatchCurrentPmap(Path path) throws IOException {
        String content = """
            939393:   java -XX:NativeMemoryTracking=summary ReservationHeavyService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   32768     8192    8192 rw---   [ anon ]
            0000000702000000  524288        0       0 -----   [ anon ]
            0000000722000000 1048576        0       0 -----   [ anon ]
            0000000762000000  524288        0       0 -----   [ anon ]
            00007f8000000000 1048576    69632   69632 rw---   [ anon ]
            00007f8040000000 1048576        0       0 -----   [ anon ]
            00007f8080000000  262144     4096    4096 rw--- libjvm.so
            total kB 4489216 81920 81920
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthBaselineNmt(Path path) throws IOException {
        String content = """
            747474:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4538368KB, committed=176128KB
                   malloc: 21504KB #210112
                   mmap:   reserved=4516864KB, committed=154624KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=49152KB, committed=22272KB)
                                        (classes #33080)
                                        (malloc=1984KB #60044)
                                        (mmap: reserved=47168KB, committed=20288KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21248KB)
                                        (    used=16064KB)
                                        (    waste=5184KB =24.39%)

            -                    Thread (reserved=20480KB, committed=1472KB)
                                        (thread #20)
                                        (stack: reserved=19456KB, committed=1344KB)
                                        (malloc=96KB #188)
                                        (arena=32KB #24)

            -                      Code (reserved=194560KB, committed=11264KB)
                                        (malloc=256KB #2212)
                                        (mmap: reserved=194304KB, committed=11008KB)

            -                        GC (reserved=98304KB, committed=48128KB)
                                        (malloc=8192KB #1822)
                                        (mmap: reserved=90112KB, committed=39936KB)

            -                  Internal (reserved=32768KB, committed=12288KB)
                                        (malloc=4096KB #70214)
                                        (mmap: reserved=28672KB, committed=8192KB)

            -                 Metaspace (reserved=43008KB, committed=22272KB)
                                        (malloc=1024KB #612)
                                        (mmap: reserved=41984KB, committed=21248KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthMidNmt(Path path) throws IOException {
        String content = """
            747474:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4574208KB, committed=208896KB
                   malloc: 27648KB #228144
                   mmap:   reserved=4546560KB, committed=181248KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=49408KB, committed=22400KB)
                                        (classes #33110)
                                        (malloc=2016KB #60312)
                                        (mmap: reserved=47392KB, committed=20384KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21376KB)
                                        (    used=16224KB)
                                        (    waste=5152KB =24.10%)

            -                    Thread (reserved=20992KB, committed=1600KB)
                                        (thread #20)
                                        (stack: reserved=19968KB, committed=1472KB)
                                        (malloc=96KB #192)
                                        (arena=32KB #25)

            -                      Code (reserved=195584KB, committed=12288KB)
                                        (malloc=288KB #2244)
                                        (mmap: reserved=195296KB, committed=12000KB)

            -                        GC (reserved=99328KB, committed=49152KB)
                                        (malloc=8192KB #1828)
                                        (mmap: reserved=91136KB, committed=40960KB)

            -                  Internal (reserved=65536KB, committed=43008KB)
                                        (malloc=9216KB #81144)
                                        (mmap: reserved=56320KB, committed=33792KB)

            -                 Metaspace (reserved=43264KB, committed=22400KB)
                                        (malloc=1024KB #614)
                                        (mmap: reserved=42240KB, committed=21376KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthCurrentNmt(Path path) throws IOException {
        String content = """
            747474:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4610048KB, committed=241664KB
                   malloc: 34816KB #248112
                   mmap:   reserved=4575232KB, committed=206848KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=49664KB, committed=22528KB)
                                        (classes #33140)
                                        (malloc=2048KB #60640)
                                        (mmap: reserved=47616KB, committed=20480KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21504KB)
                                        (    used=16384KB)
                                        (    waste=5120KB =23.81%)

            -                    Thread (reserved=21504KB, committed=1728KB)
                                        (thread #21)
                                        (stack: reserved=20480KB, committed=1600KB)
                                        (malloc=96KB #196)
                                        (arena=32KB #26)

            -                      Code (reserved=196608KB, committed=13312KB)
                                        (malloc=320KB #2288)
                                        (mmap: reserved=196288KB, committed=12992KB)

            -                        GC (reserved=100352KB, committed=50176KB)
                                        (malloc=8192KB #1836)
                                        (mmap: reserved=92160KB, committed=41984KB)

            -                  Internal (reserved=98304KB, committed=73728KB)
                                        (malloc=16384KB #92344)
                                        (mmap: reserved=81920KB, committed=57344KB)

            -                 Metaspace (reserved=43520KB, committed=22528KB)
                                        (malloc=1024KB #616)
                                        (mmap: reserved=42496KB, committed=21504KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthDiffNmt(Path path) throws IOException {
        String content = """
            747474:

            Native Memory Tracking:

            (Omitting categories weighting less than 1KB)

            Total: reserved=4610048KB +71680KB, committed=241664KB +65536KB
                   malloc: 34816KB #248112
                   mmap:   reserved=4575232KB, committed=206848KB

            -                 Java Heap (reserved=4194304KB, committed=262144KB)
                                        (mmap: reserved=4194304KB, committed=262144KB)

            -                     Class (reserved=49664KB +512KB, committed=22528KB +256KB)
                                        (classes #33140 +60)
                                        (malloc=2048KB +64KB #60640)
                                        (mmap: reserved=47616KB +448KB, committed=20480KB +192KB)
                                        (  Metadata:   )
                                        (    reserved=32768KB, committed=21504KB +256KB)
                                        (    used=16384KB +320KB)
                                        (    waste=5120KB =23.81%)

            -                    Thread (reserved=21504KB +1024KB, committed=1728KB +256KB)
                                        (thread #21 +1)
                                        (stack: reserved=20480KB +1024KB, committed=1600KB +256KB)
                                        (malloc=96KB #196)
                                        (arena=32KB #26)

            -                      Code (reserved=196608KB +2048KB, committed=13312KB +2048KB)
                                        (malloc=320KB +64KB #2288)
                                        (mmap: reserved=196288KB +1984KB, committed=12992KB +1984KB)

            -                        GC (reserved=100352KB +2048KB, committed=50176KB +2048KB)
                                        (malloc=8192KB #1836)
                                        (mmap: reserved=92160KB +2048KB, committed=41984KB +2048KB)

            -                  Internal (reserved=98304KB +65536KB, committed=73728KB +61440KB)
                                        (malloc=16384KB +12288KB #92344)
                                        (mmap: reserved=81920KB +53248KB, committed=57344KB +49152KB)

            -                 Metaspace (reserved=43520KB +512KB, committed=22528KB +256KB)
                                        (malloc=1024KB #616)
                                        (mmap: reserved=42496KB +512KB, committed=21504KB +256KB)
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthBaselinePmap(Path path) throws IOException {
        String content = """
            747474:   java -XX:NativeMemoryTracking=detail ResidentNativeGrowthService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   65536    16384   16384 rw---   [ anon ]
            0000000702000000  262144    49152   49152 rw---   [ anon ]
            0000000712000000  131072    32768   32768 rw---   [ anon ]
            000000071a000000  262144        0       0 -----   [ anon ]
            000000072a000000  393216    16384   16384 rw---   [ anon ]
            00007f8000000000  131072     4096    4096 rw--- libjvm.so
            total kB 1245184 118784 118784
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthMidPmap(Path path) throws IOException {
        String content = """
            747474:   java -XX:NativeMemoryTracking=detail ResidentNativeGrowthService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000   98304    32768   32768 rw---   [ anon ]
            0000000706000000  393216   131072  131072 rw---   [ anon ]
            000000071e000000  196608    65536   65536 rw---   [ anon ]
            000000072a000000  262144        0       0 -----   [ anon ]
            000000073a000000  524288    53248   53248 rw---   [ anon ]
            00007f8000000000  131072     4096    4096 rw--- libjvm.so
            total kB 1605632 286720 286720
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Path writeActiveNativeGrowthCurrentPmap(Path path) throws IOException {
        String content = """
            747474:   java -XX:NativeMemoryTracking=detail ResidentNativeGrowthService
            Address           Kbytes     RSS   Dirty Mode  Mapping
            0000000700000000  131072    65536   65536 rw---   [ anon ]
            0000000708000000  524288   262144  262144 rw---   [ anon ]
            0000000728000000  262144   131072  131072 rw---   [ anon ]
            0000000738000000  262144        0       0 -----   [ anon ]
            0000000748000000  393216    32768   32768 rw---   [ anon ]
            00007f8000000000  131072     4096    4096 rw--- libjvm.so
            total kB 1703936 495616 495616
            """;
        Files.writeString(path, content);
        return path;
    }

    private static Instant classLoadingWindowStart(ParsedArtifact jfrParsed) {
        if (jfrParsed != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> classLoadingSummary = jfrParsed.extractedData().get("classLoadingSummary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant firstSeen = instantValue(classLoadingSummary.get("firstSeen"));
            if (firstSeen != null) {
                return firstSeen;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = jfrParsed.extractedData().get("summary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant recordingStart = instantValue(summary.get("startTime"));
            if (recordingStart != null) {
                return recordingStart;
            }
        }
        return Instant.parse("2026-04-08T00:00:00Z");
    }

    private static Instant heapExhaustionWindowStart(ParsedArtifact jfrParsed) {
        if (jfrParsed != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> incidentWindows = jfrParsed.extractedData().get("incidentWindows") instanceof List<?> windows
                ? (List<Map<String, Object>>) windows
                : List.of();
            if (!incidentWindows.isEmpty()) {
                Instant startTime = instantValue(incidentWindows.getFirst().get("startTime"));
                if (startTime != null) {
                    return startTime;
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = jfrParsed.extractedData().get("summary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant recordingStart = instantValue(summary.get("startTime"));
            if (recordingStart != null) {
                return recordingStart;
            }
        }
        return Instant.parse("2026-04-08T00:00:00Z");
    }

    private static Instant codeCacheCrashTime(ParsedArtifact jfrParsed) {
        if (jfrParsed != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> codeCacheSummary = jfrParsed.extractedData().get("codeCacheSummary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant lastSeen = instantValue(codeCacheSummary.get("lastSeen"));
            if (lastSeen != null) {
                return lastSeen.plusMillis(250L);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = jfrParsed.extractedData().get("summary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant recordingEnd = instantValue(summary.get("endTime"));
            if (recordingEnd != null) {
                return recordingEnd.plusMillis(250L);
            }
        }
        return Instant.parse("2026-04-08T00:00:00Z");
    }

    private static Instant directBufferCrashTime(ParsedArtifact jfrParsed) {
        if (jfrParsed != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = jfrParsed.extractedData().get("summary") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            Instant endTime = instantValue(summary.get("endTime"));
            if (endTime != null) {
                return endTime.plusSeconds(2L);
            }
            Instant startTime = instantValue(summary.get("startTime"));
            if (startTime != null) {
                return startTime.plusSeconds(4L);
            }
        }
        return Instant.parse("2026-04-08T00:00:00Z");
    }

    private static Instant instantValue(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return Instant.parse(text);
        }
        return null;
    }

    private static String gcTimestamp(Instant instant) {
        return GC_TIMESTAMP_FORMAT.format(instant);
    }
}
