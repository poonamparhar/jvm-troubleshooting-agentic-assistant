package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.OomSignalArtifactParser;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import com.javaassistant.assessment.ContainerMemoryArtifactAssessor;
import com.javaassistant.assessment.JfrArtifactAssessor;
import com.javaassistant.assessment.NmtArtifactAssessor;
import com.javaassistant.assessment.OomSignalArtifactAssessor;
import com.javaassistant.assessment.ThreadDumpArtifactAssessor;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisReportAssemblerTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactAssessor assessor = new NmtArtifactAssessor();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();

    @TempDir
    Path tempDir;

    @Test
    void assemblesCanonicalReportFromParsedArtifactAndRules() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertEquals(AnalysisReport.CURRENT_SCHEMA_VERSION, report.schemaVersion());
        assertNotNull(report.analysisId());
        assertFalse(report.findings().isEmpty());
        assertTrue(report.incidentSummary().contains("analysis found"));
        assertTrue(report.artifactInventory().isEmpty());
        assertFalse(report.followUpCommands().isEmpty());
        assertEquals(AnalysisReport.CURRENT_SCHEMA_VERSION, report.toCanonicalMap().get("schemaVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
        assertNotNull(reportMetadata);
        @SuppressWarnings("unchecked")
        Map<String, Object> catalogSummary = (Map<String, Object>) reportMetadata.get("catalogSummary");
        assertNotNull(catalogSummary);
        assertEquals(report.analysisId(), catalogSummary.get("analysisId"));
        assertEquals(report.overallSeverity().name(), catalogSummary.get("overallSeverity"));
        assertTrue(((List<?>) catalogSummary.get("artifactTypes")).contains("NMT"));
    }

    @Test
    void includesThreadDumpFollowUpCommands() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/thread_dump_deadlock.txt"));
        var parsedArtifact = new ThreadDumpArtifactParser().parse(inputArtifact);
        var evaluation = new ThreadDumpArtifactAssessor().evaluate(parsedArtifact);
        AnalysisReport report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertTrue(report.followUpCommands().contains("jcmd <pid> Thread.print -l"));
        assertTrue(report.followUpCommands().contains("jstack -l <pid>"));
    }

    @Test
    void includesContainerMemoryFollowUpCommands() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/container_memory_pressure_snapshot.txt"));
        var parsedArtifact = new ContainerMemoryArtifactParser().parse(inputArtifact);
        var evaluation = new ContainerMemoryArtifactAssessor().evaluate(parsedArtifact);
        AnalysisReport report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("memory.current")));
        assertTrue(report.followUpCommands().contains("jcmd <pid> VM.native_memory summary"));
    }

    @Test
    void includesOomSignalFollowUpCommands() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/pod_oomkilled_describe.txt"));
        var parsedArtifact = new OomSignalArtifactParser().parse(inputArtifact);
        var evaluation = new OomSignalArtifactAssessor().evaluate(parsedArtifact);
        AnalysisReport report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertTrue(report.followUpCommands().contains("journalctl -k -g 'oom|Out of memory|Killed process'"));
        assertTrue(report.followUpCommands().contains("kubectl describe pod <pod-name>"));
    }

    @Test
    void includesExpandedJfrFollowUpCommands() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("deeper-recording.jfr"));
        var inputArtifact = loader.load(recordingPath);
        var parsedArtifact = new JfrArtifactParser().parse(inputArtifact);
        var evaluation = new JfrArtifactAssessor().evaluate(parsedArtifact);
        AnalysisReport report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertTrue(report.followUpCommands().contains("jfr summary <recording.jfr>"));
        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("jdk.ThreadPark")));
        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("jdk.JavaExceptionThrow")));
        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("jdk.ObjectAllocationInNewTLAB")));
        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("jdk.OldObjectSample")));
        assertTrue(report.followUpCommands().stream().anyMatch(command -> command.contains("jdk.ExecutionSample")));
    }
}
