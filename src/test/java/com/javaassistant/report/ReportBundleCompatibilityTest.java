package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.report.JsonReportRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportBundleCompatibilityTest {

    private final AnalysisReportJsonCodec codec = new AnalysisReportJsonCodec();
    private final JsonReportRenderer renderer = new JsonReportRenderer();

    @TempDir
    Path tempDir;

    @Test
    void loadsCheckedInCurrentBundleFixtures() throws Exception {
        ReportBundleService bundleService = new ReportBundleService(tempDir);

        for (String fixtureName : List.of("current-v1-single", "current-v1-correlation")) {
            copyFixtureBundle(fixtureName);

            AnalysisReport report = bundleService.load(fixtureName);

            assertEquals(AnalysisReport.CURRENT_SCHEMA_VERSION, report.schemaVersion());
            assertNotNull(report.analysisId());
            assertNotNull(report.toCanonicalMap().get("reportMetadata"));
            @SuppressWarnings("unchecked")
            Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
            assertTrue(reportMetadata.containsKey("catalogSummary"));
        }
    }

    @Test
    void treatsMissingSchemaVersionAsCurrentV1Contract() throws Exception {
        copyFixtureBundle("legacy-v1-no-schema");

        AnalysisReport report = new ReportBundleService(tempDir).load("legacy-v1-no-schema");

        assertEquals(AnalysisReport.CURRENT_SCHEMA_VERSION, report.schemaVersion());
        String rendered = renderer.render(report);
        assertTrue(rendered.contains("\"schemaVersion\": 1"));
        assertTrue(rendered.contains("\"catalogSummary\""));
    }

    @Test
    void currentCorrelationFixtureCarriesCompactCatalogSummary() throws Exception {
        AnalysisReport report = codec.fromJson(readFixture("current-v1-correlation/report.json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> catalogSummary = (Map<String, Object>) reportMetadata.get("catalogSummary");

        assertNotNull(report.supervisorTrace());
        assertEquals(OrchestrationWorkflowType.CORRELATE, report.supervisorTrace().workflowType());
        assertEquals(report.analysisId(), catalogSummary.get("analysisId"));
        assertEquals(report.overallSeverity().name(), catalogSummary.get("overallSeverity"));
        assertEquals(List.of("NMT", "PMAP"), catalogSummary.get("artifactTypes"));
        assertEquals(Boolean.TRUE, catalogSummary.get("hasCorrelationResult"));
        assertEquals(Boolean.TRUE, catalogSummary.get("aiAgentAttempted"));
        assertEquals(Boolean.TRUE, catalogSummary.get("aiAgentSelectedForUserNarrative"));
        assertEquals(Boolean.TRUE, catalogSummary.get("llmNarrativeSelectedForUserNarrative"));
        assertEquals("CORRELATE", catalogSummary.get("workflowType"));
        assertEquals("TEST", catalogSummary.get("userNarrativeProvider"));
        assertEquals("RoutingStubChatModel", catalogSummary.get("userNarrativeModel"));
        assertEquals("CorrelationAgent.analyze", catalogSummary.get("userNarrativeTemplateId"));
        assertEquals("v1", catalogSummary.get("userNarrativeTemplateVersion"));
    }

    private void copyFixtureBundle(String fixtureName) throws IOException {
        Path destination = tempDir.resolve(fixtureName);
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("report.json"), readFixture(fixtureName + "/report.json"));
    }

    private String readFixture(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("report-bundles/" + resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing fixture resource: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
