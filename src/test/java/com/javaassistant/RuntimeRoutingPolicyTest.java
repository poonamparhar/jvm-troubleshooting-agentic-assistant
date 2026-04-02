package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.SeverityLevel;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeRoutingPolicyTest {

    @Test
    void supportsStructuredAnalysisForV1ArtifactFamiliesOnly() {
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.GC_LOG));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.JFR));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.THREAD_DUMP));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.HS_ERR_LOG));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.NMT));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.HEAP_HISTOGRAM));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.PMAP));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.CONTAINER_MEMORY));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.OOM_SIGNAL));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredAnalysis(ArtifactType.UNKNOWN));
    }

    @Test
    void supportsStructuredComparisonOnlyForImplementedComparisonTypes() {
        assertTrue(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.JFR));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.THREAD_DUMP));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.NMT));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.HEAP_HISTOGRAM));
        assertTrue(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.PMAP));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.CONTAINER_MEMORY));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.OOM_SIGNAL));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.GC_LOG));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredComparison(ArtifactType.HS_ERR_LOG));
    }

    @Test
    void supportsStructuredCorrelationOnlyWhenAllArtifactsAreSupported() {
        List<InputArtifact> structuredSet = List.of(
            artifact(ArtifactType.GC_LOG, "gc.log"),
            artifact(ArtifactType.CONTAINER_MEMORY, "container.txt")
        );
        List<InputArtifact> mixedSet = List.of(
            artifact(ArtifactType.GC_LOG, "gc.log"),
            artifact(ArtifactType.UNKNOWN, "notes.txt")
        );

        assertTrue(RuntimeRoutingPolicy.supportsStructuredCorrelation(structuredSet));
        assertFalse(RuntimeRoutingPolicy.supportsStructuredCorrelation(mixedSet));
    }

    @Test
    void matchesStructuredReportToLoadedDataBySourcePath() {
        AnalysisReport report = new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-1",
            LocalDateTime.now(),
            "summary",
            null,
            List.of(),
            null,
            SeverityLevel.LOW,
            ConfidenceLevel.LOW,
            List.of(new InputArtifact(
                ArtifactType.NMT,
                new ArtifactMetadata("samples/a.nmt", "a.nmt", 10L),
                "content"
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        assertTrue(RuntimeRoutingPolicy.reportMatchesLoadedData(report, artifact(ArtifactType.NMT, "samples/a.nmt")));
        assertFalse(RuntimeRoutingPolicy.reportMatchesLoadedData(report, artifact(ArtifactType.NMT, "samples/b.nmt")));
        assertTrue(RuntimeRoutingPolicy.reportMatchesLoadedData(report, null));
    }

    private static InputArtifact artifact(ArtifactType type, String sourcePath) {
        return new InputArtifact(
            type,
            new ArtifactMetadata(sourcePath, sourcePath, 7L),
            "content"
        );
    }
}
