package com.javaassistant.correlate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import com.javaassistant.parse.HsErrArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.OomSignalArtifactParser;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.assessment.ContainerMemoryArtifactAssessor;
import com.javaassistant.assessment.GcLogArtifactAssessor;
import com.javaassistant.assessment.HeapHistogramArtifactAssessor;
import com.javaassistant.assessment.HsErrArtifactAssessor;
import com.javaassistant.assessment.NmtArtifactAssessor;
import com.javaassistant.assessment.OomSignalArtifactAssessor;
import com.javaassistant.assessment.PmapArtifactAssessor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiArtifactCorrelatorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final ContainerMemoryArtifactParser containerParser = new ContainerMemoryArtifactParser();
    private final HsErrArtifactParser hsErrParser = new HsErrArtifactParser();
    private final NmtArtifactParser nmtParser = new NmtArtifactParser();
    private final OomSignalArtifactParser oomParser = new OomSignalArtifactParser();
    private final HeapHistogramArtifactParser heapParser = new HeapHistogramArtifactParser();
    private final PmapArtifactParser pmapParser = new PmapArtifactParser();
    private final GcLogArtifactAssessor gcAssessor = new GcLogArtifactAssessor();
    private final ContainerMemoryArtifactAssessor containerAssessor = new ContainerMemoryArtifactAssessor();
    private final HsErrArtifactAssessor hsErrAssessor = new HsErrArtifactAssessor();
    private final NmtArtifactAssessor nmtAssessor = new NmtArtifactAssessor();
    private final OomSignalArtifactAssessor oomAssessor = new OomSignalArtifactAssessor();
    private final HeapHistogramArtifactAssessor heapAssessor = new HeapHistogramArtifactAssessor();
    private final PmapArtifactAssessor pmapAssessor = new PmapArtifactAssessor();
    private final MultiArtifactCorrelator correlator = new MultiArtifactCorrelator();

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
}
