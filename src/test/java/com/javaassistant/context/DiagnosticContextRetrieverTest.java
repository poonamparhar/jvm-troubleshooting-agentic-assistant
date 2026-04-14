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
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticContextRetrieverTest {

    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();
    private final DiagnosticContextRetriever retriever = new DiagnosticContextRetriever();
    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();

    @TempDir
    Path tempDir;

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

        assertEquals(indexedContext.diagnosticContext().coverage().omittedRawSlices().getFirst(), result.sliceId());
        assertTrue(result.content().contains("synthetic-line-"));
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
        IndexedArtifactDiagnosticContext indexedContext = richJfrIndexedContext(false);

        DiagnosticToolResult result = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("eventType=jdk.CustomEvent15")
        );

        assertEquals("jfr-event-type-jdk-customevent15", result.sliceId());
        assertTrue(result.content().contains("jdk.CustomEvent15"));
        assertTrue(result.content().contains("observedEventTypes"));
        assertTrue(result.content().contains("declaredEventTypes"));
    }

    @Test
    void jfrExactEventTypeRetrievalReturnsGenericEventDetailsWhenAvailable() {
        IndexedArtifactDiagnosticContext indexedContext = richJfrIndexedContext(true);

        DiagnosticToolResult result = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("eventType=jdk.CustomEvent15")
        );

        assertEquals("jfr-event-detail-jdk-customevent15", result.sliceId());
        assertTrue(result.content().contains("eventTypeDetail"));
        assertTrue(result.content().contains("declaredMetadata"));
        assertTrue(result.content().contains("sampleEvents"));
        assertTrue(result.content().contains("queue-depth"));
        assertTrue(result.content().contains("checkout"));
    }

    @Test
    void jfrIncidentAndTimeWindowRetrievalReturnChronologyAwareContext() throws Exception {
        InputArtifact artifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("incident-window.jfr")));
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsedArtifact);

        DiagnosticToolResult runtimeIncident = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("incident=runtime")
        );
        DiagnosticToolResult chronology = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("incident=chronology")
        );
        DiagnosticToolResult timeWindow = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("start=0.200s,end=0.900s")
        );

        assertEquals("jfr-incident-runtime-pressure", runtimeIncident.sliceId());
        assertTrue(runtimeIncident.label().contains("Runtime pressure window"));
        assertFalse(runtimeIncident.content().isBlank());
        assertEquals("jfr-chronology-highlights", chronology.sliceId());
        assertTrue(chronology.content().contains("chronologyHighlights"));
        assertFalse(chronology.content().isBlank());
        assertTrue(timeWindow.sliceId().startsWith("jfr-time-window-"));
        assertTrue(timeWindow.content().contains("signalFamilies") || timeWindow.content().contains("overlappingIncidentWindows"));
        assertTrue(timeWindow.content().contains("chronologyHighlights") || timeWindow.content().contains("GarbageCollection"));
        assertTrue(timeWindow.content().contains("signalFamilies"));
        assertTrue(timeWindow.content().contains("representativeEvents"));
        assertTrue(timeWindow.content().contains("topEventTypes") || timeWindow.content().contains("eventCount"));
    }

    @Test
    void jfrFocusedHotspotAllocationAndRetentionRetrievalUseTimelineNeighborhoods() throws Exception {
        InputArtifact artifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("incident-neighborhoods.jfr")));
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsedArtifact);

        DiagnosticToolResult hotspot = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("hotspot=checkoutService")
        );
        DiagnosticToolResult allocation = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("allocationClass=java.lang.String")
        );
        DiagnosticToolResult retained = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("oldObject=JNI Global")
        );

        assertEquals("jfr-hotspot-checkoutservice", hotspot.sliceId());
        assertTrue(hotspot.content().contains("hotspotQuery"));
        assertTrue(hotspot.content().contains("topStacks") || hotspot.content().contains("matchingHotspotSummaries"));
        assertTrue(hotspot.content().contains("representativeEvents"));

        assertEquals("jfr-allocation-class-java-lang-string", allocation.sliceId());
        assertTrue(allocation.content().contains("allocationClassQuery"));
        assertTrue(allocation.content().contains("matchingClasses"));
        assertTrue(allocation.content().contains("topMethods"));
        assertTrue(allocation.content().contains("representativeEvents"));

        assertEquals("jfr-old-object-jni-global", retained.sliceId());
        assertTrue(retained.content().contains("oldObjectFocusQuery"));
        assertTrue(retained.content().contains("matchingRoots"));
        assertTrue(retained.content().contains("maxReferenceDepth"));
        assertTrue(retained.content().contains("representativeEvents"));
    }

    @Test
    void jfrEventFamilyAndThreadRetrievalUseFocusedTimelineNeighborhoods() throws Exception {
        InputArtifact artifact = loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("jfr-thread-focus.jfr")));
        ParsedArtifact parsedArtifact = jfrParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsedArtifact);

        DiagnosticToolResult eventFamily = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("eventType=gc")
        );
        DiagnosticToolResult thread = retriever.retrieveJfr(
            indexedContext,
            JfrSelector.fromQuery("thread=checkout-worker")
        );

        assertEquals("jfr-event-family-gc", eventFamily.sliceId());
        assertTrue(eventFamily.content().contains("eventTypeQuery"));
        assertTrue(eventFamily.content().contains("signalFamilies") || eventFamily.content().contains("matchingEventTypes"));
        assertTrue(eventFamily.content().contains("representativeEvents"));
        assertTrue(eventFamily.content().contains("GarbageCollection") || eventFamily.content().contains("gcPause"));

        assertEquals("jfr-thread-checkout-worker", thread.sliceId());
        assertTrue(thread.content().contains("threadQuery"));
        assertTrue(thread.content().contains("signalFamilies"));
        assertTrue(thread.content().contains("representativeEvents"));
        assertTrue(thread.content().contains("checkout-worker"));
    }

    @Test
    void gcCauseRetrievalUsesParsedPauseCauseMatches() throws Exception {
        var artifact = loader.load(java.nio.file.Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("cause=G1 Compaction Pause")
        );

        assertEquals("gc-cause-g1-compaction-pause", result.sliceId());
        assertTrue(result.label().contains("G1 Compaction Pause"));
        assertTrue(result.content().contains("Pause Full (G1 Compaction Pause)"));
    }

    @Test
    void gcIncidentAliasRetrievalResolvesDominantCollectorWindow() throws Exception {
        var artifact = loader.load(java.nio.file.Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("incident=dominant-pressure")
        );

        assertEquals("gc-incident-dominant-g1-distress-window", result.sliceId());
        assertTrue(result.label().contains("Dominant G1 distress window"));
        assertTrue(result.content().contains("Evacuation Failure"));
        assertTrue(result.content().contains("G1 Compaction Pause"));
    }

    @Test
    void gcPhaseKindRetrievalUsesParsedPhaseSamples() throws Exception {
        var artifact = loader.load(java.nio.file.Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("phaseKind=CONCURRENT")
        );

        assertEquals("gc-phase-kind-concurrent", result.sliceId());
        assertTrue(result.label().contains("CONCURRENT"));
        assertTrue(result.content().contains("Concurrent"));
    }

    @Test
    void gcElapsedWindowRetrievalUsesParsedTimelineBounds() throws Exception {
        var artifact = loader.load(java.nio.file.Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("start=6.6s,end=7.35s")
        );

        assertEquals("gc-time-window-6-600-7-350", result.sliceId());
        assertTrue(result.label().contains("6.600s to 7.350s"));
        assertTrue(result.content().contains("GC(44)"));
        assertTrue(result.content().contains("GC(45)"));
    }

    @Test
    void gcStreakRetrievalReturnsTheHeaviestFullGcCluster() throws Exception {
        var artifact = loader.load(java.nio.file.Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var parsed = gcParser.parse(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexer.index(artifact, parsed);

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("streak=full-gc")
        );

        assertEquals("gc-streak-full-gc", result.sliceId());
        assertTrue(result.label().contains("full-GC streak"));
        assertTrue(result.content().contains("Window"));
        assertTrue(result.content().contains("Window 1 around GC(45)"));
        assertTrue(result.content().contains("GC(45) Pause Full"));
    }

    @Test
    void legacyG1ElapsedWindowRetrievalCapturesToSpaceDistressCluster() throws Exception {
        IndexedArtifactDiagnosticContext indexedContext = legacyGcIndexedContext(
            "src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"
        );

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("start=24s,end=32.6s")
        );

        assertEquals("gc-time-window-24-000-32-600", result.sliceId());
        assertTrue(result.label().contains("24.000s to 32.600s"));
        assertTrue(result.content().contains("to-space exhausted"));
        assertTrue(result.content().contains("Full GC (G1 Compaction Pause)"));
    }

    @Test
    void legacyCmsFailureStreakRetrievalReturnsConcurrentModeFailureCluster() throws Exception {
        IndexedArtifactDiagnosticContext indexedContext = legacyGcIndexedContext(
            "src/test/resources/reference-incidents/analyze-gc-legacy-cms-pressure/gc_legacy_cms_pressure_large.log"
        );

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("streak=failure")
        );

        assertEquals("gc-streak-failure", result.sliceId());
        assertTrue(result.label().contains("failure-signal streak"));
        assertTrue(result.content().contains("concurrent mode failure"));
        assertTrue(result.content().contains("CMS-concurrent-mark"));
    }

    @Test
    void legacySerialFullGcStreakRetrievalReturnsRepresentativeAllocationFailureWindows() throws Exception {
        IndexedArtifactDiagnosticContext indexedContext = legacyGcIndexedContext(
            "src/test/resources/reference-incidents/analyze-gc-legacy-serial-pressure/gc_legacy_serial_pressure_large.log"
        );

        DiagnosticToolResult result = retriever.retrieve(
            indexedContext,
            ContextSelector.fromQuery("streak=full-gc")
        );

        assertEquals("gc-streak-full-gc", result.sliceId());
        assertTrue(result.label().contains("full-GC streak"));
        assertTrue(result.content().contains("Window"));
        assertTrue(result.content().contains("Allocation Failure"));
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

    private IndexedArtifactDiagnosticContext legacyGcIndexedContext(String artifactPath) throws Exception {
        InputArtifact artifact = loader.load(java.nio.file.Path.of(artifactPath));
        ParsedArtifact parsedArtifact = gcParser.parse(artifact);
        return indexer.index(artifact, parsedArtifact);
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

    private IndexedArtifactDiagnosticContext richJfrIndexedContext(boolean includeEventTypeDetails) {
        List<Map<String, Object>> observedEventTypes = new ArrayList<>();
        List<Map<String, Object>> declaredEventTypes = new ArrayList<>();
        List<Map<String, Object>> eventTypeDetails = new ArrayList<>();
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
            eventTypeDetails.add(Map.of(
                "name", eventName,
                "label", "Custom Event " + index,
                "eventCount", index * 10,
                "maxDurationMs", index * 25L,
                "observedFields", List.of(
                    Map.of("field", "queue-depth", "typeName", "long", "eventCount", index * 10, "share", 1.0d),
                    Map.of("field", "service", "typeName", "java.lang.String", "eventCount", index * 10, "share", 1.0d)
                ),
                "sampleEvents", List.of(
                    Map.of(
                        "durationMs", index * 25L,
                        "fields", Map.of(
                            "queue-depth", index * 100,
                            "service", "checkout"
                        )
                    )
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
        if (includeEventTypeDetails) {
            extractedData.put("eventTypeDetails", eventTypeDetails);
        }

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
