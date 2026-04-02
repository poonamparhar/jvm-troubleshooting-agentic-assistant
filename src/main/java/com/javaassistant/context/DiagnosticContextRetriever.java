package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Curated retrieval over one indexed artifact. Agents never read arbitrary files directly.
 */
public class DiagnosticContextRetriever {

    private static final int MAX_RESULT_CHARS = 4200;
    private static final int MAX_SEARCH_WINDOW_LINES = 18;
    private static final List<String> JFR_SIGNAL_KEYS = List.of(
        "gcSummary",
        "lockSummary",
        "threadParkSummary",
        "ioSummary",
        "exceptionSummary",
        "safepointSummary",
        "allocationSummary",
        "allocationFieldSummary",
        "allocationHotspotSummary",
        "oldObjectSummary",
        "oldObjectFieldSummary",
        "executionSummary",
        "overallHotspotSummary",
        "executionHotspotSummary",
        "runtimeHotspotSummary",
        "observedEventTypes",
        "declaredEventTypes",
        "topEventTypes",
        "summary",
        "coverage"
    );

    public DiagnosticToolResult retrieve(IndexedArtifactDiagnosticContext indexedContext, ContextSelector selector) {
        return retrieve(indexedContext, selector, Set.of());
    }

    public DiagnosticToolResult retrieve(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        Set<String> seenSliceIds
    ) {
        Objects.requireNonNull(indexedContext, "indexedContext");
        ContextSelector effectiveSelector = selector != null
            ? selector
            : new ContextSelector(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Set<String> normalizedSeenSliceIds = normalizeSeenSliceIds(seenSliceIds);

        if (indexedContext.parsedArtifact() != null && indexedContext.parsedArtifact().type() == ArtifactType.JFR) {
            return retrieveJfr(indexedContext, JfrSelector.fromContextSelector(effectiveSelector), normalizedSeenSliceIds);
        }

        if (effectiveSelector.sliceId() != null) {
            DiagnosticToolResult result = sliceById(
                indexedContext,
                effectiveSelector.sliceId(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars(),
                false,
                normalizedSeenSliceIds
            );
            if (result != null) {
                return result;
            }
        }

        if (effectiveSelector.lineStart() != null || effectiveSelector.lineEnd() != null) {
            return rawLineRange(
                indexedContext,
                effectiveSelector.lineStart(),
                effectiveSelector.lineEnd(),
                "Requested raw line range",
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
        }

        if (effectiveSelector.sectionId() != null) {
            DiagnosticToolResult structuredSection = structuredSection(
                indexedContext,
                effectiveSelector.sectionId(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars(),
                false,
                normalizedSeenSliceIds
            );
            if (structuredSection != null) {
                return structuredSection;
            }
            DiagnosticToolResult rawSection = rawSection(
                indexedContext,
                effectiveSelector.sectionId(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (rawSection != null) {
                return rawSection;
            }
        }

        if (effectiveSelector.gcId() != null) {
            DiagnosticToolResult result = searchRawLines(
                indexedContext,
                line -> line.contains("GC(" + effectiveSelector.gcId() + ")"),
                "GC event " + effectiveSelector.gcId(),
                "raw-search-gc-" + effectiveSelector.gcId(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
            }
        }
        if (effectiveSelector.threadName() != null) {
            DiagnosticToolResult result = searchRawLines(
                indexedContext,
                line -> line.toLowerCase(Locale.ROOT).contains(effectiveSelector.threadName().toLowerCase(Locale.ROOT)),
                "Thread context for " + effectiveSelector.threadName(),
                "raw-search-thread-" + sanitizeId(effectiveSelector.threadName()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
            }
        }
        if (effectiveSelector.className() != null) {
            DiagnosticToolResult structuredClass = searchStructuredBlocks(
                indexedContext,
                effectiveSelector.className(),
                "Structured class context for " + effectiveSelector.className(),
                "structured-search-class-" + sanitizeId(effectiveSelector.className()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (structuredClass != null) {
                return structuredClass;
            }
            DiagnosticToolResult rawClass = searchRawLines(
                indexedContext,
                line -> line.toLowerCase(Locale.ROOT).contains(effectiveSelector.className().toLowerCase(Locale.ROOT)),
                "Raw class context for " + effectiveSelector.className(),
                "raw-search-class-" + sanitizeId(effectiveSelector.className()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (rawClass != null) {
                return rawClass;
            }
        }
        if (effectiveSelector.hotspotKey() != null) {
            DiagnosticToolResult structuredHotspot = searchStructuredBlocks(
                indexedContext,
                effectiveSelector.hotspotKey(),
                "Structured hotspot context for " + effectiveSelector.hotspotKey(),
                "structured-search-hotspot-" + sanitizeId(effectiveSelector.hotspotKey()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (structuredHotspot != null) {
                return structuredHotspot;
            }
        }
        if (effectiveSelector.mappingCategory() != null) {
            DiagnosticToolResult structuredCategory = searchStructuredBlocks(
                indexedContext,
                effectiveSelector.mappingCategory(),
                "Structured category context for " + effectiveSelector.mappingCategory(),
                "structured-search-category-" + sanitizeId(effectiveSelector.mappingCategory()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (structuredCategory != null) {
                return structuredCategory;
            }
            DiagnosticToolResult rawCategory = searchRawLines(
                indexedContext,
                line -> line.toLowerCase(Locale.ROOT).contains(effectiveSelector.mappingCategory().toLowerCase(Locale.ROOT)),
                "Raw category context for " + effectiveSelector.mappingCategory(),
                "raw-search-category-" + sanitizeId(effectiveSelector.mappingCategory()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (rawCategory != null) {
                return rawCategory;
            }
        }
        if (effectiveSelector.timestampStart() != null || effectiveSelector.timestampEnd() != null) {
            DiagnosticToolResult result = timestampWindow(
                indexedContext,
                effectiveSelector.timestampStart(),
                effectiveSelector.timestampEnd(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
            }
        }
        if (effectiveSelector.pattern() != null) {
            DiagnosticToolResult structuredPattern = searchStructuredBlocks(
                indexedContext,
                effectiveSelector.pattern(),
                "Structured context matching \"" + effectiveSelector.pattern() + "\"",
                "structured-search-pattern-" + sanitizeId(effectiveSelector.pattern()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (structuredPattern != null) {
                return structuredPattern;
            }
            DiagnosticToolResult rawPattern = searchRawLines(
                indexedContext,
                line -> line.toLowerCase(Locale.ROOT).contains(effectiveSelector.pattern().toLowerCase(Locale.ROOT)),
                "Raw context matching \"" + effectiveSelector.pattern() + "\"",
                "raw-search-pattern-" + sanitizeId(effectiveSelector.pattern()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (rawPattern != null) {
                return rawPattern;
            }
        }

        if (effectiveSelector.hasSpecificRequest()) {
            return noSelectorMatch(indexedContext);
        }
        return nextOmittedContext(indexedContext, normalizedSeenSliceIds);
    }

    public DiagnosticToolResult retrieveJfr(IndexedArtifactDiagnosticContext indexedContext, JfrSelector selector) {
        return retrieveJfr(indexedContext, selector, Set.of());
    }

    public DiagnosticToolResult retrieveJfr(
        IndexedArtifactDiagnosticContext indexedContext,
        JfrSelector selector,
        Set<String> seenSliceIds
    ) {
        Objects.requireNonNull(indexedContext, "indexedContext");
        JfrSelector effectiveSelector = selector != null
            ? selector
            : new JfrSelector(null, null, null, null, null, null, null, null, null, null);
        Set<String> normalizedSeenSliceIds = normalizeSeenSliceIds(seenSliceIds);
        ParsedArtifact parsedArtifact = indexedContext.parsedArtifact();
        if (parsedArtifact == null) {
            return unavailable(indexedContext, "No parsed JFR data was available.");
        }

        if (effectiveSelector.sliceId() != null) {
            DiagnosticToolResult result = sliceById(
                indexedContext,
                effectiveSelector.sliceId(),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars(),
                false,
                normalizedSeenSliceIds
            );
            if (result != null) {
                return result;
            }
        }
        if (effectiveSelector.eventType() != null) {
            DiagnosticToolResult eventFamily = jfrByEventType(
                parsedArtifact,
                effectiveSelector.eventType(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (eventFamily != null) {
                return eventFamily;
            }
        }
        if (effectiveSelector.hotspotKey() != null) {
            DiagnosticToolResult hotspot = jfrByHotspot(
                parsedArtifact,
                effectiveSelector.hotspotKey(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (hotspot != null) {
                return hotspot;
            }
        }
        if (effectiveSelector.allocationClass() != null) {
            DiagnosticToolResult allocation = jfrAllocationClass(
                parsedArtifact,
                effectiveSelector.allocationClass(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (allocation != null) {
                return allocation;
            }
        }
        if (effectiveSelector.oldObjectFocus() != null) {
            DiagnosticToolResult oldObject = jfrOldObjectFocus(
                parsedArtifact,
                effectiveSelector.oldObjectFocus(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (oldObject != null) {
                return oldObject;
            }
        }
        if (effectiveSelector.timeWindowStart() != null || effectiveSelector.timeWindowEnd() != null) {
            Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
            LinkedHashMap<String, Object> window = new LinkedHashMap<>();
            window.put("requestedStart", effectiveSelector.timeWindowStart());
            window.put("requestedEnd", effectiveSelector.timeWindowEnd());
            window.put("availableStart", summary.get("startTime"));
            window.put("availableEnd", summary.get("endTime"));
            window.put("durationMs", summary.get("durationMs"));
            window.put("note", "Use the JFR computation tool when a more focused time-window summary is needed.");
            return derivedResult(
                indexedContext,
                "jfr-time-window-request",
                "Requested JFR time window",
                window,
                "summary.startTime/endTime",
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars(),
                false
            );
        }
        if (effectiveSelector.pattern() != null) {
            DiagnosticToolResult patternMatch = searchStructuredBlocks(
                indexedContext,
                effectiveSelector.pattern(),
                "JFR context matching \"" + effectiveSelector.pattern() + "\"",
                "jfr-pattern-" + sanitizeId(effectiveSelector.pattern()),
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (patternMatch != null) {
                return patternMatch;
            }
        }

        if (effectiveSelector.hasSpecificRequest()) {
            return noSelectorMatch(indexedContext);
        }
        return nextOmittedContext(indexedContext, normalizedSeenSliceIds);
    }

    private DiagnosticToolResult jfrByEventType(
        ParsedArtifact parsedArtifact,
        String eventType,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        String normalized = eventType.toLowerCase(Locale.ROOT);
        Map<String, String> aliases = Map.of(
            "gc", "gcSummary",
            "lock", "lockSummary",
            "threadpark", "threadParkSummary",
            "io", "ioSummary",
            "exception", "exceptionSummary",
            "safepoint", "safepointSummary",
            "allocation", "allocationFieldSummary",
            "oldobject", "oldObjectFieldSummary",
            "execution", "executionHotspotSummary",
            "runtime", "runtimeHotspotSummary"
        );
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (normalized.contains(alias.getKey())) {
                Object value = parsedArtifact.extractedData().get(alias.getValue());
                if (value != null) {
                    return derivedResult(
                        indexedContext,
                        alias.getValue(),
                        humanLabel(alias.getValue()),
                        value,
                        "extractedData." + alias.getValue(),
                        contentOffset,
                        contentChars,
                        false
                    );
                }
            }
        }

        List<Map<String, Object>> observedEventTypes = listOfMaps(parsedArtifact.extractedData().get("observedEventTypes"));
        List<Map<String, Object>> declaredEventTypes = listOfMaps(parsedArtifact.extractedData().get("declaredEventTypes"));
        List<Map<String, Object>> observedMatches = observedEventTypes.stream()
            .filter(event -> containsText(event, normalized))
            .toList();
        List<Map<String, Object>> declaredMatches = declaredEventTypes.stream()
            .filter(event -> containsText(event, normalized))
            .toList();
        if (observedMatches.isEmpty() && declaredMatches.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (!observedMatches.isEmpty()) {
            payload.put("observedEventTypes", observedMatches);
        }
        if (!declaredMatches.isEmpty()) {
            payload.put("declaredEventTypes", declaredMatches);
        }
        return derivedResult(
            indexedContext,
            "jfr-event-type-" + sanitizeId(eventType),
            "JFR event type context for " + eventType,
            payload,
            "extractedData.observedEventTypes + extractedData.declaredEventTypes",
            contentOffset,
            contentChars,
            false
        );
    }

    private DiagnosticToolResult jfrByHotspot(
        ParsedArtifact parsedArtifact,
        String hotspotKey,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        List<String> hotspotKeys = List.of(
            "overallHotspotSummary",
            "executionHotspotSummary",
            "runtimeHotspotSummary",
            "allocationHotspotSummary"
        );
        String normalized = hotspotKey.toLowerCase(Locale.ROOT);
        for (String key : hotspotKeys) {
            Map<String, Object> map = mapValue(parsedArtifact.extractedData().get(key));
            if (containsText(map, normalized)) {
                return derivedResult(
                    indexedContext,
                    key + "-" + sanitizeId(hotspotKey),
                    humanLabel(key),
                    map,
                    "extractedData." + key,
                    contentOffset,
                    contentChars,
                    false
                );
            }
        }
        return null;
    }

    private DiagnosticToolResult jfrAllocationClass(
        ParsedArtifact parsedArtifact,
        String allocationClass,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("allocationFieldSummary"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topClasses = (List<Map<String, Object>>) summary.get("topAllocatingClasses");
        if (topClasses == null || topClasses.isEmpty()) {
            return null;
        }
        String normalized = allocationClass.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = topClasses.stream()
            .filter(item -> stringValue(item.get("className")).toLowerCase(Locale.ROOT).contains(normalized))
            .toList();
        if (matches.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("topClass", summary.get("topClass"));
        payload.put("topClassEventCount", summary.get("topClassEventCount"));
        payload.put("topClassAllocatedBytes", summary.get("topClassAllocatedBytes"));
        payload.put("matchingClasses", matches);
        return derivedResult(
            indexedContext,
            "jfr-allocation-class-" + sanitizeId(allocationClass),
            "JFR allocation context for " + allocationClass,
            payload,
            "extractedData.allocationFieldSummary",
            contentOffset,
            contentChars,
            false
        );
    }

    private DiagnosticToolResult jfrOldObjectFocus(
        ParsedArtifact parsedArtifact,
        String oldObjectFocus,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("oldObjectFieldSummary"));
        String normalized = oldObjectFocus.toLowerCase(Locale.ROOT);
        if (!containsText(summary, normalized)) {
            return null;
        }
        return derivedResult(
            indexedContext,
            "jfr-old-object-" + sanitizeId(oldObjectFocus),
            "JFR old-object context for " + oldObjectFocus,
            summary,
            "extractedData.oldObjectFieldSummary",
            contentOffset,
            contentChars,
            false
        );
    }

    private DiagnosticToolResult rawLineRange(
        IndexedArtifactDiagnosticContext indexedContext,
        Integer startLineInclusive,
        Integer endLineInclusive,
        String label,
        Integer contentOffset,
        Integer contentChars
    ) {
        if (indexedContext.rawLines().isEmpty()) {
            return unavailable(indexedContext, "No raw text lines were available for this artifact.");
        }
        int start = startLineInclusive != null ? startLineInclusive : 1;
        int end = endLineInclusive != null ? endLineInclusive : start;
        int normalizedStart = Math.max(1, start);
        int normalizedEnd = Math.min(indexedContext.rawLines().size(), Math.max(normalizedStart, end));
        return rawResult(
            indexedContext,
            "raw-lines-" + normalizedStart + "-" + normalizedEnd,
            label,
            renderRawLines(indexedContext.rawLines(), normalizedStart, normalizedEnd),
            artifactPath(indexedContext) + " lines " + normalizedStart + "-" + normalizedEnd,
            contentOffset,
            contentChars,
            false
        );
    }

    private DiagnosticToolResult rawSection(
        IndexedArtifactDiagnosticContext indexedContext,
        String sectionId,
        Integer contentOffset,
        Integer contentChars
    ) {
        IndexedArtifactDiagnosticContext.LineRange range = rawSectionRange(indexedContext, sectionId);
        if (range == null) {
            return null;
        }
        return rawLineRange(
            indexedContext,
            range.startLineInclusive(),
            range.endLineInclusive(),
            "Raw section " + sectionId,
            contentOffset,
            contentChars
        );
    }

    private DiagnosticToolResult structuredSection(
        IndexedArtifactDiagnosticContext indexedContext,
        String sectionId,
        Integer contentOffset,
        Integer contentChars,
        boolean progressiveResult,
        Set<String> seenSliceIds
    ) {
        String resolvedSectionId = resolveStructuredBlockKey(indexedContext, sectionId);
        if (resolvedSectionId == null) {
            return null;
        }
        return pagedResult(
            indexedContext,
            resolvedSectionId,
            "structured",
            progressiveResult ? "Additional structured context: " + humanLabel(resolvedSectionId) : humanLabel(resolvedSectionId),
            indexedContext.structuredBlocks().get(resolvedSectionId),
            "extractedData." + resolvedSectionId,
            contentOffset,
            contentChars,
            progressiveResult && hasRemainingOmittedContext(indexedContext, seenSliceIds, resolvedSectionId)
        );
    }

    private DiagnosticToolResult timestampWindow(
        IndexedArtifactDiagnosticContext indexedContext,
        String startToken,
        String endToken,
        Integer contentOffset,
        Integer contentChars
    ) {
        if (indexedContext.rawLines().isEmpty()) {
            return null;
        }
        int startLine = -1;
        int endLine = -1;
        for (int index = 0; index < indexedContext.rawLines().size(); index++) {
            String line = indexedContext.rawLines().get(index);
            if (startLine < 0 && startToken != null && line.contains(startToken)) {
                startLine = index + 1;
            }
            if (endToken != null && line.contains(endToken)) {
                endLine = index + 1;
            }
        }
        if (startLine < 0 && endLine < 0) {
            return null;
        }
        int resolvedStart = startLine > 0 ? startLine : Math.max(1, endLine - 5);
        int resolvedEnd = endLine > 0 ? endLine : Math.min(indexedContext.rawLines().size(), resolvedStart + 10);
        return rawLineRange(indexedContext, resolvedStart, resolvedEnd, "Raw timestamp window context", contentOffset, contentChars);
    }

    private DiagnosticToolResult searchStructuredBlocks(
        IndexedArtifactDiagnosticContext indexedContext,
        String query,
        String label,
        String sliceId,
        Integer contentOffset,
        Integer contentChars
    ) {
        String normalized = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : indexedContext.structuredBlocks().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(normalized)
                || entry.getValue().toLowerCase(Locale.ROOT).contains(normalized)) {
                return pagedResult(
                    indexedContext,
                    sliceId,
                    "structured",
                    label,
                    entry.getValue(),
                    "extractedData." + entry.getKey(),
                    contentOffset,
                    contentChars,
                    false
                );
            }
        }
        return null;
    }

    private DiagnosticToolResult searchRawLines(
        IndexedArtifactDiagnosticContext indexedContext,
        Predicate<String> predicate,
        String label,
        String sliceId,
        Integer contentOffset,
        Integer contentChars
    ) {
        if (indexedContext.rawLines().isEmpty()) {
            return null;
        }
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < indexedContext.rawLines().size(); index++) {
            if (predicate.test(indexedContext.rawLines().get(index))) {
                matches.add(index + 1);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        List<String> traceabilityRanges = new ArrayList<>();
        int windowsAdded = 0;
        for (Integer matchLine : matches) {
            int start = Math.max(1, matchLine - 2);
            int end = Math.min(indexedContext.rawLines().size(), matchLine + MAX_SEARCH_WINDOW_LINES - 1);
            String rangeKey = start + "-" + end;
            if (traceabilityRanges.contains(rangeKey)) {
                continue;
            }
            traceabilityRanges.add(rangeKey);
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("Window ").append(windowsAdded + 1).append(" around matching line ").append(matchLine).append(":\n");
            builder.append(renderRawLines(indexedContext.rawLines(), start, end));
            windowsAdded++;
            if (windowsAdded >= 3) {
                break;
            }
        }
        return rawResult(
            indexedContext,
            sliceId,
            label,
            builder.toString().stripTrailing(),
            artifactPath(indexedContext) + " lines " + String.join(", ", traceabilityRanges),
            contentOffset,
            contentChars,
            matches.size() > windowsAdded
        );
    }

    private DiagnosticToolResult sliceById(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        Integer contentOffset,
        Integer contentChars,
        boolean progressiveResult,
        Set<String> seenSliceIds
    ) {
        for (ContextSlice slice : indexedContext.allRawOrDerivedSlices()) {
            if (slice.sliceId().equalsIgnoreCase(sliceId)) {
                return toolResult(
                    indexedContext,
                    slice,
                    contentOffset,
                    contentChars,
                    progressiveResult && hasRemainingOmittedContext(indexedContext, seenSliceIds, slice.sliceId())
                );
            }
        }

        String resolvedStructuredBlock = resolveStructuredBlockKey(indexedContext, sliceId);
        if (resolvedStructuredBlock != null) {
            return structuredSection(
                indexedContext,
                resolvedStructuredBlock,
                contentOffset,
                contentChars,
                progressiveResult,
                seenSliceIds
            );
        }
        return null;
    }

    private DiagnosticToolResult nextOmittedContext(
        IndexedArtifactDiagnosticContext indexedContext,
        Set<String> seenSliceIds
    ) {
        for (String omittedStructuredBlock : indexedContext.diagnosticContext().coverage().omittedStructuredBlocks()) {
            if (!containsSeenSliceId(seenSliceIds, omittedStructuredBlock)) {
                return structuredSection(indexedContext, omittedStructuredBlock, null, null, true, seenSliceIds);
            }
        }
        for (String omittedSliceId : indexedContext.diagnosticContext().coverage().omittedRawSlices()) {
            if (!containsSeenSliceId(seenSliceIds, omittedSliceId)) {
                return sliceById(indexedContext, omittedSliceId, null, null, true, seenSliceIds);
            }
        }
        for (ContextSlice slice : indexedContext.allRawOrDerivedSlices()) {
            if (!containsSeenSliceId(seenSliceIds, slice.sliceId())) {
                return toolResult(
                    indexedContext,
                    slice,
                    null,
                    null,
                    hasRemainingOmittedContext(indexedContext, seenSliceIds, slice.sliceId())
                );
            }
        }
        return unavailable(indexedContext, "No additional curated context was available for that selector.");
    }

    private DiagnosticToolResult rawResult(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        String label,
        String content,
        String traceability,
        Integer contentOffset,
        Integer contentChars,
        boolean moreAvailable
    ) {
        return pagedResult(
            indexedContext,
            sliceId,
            "raw",
            label,
            content,
            traceability,
            contentOffset,
            contentChars,
            moreAvailable
        );
    }

    private String renderRawLines(List<String> rawLines, int startLineInclusive, int endLineInclusive) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = startLineInclusive; lineNumber <= endLineInclusive; lineNumber++) {
            builder.append(lineNumber).append(": ").append(rawLines.get(lineNumber - 1)).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private DiagnosticToolResult derivedResult(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        String label,
        Object content,
        String traceability,
        Integer contentOffset,
        Integer contentChars,
        boolean moreAvailable
    ) {
        return pagedResult(
            indexedContext,
            sliceId,
            "derived",
            label,
            DiagnosticContextRenderSupport.renderFullValue(content),
            traceability,
            contentOffset,
            contentChars,
            moreAvailable
        );
    }

    private DiagnosticToolResult toolResult(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSlice slice,
        Integer contentOffset,
        Integer contentChars,
        boolean moreAvailable
    ) {
        return pagedResult(
            indexedContext,
            slice.sliceId(),
            slice.kind(),
            slice.label(),
            slice.content(),
            slice.traceability(),
            contentOffset,
            contentChars,
            moreAvailable
        );
    }

    private DiagnosticToolResult pagedResult(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        String kind,
        String label,
        String content,
        String traceability,
        Integer contentOffset,
        Integer contentChars,
        boolean moreAvailable
    ) {
        ContentPage page = pageContent(content, contentOffset, contentChars);
        return new DiagnosticToolResult(
            indexedContext.diagnosticContext().artifactType(),
            artifactPath(indexedContext),
            sliceId,
            kind,
            label,
            page.content(),
            appendTraceabilityRange(traceability, page),
            page.truncated(),
            moreAvailable || page.moreAvailable()
        );
    }

    private ContentPage pageContent(String content, Integer requestedOffset, Integer requestedChars) {
        String normalized = DiagnosticContextRenderSupport.normalizeTextBlock(content);
        if (normalized.isBlank()) {
            return new ContentPage("(none)", "", false, false);
        }

        int totalLength = normalized.length();
        int offset = requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        if (offset >= totalLength) {
            return new ContentPage(
                "(requested offset past end of content)",
                " chars " + (totalLength + 1) + "-" + totalLength + " of " + totalLength,
                false,
                false
            );
        }

        int charCount = requestedChars != null && requestedChars > 0 ? requestedChars : MAX_RESULT_CHARS;
        int endExclusive = Math.min(totalLength, offset + charCount);
        boolean moreAvailable = endExclusive < totalLength;
        boolean truncated = moreAvailable;
        String traceabilityRange = offset > 0 || requestedChars != null || moreAvailable
            ? " chars " + (offset + 1) + "-" + endExclusive + " of " + totalLength
            : "";

        return new ContentPage(
            normalized.substring(offset, endExclusive),
            traceabilityRange,
            truncated,
            moreAvailable
        );
    }

    private String appendTraceabilityRange(String traceability, ContentPage page) {
        if (page == null || page.traceabilityRange().isBlank()) {
            return traceability;
        }
        if (traceability == null || traceability.isBlank()) {
            return page.traceabilityRange().trim();
        }
        return traceability + page.traceabilityRange();
    }

    private IndexedArtifactDiagnosticContext.LineRange rawSectionRange(
        IndexedArtifactDiagnosticContext indexedContext,
        String sectionId
    ) {
        IndexedArtifactDiagnosticContext.LineRange range = indexedContext.rawSections().get(sectionId);
        if (range == null) {
            range = indexedContext.rawSections().get(sectionId.toLowerCase(Locale.ROOT));
        }
        if (range == null && indexedContext.inputArtifact() != null && indexedContext.inputArtifact().type() == ArtifactType.THREAD_DUMP) {
            range = indexedContext.rawSections().get("thread:" + sectionId.toLowerCase(Locale.ROOT));
        }
        return range;
    }

    private String resolveStructuredBlockKey(IndexedArtifactDiagnosticContext indexedContext, String sectionId) {
        String block = indexedContext.structuredBlocks().get(sectionId);
        if (block != null) {
            return sectionId;
        }
        for (Map.Entry<String, String> entry : indexedContext.structuredBlocks().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(sectionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean hasRemainingOmittedContext(
        IndexedArtifactDiagnosticContext indexedContext,
        Set<String> seenSliceIds,
        String currentSliceId
    ) {
        for (String omittedStructuredBlock : indexedContext.diagnosticContext().coverage().omittedStructuredBlocks()) {
            if (!matchesSliceId(omittedStructuredBlock, currentSliceId) && !containsSeenSliceId(seenSliceIds, omittedStructuredBlock)) {
                return true;
            }
        }
        for (String omittedSliceId : indexedContext.diagnosticContext().coverage().omittedRawSlices()) {
            if (!matchesSliceId(omittedSliceId, currentSliceId) && !containsSeenSliceId(seenSliceIds, omittedSliceId)) {
                return true;
            }
        }
        for (ContextSlice slice : indexedContext.allRawOrDerivedSlices()) {
            if (!matchesSliceId(slice.sliceId(), currentSliceId) && !containsSeenSliceId(seenSliceIds, slice.sliceId())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSliceId(String left, String right) {
        return normalizeSliceId(left).equals(normalizeSliceId(right));
    }

    private boolean containsSeenSliceId(Set<String> seenSliceIds, String sliceId) {
        return seenSliceIds.contains(normalizeSliceId(sliceId));
    }

    private Set<String> normalizeSeenSliceIds(Set<String> seenSliceIds) {
        if (seenSliceIds == null || seenSliceIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String seenSliceId : seenSliceIds) {
            if (seenSliceId != null && !seenSliceId.isBlank()) {
                normalized.add(normalizeSliceId(seenSliceId));
            }
        }
        return Set.copyOf(normalized);
    }

    private String normalizeSliceId(String sliceId) {
        return sliceId == null ? "" : sliceId.toLowerCase(Locale.ROOT);
    }

    private DiagnosticToolResult noSelectorMatch(IndexedArtifactDiagnosticContext indexedContext) {
        return unavailable(indexedContext, "No curated context matched the requested selector.");
    }

    private DiagnosticToolResult unavailable(IndexedArtifactDiagnosticContext indexedContext, String reason) {
        return new DiagnosticToolResult(
            indexedContext != null ? indexedContext.diagnosticContext().artifactType() : null,
            artifactPath(indexedContext),
            "unavailable",
            "notice",
            "No additional context",
            reason,
            "internal-curated-retrieval",
            false,
            false
        );
    }

    private String artifactPath(IndexedArtifactDiagnosticContext indexedContext) {
        return indexedContext != null
            && indexedContext.inputArtifact() != null
            && indexedContext.inputArtifact().metadata() != null
            ? indexedContext.inputArtifact().metadata().sourcePath()
            : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                copy.add(Collections.unmodifiableMap(converted));
            }
        }
        return List.copyOf(copy);
    }

    private boolean containsText(Object value, String normalizedQuery) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(entry ->
                containsText(entry.getKey(), normalizedQuery) || containsText(entry.getValue(), normalizedQuery)
            );
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> containsText(item, normalizedQuery));
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sanitizeId(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private String humanLabel(String key) {
        String label = key.replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private record ContentPage(String content, String traceabilityRange, boolean truncated, boolean moreAvailable) { }
}
