package com.javaassistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticContextIndexerTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();

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
        InputArtifact artifact = wideSyntheticArtifact(48, 180);
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
                "synthetic-line-320 late-clue",
                List.of(320),
                Map.of("lineNumber", 320)
            )),
            List.of()
        );
    }
}
