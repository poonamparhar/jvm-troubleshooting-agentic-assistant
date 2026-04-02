package com.javaassistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosticContextRetrieverTest {

    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
    private final DiagnosticContextRetriever retriever = new DiagnosticContextRetriever();

    @Test
    void blankRetrievalReturnsFirstOmittedStructuredBlockBeforeRepeatingVisibleContext() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 15);

        DiagnosticToolResult result = retriever.retrieve(indexedContext, ContextSelector.fromQuery(""));

        assertEquals("section13", result.sliceId());
        assertTrue(result.label().startsWith("Additional structured context:"));
        assertTrue(result.moreAvailable());
    }

    @Test
    void blankRetrievalProgressesWhenPriorOmittedSlicesHaveAlreadyBeenSeen() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 15);

        DiagnosticToolResult result = retriever.retrieve(indexedContext, ContextSelector.fromQuery(""), Set.of("section13"));

        assertEquals("section14", result.sliceId());
    }

    @Test
    void blankRetrievalFallsThroughToOmittedRawSlicesWhenStructuredCoverageIsComplete() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 4);

        DiagnosticToolResult result = retriever.retrieve(indexedContext, ContextSelector.fromQuery(""));

        assertEquals("raw-chunk-010", result.sliceId());
        assertTrue(result.content().contains("217: synthetic-line-217"));
        assertTrue(result.moreAvailable());
    }

    @Test
    void sliceIdRetrievalReturnsExactOmittedRawChunkWithoutFallingBackToGlobalCoverageFlags() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 4);

        DiagnosticToolResult nextToLastChunk = retriever.retrieve(indexedContext, ContextSelector.fromQuery("sliceId=raw-chunk-012"));
        DiagnosticToolResult lastChunk = retriever.retrieve(indexedContext, ContextSelector.fromQuery("sliceId=raw-chunk-013"));

        assertTrue(nextToLastChunk.content().contains("265: synthetic-line-265"));
        assertFalse(nextToLastChunk.moreAvailable());
        assertTrue(lastChunk.content().contains("289: synthetic-line-289"));
        assertFalse(lastChunk.moreAvailable());
    }

    @Test
    void patternSearchFindsLateClueOutsideTheStartingContext() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 4);

        DiagnosticToolResult result = retriever.retrieve(indexedContext, ContextSelector.fromQuery("pattern=late-clue"));

        assertTrue(result.sliceId().contains("late-clue"));
        assertTrue(result.content().contains("late-clue"));
    }

    @Test
    void specificSelectorDoesNotFallBackToUnrelatedOmittedContext() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 15);

        DiagnosticToolResult result = retriever.retrieve(indexedContext, ContextSelector.fromQuery("gcId=999"));

        assertEquals("unavailable", result.sliceId());
        assertTrue(result.content().contains("No curated context matched the requested selector."));
    }

    @Test
    void sliceIdRetrievalSupportsPagingLargeStructuredBlocks() {
        IndexedArtifactDiagnosticContext indexedContext = indexedContext(320, 1, 6000);

        DiagnosticToolResult firstPage = retriever.retrieve(indexedContext, ContextSelector.fromQuery("sliceId=section1,chars=400"));
        DiagnosticToolResult secondPage = retriever.retrieve(indexedContext, ContextSelector.fromQuery("sliceId=section1,offset=400,chars=400"));

        assertEquals("section1", firstPage.sliceId());
        assertTrue(firstPage.truncated());
        assertTrue(secondPage.traceability().contains("chars 401-800"));
        assertNotEquals(firstPage.traceability(), secondPage.traceability());
    }

    @Test
    void genericRetrieveForJfrSupportsSliceIdPaging() {
        IndexedArtifactDiagnosticContext indexedContext = jfrIndexedContext();

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("sliceId=executionHotspotSummary,offset=120,chars=200")
        );

        assertEquals("executionHotspotSummary", result.sliceId());
        assertEquals("derived", result.kind());
        assertTrue(result.traceability().contains("chars 121-320"));
    }

    @Test
    void jfrEventTypeRetrievalSearchesTheFullObservedCatalogNotJustTopEventTypes() {
        IndexedArtifactDiagnosticContext indexedContext = richJfrIndexedContext();

        DiagnosticToolResult result = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("eventType=jdk.CustomEvent15")
        );

        assertEquals("jfr-event-type-jdk-customevent15", result.sliceId());
        assertTrue(result.content().contains("jdk.CustomEvent15"));
        assertTrue(result.content().contains("observedEventTypes"));
        assertTrue(result.content().contains("declaredEventTypes"));
    }

    private IndexedArtifactDiagnosticContext indexedContext(int lineCount, int sectionCount) {
        return indexedContext(lineCount, sectionCount, 180);
    }

    private IndexedArtifactDiagnosticContext indexedContext(int lineCount, int sectionCount, int valueLength) {
        InputArtifact artifact = syntheticArtifact(lineCount);
        ParsedArtifact parsedArtifact = syntheticParsedArtifact(artifact, sectionCount, valueLength);
        return indexer.index(artifact, parsedArtifact);
    }

    private InputArtifact syntheticArtifact(int lineCount) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            builder.append("synthetic-line-").append(lineNumber);
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

    private IndexedArtifactDiagnosticContext jfrIndexedContext() {
        List<Map<String, Object>> executionHotspots = new ArrayList<>();
        for (int index = 1; index <= 60; index++) {
            executionHotspots.add(Map.of(
                "methodName", "com.example.Service.method" + index,
                "sampleCount", index * 10,
                "packageName", "com.example.synthetic.package" + index
            ));
        }
        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", Map.of(
            "startTime", "2026-04-01T12:00:00Z",
            "endTime", "2026-04-01T12:00:10Z",
            "durationMs", 10000
        ));
        extractedData.put("executionHotspotSummary", Map.of(
            "topMethods", executionHotspots,
            "topPackage", "com.example.synthetic"
        ));

        InputArtifact artifact = new InputArtifact(
            ArtifactType.JFR,
            new ArtifactMetadata(
                "/tmp/synthetic.jfr",
                "synthetic.jfr",
                0,
                LocalDateTime.of(2026, 4, 1, 12, 0),
                Map.of("contentRepresentation", "external-binary-jfr")
            ),
            ""
        );
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.JFR,
            artifact.metadata(),
            "synthetic-jfr-parser",
            extractedData,
            List.of(),
            List.of()
        );
        return indexer.index(artifact, parsedArtifact);
    }

    private IndexedArtifactDiagnosticContext richJfrIndexedContext() {
        List<Map<String, Object>> observedEventTypes = new ArrayList<>();
        List<Map<String, Object>> declaredEventTypes = new ArrayList<>();
        for (int index = 1; index <= 15; index++) {
            String eventName = "jdk.CustomEvent" + index;
            observedEventTypes.add(Map.of(
                "name", eventName,
                "count", index * 10,
                "totalDurationMs", index * 25L
            ));
            declaredEventTypes.add(Map.of(
                "name", eventName,
                "label", "Custom Event " + index,
                "fieldCount", 2,
                "fields", List.of(
                    Map.of("name", "fieldA", "typeName", "long"),
                    Map.of("name", "fieldB", "typeName", "java.lang.String")
                )
            ));
        }

        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", Map.of(
            "startTime", "2026-04-01T12:00:00Z",
            "endTime", "2026-04-01T12:00:10Z",
            "durationMs", 10000
        ));
        extractedData.put("observedEventTypes", observedEventTypes);
        extractedData.put("topEventTypes", observedEventTypes.subList(0, 10));
        extractedData.put("declaredEventTypes", declaredEventTypes);

        InputArtifact artifact = new InputArtifact(
            ArtifactType.JFR,
            new ArtifactMetadata(
                "/tmp/rich-synthetic.jfr",
                "rich-synthetic.jfr",
                0,
                LocalDateTime.of(2026, 4, 1, 12, 0),
                Map.of("contentRepresentation", "external-binary-jfr")
            ),
            ""
        );
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.JFR,
            artifact.metadata(),
            "synthetic-jfr-parser",
            extractedData,
            List.of(),
            List.of()
        );
        return indexer.index(artifact, parsedArtifact);
    }
}
