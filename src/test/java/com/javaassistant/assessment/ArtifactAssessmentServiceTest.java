package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ArtifactParsingService;
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
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactAssessmentServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ArtifactParsingService parsingService = new ArtifactParsingService(List.of(
        new GcLogArtifactParser(),
        new JfrArtifactParser(),
        new ThreadDumpArtifactParser(),
        new HsErrArtifactParser(),
        new NmtArtifactParser(),
        new ContainerMemoryArtifactParser(),
        new OomSignalArtifactParser(),
        new HeapHistogramArtifactParser(),
        new PmapArtifactParser()
    ));
    private final ArtifactAssessmentService assessmentService = new ArtifactAssessmentService(List.of(
        new GcLogArtifactAssessor(),
        new JfrArtifactAssessor(),
        new ThreadDumpArtifactAssessor(),
        new HsErrArtifactAssessor(),
        new NmtArtifactAssessor(),
        new ContainerMemoryArtifactAssessor(),
        new OomSignalArtifactAssessor(),
        new HeapHistogramArtifactAssessor(),
        new PmapArtifactAssessor()
    ));

    @TempDir
    Path tempDir;

    @Test
    void evaluatesParsedArtifactThroughRegistry() throws Exception {
        var parsed = parsingService.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
    }

    @Test
    void evaluatesThreadDumpThroughRegistry() throws Exception {
        var parsed = parsingService.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-java-deadlock")));
    }

    @Test
    void evaluatesJfrThroughRegistry() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("recording.jfr"));
        var parsed = parsingService.parse(loader.load(recordingPath));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-lock-contention-events")));
    }

    @Test
    void evaluatesDeeperJfrAnalyticsThroughRegistry() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("deeper-recording.jfr"));
        var parsed = parsingService.parse(loader.load(recordingPath));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-io-latency-events")));
    }

    @Test
    void evaluatesJfrHotPathAnalysisThroughRegistry() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("hot-path-recording.jfr"));
        var parsed = parsingService.parse(loader.load(recordingPath));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-execution-hot-path")));
    }

    @Test
    void evaluatesJfrAllocationAnalysisThroughRegistry() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createAllocationPathRecording(tempDir.resolve("allocation-recording.jfr"));
        var parsed = parsingService.parse(loader.load(recordingPath));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-allocation-churn")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-allocation-hot-path")));
    }

    @Test
    void evaluatesJfrOldObjectAnalysisThroughRegistry() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createRetainedObjectRecording(tempDir.resolve("old-object-recording.jfr"));
        var parsed = parsingService.parse(loader.load(recordingPath));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-old-object-retention-candidates")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("jfr-old-object-reference-depth")));
    }

    @Test
    void evaluatesContainerMemoryThroughRegistry() throws Exception {
        var parsed = parsingService.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-limit-pressure")));
    }

    @Test
    void evaluatesOomSignalThroughRegistry() throws Exception {
        var parsed = parsingService.parse(loader.load(Path.of("samples/pod_oomkilled_describe.txt")));
        var evaluation = assessmentService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("oom-signal-pod-oomkilled")));
    }
}
