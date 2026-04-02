package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.assessment.NmtArtifactAssessor;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportBundleServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactAssessor assessor = new NmtArtifactAssessor();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();

    @TempDir
    Path tempDir;

    @Test
    void savesAllReportFormatsAndReadsThemBack() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation, "Short narrative");

        var bundleService = new ReportBundleService(tempDir);
        Path bundlePath = bundleService.save(report);

        assertTrue(Files.exists(bundlePath.resolve("report.txt")));
        assertTrue(Files.exists(bundlePath.resolve("report.json")));
        assertTrue(Files.exists(bundlePath.resolve("report.md")));
        assertTrue(Files.exists(bundlePath.resolve("report.html")));
        assertTrue(bundleService.readReport(report.analysisId(), "json").contains("\"analysisId\""));
        assertTrue(bundleService.readReport(report.analysisId(), "json").contains("\"schemaVersion\": 1"));
        assertTrue(bundleService.readReport(report.analysisId(), "markdown").contains("# JVM Analysis Report"));
        assertTrue(bundleService.readReport(report.analysisId(), "html").contains("<html>"));
    }

    @Test
    void loadsSavedReportBundleBackIntoStructuredModel() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation, "Short narrative");

        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(report);
        var loaded = bundleService.load(report.analysisId());

        assertEquals(AnalysisReport.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(report.analysisId(), loaded.analysisId());
        assertEquals(report.incidentSummary(), loaded.incidentSummary());
        assertEquals(report.overallSeverity(), loaded.overallSeverity());
        assertEquals(report.findings().size(), loaded.findings().size());
        assertEquals(report.recommendedActions().size(), loaded.recommendedActions().size());
        assertNotNull(loaded.parsedArtifacts());
        assertEquals(report.inputArtifacts().get(0).metadata().sourcePath(), loaded.inputArtifacts().get(0).metadata().sourcePath());
    }

    @Test
    void rendersNmtDiffDeltasInTextAndJsonReports() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_diff_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(report);

        String textReport = bundleService.readReport(report.analysisId(), "text");
        String jsonReport = bundleService.readReport(report.analysisId(), "json");

        assertTrue(textReport.contains("Structured Deltas:"));
        assertTrue(textReport.contains("java_nmt_diff_3391237.txt [NMT diff]"));
        assertTrue(textReport.contains("total reserved +9123KB, committed -169093KB"));
        assertTrue(jsonReport.contains("\"artifactSummaries\""));
        assertTrue(jsonReport.contains("\"snapshotKind\": \"diff\""));
        assertTrue(jsonReport.contains("\"nmtDiffSummary\""));
        assertTrue(jsonReport.contains("\"totalDeltaKb\""));
    }

    @Test
    void persistsArtifactInventoryAcrossSavedBundles() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation).withArtifactInventory(
            java.util.List.of(
                new ArtifactInventoryEntry(
                    inputArtifact.metadata().sourcePath(),
                    inputArtifact.metadata().displayName(),
                    inputArtifact.type(),
                    ArtifactInventoryStatus.SUPPORTED,
                    "Included in structured analysis."
                ),
                new ArtifactInventoryEntry(
                    "samples/single_process_data/process_info_3391237.txt",
                    "process_info_3391237.txt",
                    ArtifactType.UNKNOWN,
                    ArtifactInventoryStatus.UNSUPPORTED,
                    "No supported artifact signature detected."
                )
            )
        );

        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(report);
        var loaded = bundleService.load(report.analysisId());

        assertEquals(2, loaded.artifactInventory().size());
        assertEquals(ArtifactInventoryStatus.UNSUPPORTED, loaded.artifactInventory().get(1).status());
        assertTrue(bundleService.readReport(report.analysisId(), "text").contains("Artifact Inventory:"));
        assertTrue(bundleService.readReport(report.analysisId(), "markdown").contains("## Artifact Inventory"));
        assertTrue(bundleService.readReport(report.analysisId(), "html").contains("<h2>Artifact Inventory</h2>"));
        assertTrue(bundleService.readReport(report.analysisId(), "json").contains("\"artifactInventory\""));
    }

    @Test
    void redactsSensitiveStringsInShareableFormatsButKeepsJsonFullFidelity() throws Exception {
        AnalysisReport report = reportWithSensitiveContent();

        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(report);

        String textReport = bundleService.readReport(report.analysisId(), "text");
        String markdownReport = bundleService.readReport(report.analysisId(), "markdown");
        String htmlReport = bundleService.readReport(report.analysisId(), "html");
        String jsonReport = bundleService.readReport(report.analysisId(), "json");

        for (String shareableReport : List.of(textReport, markdownReport, htmlReport)) {
            assertTrue(shareableReport.contains("internal-safe-v1"));
            assertTrue(shareableReport.contains("redacted-path-"));
            assertTrue(shareableReport.contains("redacted-host-"));
            assertTrue(shareableReport.contains("redacted-command-"));
            assertTrue(shareableReport.contains("redacted-env-"));
            assertFalse(shareableReport.contains("/srv/prod/apps/orders/current"));
            assertFalse(shareableReport.contains("app01.prod.example.com"));
            assertFalse(shareableReport.contains("sun.java.command=/srv/prod/apps/orders/current/orders.jar"));
            assertFalse(shareableReport.contains("OCI_API_KEY=super-secret"));
        }

        assertTrue(textReport.contains("Correlation Summary:"));
        assertTrue(textReport.contains("Evidence:"));
        assertTrue(markdownReport.contains("## Findings"));
        assertTrue(markdownReport.contains("```text"));
        assertTrue(htmlReport.contains("<h2>Correlation Summary</h2>"));
        assertTrue(htmlReport.contains("class=\"evidence\""));

        assertTrue(jsonReport.contains("/srv/prod/apps/orders/current"));
        assertTrue(jsonReport.contains("app01.prod.example.com"));
        assertTrue(jsonReport.contains("OCI_API_KEY=super-secret"));
        assertTrue(jsonReport.contains("\"reportMetadata\""));
        assertTrue(jsonReport.contains("\"redactionProfile\": \"internal-safe-v1\""));
    }

    @Test
    void failsClearlyWhenSavedJsonBundleIsCorrupt() throws Exception {
        Path bundlePath = tempDir.resolve("broken-report");
        Files.createDirectories(bundlePath);
        Files.writeString(bundlePath.resolve("report.json"), "{ definitely-not-valid-json");

        var bundleService = new ReportBundleService(tempDir);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> bundleService.load("broken-report")
        );

        assertTrue(exception.getMessage() != null && !exception.getMessage().isBlank());
    }

    @Test
    void listsSavedBundlesUsingCatalogMetadataAndFilters() throws Exception {
        AnalysisReport nmtReport = reportWithSensitiveContent();
        AnalysisReport threadDumpReport = minimalCatalogReport(
            "20260329183000-thread-dump",
            LocalDateTime.of(2026, 3, 29, 18, 30, 0),
            ArtifactType.THREAD_DUMP,
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            true
        );

        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(nmtReport);
        bundleService.save(threadDumpReport);

        ReportCatalogResult allEntries = bundleService.listCatalogEntries();
        assertEquals(2, allEntries.entries().size());
        assertEquals("20260329183000-thread-dump", allEntries.entries().getFirst().analysisId());
        assertTrue(allEntries.entries().getFirst().artifactTypes().contains(ArtifactType.THREAD_DUMP));
        assertTrue(allEntries.entries().getFirst().hasCorrelationResult());

        ReportCatalogResult filteredEntries = bundleService.listCatalogEntries(SeverityLevel.CRITICAL, ArtifactType.THREAD_DUMP);
        assertEquals(1, filteredEntries.entries().size());
        assertEquals("20260329183000-thread-dump", filteredEntries.entries().getFirst().analysisId());
        assertEquals("internal-safe-v1", filteredEntries.entries().getFirst().redactionProfile());
    }

    @Test
    void skipsUnreadableBundlesWhenBuildingCatalog() throws Exception {
        var bundleService = new ReportBundleService(tempDir);
        bundleService.save(reportWithSensitiveContent());

        Path brokenBundle = tempDir.resolve("broken-catalog-entry");
        Files.createDirectories(brokenBundle);
        Files.writeString(brokenBundle.resolve("report.json"), "{ definitely-not-valid-json");

        ReportCatalogResult result = bundleService.listCatalogEntries();

        assertEquals(1, result.entries().size());
        assertTrue(result.skippedBundles().stream().anyMatch(item -> item.contains("broken-catalog-entry")));
    }

    private AnalysisReport reportWithSensitiveContent() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 3, 29, 16, 20, 0);
        ArtifactMetadata metadata = new ArtifactMetadata(
            "/srv/prod/apps/orders/current/nmt-summary.txt",
            "orders-app01.prod.example.com.nmt.txt",
            2048L,
            createdAt,
            Map.of("hostname", "app01.prod.example.com")
        );
        InputArtifact inputArtifact = new InputArtifact(
            ArtifactType.NMT,
            metadata,
            "host=app01.prod.example.com path=/srv/prod/apps/orders/current"
        );
        Evidence evidence = new Evidence(
            "evidence-1",
            "/srv/prod/apps/orders/current/nmt-summary.txt",
            "Command line and environment snapshot",
            "sun.java.command=/srv/prod/apps/orders/current/orders.jar --config /etc/orders/prod.yaml",
            "OCI_API_KEY=super-secret\nHOSTNAME=app01.prod.example.com\nJAVA_HOME=/Users/poonam/jdks/jdk-25.jdk/Contents/Home",
            List.of(17, 18),
            Map.of("hostname", "app01.prod.example.com", "rssKb", 654321)
        );
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.NMT,
            metadata,
            "test-parser",
            Map.of("snapshotKind", "summary"),
            List.of(evidence),
            List.of()
        );
        Finding finding = new Finding(
            "finding-1",
            "Native memory pressure on app01.prod.example.com",
            "Command line points at /srv/prod/apps/orders/current/orders.jar on app01.prod.example.com.",
            "native-memory",
            SeverityLevel.HIGH,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            List.of("/srv/prod/apps/orders/current/nmt-summary.txt"),
            List.of("evidence-1"),
            "JAVA_HOME=/Users/poonam/jdks/jdk-25.jdk/Contents/Home and HOSTNAME=app01.prod.example.com indicate the affected node."
        );
        RecommendedAction action = new RecommendedAction(
            "action-1",
            "Capture a fresh NMT diff from /srv/prod/apps/orders/current",
            "Run the collection steps on app01.prod.example.com after validating OCI_API_KEY=super-secret.",
            ActionType.DATA_COLLECTION,
            ActionPriority.URGENT,
            List.of(
                "ssh app01.prod.example.com",
                "jcmd 123 VM.native_memory detail.diff"
            ),
            List.of("finding-1")
        );
        CorrelationResult correlationResult = new CorrelationResult(
            "app01.prod.example.com shows consistent native pressure from /srv/prod/apps/orders/current.",
            ConfidenceLevel.HIGH,
            List.of(),
            List.of(),
            List.of("/srv/prod/apps/orders/current/nmt-summary.txt")
        );

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "20260329162000-app01.prod.example.com.orders",
            createdAt,
            "app01.prod.example.com is under native memory pressure and the JVM points at /srv/prod/apps/orders/current/orders.jar.",
            "The bundle suggests a repeatable native-memory issue on app01.prod.example.com.",
            List.of(),
            null,
            SeverityLevel.HIGH,
            ConfidenceLevel.HIGH,
            List.of(inputArtifact),
            List.of(parsedArtifact),
            List.of(evidence),
            List.of(finding),
            List.of(action),
            List.of("Need an hs_err or GC log from app01.prod.example.com for stronger confirmation."),
            List.of("grep -n \"Problematic frame\" /srv/prod/apps/orders/current/hs_err_pid1.log"),
            List.of(new ArtifactInventoryEntry(
                "/srv/prod/apps/orders/current/nmt-summary.txt",
                "orders-app01.prod.example.com.nmt.txt",
                ArtifactType.NMT,
                ArtifactInventoryStatus.SUPPORTED,
                "Collected from app01.prod.example.com under /srv/prod/apps/orders/current."
            )),
            correlationResult
        );
    }

    private AnalysisReport minimalCatalogReport(
        String analysisId,
        LocalDateTime createdAt,
        ArtifactType artifactType,
        SeverityLevel severityLevel,
        ConfidenceLevel confidenceLevel,
        boolean hasCorrelationResult
    ) {
        ArtifactMetadata metadata = new ArtifactMetadata(
            "samples/" + analysisId + ".txt",
            analysisId + ".txt",
            12L,
            createdAt,
            Map.of()
        );
        InputArtifact inputArtifact = new InputArtifact(artifactType, metadata, "sample");
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            artifactType,
            metadata,
            "catalog-test",
            Map.of("snapshotKind", "summary"),
            List.of(),
            List.of()
        );
        CorrelationResult correlationResult = hasCorrelationResult
            ? new CorrelationResult("Synthetic correlation", confidenceLevel, List.of(), List.of(), List.of(metadata.sourcePath()))
            : null;

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            analysisId,
            createdAt,
            "Synthetic catalog report",
            null,
            List.of(),
            null,
            severityLevel,
            confidenceLevel,
            List.of(inputArtifact),
            List.of(parsedArtifact),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("echo next"),
            List.of(),
            correlationResult
        );
    }
}
