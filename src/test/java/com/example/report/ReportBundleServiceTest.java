package com.example.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.NmtArtifactParser;
import com.example.rules.NmtArtifactRuleEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportBundleServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactRuleEngine ruleEngine = new NmtArtifactRuleEngine();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();

    @TempDir
    Path tempDir;

    @Test
    void savesAllReportFormatsAndReadsThemBack() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = ruleEngine.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation, "Short narrative");

        var bundleService = new ReportBundleService(tempDir);
        Path bundlePath = bundleService.save(report);

        assertTrue(Files.exists(bundlePath.resolve("report.txt")));
        assertTrue(Files.exists(bundlePath.resolve("report.json")));
        assertTrue(Files.exists(bundlePath.resolve("report.md")));
        assertTrue(Files.exists(bundlePath.resolve("report.html")));
        assertTrue(bundleService.readReport(report.analysisId(), "json").contains("\"analysisId\""));
        assertTrue(bundleService.readReport(report.analysisId(), "markdown").contains("# JVM Analysis Report"));
        assertTrue(bundleService.readReport(report.analysisId(), "html").contains("<html>"));
    }
}
