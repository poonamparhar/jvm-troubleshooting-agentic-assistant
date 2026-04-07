package com.javaassistant.correlate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import com.javaassistant.parse.HsErrArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.OomSignalArtifactParser;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import com.javaassistant.assessment.ContainerMemoryArtifactAssessor;
import com.javaassistant.assessment.GcLogArtifactAssessor;
import com.javaassistant.assessment.HeapHistogramArtifactAssessor;
import com.javaassistant.assessment.HsErrArtifactAssessor;
import com.javaassistant.assessment.JfrArtifactAssessor;
import com.javaassistant.assessment.NmtArtifactAssessor;
import com.javaassistant.assessment.OomSignalArtifactAssessor;
import com.javaassistant.assessment.PmapArtifactAssessor;
import com.javaassistant.assessment.ThreadDumpArtifactAssessor;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiArtifactCorrelatorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final ContainerMemoryArtifactParser containerParser = new ContainerMemoryArtifactParser();
    private final HsErrArtifactParser hsErrParser = new HsErrArtifactParser();
    private final NmtArtifactParser nmtParser = new NmtArtifactParser();
    private final OomSignalArtifactParser oomParser = new OomSignalArtifactParser();
    private final HeapHistogramArtifactParser heapParser = new HeapHistogramArtifactParser();
    private final PmapArtifactParser pmapParser = new PmapArtifactParser();
    private final ThreadDumpArtifactParser threadDumpParser = new ThreadDumpArtifactParser();
    private final JfrArtifactAssessor jfrAssessor = new JfrArtifactAssessor();
    private final GcLogArtifactAssessor gcAssessor = new GcLogArtifactAssessor();
    private final ContainerMemoryArtifactAssessor containerAssessor = new ContainerMemoryArtifactAssessor();
    private final HsErrArtifactAssessor hsErrAssessor = new HsErrArtifactAssessor();
    private final NmtArtifactAssessor nmtAssessor = new NmtArtifactAssessor();
    private final OomSignalArtifactAssessor oomAssessor = new OomSignalArtifactAssessor();
    private final HeapHistogramArtifactAssessor heapAssessor = new HeapHistogramArtifactAssessor();
    private final PmapArtifactAssessor pmapAssessor = new PmapArtifactAssessor();
    private final ThreadDumpArtifactAssessor threadDumpAssessor = new ThreadDumpArtifactAssessor();
    private final MultiArtifactCorrelator correlator = new MultiArtifactCorrelator();

    @TempDir
    Path tempDir;

    @Test
    void emitsCrossArtifactMemoryPressureFinding() throws Exception {
        var gcParsed = gcParser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var pmapParsed = pmapParser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        var correlation = correlator.correlate(
            List.of(gcParsed, nmtParsed, pmapParsed),
            List.of(gcAssessor.evaluate(gcParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-memory-pressure")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-memory-pressure")));
    }

    @Test
    void emitsMetaspacePressureCorrelation() throws Exception {
        var gcParsed = gcParser.parse(loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_diff_3391237.txt")));

        var correlation = correlator.correlate(
            List.of(gcParsed, nmtParsed),
            List.of(gcAssessor.evaluate(gcParsed), nmtAssessor.evaluate(nmtParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-metaspace-class-pressure");
        assertTrue(finding.evidenceIds().contains("gc-metaspace-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-metaspace-class-pressure")));
    }

    @Test
    void emitsContainerMemoryCorrelationWhenJvmSignalsHitCgroupLimits() throws Exception {
        var gcParsed = gcParser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var containerParsed = containerParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));

        var correlation = correlator.correlate(
            List.of(gcParsed, containerParsed),
            List.of(gcAssessor.evaluate(gcParsed), containerAssessor.evaluate(containerParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-container-memory-pressure");
        assertTrue(finding.evidenceIds().contains("container-memory-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-container-memory-pressure")));
    }

    @Test
    void emitsConfirmedOomCorrelationsWhenKillSignalsAlignWithContainerAndJvmEvidence() throws Exception {
        var gcParsed = gcParser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var containerParsed = containerParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var oomParsed = oomParser.parse(loader.load(Path.of("samples/kernel_oom_kill.log")));

        var correlation = correlator.correlate(
            List.of(gcParsed, containerParsed, oomParsed),
            List.of(gcAssessor.evaluate(gcParsed), containerAssessor.evaluate(containerParsed), oomAssessor.evaluate(oomParsed))
        );

        Finding containerEscalation = finding(correlation.findings(), "correlation-container-oom-escalation");
        assertTrue(containerEscalation.evidenceIds().contains("oom-signal-kernel-event"));
        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-jvm-memory-escalated-to-oom")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-container-oom-escalation")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-jvm-memory-escalated-to-oom")));
    }

    @Test
    void emitsContainerOomEscalationWhenPressureSnapshotShortlyPrecedesOomKill() throws Exception {
        var containerParsed = containerParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var oomParsed = oomParser.parse(loader.load(Path.of("samples/kernel_oom_kill.log")));
        var timedContainer = withCaptureTime(containerParsed, latestOomEventTime(oomParsed).minusSeconds(90L));

        var correlation = correlator.correlate(
            List.of(timedContainer, oomParsed),
            List.of(containerAssessor.evaluate(timedContainer), oomAssessor.evaluate(oomParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-container-oom-escalation");
        assertTrue(finding.summary().contains("precedes the OOM window"));
    }

    @Test
    void emitsNativeOomConfirmationAndCrashDistressCorrelation() throws Exception {
        var hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var pmapParsed = pmapParser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        var correlation = correlator.correlate(
            List.of(hsErrParsed, nmtParsed, pmapParsed),
            List.of(hsErrAssessor.evaluate(hsErrParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        Finding nativeOom = finding(correlation.findings(), "correlation-native-oom-confirmed");
        assertTrue(nativeOom.evidenceIds().contains("hs-err-native-allocation-failure"));
        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-crash-under-memory-distress")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-native-oom-confirmed")));
    }

    @Test
    void emitsNativeOomConfirmationWhenSnapshotShortlyPrecedesCrash() throws Exception {
        var hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed).minusSeconds(120L));

        var correlation = correlator.correlate(
            List.of(hsErrParsed, timedNmt),
            List.of(hsErrAssessor.evaluate(hsErrParsed), nmtAssessor.evaluate(timedNmt))
        );

        Finding finding = finding(correlation.findings(), "correlation-native-oom-confirmed");
        assertTrue(finding.summary().contains("before the crash window"));
    }

    @Test
    void emitsMixedHeapAndNativePressureCorrelation() throws Exception {
        var heapParsed = heapParser.parse(loader.load(Path.of("samples/heap_histogram_1.txt")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var pmapParsed = pmapParser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        var correlation = correlator.correlate(
            List.of(heapParsed, nmtParsed, pmapParsed),
            List.of(heapAssessor.evaluate(heapParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-mixed-heap-native-pressure")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-mixed-heap-native-pressure")));
    }

    @Test
    void emitsJfrGcHeapCorrelationWhenSignalsConverge() throws Exception {
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-correlation-incident.jfr"))));
        var gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("jfr-correlation-incident.log"), jfrParsed), ArtifactType.GC_LOG));
        var heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("heap-histogram-matching.txt"))));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, heapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), heapAssessor.evaluate(heapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-gc-heap-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-old-object-field-summary"));
        assertTrue(finding.evidenceIds().contains("gc-full-gc-summary"));
        assertTrue(finding.evidenceIds().contains("histogram-top-consumer"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-jfr-gc-heap-pressure")));
    }

    @Test
    void emitsJfrThreadDumpCorrelationWhenSignalsConverge() throws Exception {
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-thread-contention.jfr"))));
        var threadDumpParsed = threadDumpParser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var timedThreadDump = withCaptureTime(threadDumpParsed, firstIncidentWindowMidpoint(jfrParsed));

        var correlation = correlator.correlate(
            List.of(jfrParsed, timedThreadDump),
            List.of(jfrAssessor.evaluate(jfrParsed), threadDumpAssessor.evaluate(timedThreadDump))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-thread-contention");
        assertTrue(finding.evidenceIds().contains("jfr-lock-summary"));
        assertTrue(finding.evidenceIds().contains("thread-dump-deadlock"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-jfr-thread-contention")));
    }

    @Test
    void carriesConcreteJfrThreadDumpJoinKeysIntoTheCorrelationFinding() throws Exception {
        var jfrParsed = jfrParser.parse(
            loader.load(JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(tempDir.resolve("jfr-thread-contention-joins.jfr")))
        );
        var threadDumpParsed = threadDumpParser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var timedThreadDump = withCaptureTime(threadDumpParsed, firstIncidentWindowMidpoint(jfrParsed));

        var correlation = correlator.correlate(
            List.of(jfrParsed, timedThreadDump),
            List.of(jfrAssessor.evaluate(jfrParsed), threadDumpAssessor.evaluate(timedThreadDump))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-thread-contention");
        assertTrue(finding.summary().contains("http-nio-8080-exec-17"));
        assertTrue(finding.summary().contains("http-nio-8080-exec"));
    }

    @Test
    void doesNotEmitJfrGcHeapCorrelationWhenTimedHeapSnapshotFallsOutsideIncidentWindow() throws Exception {
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-gc-heap-mismatch.jfr"))));
        var gcParsed = gcParser.parse(loader.load(createGcLogOverlappingIncidentWindow(tempDir.resolve("jfr-gc-heap-mismatch.log"), jfrParsed), ArtifactType.GC_LOG));
        var heapParsed = heapParser.parse(loader.load(createMatchingHeapHistogram(tempDir.resolve("heap-histogram-mismatch.txt"))));
        var timedHeap = withCaptureTime(heapParsed, incidentWindowStart(jfrParsed).plusSeconds(1800L));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, timedHeap),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), heapAssessor.evaluate(timedHeap))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-jfr-gc-heap-pressure")));
        assertTrue(correlation.summary().contains("outside the same incident window"));
    }

    @Test
    void doesNotEmitJfrThreadDumpCorrelationWhenTimedThreadDumpFallsOutsideIncidentWindow() throws Exception {
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-thread-contention-mismatch.jfr"))));
        var threadDumpParsed = threadDumpParser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var timedThreadDump = withCaptureTime(threadDumpParsed, incidentWindowStart(jfrParsed).plusSeconds(1800L));

        var correlation = correlator.correlate(
            List.of(jfrParsed, timedThreadDump),
            List.of(jfrAssessor.evaluate(jfrParsed), threadDumpAssessor.evaluate(timedThreadDump))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-jfr-thread-contention")));
        assertTrue(correlation.summary().contains("thread-dump capture time"));
    }

    @Test
    void emitsMixedHeapNativePressureWhenJfrProvidesHeapSideSignals() throws Exception {
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createRetainedObjectRecording(tempDir.resolve("jfr-retained-native.jfr"))));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var pmapParsed = pmapParser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, nmtParsed, pmapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-mixed-heap-native-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-old-object-field-summary"));
    }

    @Test
    void doesNotEmitNativeOomConfirmationWhenTimedNativeSnapshotFallsOutsideCrashWindow() throws Exception {
        var hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        var nmtParsed = nmtParser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var timedNmt = withCaptureTime(nmtParsed, Instant.parse("2026-03-30T18:42:13Z"));

        var correlation = correlator.correlate(
            List.of(hsErrParsed, timedNmt),
            List.of(hsErrAssessor.evaluate(hsErrParsed), nmtAssessor.evaluate(timedNmt))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-native-oom-confirmed")));
        assertTrue(correlation.summary().contains("hs_err log and native-memory artifacts"));
    }

    @Test
    void doesNotEmitContainerOomEscalationWhenTimedSnapshotFallsOutsideOomWindow() throws Exception {
        var containerParsed = containerParser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var timedContainer = withCaptureTime(containerParsed, Instant.parse("2026-03-31T18:42:13Z"));
        var oomParsed = oomParser.parse(loader.load(Path.of("samples/pod_oomkilled_describe.txt")));

        var correlation = correlator.correlate(
            List.of(timedContainer, oomParsed),
            List.of(containerAssessor.evaluate(timedContainer), oomAssessor.evaluate(oomParsed))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-container-oom-escalation")));
        assertTrue(correlation.summary().contains("container-memory snapshot and the OOM or restart signals"));
    }

    @Test
    void summarizesMissingCompanionEvidenceWhenNoUnifiedCorrelationCanBeMade() throws Exception {
        var hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));

        var correlation = correlator.correlate(
            List.of(hsErrParsed),
            List.of(hsErrAssessor.evaluate(hsErrParsed))
        );

        assertTrue(correlation.findings().isEmpty());
        assertTrue(correlation.summary().contains("matching NMT or pmap evidence was not provided"));
        assertFalse(correlation.contributingArtifactPaths().isEmpty());
    }

    private Finding finding(List<Finding> findings, String id) {
        return findings.stream()
            .filter(finding -> finding.id().equals(id))
            .findFirst()
            .orElseThrow();
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

    private Path createGcLogOverlappingIncidentWindow(Path path, ParsedArtifact jfrParsed) throws Exception {
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

    private Instant incidentWindowStart(ParsedArtifact jfrParsed) {
        return Instant.parse(firstIncidentWindow(jfrParsed).get("startTime").toString());
    }

    private Instant crashTime(ParsedArtifact hsErrParsed) {
        return Instant.parse(hsErrParsed.extractedData().get("crashTime").toString());
    }

    private Instant latestOomEventTime(ParsedArtifact oomParsed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) oomParsed.extractedData().get("summary");
        return Instant.parse(summary.get("latestAbsoluteEventTime").toString());
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
