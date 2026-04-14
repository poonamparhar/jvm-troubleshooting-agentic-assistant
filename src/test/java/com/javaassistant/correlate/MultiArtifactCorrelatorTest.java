package com.javaassistant.correlate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
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
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
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
    void emitsGeneratedMetaspacePressureCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createMetaspacePressureBundle(tempDir);
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));

        var correlation = correlator.correlate(
            List.of(gcParsed, nmtParsed, pmapParsed),
            List.of(gcAssessor.evaluate(gcParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-metaspace-class-pressure")));
        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-native-pressure")));
    }

    @Test
    void emitsGeneratedNativeReservationMismatchCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createReservedCommittedMismatchBundle(tempDir);
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));

        var correlation = correlator.correlate(
            List.of(nmtParsed, pmapParsed),
            List.of(nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-native-reservation-mismatch");
        assertTrue(finding.evidenceIds().contains("nmt-total"));
        assertTrue(finding.evidenceIds().contains("pmap-resident-gap"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-native-reservation-mismatch")
        ));
        assertFalse(correlation.findings().stream().anyMatch(candidate -> candidate.id().equals("correlation-native-pressure")));
    }

    @Test
    void emitsGeneratedActiveNativeGrowthCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createActiveNativeGrowthBundle(tempDir);
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));
        var nmtEvaluation = nmtAssessor.evaluate(nmtParsed);
        var pmapEvaluation = pmapAssessor.evaluate(pmapParsed);

        var correlation = correlator.correlate(
            List.of(nmtParsed, pmapParsed),
            List.of(nmtEvaluation, pmapEvaluation)
        );

        assertTrue(nmtEvaluation.findings().stream().anyMatch(candidate -> candidate.id().equals("nmt-native-allocation-growth")));
        assertTrue(pmapEvaluation.findings().stream().anyMatch(candidate -> candidate.id().equals("pmap-anon-pressure")));
        Finding finding = finding(correlation.findings(), "correlation-native-pressure");
        assertTrue(finding.evidenceIds().contains("nmt-total-delta"));
        assertTrue(finding.evidenceIds().contains("pmap-largest-resident-mapping"));
        assertFalse(correlation.findings().stream().anyMatch(candidate ->
            candidate.id().equals("correlation-native-reservation-mismatch")
        ));
    }

    @Test
    void emitsGeneratedThreadGrowthCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createThreadGrowthBundle(tempDir);
        var threadDumpParsed = threadDumpParser.parse(loader.load(bundle.get("thread-dump")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, nmtParsed),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), nmtAssessor.evaluate(nmtParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-thread-pool-thread-pressure");
        assertTrue(finding.evidenceIds().contains("thread-dump-blocked-threads"));
        assertTrue(finding.evidenceIds().contains("nmt-thread-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-thread-pool-thread-pressure")));
    }

    @Test
    void emitsGeneratedNativeThreadExhaustionCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createNativeThreadExhaustionBundle(tempDir);
        var threadDumpParsed = threadDumpParser.parse(loader.load(bundle.get("thread-dump")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, nmtParsed, hsErrParsed),
            List.of(
                threadDumpAssessor.evaluate(threadDumpParsed),
                nmtAssessor.evaluate(nmtParsed),
                hsErrAssessor.evaluate(hsErrParsed)
            )
        );

        Finding finding = finding(correlation.findings(), "correlation-native-thread-exhaustion-confirmed");
        assertTrue(finding.evidenceIds().contains("hs-err-native-thread-exhaustion"));
        assertTrue(finding.evidenceIds().contains("nmt-thread-summary"));
        assertTrue(correlation.findings().stream().anyMatch(candidate -> candidate.id().equals("correlation-thread-pool-thread-pressure")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-native-thread-exhaustion-confirmed")));
    }

    @Test
    void emitsGeneratedCompressedClassSpaceCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDir);
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));
        var timedNmt = withCaptureTime(nmtParsed, crashTime(hsErrParsed).minusSeconds(10L));

        var correlation = correlator.correlate(
            List.of(timedNmt, hsErrParsed),
            List.of(nmtAssessor.evaluate(timedNmt), hsErrAssessor.evaluate(hsErrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-compressed-class-space-exhaustion");
        assertTrue(finding.evidenceIds().contains("hs-err-compressed-class-space"));
        assertTrue(finding.evidenceIds().contains("nmt-class-space-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-compressed-class-space-exhaustion")
        ));
    }

    @Test
    void emitsGeneratedClassLoadingMetaspaceCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createClassLoadingMetaspaceBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, nmtParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), nmtAssessor.evaluate(nmtParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-metaspace-class-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-class-loading-summary"));
        assertTrue(finding.evidenceIds().contains("gc-metaspace-summary"));
        assertTrue(finding.evidenceIds().contains("nmt-metaspace-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-jfr-metaspace-class-pressure")));
    }

    @Test
    void emitsGeneratedCodeCacheExhaustionCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCodeCacheFullBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, nmtParsed, hsErrParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), nmtAssessor.evaluate(nmtParsed), hsErrAssessor.evaluate(hsErrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-code-cache-exhaustion-confirmed");
        assertTrue(finding.evidenceIds().contains("jfr-code-cache-summary"));
        assertTrue(finding.evidenceIds().contains("hs-err-code-cache-status"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-code-cache-exhaustion-confirmed")
        ));
    }

    @Test
    void emitsGeneratedDirectBufferNativePressureCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createDirectBufferNativeLeakBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, nmtParsed, pmapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), nmtAssessor.evaluate(nmtParsed), pmapAssessor.evaluate(pmapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-mixed-heap-native-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-allocation-field-summary"));
        assertTrue(finding.evidenceIds().contains("nmt-total-delta"));
        assertTrue(correlation.findings().stream().anyMatch(candidate -> candidate.id().equals("correlation-native-pressure")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-mixed-heap-native-pressure")));
    }

    @Test
    void emitsGeneratedDirectBufferNativeOomCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createDirectBufferNativeOomBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));
        var hsErrParsed = hsErrParser.parse(loader.load(bundle.get("hs-err")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, nmtParsed, pmapParsed, hsErrParsed),
            List.of(
                jfrAssessor.evaluate(jfrParsed),
                nmtAssessor.evaluate(nmtParsed),
                pmapAssessor.evaluate(pmapParsed),
                hsErrAssessor.evaluate(hsErrParsed)
            )
        );

        Finding mixedPressure = finding(correlation.findings(), "correlation-mixed-heap-native-pressure");
        assertTrue(mixedPressure.evidenceIds().contains("jfr-allocation-field-summary"));
        assertTrue(mixedPressure.evidenceIds().contains("nmt-total-delta"));
        assertTrue(correlation.findings().stream().anyMatch(candidate -> candidate.id().equals("correlation-native-pressure")));

        Finding nativeOom = finding(correlation.findings(), "correlation-native-oom-confirmed");
        assertTrue(nativeOom.evidenceIds().contains("hs-err-native-allocation-failure"));
        assertTrue(correlation.findings().stream().anyMatch(candidate -> candidate.id().equals("correlation-crash-under-memory-distress")));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-native-oom-confirmed")));
    }

    @Test
    void emitsGeneratedContainerBudgetCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createContainerBudgetJvmBundle(tempDir);
        var containerParsed = containerParser.parse(loader.load(bundle.get("container")));
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));

        var correlation = correlator.correlate(
            List.of(containerParsed, gcParsed, nmtParsed),
            List.of(containerAssessor.evaluate(containerParsed), gcAssessor.evaluate(gcParsed), nmtAssessor.evaluate(nmtParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-container-memory-pressure");
        assertTrue(finding.evidenceIds().contains("container-memory-summary"));
        assertTrue(finding.evidenceIds().contains("gc-full-gc-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-container-memory-pressure")
        ));
    }

    @Test
    void emitsGeneratedHeapExhaustionCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createHeapExhaustionBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var heapParsed = heapParser.parse(loader.load(bundle.get("heap")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, heapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), heapAssessor.evaluate(heapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-gc-heap-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-old-object-field-summary"));
        assertTrue(finding.evidenceIds().contains("gc-full-gc-summary"));
        assertTrue(finding.evidenceIds().contains("histogram-top-consumer"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-jfr-gc-heap-pressure")
        ));
    }

    @Test
    void emitsGeneratedJavaHeapSpaceCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createJavaHeapSpaceExhaustionBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var heapParsed = heapParser.parse(loader.load(bundle.get("heap")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, heapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), heapAssessor.evaluate(heapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-gc-heap-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-old-object-field-summary"));
        assertTrue(finding.evidenceIds().contains("gc-full-gc-summary"));
        assertTrue(finding.evidenceIds().contains("histogram-top-consumer"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-jfr-gc-heap-pressure")
        ));
    }

    @Test
    void emitsGeneratedG1HumongousPressureCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createG1HumongousAllocationPressureBundle(tempDir);
        var jfrParsed = jfrParser.parse(loader.load(bundle.get("jfr")));
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var heapParsed = heapParser.parse(loader.load(bundle.get("heap")));

        var correlation = correlator.correlate(
            List.of(jfrParsed, gcParsed, heapParsed),
            List.of(jfrAssessor.evaluate(jfrParsed), gcAssessor.evaluate(gcParsed), heapAssessor.evaluate(heapParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-jfr-gc-heap-pressure");
        assertTrue(finding.evidenceIds().contains("jfr-old-object-field-summary"));
        assertTrue(finding.evidenceIds().contains("gc-humongous-summary"));
        assertTrue(finding.evidenceIds().contains("histogram-top-consumer"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-correlation-jfr-gc-heap-pressure")
        ));
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
    void emitsGeneratedMixedHeapAndNativePressureCorrelation() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createMixedHeapNativePressureBundle(tempDir);
        var gcParsed = gcParser.parse(loader.load(bundle.get("gc")));
        var heapParsed = heapParser.parse(loader.load(bundle.get("heap")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var pmapParsed = pmapParser.parse(loader.load(bundle.get("pmap")));

        var correlation = correlator.correlate(
            List.of(gcParsed, heapParsed, nmtParsed, pmapParsed),
            List.of(
                gcAssessor.evaluate(gcParsed),
                heapAssessor.evaluate(heapParsed),
                nmtAssessor.evaluate(nmtParsed),
                pmapAssessor.evaluate(pmapParsed)
            )
        );

        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-mixed-heap-native-pressure")));
        assertTrue(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-native-pressure")));
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
    void emitsDownstreamIoPileupCorrelationWhenThreadDumpAndJfrAgree() throws Exception {
        var threadDumpParsed = threadDumpParser.parse(syntheticArtifact(ArtifactType.THREAD_DUMP, "samples/thread-dump-io-pileup.txt", downstreamIoThreadDump()));
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("io-pileup-recording.jfr"))));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, jfrParsed),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), jfrAssessor.evaluate(jfrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-downstream-io-pileup");
        assertTrue(finding.evidenceIds().contains("jfr-io-summary"));
        assertTrue(finding.evidenceIds().contains("thread-dump-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-downstream-io-pileup")));
    }

    @Test
    void emitsForkJoinStarvationCorrelationWhenThreadDumpAndJfrAgree() throws Exception {
        var threadDumpParsed = threadDumpParser.parse(
            syntheticArtifact(ArtifactType.THREAD_DUMP, "samples/thread-dump-forkjoin-starvation.txt", forkJoinStarvationThreadDump())
        );
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("forkjoin-recording.jfr"))));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, jfrParsed),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), jfrAssessor.evaluate(jfrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-forkjoin-starvation");
        assertTrue(finding.evidenceIds().contains("jfr-thread-park-summary"));
        assertTrue(finding.evidenceIds().contains("thread-dump-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-forkjoin-starvation")));
    }

    @Test
    void emitsVirtualThreadPinningCorrelationWhenThreadDumpAndJfrAgree() throws Exception {
        var threadDumpParsed = threadDumpParser.parse(
            syntheticArtifact(ArtifactType.THREAD_DUMP, "samples/thread-dump-virtual-thread-pinning.txt", virtualThreadPinningThreadDump())
        );
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createVirtualThreadPinningRecording(tempDir.resolve("pinning-recording.jfr"))));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, jfrParsed),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), jfrAssessor.evaluate(jfrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-virtual-thread-pinning");
        assertTrue(finding.evidenceIds().contains("jfr-recording-summary"));
        assertTrue(finding.evidenceIds().contains("thread-dump-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-virtual-thread-pinning")));
    }

    @Test
    void emitsBusySpinCorrelationWhenThreadDumpAndJfrAgree() throws Exception {
        var threadDumpParsed = threadDumpParser.parse(syntheticArtifact(ArtifactType.THREAD_DUMP, "samples/thread-dump-busy-spin.txt", busySpinThreadDump()));
        var jfrParsed = jfrParser.parse(loader.load(JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("busy-spin-recording.jfr"))));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, jfrParsed),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), jfrAssessor.evaluate(jfrParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-busy-spin-hot-path");
        assertTrue(finding.evidenceIds().contains("jfr-execution-hotspots"));
        assertTrue(finding.evidenceIds().contains("thread-dump-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-busy-spin-hot-path")));
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
    void doesNotEmitThreadGrowthCorrelationWhenNmtSnapshotFallsOutsideThreadDumpWindow() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createThreadGrowthBundle(tempDir);
        var threadDumpParsed = threadDumpParser.parse(loader.load(bundle.get("thread-dump")));
        var nmtParsed = nmtParser.parse(loader.load(bundle.get("nmt")));
        var timedNmt = withCaptureTime(nmtParsed, Instant.parse("2026-04-08T17:02:15Z"));

        var correlation = correlator.correlate(
            List.of(threadDumpParsed, timedNmt),
            List.of(threadDumpAssessor.evaluate(threadDumpParsed), nmtAssessor.evaluate(timedNmt))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-thread-pool-thread-pressure")));
        assertTrue(correlation.summary().contains("thread dump and NMT"));
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
    void emitsCrashUnderMemoryDistressWhenGcPressureAndHsErrCrashCoexist() throws Exception {
        var hsErrParsed = hsErrParser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var gcParsed = gcParser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        var correlation = correlator.correlate(
            List.of(hsErrParsed, gcParsed),
            List.of(hsErrAssessor.evaluate(hsErrParsed), gcAssessor.evaluate(gcParsed))
        );

        Finding finding = finding(correlation.findings(), "correlation-crash-under-memory-distress");
        assertTrue(finding.evidenceIds().contains("hs-err-problematic-frame"));
        assertTrue(finding.evidenceIds().contains("gc-full-gc-summary"));
        assertTrue(correlation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-correlation-crash-under-memory-distress")));
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
    void surfacesHostVsCgroupAmbiguityWhenKernelOomHasNoMemcgAndContainerLooksHealthy() {
        var containerParsed = containerParser.parse(
            syntheticArtifact(ArtifactType.CONTAINER_MEMORY, "samples/container-memory-healthy.txt", healthyContainerSnapshot())
        );
        var oomParsed = oomParser.parse(
            syntheticArtifact(ArtifactType.OOM_SIGNAL, "samples/kernel-oom-no-memcg.log", kernelOomWithoutMemcg())
        );

        var correlation = correlator.correlate(
            List.of(containerParsed, oomParsed),
            List.of(containerAssessor.evaluate(containerParsed), oomAssessor.evaluate(oomParsed))
        );

        assertFalse(correlation.findings().stream().anyMatch(finding -> finding.id().equals("correlation-container-memory-pressure")));
        assertTrue(correlation.summary().contains("host-wide pressure or a container-local budget"));
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

    private InputArtifact syntheticArtifact(ArtifactType artifactType, String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            artifactType,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }

    private String downstreamIoThreadDump() {
        return """
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "checkout-io-pool-17" #17 daemon prio=5 os_prio=31 cpu=932.40ms elapsed=18.00s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at sun.nio.ch.SocketDispatcher.read0(Native Method)
                at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

            "checkout-io-pool-18" #18 daemon prio=5 os_prio=31 cpu=910.10ms elapsed=17.80s tid=0x0000000102a54000 nid=0x8303 runnable [0x000000016f5d3000]
               java.lang.Thread.State: RUNNABLE
                at sun.nio.ch.SocketDispatcher.read0(Native Method)
                at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

            "checkout-io-pool-19" #19 daemon prio=5 os_prio=31 cpu=902.70ms elapsed=17.60s tid=0x0000000102a86000 nid=0x8403 runnable [0x000000016f4d3000]
               java.lang.Thread.State: RUNNABLE
                at sun.nio.ch.SocketDispatcher.read0(Native Method)
                at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

            "checkout-io-pool-20" #20 daemon prio=5 os_prio=31 cpu=8.40ms elapsed=17.55s tid=0x0000000102ab7000 nid=0x8503 waiting on condition [0x000000016f3d3000]
               java.lang.Thread.State: WAITING (parking)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
            """;
    }

    private String forkJoinStarvationThreadDump() {
        return """
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "ForkJoinPool.commonPool-worker-1" #31 daemon prio=5 os_prio=31 cpu=120.10ms elapsed=21.00s tid=0x0000000102a23000 nid=0x8203 waiting on condition [0x000000016f6d3000]
               java.lang.Thread.State: WAITING (parking)
                at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
                at java.util.concurrent.ForkJoinTask.join(ForkJoinTask.java:670)

            "ForkJoinPool.commonPool-worker-2" #32 daemon prio=5 os_prio=31 cpu=118.40ms elapsed=20.70s tid=0x0000000102a54000 nid=0x8303 waiting on condition [0x000000016f5d3000]
               java.lang.Thread.State: WAITING (parking)
                at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
                at java.util.concurrent.ForkJoinTask.join(ForkJoinTask.java:670)

            "ForkJoinPool.commonPool-worker-3" #33 daemon prio=5 os_prio=31 cpu=117.90ms elapsed=20.40s tid=0x0000000102a86000 nid=0x8403 waiting on condition [0x000000016f4d3000]
               java.lang.Thread.State: WAITING (parking)
                at java.util.concurrent.CompletableFuture$Signaller.block(CompletableFuture.java:1864)
                at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
            """;
    }

    private String virtualThreadPinningThreadDump() {
        return """
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "VirtualThread[#42]/runnable@ForkJoinPool-1-worker-3" #42 daemon prio=5 os_prio=31 cpu=834.20ms elapsed=9.10s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:687)
                at com.acme.jdbc.BlockingQuery.run(BlockingQuery.java:88)

            "ForkJoinPool-1-worker-3" #43 daemon prio=5 os_prio=31 cpu=812.10ms elapsed=9.05s tid=0x0000000102a54000 nid=0x8303 runnable [0x000000016f5d3000]
               java.lang.Thread.State: RUNNABLE
                at java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:687)
                at com.acme.jdbc.BlockingQuery.run(BlockingQuery.java:88)
            """;
    }

    private String busySpinThreadDump() {
        return """
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "pricing-hot-loop-1" #51 daemon prio=5 os_prio=31 cpu=18432.50ms elapsed=19.20s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at com.javaassistant.testsupport.JfrTestRecordingFactory.checkoutService(JfrTestRecordingFactory.java:734)
                at com.acme.pricing.QuoteWorker.run(QuoteWorker.java:81)

            "pricing-hot-loop-2" #52 daemon prio=5 os_prio=31 cpu=12.10ms elapsed=19.00s tid=0x0000000102a54000 nid=0x8303 waiting on condition [0x000000016f5d3000]
               java.lang.Thread.State: WAITING (parking)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
            """;
    }

    private String healthyContainerSnapshot() {
        return """
            [memory.current]
            536870912

            [memory.max]
            1073741824

            [memory.high]
            943718400

            [memory.events]
            low 0
            high 0
            max 0
            oom 0
            oom_kill 0

            [memory.stat]
            anon 402653184
            file 117440512
            kernel 16777216

            [memory.pressure]
            some avg10=0.10 avg60=0.05 avg300=0.01 total=128
            full avg10=0.00 avg60=0.00 avg300=0.00 total=0
            """;
    }

    private String kernelOomWithoutMemcg() {
        return """
            Mar 30 18:42:13 build-host kernel: Killed process 4242 (java) total-vm:25165824kB, anon-rss:1048576kB, file-rss:0kB, shmem-rss:0kB
            """;
    }
}
