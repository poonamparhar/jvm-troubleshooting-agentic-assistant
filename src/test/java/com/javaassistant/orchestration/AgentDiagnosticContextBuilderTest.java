package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDiagnosticContextBuilderTest {

    private final AgentDiagnosticContextBuilder contextBuilder = new AgentDiagnosticContextBuilder();
    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();

    @TempDir
    Path tempDir;

    @Test
    void buildsSingleArtifactContextFromStructuredContextAndChunkedRawContent() {
        String longAttributeValue = "attr-".repeat(80);
        String longDetail = "detail-".repeat(70);
        String longContent = IntStream.rangeClosed(1, 720)
            .mapToObj(index -> "synthetic-line-" + index)
            .collect(Collectors.joining("\n"));
        InputArtifact artifact = new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/tmp/gc.log",
                "gc.log",
                longContent.length(),
                LocalDateTime.of(2026, 3, 31, 18, 0),
                Map.of("veryLongAttribute", longAttributeValue)
            ),
            longContent
        );

        Map<String, Object> extractedData = new LinkedHashMap<>();
        for (int index = 1; index <= 18; index++) {
            extractedData.put("section" + index, Map.of("value", "v".repeat(420)));
        }

        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.GC_LOG,
            artifact.metadata(),
            "test-parser",
            extractedData,
            List.of(new Evidence("ev-1", "/tmp/gc.log", "Pause sample", longDetail, "snippet", List.of(10), Map.of("pauseMs", 123.4))),
            List.of()
        );

        String startingContext = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            )
        );

        assertTrue(startingContext.contains("STRUCTURED_FACTS"));
        assertTrue(startingContext.contains("STRUCTURED_CONTEXT_SLICES"));
        assertTrue(startingContext.contains("REPRESENTATIVE_CONTEXT_SLICES"));
        assertTrue(startingContext.contains("CONTEXT_COVERAGE"));
        assertTrue(startingContext.contains("Slice 1"));
        assertTrue(startingContext.contains("Omitted Structured Sections ("));
        assertTrue(startingContext.contains("Omitted Context Slices ("));
        assertTrue(startingContext.contains("Retrieval Hint: leave the selector blank to fetch the next omitted item."));
        assertTrue(startingContext.contains("offset=<charOffset>, chars=<charCount>"));
        assertFalse(startingContext.contains("Display Name:"));
        assertFalse(startingContext.contains("Content Length:"));
        assertFalse(startingContext.contains("Parser Version:"));
        assertFalse(startingContext.contains("artifactAttributes"));
        assertFalse(startingContext.contains(longAttributeValue));
        assertTrue(startingContext.contains(longDetail));
        assertTrue(startingContext.contains("Source: /tmp/gc.log"));
        assertTrue(startingContext.contains("Source: /tmp/gc.log line 10"));
        assertFalse(startingContext.contains("Traceability:"));
        assertFalse(startingContext.contains("Kind:"));
        assertFalse(startingContext.contains("extractedData."));
        assertFalse(startingContext.contains("... [truncated]"));
        assertTrue(startingContext.contains("... "));
        assertFalse(startingContext.contains("DETERMINISTIC_FINDINGS"));
        assertFalse(startingContext.contains("RECOMMENDED_ACTIONS"));
    }

    @Test
    void buildsGcSingleArtifactContextWithCompactStartingSummary() throws Exception {
        InputArtifact artifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-full-compaction/gc_full_compaction_small.log")
        );
        ParsedArtifact parsedArtifact = gcParser.parse(artifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String startingContext = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(artifact, parsedArtifact)
        );

        assertTrue(startingContext.contains("GC_STARTING_SUMMARY"));
        assertTrue(startingContext.contains("summaryLines"));
        assertTrue(startingContext.contains("Pause profile:"));
        assertTrue(startingContext.contains("Full-GC profile:"));
        assertTrue(startingContext.contains("Recovery shape:"));
        assertTrue(startingContext.contains("Pause mix:"));
        assertTrue(startingContext.contains("dominantIncident"));
        assertTrue(startingContext.contains("G1 distress signals:"));
        assertTrue(startingContext.contains("G1 Compaction Pause"));
    }

    @Test
    void buildsJfrSingleArtifactContextWithCompactStartingSummary() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-current-recording.jfr"));
        InputArtifact artifact = loader.load(recordingPath);
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String startingContext = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(artifact, parsedArtifact)
        );

        assertTrue(startingContext.contains("JFR_STARTING_SUMMARY"));
        assertTrue(startingContext.contains("summaryLines"));
        assertTrue(startingContext.contains("Recording window:"));
        assertTrue(startingContext.contains("Execution hotspot:"));
        assertTrue(startingContext.contains("Runtime pressure:"));
        assertTrue(startingContext.contains("Allocation pressure:"));
        assertTrue(startingContext.contains("Retained-object signals:"));
        assertTrue(startingContext.contains("Incident window:"));
        assertTrue(startingContext.contains("dominantHotspot"));
        assertTrue(startingContext.contains("runtimeSignals"));
        assertTrue(startingContext.contains("memorySignals"));
        assertTrue(startingContext.contains("primaryIncidentWindow"));
        assertTrue(startingContext.contains("chronologyHighlights"));
        assertTrue(startingContext.contains("checkoutAllocationService"));
        assertFalse(startingContext.contains("timelineEvents:"));
    }

    @Test
    void buildsJfrStartingSummaryWithClassLoadingSignals() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createClassLoadingPressureRecording(tempDir.resolve("jfr-class-loading-recording.jfr"));
        InputArtifact artifact = loader.load(recordingPath);
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String startingContext = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(artifact, parsedArtifact)
        );

        assertTrue(startingContext.contains("JFR_STARTING_SUMMARY"));
        assertTrue(startingContext.contains("Class loading:"));
        assertTrue(startingContext.contains("DynamicProxyLoader"));
        assertTrue(startingContext.contains("classLoading"));
        assertTrue(startingContext.contains("definedClassCount"));
        assertTrue(startingContext.contains("totalMetadataBytes"));
    }

    @Test
    void buildsJfrStartingSummaryWithCodeCacheSignals() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createCodeCachePressureRecording(tempDir.resolve("jfr-code-cache-recording.jfr"));
        InputArtifact artifact = loader.load(recordingPath);
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String startingContext = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(artifact, parsedArtifact)
        );

        assertTrue(startingContext.contains("JFR_STARTING_SUMMARY"));
        assertTrue(startingContext.contains("Code cache:"));
        assertTrue(startingContext.contains("compiler disabled"));
        assertTrue(startingContext.contains("codeCache"));
        assertTrue(startingContext.contains("topCompilationMethod"));
    }

    @Test
    void buildsGcComparisonContextWithCollectorAwareDeltaSummary() throws Exception {
        InputArtifact baselineArtifact = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        InputArtifact currentArtifact = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_current_small.log"));
        ParsedArtifact baselineParsed = gcParser.parse(baselineArtifact);
        ParsedArtifact currentParsed = gcParser.parse(currentArtifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String comparisonContext = contextBuilder.buildComparisonContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                baselineArtifact,
                baselineParsed,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(baselineArtifact, baselineParsed),
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                currentArtifact,
                currentParsed,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(currentArtifact, currentParsed)
        );

        assertTrue(comparisonContext.contains("GC_COMPARISON_SUMMARY"));
        assertTrue(comparisonContext.contains("collectorComparison"));
        assertTrue(comparisonContext.contains("pauseAndOverheadDelta"));
        assertTrue(comparisonContext.contains("pressureAndRecoveryDelta"));
        assertTrue(comparisonContext.contains("collectorSpecificDelta"));
        assertTrue(comparisonContext.contains("dominantPauseCauseShift"));
        assertTrue(comparisonContext.contains("causeMixPair"));
        assertTrue(comparisonContext.contains("regressionSynopsis"));
        assertTrue(comparisonContext.contains("summaryLines"));
        assertTrue(comparisonContext.contains("Pause profile:"));
        assertTrue(comparisonContext.contains("Recovery shape:"));
        assertTrue(comparisonContext.contains("baselineCauseMix"));
        assertTrue(comparisonContext.contains("currentCauseMix"));
        assertTrue(comparisonContext.contains("topPauseCausesByTotalPauseMs"));
        assertTrue(comparisonContext.contains("recoveryShapePair"));
        assertTrue(comparisonContext.contains("baselineRecoveryShape"));
        assertTrue(comparisonContext.contains("currentRecoveryShape"));
        assertTrue(comparisonContext.contains("maxNearCapacityPauseStreak"));
        assertTrue(comparisonContext.contains("dominantIncidentPair"));
        assertTrue(comparisonContext.contains("baselineIncident"));
        assertTrue(comparisonContext.contains("currentIncident"));
        assertTrue(comparisonContext.contains("p95PauseMsDelta"));
        assertTrue(comparisonContext.contains("fullGcCountDelta"));
        assertTrue(comparisonContext.contains("toSpaceExhaustedCountDelta"));
        assertTrue(comparisonContext.contains("averageFullGcReclaimedMb"));
        assertTrue(comparisonContext.contains("Pause Young (Mixed)"));
        assertTrue(comparisonContext.contains("G1 Compaction Pause"));
        assertFalse(comparisonContext.contains("DETERMINISTIC_COMPARISON_FINDINGS"));
    }

    @Test
    void buildsJfrComparisonContextWithCompactDeltaSummary() throws Exception {
        InputArtifact baselineArtifact = loader.load(
            JfrTestRecordingFactory.createComparisonBaselineRecording(tempDir.resolve("jfr-baseline-recording.jfr"))
        );
        InputArtifact currentArtifact = loader.load(
            JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("jfr-current-recording-compare.jfr"))
        );
        ParsedArtifact baselineParsed = jfrParser.parse(baselineArtifact);
        ParsedArtifact currentParsed = jfrParser.parse(currentArtifact);

        DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
        String comparisonContext = contextBuilder.buildComparisonContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                baselineArtifact,
                baselineParsed,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(baselineArtifact, baselineParsed),
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                currentArtifact,
                currentParsed,
                new AssessmentResult(List.of(), List.of(), List.of())
            ),
            indexer.index(currentArtifact, currentParsed)
        );

        assertTrue(comparisonContext.contains("JFR_COMPARISON_SUMMARY"));
        assertTrue(comparisonContext.contains("regressionSynopsis"));
        assertTrue(comparisonContext.contains("summaryLines"));
        assertTrue(comparisonContext.contains("Recording window:"));
        assertTrue(comparisonContext.contains("Incident window:"));
        assertTrue(comparisonContext.contains("Hotspot shift:"));
        assertTrue(comparisonContext.contains("Event-family trend:"));
        assertTrue(comparisonContext.contains("Thread trend:"));
        assertTrue(comparisonContext.contains("Runtime pressure:"));
        assertTrue(comparisonContext.contains("Allocation pressure:"));
        assertTrue(comparisonContext.contains("Retained-object signals:"));
        assertTrue(comparisonContext.contains("recordingComparison"));
        assertTrue(comparisonContext.contains("dominantIncidentPair"));
        assertTrue(comparisonContext.contains("dominantHotspotPair"));
        assertTrue(comparisonContext.contains("eventFamilyRegression"));
        assertTrue(comparisonContext.contains("threadRegression"));
        assertTrue(comparisonContext.contains("runtimePressureDelta"));
        assertTrue(comparisonContext.contains("allocationDelta"));
        assertTrue(comparisonContext.contains("retentionDelta"));
        assertTrue(comparisonContext.contains("baselineIncident"));
        assertTrue(comparisonContext.contains("currentIncident"));
        assertTrue(comparisonContext.contains("windowDelta"));
        assertTrue(comparisonContext.contains("baselineHotspot"));
        assertTrue(comparisonContext.contains("currentHotspot"));
        assertTrue(comparisonContext.contains("hotspotShift"));
        assertTrue(comparisonContext.contains("topFamilies"));
        assertTrue(comparisonContext.contains("topThreads"));
        assertTrue(comparisonContext.contains("checkout-worker"));
        assertTrue(comparisonContext.contains("report-worker"));
        assertTrue(comparisonContext.contains("checkoutService"));
        assertTrue(comparisonContext.contains("LinkedHashMap"));
        assertFalse(comparisonContext.contains("DETERMINISTIC_COMPARISON_FINDINGS"));
    }
}
