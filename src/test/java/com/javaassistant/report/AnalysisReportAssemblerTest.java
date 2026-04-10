package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.SeverityLevel;
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
import java.time.LocalDateTime;
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

    @Test
    void correlationReportsUseIncidentLevelSeverityAndSummary() {
        InputArtifact nmtArtifact = artifact(ArtifactType.NMT, "/tmp/current.nmt", "current.nmt");
        InputArtifact pmapArtifact = artifact(ArtifactType.PMAP, "/tmp/current.pmap", "current.pmap");

        ParsedArtifact nmtParsedArtifact = parsedArtifact(
            nmtArtifact,
            evidence("nmt-total", nmtArtifact.metadata().sourcePath()),
            evidence("nmt-category-internal", nmtArtifact.metadata().sourcePath())
        );
        ParsedArtifact pmapParsedArtifact = parsedArtifact(
            pmapArtifact,
            evidence("pmap-largest-mapping", pmapArtifact.metadata().sourcePath())
        );

        AssessmentResult nmtEvaluation = new AssessmentResult(
            List.of(finding(
                "nmt-native-allocation-growth",
                "Native-memory growth is concentrated in internal categories",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                nmtArtifact.metadata().sourcePath(),
                "nmt-total",
                "nmt-category-internal"
            )),
            List.of(),
            List.of()
        );
        AssessmentResult pmapEvaluation = new AssessmentResult(List.of(), List.of(), List.of());

        CorrelationResult correlationResult = new CorrelationResult(
            "No deterministic cross-artifact correlations were strong enough to emit a unified finding.",
            ConfidenceLevel.LOW,
            List.of(),
            List.of(),
            List.of(nmtArtifact.metadata().sourcePath(), pmapArtifact.metadata().sourcePath())
        );

        AnalysisReport report = assembler.assemble(
            List.of(nmtArtifact, pmapArtifact),
            List.of(nmtParsedArtifact, pmapParsedArtifact),
            List.of(nmtEvaluation, pmapEvaluation),
            correlationResult
        );

        assertEquals(SeverityLevel.LOW, report.overallSeverity());
        assertEquals(ConfidenceLevel.LOW, report.confidence());
        assertEquals(correlationResult.summary(), report.incidentSummary());
        assertEquals(List.of("nmt-native-allocation-growth"), report.findings().stream().map(Finding::id).toList());
    }

    private InputArtifact artifact(ArtifactType artifactType, String sourcePath, String displayName) {
        return new InputArtifact(
            artifactType,
            new ArtifactMetadata(sourcePath, displayName, 1024L, LocalDateTime.of(2026, 4, 9, 12, 0), Map.of()),
            "test-content"
        );
    }

    private ParsedArtifact parsedArtifact(InputArtifact artifact, Evidence... evidence) {
        return new ParsedArtifact(
            artifact.type(),
            artifact.metadata(),
            "test-parser",
            Map.of(),
            List.of(evidence),
            List.of()
        );
    }

    private Evidence evidence(String id, String artifactPath) {
        return new Evidence(id, artifactPath, id, "detail", "snippet", List.of(1), Map.of());
    }

    private Finding finding(
        String id,
        String title,
        SeverityLevel severity,
        ConfidenceLevel confidence,
        String artifactPath,
        String... evidenceIds
    ) {
        return new Finding(
            id,
            title,
            title,
            "test.category",
            severity,
            confidence,
            FindingStatus.CONFIRMED,
            List.of(artifactPath),
            List.of(evidenceIds),
            "test rationale"
        );
    }
}
