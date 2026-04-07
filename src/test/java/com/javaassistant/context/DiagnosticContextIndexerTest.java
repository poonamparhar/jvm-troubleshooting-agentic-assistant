package com.javaassistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticContextIndexerTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();
    private final ThreadDumpArtifactParser threadDumpParser = new ThreadDumpArtifactParser();

    @TempDir
    Path tempDir;

    @Test
    void boundsStartingContextAndReportsCoverageForTextArtifacts() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        assertFalse(indexedContext.diagnosticContext().structuredFacts().isEmpty());
        assertTrue(indexedContext.diagnosticContext().structuredSlices().size() <= DiagnosticContextIndexer.MAX_STARTING_STRUCTURED_SLICES);
        assertTrue(indexedContext.diagnosticContext().representativeSlices().size() <= DiagnosticContextIndexer.MAX_STARTING_REPRESENTATIVE_SLICES);
        assertTrue(indexedContext.diagnosticContext().coverage().additionalContextAvailable());
        assertFalse(indexedContext.diagnosticContext().coverage().omittedRawSlices().isEmpty());
        assertFalse(indexedContext.rawLines().isEmpty());
    }

    @Test
    void prioritizesGcSummariesAndIncidentWindowsInStartingContext() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);
        List<String> structuredSliceIds = indexedContext.diagnosticContext().structuredSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();
        List<String> representativeSliceIds = indexedContext.diagnosticContext().representativeSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();

        assertTrue(structuredSliceIds.contains("collectorPressureSummary"));
        assertTrue(structuredSliceIds.contains("failureSummary"));
        assertTrue(structuredSliceIds.contains("phaseSummary"));
        assertTrue(structuredSliceIds.contains("recoverySummary"));
        assertTrue(structuredSliceIds.contains("g1CycleProgressionSummary"));
        assertTrue(indexedContext.diagnosticContext().coverage().omittedStructuredBlocks().contains("pauses"));
        assertTrue(representativeSliceIds.contains("gc-incident-dominant-g1-distress-window"));
        assertTrue(representativeSliceIds.contains("gc-incident-first-evacuation-failure"));
        assertTrue(representativeSliceIds.contains("gc-incident-longest-full-gc"));
        assertTrue(representativeSliceIds.contains("gc-incident-densest-failure-region"));
        assertTrue(representativeSliceIds.contains("gc-incident-tail"));
        assertEquals("gc-incident-dominant-g1-distress-window", representativeSliceIds.getFirst());
        assertTrue(representativeSliceIds.indexOf("gc-incident-first-evacuation-failure") < representativeSliceIds.indexOf("raw-chunk-001"));
        assertTrue(representativeSliceIds.indexOf("gc-incident-longest-full-gc") < representativeSliceIds.indexOf("raw-chunk-001"));
        assertTrue(representativeSliceIds.subList(0, Math.min(6, representativeSliceIds.size())).stream()
            .noneMatch(sliceId -> sliceId.startsWith("raw-chunk-")));
    }

    @Test
    void includesCollectorSpecificFactsForGcArtifacts() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);
        Map<String, Object> structuredFacts = indexedContext.diagnosticContext().structuredFacts();

        assertEquals("G1", structuredFacts.get("collector"));
        assertTrue(String.valueOf(structuredFacts.get("collectorFocusAreas")).contains("evacuation failures"));
        assertTrue(String.valueOf(structuredFacts.get("collectorInterpretationHint")).contains("full GCs"));
        assertTrue(structuredFacts.containsKey("collectorSummary.fullGcCount"));
        assertTrue(structuredFacts.containsKey("collectorSummary.maxNearCapacityPauseStreak"));
        assertTrue(structuredFacts.containsKey("collectorSummary.evacuationFailurePauseCount"));
        assertTrue(structuredFacts.containsKey("recovery.mixedPauseCount"));
        assertTrue(structuredFacts.containsKey("recovery.averagePostGcOccupancyRatio"));
        assertTrue(structuredFacts.containsKey("g1.mixedPausesBeforeFirstFullGc"));
        assertTrue(structuredFacts.containsKey("g1.lowReclaimHighRetentionFullGcCount"));
    }

    @Test
    void prioritizesLegacyG1ToSpaceDistressAndKeepsTheFullPauseBlockInFirstPass() throws Exception {
        var artifact = loader.load(Path.of(
            "src/test/resources/reference-incidents/analyze-gc-first-pass-legacy-g1-distress/gc_legacy_g1_distress_small.log"
        ));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);
        Map<String, Object> structuredFacts = indexedContext.diagnosticContext().structuredFacts();
        List<String> representativeSliceIds = indexedContext.diagnosticContext().representativeSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();
        ContextSlice distressSlice = sliceById(indexedContext, "gc-incident-dominant-g1-distress-window");

        assertEquals("G1", structuredFacts.get("collector"));
        assertEquals(1L, ((Number) structuredFacts.get("collectorSummary.toSpaceExhaustedCount")).longValue());
        assertEquals(1L, ((Number) structuredFacts.get("failure.toSpaceExhaustedCount")).longValue());
        assertEquals("gc-incident-dominant-g1-distress-window", representativeSliceIds.getFirst());
        assertTrue(representativeSliceIds.contains("gc-incident-first-to-space-distress"));
        assertTrue(distressSlice.content().contains("Humongous regions: 4->6"));
        assertTrue(distressSlice.content().contains("Times: user=0.40 sys=0.00, real=0.15 secs"));
    }

    @Test
    void prioritizesLegacyCmsFailureAndConcurrentSignalsWithoutWastingASliceOnG1OnlyData() throws Exception {
        var artifact = loader.load(Path.of(
            "src/test/resources/reference-incidents/analyze-gc-first-pass-cms-concurrent-failure/gc_cms_concurrent_failure_small.log"
        ));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);
        Map<String, Object> structuredFacts = indexedContext.diagnosticContext().structuredFacts();
        List<String> structuredSliceIds = indexedContext.diagnosticContext().structuredSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();
        List<String> representativeSliceIds = indexedContext.diagnosticContext().representativeSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();
        ContextSlice cmsIncidentSlice = sliceById(indexedContext, "gc-incident-dominant-cms-fallback-window");

        assertEquals("CMS", structuredFacts.get("collector"));
        assertEquals(3L, ((Number) structuredFacts.get("collectorSummary.concurrentModeFailureCount")).longValue());
        assertEquals(3L, ((Number) structuredFacts.get("failure.concurrentModeFailureCount")).longValue());
        assertEquals(1L, ((Number) structuredFacts.get("concurrent.concurrentPhaseCount")).longValue());
        assertTrue(structuredSliceIds.contains("collectorPressureSummary"));
        assertTrue(structuredSliceIds.contains("failureSummary"));
        assertTrue(structuredSliceIds.contains("concurrentSummary"));
        assertFalse(structuredSliceIds.contains("g1CycleProgressionSummary"));
        assertTrue(representativeSliceIds.contains("gc-incident-dominant-cms-fallback-window"));
        assertTrue(cmsIncidentSlice.content().contains("CMS-concurrent-mark"));
        assertTrue(cmsIncidentSlice.content().contains("concurrent mode failure"));
    }

    @Test
    void omitsEmptyGcStructuredBlocksForLegacySerialFirstPass() throws Exception {
        var artifact = loader.load(Path.of(
            "src/test/resources/reference-incidents/analyze-gc-first-pass-serial-full-gc/gc_serial_full_gc_small.log"
        ));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);
        Map<String, Object> structuredFacts = indexedContext.diagnosticContext().structuredFacts();
        List<String> structuredSliceIds = indexedContext.diagnosticContext().structuredSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();
        List<String> representativeSliceIds = indexedContext.diagnosticContext().representativeSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();

        assertEquals("Serial", structuredFacts.get("collector"));
        assertTrue(((Number) structuredFacts.get("collectorSummary.maxFullGcStreak")).longValue() >= 3L);
        assertFalse(structuredSliceIds.contains("g1CycleProgressionSummary"));
        assertFalse(structuredSliceIds.contains("failureSummary"));
        assertFalse(structuredSliceIds.contains("concurrentSummary"));
        assertFalse(structuredSliceIds.contains("allocationStalls"));
        assertFalse(structuredSliceIds.contains("mmuSamples"));
        assertTrue(structuredSliceIds.contains("collectorPressureSummary"));
        assertTrue(structuredSliceIds.contains("recoverySummary"));
        assertTrue(representativeSliceIds.contains("gc-incident-dominant-full-gc-window"));
        assertFalse(representativeSliceIds.contains("gc-incident-longest-concurrent-phase"));
        assertEquals("gc-incident-dominant-full-gc-window", representativeSliceIds.getFirst());
        assertTrue(sliceById(indexedContext, "gc-incident-dominant-full-gc-window").content().contains("0.2200000 secs"));
        assertTrue(sliceById(indexedContext, "gc-incident-dominant-full-gc-window").content().contains("0.2900000 secs"));
    }

    @Test
    void keepsJfrStartingContextDerivedOnly() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("hot-path-recording.jfr"));
        var artifact = loader.load(recordingPath);
        var parsed = jfrParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        assertTrue(indexedContext.rawLines().isEmpty());
        assertFalse(indexedContext.diagnosticContext().representativeSlices().isEmpty());
        assertTrue(indexedContext.diagnosticContext().representativeSlices().stream().allMatch(slice -> !"raw".equals(slice.kind())));
        assertTrue(indexedContext.diagnosticContext().representativeSlices().stream()
            .noneMatch(slice -> slice.content().contains("Raw recording bytes")));
        assertFalse(indexedContext.diagnosticContext().structuredSlices().stream()
            .anyMatch(slice -> "artifactAttributes".equals(slice.sliceId())));
    }

    @Test
    void keepsJvmRuntimeInfoOutOfTheAgentStartingContext() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createIncidentWindowRecordingWithJvmInfo(tempDir.resolve("jfr-jvm-info-hidden.jfr"));
        var artifact = loader.load(recordingPath);
        var parsed = jfrParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        assertTrue(parsed.extractedData().containsKey("jvmRuntimeInfo"));
        assertFalse(indexedContext.diagnosticContext().structuredSlices().stream()
            .anyMatch(slice -> "jvmRuntimeInfo".equals(slice.sliceId())));
        assertFalse(indexedContext.diagnosticContext().structuredFacts().containsKey("jvmRuntimeInfo"));
    }

    @Test
    void preservesCompleteRawChunkCoverageInternallyForLargeTextArtifacts() {
        InputArtifact artifact = syntheticArtifact(320);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, 15);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsedArtifact);

        List<String> rawChunkIds = indexedContext.allRawOrDerivedSlices().stream()
            .filter(slice -> slice.sliceId().startsWith("raw-chunk-"))
            .map(ContextSlice::sliceId)
            .toList();

        assertEquals(14, rawChunkIds.size());
        assertTrue(rawChunkIds.contains("raw-chunk-014"));
        assertTrue(indexedContext.diagnosticContext().coverage().omittedStructuredBlocks().contains("section13"));
        assertTrue(indexedContext.diagnosticContext().coverage().omittedRawSlices().contains("raw-chunk-013"));
        assertTrue(indexedContext.allRawOrDerivedSlices().stream()
            .filter(slice -> "raw-chunk-014".equals(slice.sliceId()))
            .findFirst()
            .orElseThrow()
            .content()
            .contains("320: synthetic-line-320 late-clue"));
    }

    @Test
    void keepsFullInternalSliceContentWhileBoundingTheStartingContext() {
        InputArtifact artifact = wideSyntheticArtifact(220, 180);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, 1, 6000);

        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsedArtifact);

        String fullStructuredBlock = indexedContext.structuredBlocks().get("section1");
        ContextSlice startingStructuredSlice = indexedContext.diagnosticContext().structuredSlices().getFirst();
        ContextSlice fullRawChunk = indexedContext.allRawOrDerivedSlices().stream()
            .filter(slice -> "raw-chunk-001".equals(slice.sliceId()))
            .findFirst()
            .orElseThrow();
        ContextSlice startingRawChunk = indexedContext.diagnosticContext().representativeSlices().stream()
            .filter(slice -> "raw-chunk-001".equals(slice.sliceId()))
            .findFirst()
            .orElseThrow();

        assertTrue(fullStructuredBlock.length() > startingStructuredSlice.content().length());
        assertTrue(startingStructuredSlice.truncated());
        assertTrue(fullRawChunk.content().length() > startingRawChunk.content().length());
        assertTrue(startingRawChunk.truncated());
    }

    @Test
    void usesLargerModelAwareBudgetWhenTheConfiguredContextWindowIsBigger() {
        DiagnosticContextIndexer largerBudgetIndexer = new DiagnosticContextIndexer(
            DiagnosticContextIndexer.StartingContextBudget.forApproximateContextWindowTokens(16384)
        );
        InputArtifact artifact = syntheticArtifact(720);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, 18, 260);

        IndexedArtifactDiagnosticContext indexedContext = largerBudgetIndexer.index(artifact, parsedArtifact);

        assertTrue(indexedContext.diagnosticContext().structuredSlices().size() > 12);
        assertTrue(indexedContext.diagnosticContext().representativeSlices().size() > 12);
        assertTrue(indexedContext.diagnosticContext().structuredSlices().size() <= 18);
        assertTrue(indexedContext.diagnosticContext().representativeSlices().size() <= 18);
    }

    @Test
    void usesMoreConservativeBudgetForCompactOllamaModelsLikeLlama32() {
        DiagnosticContextIndexer.StartingContextBudget budget =
            DiagnosticContextIndexer.StartingContextBudget.forModel("OLLAMA", "llama3.2", "llama3", 16384);
        DiagnosticContextIndexer compactLocalIndexer = new DiagnosticContextIndexer(budget);
        InputArtifact artifact = syntheticArtifact(720);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, 18, 260);

        IndexedArtifactDiagnosticContext indexedContext = compactLocalIndexer.index(artifact, parsedArtifact);

        assertEquals(new DiagnosticContextIndexer.StartingContextBudget(8, 8, 8, 1800, 1200), budget);
        assertTrue(indexedContext.diagnosticContext().structuredSlices().size() <= 10);
        assertTrue(indexedContext.diagnosticContext().representativeSlices().size() <= 8);
        assertTrue(indexedContext.diagnosticContext().coverage().additionalContextAvailable());
    }

    @Test
    void compactGcFirstPassUsesIncidentWindowsBeforeGenericChunks() throws Exception {
        DiagnosticContextIndexer compactLocalIndexer = new DiagnosticContextIndexer(
            DiagnosticContextIndexer.StartingContextBudget.forModel("OLLAMA", "llama3.2", "llama3", 16384)
        );
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = compactLocalIndexer.index(artifact, parsed);
        List<String> representativeSliceIds = indexedContext.diagnosticContext().representativeSlices().stream()
            .map(ContextSlice::sliceId)
            .toList();

        assertEquals(8, representativeSliceIds.size());
        assertTrue(representativeSliceIds.contains("gc-incident-dominant-g1-distress-window"));
        assertTrue(representativeSliceIds.contains("gc-incident-first-evacuation-failure"));
        assertTrue(representativeSliceIds.contains("gc-incident-longest-full-gc"));
        assertTrue(representativeSliceIds.contains("gc-incident-first-concurrent-abort"));
        assertTrue(representativeSliceIds.contains("gc-incident-first-full-compaction-attempt"));
        assertTrue(representativeSliceIds.contains("gc-incident-peak-occupancy"));
        assertTrue(representativeSliceIds.contains("gc-incident-densest-failure-region"));
        assertTrue(representativeSliceIds.contains("gc-incident-tail"));
        assertTrue(representativeSliceIds.stream().noneMatch(sliceId -> sliceId.startsWith("raw-chunk-")));
    }

    @Test
    void frontLoadsEntireSmallThreadDumpWhenItFitsWithinTheFirstPassBudget() throws Exception {
        DiagnosticContextIndexer compactLocalIndexer = new DiagnosticContextIndexer(
            DiagnosticContextIndexer.StartingContextBudget.forModel("OLLAMA", "llama3.2", "llama3", 16384)
        );
        InputArtifact artifact = loader.load(Path.of("samples/thread_dump_deadlock.txt"));
        ParsedArtifact parsedArtifact = threadDumpParser.parse(artifact);

        IndexedArtifactDiagnosticContext indexedContext = compactLocalIndexer.index(artifact, parsedArtifact);

        assertFalse(indexedContext.diagnosticContext().coverage().additionalContextAvailable());
        assertTrue(indexedContext.diagnosticContext().coverage().omittedStructuredBlocks().isEmpty());
        assertTrue(indexedContext.diagnosticContext().coverage().omittedRawSlices().isEmpty());
        assertTrue(indexedContext.diagnosticContext().coverage().truncationMarkers().isEmpty());
    }

    @Test
    void frontLoadsEntireSmallGcLogWhenItFitsWithinTheFirstPassBudget() {
        DiagnosticContextIndexer compactLocalIndexer = new DiagnosticContextIndexer(
            DiagnosticContextIndexer.StartingContextBudget.forModel("OLLAMA", "llama3.2", "llama3", 16384)
        );
        InputArtifact artifact = syntheticArtifact(80);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, 4);

        IndexedArtifactDiagnosticContext indexedContext = compactLocalIndexer.index(artifact, parsedArtifact);

        assertFalse(indexedContext.diagnosticContext().coverage().additionalContextAvailable());
        assertTrue(indexedContext.diagnosticContext().coverage().omittedStructuredBlocks().isEmpty());
        assertTrue(indexedContext.diagnosticContext().coverage().omittedRawSlices().isEmpty());
        assertTrue(indexedContext.diagnosticContext().coverage().truncationMarkers().isEmpty());
    }

    @Test
    void keepsRicherBudgetForStrongerModelsEvenWhenRunningThroughOllama() {
        DiagnosticContextIndexer.StartingContextBudget standard32k =
            DiagnosticContextIndexer.StartingContextBudget.forApproximateContextWindowTokens(32768);

        assertEquals(
            standard32k,
            DiagnosticContextIndexer.StartingContextBudget.forModel("OLLAMA", "qwen2.5:32b", "qwen2", 32768)
        );
        assertEquals(
            standard32k,
            DiagnosticContextIndexer.StartingContextBudget.forModel("OCI", "xai.grok-4", "grok4", 32768)
        );
    }

    private ContextSlice sliceById(IndexedArtifactDiagnosticContext indexedContext, String sliceId) {
        return indexedContext.diagnosticContext().representativeSlices().stream()
            .filter(slice -> sliceId.equals(slice.sliceId()))
            .findFirst()
            .orElseThrow();
    }

    private InputArtifact syntheticArtifact(int lineCount) {
        return wideSyntheticArtifact(lineCount, 0);
    }

    private InputArtifact wideSyntheticArtifact(int lineCount, int lineWidth) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            builder.append("synthetic-line-").append(lineNumber);
            if (lineWidth > 0) {
                builder.append(' ').append("x".repeat(lineWidth));
            }
            if (lineNumber == lineCount) {
                builder.append(" late-clue");
            }
            if (lineNumber < lineCount) {
                builder.append('\n');
            }
        }
        String content = builder.toString();
        return new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/tmp/synthetic-gc.log",
                "synthetic-gc.log",
                content.length(),
                LocalDateTime.of(2026, 4, 1, 12, 0),
                Map.of()
            ),
            content
        );
    }

    private ParsedArtifact syntheticParsedArtifact(InputArtifact artifact, int sectionCount) {
        return syntheticParsedArtifact(artifact, sectionCount, 180);
    }

    private ParsedArtifact syntheticParsedArtifact(InputArtifact artifact, int sectionCount, int valueLength) {
        int lateClueLine = artifact.content() != null ? artifact.content().split("\\R", -1).length : 1;
        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        for (int index = 1; index <= sectionCount; index++) {
            extractedData.put("section" + index, Map.of("value", "v".repeat(valueLength)));
        }
        return new ParsedArtifact(
            ArtifactType.GC_LOG,
            artifact.metadata(),
            "synthetic-parser",
            extractedData,
            List.of(new Evidence(
                "late-clue",
                artifact.metadata().sourcePath(),
                "Late clue",
                "A late clue is present near the file tail.",
                "synthetic-line-" + lateClueLine + " late-clue",
                List.of(lateClueLine),
                Map.of("lineNumber", lateClueLine)
            )),
            List.of()
        );
    }
}
