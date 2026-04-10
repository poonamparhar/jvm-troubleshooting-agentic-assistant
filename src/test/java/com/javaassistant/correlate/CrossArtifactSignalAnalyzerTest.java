package com.javaassistant.correlate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import com.javaassistant.parse.HsErrArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.OomSignalArtifactParser;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossArtifactSignalAnalyzerTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final ThreadDumpArtifactParser threadDumpParser = new ThreadDumpArtifactParser();
    private final HeapHistogramArtifactParser heapParser = new HeapHistogramArtifactParser();
    private final HsErrArtifactParser hsErrParser = new HsErrArtifactParser();
    private final ContainerMemoryArtifactParser containerMemoryParser = new ContainerMemoryArtifactParser();
    private final NmtArtifactParser nmtParser = new NmtArtifactParser();
    private final OomSignalArtifactParser oomSignalParser = new OomSignalArtifactParser();
    private final PmapArtifactParser pmapParser = new PmapArtifactParser();
    private final CrossArtifactSignalAnalyzer analyzer = new CrossArtifactSignalAnalyzer();

    @TempDir
    Path tempDir;

    @Test
    void detectsAbsoluteOverlapBetweenJfrIncidentWindowAndGcDistressWindow() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-overlap.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("timed-overlap.log"), jfrParsed), ArtifactType.GC_LOG));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("jfr-gc-time-alignment");

        assertNotNull(alignment);
        assertEquals("ABSOLUTE_OVERLAP", alignment.status(), alignment.toString());
        assertTrue(alignment.windowIds().stream().anyMatch(windowId -> windowId.startsWith("incident-window:")));
        assertTrue(alignment.windowIds().contains("gc-distress-window"));
    }

    @Test
    void reportsNoSharedClockWhenGcLogOnlyHasElapsedSeconds() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("elapsed-only.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(createElapsedOnlyGcLog(tempDir.resolve("elapsed-only.log")), ArtifactType.GC_LOG));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("jfr-gc-time-alignment");

        assertNotNull(alignment);
        assertEquals("NO_SHARED_CLOCK", alignment.status());
        assertTrue(alignment.detail().contains("elapsed seconds"));
    }

    @Test
    void anchorsElapsedOnlyGcLogWhenJfrExposesJvmStartTime() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecordingWithJvmInfo(tempDir.resolve("elapsed-anchored.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(
            createElapsedOnlyGcLogAlignedToIncidentWindow(tempDir.resolve("elapsed-anchored.log"), jfrParsed),
            ArtifactType.GC_LOG
        ));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("jfr-gc-time-alignment");
        CrossArtifactSignalAnalyzer.ArtifactTimingProfile gcTiming = summary.crossArtifactTiming().timingCoverage().stream()
            .filter(profile -> profile.artifactType() == ArtifactType.GC_LOG)
            .findFirst()
            .orElseThrow();

        assertNotNull(alignment);
        assertEquals("ABSOLUTE_OVERLAP", alignment.status(), alignment.toString());
        assertEquals("ELAPSED_ANCHORED_WITH_JFR_JVM_START", gcTiming.timingStatus());
        assertTrue(gcTiming.notes().stream().anyMatch(note -> note.contains("jvmStartTime")));
        assertTrue(gcTiming.timingWindows().stream().anyMatch(window -> window.containsKey("startTime")));
    }

    @Test
    void marksHeapHistogramAsUntimedCompanionWithoutExplicitCaptureTimestamp() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("untimed-companion.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("untimed-companion.log"), jfrParsed), ArtifactType.GC_LOG));
        ParsedArtifact heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("untimed-companion-histogram.txt"))));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed, heapParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("heap-histogram-time-placement");

        assertNotNull(alignment);
        assertEquals("UNTIMED_COMPANION", alignment.status());
    }

    @Test
    void placesHeapHistogramOnTimelineWhenExplicitCaptureTimestampIsPresent() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-companion.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("timed-companion.log"), jfrParsed), ArtifactType.GC_LOG));
        ParsedArtifact heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("timed-companion-histogram.txt"))));

        Instant captureTime = firstIncidentWindowMidpoint(jfrParsed);
        ParsedArtifact timedHeap = withCaptureTime(heapParsed, captureTime);

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed, timedHeap));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("heap-histogram-time-placement");

        assertNotNull(alignment);
        assertEquals("ABSOLUTE_OVERLAP", alignment.status());
        assertTrue(alignment.metrics().containsKey("captureStartTime"));
    }

    @Test
    void annotatesJfrHeapClassOverlapWhenTimedHeapSnapshotOverlapsJvmIncidentWindow() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-heap-class-overlap.jfr")))
        );
        ParsedArtifact heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("timed-heap-class-overlap.txt"))));
        ParsedArtifact timedHeap = withCaptureTime(heapParsed, firstIncidentWindowMidpoint(jfrParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, timedHeap));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-heap-class-overlap");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("timed JVM incident window"));
        assertEquals("ABSOLUTE_OVERLAP", summary.crossArtifactTiming().alignment("heap-histogram-time-placement").status());
    }

    @Test
    void suppressesTripleJfrGcHeapAlignmentWhenTimedHeapSnapshotFallsOutsideIncidentWindow() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-heap-no-overlap.jfr")))
        );
        ParsedArtifact gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("timed-heap-no-overlap.log"), jfrParsed), ArtifactType.GC_LOG));
        ParsedArtifact heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("timed-heap-no-overlap.txt"))));
        ParsedArtifact timedHeap = withCaptureTime(heapParsed, incidentWindowStart(jfrParsed).plus(Duration.ofMinutes(30L)));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed, timedHeap));

        assertNull(summary.alignment("jfr-gc-heap-pressure-alignment"));
        assertNotNull(summary.alignment("jfr-heap-class-overlap"));
        assertTrue(summary.alignment("jfr-heap-class-overlap").detail().contains("outside the timed JVM incident windows"));
        assertEquals("ABSOLUTE_NO_OVERLAP", summary.crossArtifactTiming().alignment("heap-histogram-time-placement").status());
    }

    @Test
    void annotatesJfrNativeAlignmentWhenTimedNmtSnapshotOverlapsJvmIncidentWindow() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-native-overlap.jfr")))
        );
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, firstIncidentWindowMidpoint(jfrParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-native-pressure-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("timed JVM incident window"));
        assertEquals("ABSOLUTE_OVERLAP", summary.crossArtifactTiming().alignment("nmt-time-placement").status());
        assertTrue(((Number) alignment.metrics().get("timedNativePlacementCount")).longValue() >= 1L);
    }

    @Test
    void annotatesJfrThreadDumpAlignmentWhenTimedThreadDumpOverlapsJvmIncidentWindow() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("timed-thread-dump-overlap.jfr")))
        );
        ParsedArtifact threadDumpParsed = threadDumpParser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        ParsedArtifact timedThreadDump = withCaptureTime(threadDumpParsed, firstIncidentWindowMidpoint(jfrParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, timedThreadDump));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-thread-dump-contention-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("timed JVM incident window"));
        assertEquals("ABSOLUTE_OVERLAP", summary.crossArtifactTiming().alignment("thread-dump-time-placement").status());
    }

    @Test
    void alignsThreadDumpAndNmtWhenThreadPressureSignalsConverge() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createThreadGrowthBundle(tempDir);
        ParsedArtifact threadDumpParsed = threadDumpParser.parse(loader.load(bundle.get("thread-dump")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, Instant.parse("2026-04-07T17:02:15Z"));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(threadDumpParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("thread-dump-nmt-thread-pressure-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("thread-dump-nmt-time-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("thread footprint is still growing"));
        assertTrue(alignment.detail().contains("http-worker"));
        assertEquals("ABSOLUTE_OVERLAP", timing.status());
    }

    @Test
    void marksThreadDumpAndNmtAsDifferentWindowsWhenCaptureTimesDoNotOverlap() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createThreadGrowthBundle(tempDir);
        ParsedArtifact threadDumpParsed = threadDumpParser.parse(loader.load(bundle.get("thread-dump")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, Instant.parse("2026-04-08T17:02:15Z"));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(threadDumpParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("thread-dump-nmt-thread-pressure-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("thread-dump-nmt-time-alignment");

        assertNotNull(alignment);
        assertEquals("ABSOLUTE_NO_OVERLAP", timing.status());
        assertTrue(alignment.detail().contains("do not overlap"));
    }

    @Test
    void alignsNativeThreadExhaustionHsErrWithThreadPressureSignals() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createNativeThreadExhaustionBundle(tempDir);
        ParsedArtifact hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed).minusSeconds(2L));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(hsErrParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("hs-err-native-pressure-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("hs-err-native-time-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("native thread exhaustion"));
        assertEquals("ABSOLUTE_SEQUENCE_NEARBY", timing.status());
    }

    @Test
    void alignsCompressedClassSpaceSignalsAcrossHsErrAndNmt() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDir);
        ParsedArtifact hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed).minusSeconds(10L));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(hsErrParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("compressed-class-space-pressure-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("hs-err-native-time-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("compressed class space"));
        assertEquals(62_976L, ((Number) alignment.metrics().get("classSpaceUsedKb")).longValue());
        assertEquals("ABSOLUTE_SEQUENCE_NEARBY", timing.status());
    }

    @Test
    void alignsJfrClassLoadingWithGcAndNmtMetaspacePressure() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createClassLoadingMetaspaceBundle(tempDir);
        ParsedArtifact jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        ParsedArtifact gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, gcParsed, nmtParsed));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-metaspace-class-pressure-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("jfr-gc-time-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("class-loading pressure"));
        assertTrue(alignment.detail().contains("metadata-triggered pressure"));
        assertEquals("DynamicProxyLoader", alignment.metrics().get("topClassLoader"));
        assertEquals("ABSOLUTE_OVERLAP", timing.status());
    }

    @Test
    void alignsJfrCodeCachePressureWithHsErrAndNmtSignals() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCodeCacheFullBundle(tempDir);
        ParsedArtifact jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, nmtParsed, hsErrParsed));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-code-cache-pressure-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("code-cache"));
        assertEquals("C2", alignment.metrics().get("topCompiler"));
        assertEquals(61_440L, ((Number) alignment.metrics().get("codeCommittedKb")).longValue());
        assertEquals(640L, ((Number) alignment.metrics().get("codeCacheFreeKb")).longValue());
    }

    @Test
    void alignsJfrAllocationPressureWithNativeGrowthForGeneratedDirectBufferScenario() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createDirectBufferNativeLeakBundle(tempDir);
        ParsedArtifact jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, nmtParsed, pmapParsed));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-native-pressure-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("native-pressure signals"));
        assertTrue(alignment.detail().contains("capture times"));
        assertEquals("java.nio.ByteBuffer", alignment.metrics().get("topAllocationClass"));
        assertTrue(((Number) alignment.metrics().get("totalAllocatedBytes")).longValue() >= 18_000_000L);
    }

    @Test
    void alignsNmtAndPmapReservationMismatchForGeneratedScenario() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createReservedCommittedMismatchBundle(tempDir);
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        ParsedArtifact pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));
        Instant captureTime = Instant.parse("2026-04-08T17:02:15Z");
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, captureTime);
        ParsedArtifact timedPmap = withCaptureTime(pmapParsed, captureTime);

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(timedNmt, timedPmap));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("nmt-pmap-reservation-mismatch-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("nmt-pmap-time-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("reservation-heavy native footprint"));
        assertTrue(alignment.detail().contains("capture times"));
        assertEquals(1_146_880L, ((Number) alignment.metrics().get("nonHeapReservedKb")).longValue());
        assertEquals(4_407_296L, ((Number) alignment.metrics().get("reservedGapKb")).longValue());
        assertTrue(((List<?>) alignment.metrics().get("reservationGapCategories")).contains("Code"));
        assertNotNull(timing);
        assertEquals("ABSOLUTE_OVERLAP", timing.status());
    }

    @Test
    void placesThreadDumpOnTimelineWhenCaptureTimestampIsEmbeddedInTheDump() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(tempDir.resolve("embedded-thread-dump-time.jfr")))
        );
        ParsedArtifact threadDumpParsed = threadDumpParser.parse(
            loader.load(createTimedThreadDump(tempDir.resolve("embedded-thread-dump-time.txt"), firstIncidentWindowMidpoint(jfrParsed)))
        );

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, threadDumpParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment alignment = summary.crossArtifactTiming().alignment("thread-dump-time-placement");

        assertNotNull(alignment);
        assertEquals("ABSOLUTE_OVERLAP", alignment.status());
        assertTrue(alignment.metrics().containsKey("captureStartTime"));
    }

    @Test
    void surfacesConcreteJfrThreadDumpJoinKeysWhenThreadNamesAndPoolsMatch() throws Exception {
        ParsedArtifact jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(tempDir.resolve("thread-join-overlap.jfr")))
        );
        ParsedArtifact threadDumpParsed = threadDumpParser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        ParsedArtifact timedThreadDump = withCaptureTime(threadDumpParsed, firstIncidentWindowMidpoint(jfrParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(jfrParsed, timedThreadDump));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("jfr-thread-dump-contention-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("shared thread names"));
        assertTrue(alignment.detail().contains("shared executor or worker pools"));
        assertTrue(((List<?>) alignment.metrics().get("sharedThreadNames")).contains("http-nio-8080-exec-17"));
        assertTrue(((List<?>) alignment.metrics().get("sharedPoolNames")).contains("http-nio-8080-exec"));
    }

    @Test
    void annotatesHsErrNativeAlignmentWhenCrashAndNativeSnapshotOverlap() throws Exception {
        ParsedArtifact hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(hsErrParsed, timedNmt));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("hs-err-native-pressure-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("crash window"));
        assertEquals("ABSOLUTE_OVERLAP", summary.crossArtifactTiming().alignment("hs-err-native-time-alignment").status());
    }

    @Test
    void reportsNearbySequenceWhenNativeSnapshotPrecedesCrash() throws Exception {
        ParsedArtifact hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        ParsedArtifact nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        ParsedArtifact timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed).minusSeconds(120L));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(hsErrParsed, timedNmt));
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("hs-err-native-time-alignment");
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("hs-err-native-pressure-alignment");

        assertNotNull(timing);
        assertEquals("ABSOLUTE_SEQUENCE_NEARBY", timing.status());
        assertEquals("PRIMARY_AFTER_COMPANION", timing.metrics().get("sequenceDirection"));
        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("before the crash window"));
    }

    @Test
    void annotatesContainerOomAlignmentWhenTimedSnapshotMatchesOomWindow() throws Exception {
        ParsedArtifact containerParsed = containerMemoryParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        ParsedArtifact oomParsed = oomSignalParser.parse(loader.load(Path.of("samples/pod_oomkilled_describe.txt")));
        ParsedArtifact timedContainer = withCaptureTime(containerParsed, latestOomEventTime(oomParsed));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(timedContainer, oomParsed));
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("container-oom-pressure-alignment");

        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("same memory-budget incident"));
        assertEquals("ABSOLUTE_OVERLAP", summary.crossArtifactTiming().alignment("container-oom-time-alignment").status());
    }

    @Test
    void reportsNearbySequenceWhenContainerSnapshotPrecedesOomKill() throws Exception {
        ParsedArtifact containerParsed = containerMemoryParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        ParsedArtifact oomParsed = oomSignalParser.parse(loader.load(Path.of("samples/kernel_oom_kill.log")));
        ParsedArtifact timedContainer = withCaptureTime(containerParsed, latestOomEventTime(oomParsed).minusSeconds(90L));

        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary summary = analyzer.summarize(List.of(timedContainer, oomParsed));
        CrossArtifactSignalAnalyzer.TimingAlignment timing = summary.crossArtifactTiming().alignment("container-oom-time-alignment");
        CrossArtifactSignalAnalyzer.SignalAlignment alignment = summary.alignment("container-oom-pressure-alignment");

        assertNotNull(timing);
        assertEquals("ABSOLUTE_SEQUENCE_NEARBY", timing.status());
        assertEquals("PRIMARY_BEFORE_COMPANION", timing.metrics().get("sequenceDirection"));
        assertNotNull(alignment);
        assertTrue(alignment.detail().contains("precedes the OOM window"));
    }

    private Path createGcLogOverlappingIncidentWindow(Path path, ParsedArtifact jfrParsed) throws Exception {
        Instant incidentStart = incidentWindowStart(jfrParsed);
        Instant gcStart = incidentStart;
        Instant gcSecond = incidentStart;
        Instant bootstrap = gcStart.minusSeconds(1L);

        String content = ""
            + "[" + bootstrap + "][0.100s][info][gc] Using G1\n"
            + "[" + gcStart + "][1.100s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 1020M->1018M(1024M) 220.000ms\n"
            + "[" + gcSecond + "][1.450s][info][gc] GC(2) Pause Full (G1 Compaction Pause) 1022M->1020M(1024M) 260.000ms\n";
        Files.writeString(path, content);
        return path;
    }

    private Path createElapsedOnlyGcLog(Path path) throws Exception {
        String content = ""
            + "1.000: [Full GC (G1 Compaction Pause) 1020M->1018M(1024M), 0.2200000 secs]\n"
            + "2.000: [Full GC (G1 Compaction Pause) 1022M->1020M(1024M), 0.2600000 secs]\n";
        Files.writeString(path, content);
        return path;
    }

    private Path createElapsedOnlyGcLogAlignedToIncidentWindow(Path path, ParsedArtifact jfrParsed) throws Exception {
        Instant jvmStartTime = Instant.parse(jvmRuntimeInfo(jfrParsed).get("jvmStartTime").toString());
        Instant incidentStart = incidentWindowStart(jfrParsed);
        double firstElapsedSeconds = Duration.between(jvmStartTime, incidentStart).toMillis() / 1000.0d;
        double secondElapsedSeconds = firstElapsedSeconds + 0.35d;

        String content = String.format(
            Locale.ROOT,
            "%.3f: [Full GC (G1 Compaction Pause) 1020M->1018M(1024M), 0.2200000 secs]%n%.3f: [Full GC (G1 Compaction Pause) 1022M->1020M(1024M), 0.2600000 secs]%n",
            firstElapsedSeconds,
            secondElapsedSeconds
        );
        Files.writeString(path, content);
        return path;
    }

    private Path createMatchingHeapHistogram(Path path) throws Exception {
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

    private Path createTimedThreadDump(Path path, Instant captureTime) throws Exception {
        String sample = Files.readString(Path.of("samples/thread_dump_deadlock.txt"));
        int firstNewline = sample.indexOf('\n');
        String threadDumpBody = firstNewline >= 0 ? sample.substring(firstNewline + 1).stripLeading() : sample;
        Files.writeString(path, "Capture time: " + captureTime + "\n" + threadDumpBody);
        return path;
    }

    private Instant incidentWindowStart(ParsedArtifact jfrParsed) {
        return Instant.parse(firstIncidentWindow(jfrParsed).get("startTime").toString());
    }

    private Instant crashTime(ParsedArtifact hsErrParsed) {
        return Instant.parse(String.valueOf(hsErrParsed.extractedData().get("crashTime")));
    }

    @SuppressWarnings("unchecked")
    private Instant latestOomEventTime(ParsedArtifact oomParsed) {
        Map<String, Object> summary = (Map<String, Object>) oomParsed.extractedData().get("summary");
        return Instant.parse(String.valueOf(summary.get("latestAbsoluteEventTime")));
    }

    private Instant firstIncidentWindowMidpoint(ParsedArtifact jfrParsed) {
        Map<String, Object> incidentWindow = firstIncidentWindow(jfrParsed);
        Instant start = Instant.parse(incidentWindow.get("startTime").toString());
        Instant end = Instant.parse(incidentWindow.get("endTime").toString());
        if (!end.isAfter(start)) {
            return start;
        }
        return start.plusMillis(Duration.between(start, end).toMillis() / 2L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstIncidentWindow(ParsedArtifact jfrParsed) {
        List<Map<String, Object>> incidentWindows = (List<Map<String, Object>>) jfrParsed.extractedData().get("incidentWindows");
        return incidentWindows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jvmRuntimeInfo(ParsedArtifact jfrParsed) {
        return (Map<String, Object>) jfrParsed.extractedData().get("jvmRuntimeInfo");
    }

    private ParsedArtifact withCaptureTime(ParsedArtifact parsedArtifact, Instant captureTime) {
        ArtifactMetadata metadata = parsedArtifact.metadata();
        ArtifactMetadata updatedMetadata = new ArtifactMetadata(
            metadata.sourcePath(),
            metadata.displayName(),
            metadata.contentLength(),
            metadata.discoveredAt(),
            Map.of("captureTime", captureTime.toString())
        );
        return new ParsedArtifact(
            parsedArtifact.type(),
            updatedMetadata,
            parsedArtifact.parserVersion(),
            parsedArtifact.extractedData(),
            parsedArtifact.evidence(),
            parsedArtifact.warnings()
        );
    }
}
