package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final int GC_EVENT_WINDOW_PADDING = 2;
    private static final double GC_DEFAULT_WINDOW_SECONDS = 15.0d;
    private static final double GC_STREAK_MAX_GAP_SECONDS = 2.0d;
    private static final int GC_STREAK_MAX_GAP_LINES = 160;
    private static final List<String> JFR_SIGNAL_KEYS = List.of(
        "incidentWindowSummary",
        "chronologyHighlights",
        "gcSummary",
        "lockSummary",
        "monitorWaitSummary",
        "threadParkSummary",
        "ioSummary",
        "exceptionSummary",
        "safepointSummary",
        "classLoadingSummary",
        "cpuLoadSummary",
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
            : new ContextSelector(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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

        if (indexedContext.parsedArtifact() != null
            && indexedContext.parsedArtifact().type() == ArtifactType.GC_LOG
            && effectiveSelector.incident() != null) {
            DiagnosticToolResult result = retrieveGcIncidentContext(
                indexedContext,
                effectiveSelector,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars(),
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

        if (indexedContext.parsedArtifact() != null
            && indexedContext.parsedArtifact().type() == ArtifactType.GC_LOG
            && hasGcFocusedSelector(effectiveSelector)) {
            DiagnosticToolResult result = retrieveGcFocusedContext(
                indexedContext,
                effectiveSelector,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
            }
        }

        if (indexedContext.parsedArtifact() != null
            && indexedContext.parsedArtifact().type() == ArtifactType.GC_LOG
            && effectiveSelector.streakKind() != null) {
            DiagnosticToolResult result = retrieveGcStreakContext(
                indexedContext,
                effectiveSelector,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
            }
        }

        if (indexedContext.parsedArtifact() != null
            && indexedContext.parsedArtifact().type() == ArtifactType.GC_LOG
            && (effectiveSelector.timestampStart() != null
            || effectiveSelector.timestampEnd() != null
            || effectiveSelector.windowSeconds() != null)) {
            DiagnosticToolResult result = retrieveGcTimeWindow(
                indexedContext,
                effectiveSelector,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (result != null) {
                return result;
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

    private boolean hasGcFocusedSelector(ContextSelector selector) {
        return selector != null
            && (selector.cause() != null
            || selector.pauseType() != null
            || selector.phase() != null
            || selector.phaseKind() != null
            || selector.signalType() != null);
    }

    private DiagnosticToolResult retrieveGcFocusedContext(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        Integer contentOffset,
        Integer contentChars
    ) {
        List<Map<String, Object>> pauses = listOfMaps(indexedContext.parsedArtifact().extractedData().get("pauses"));
        List<Map<String, Object>> phaseSamples = listOfMaps(indexedContext.parsedArtifact().extractedData().get("phaseSamples"));
        List<Map<String, Object>> failureSignals = listOfMaps(indexedContext.parsedArtifact().extractedData().get("failureSignals"));

        if (selector.cause() != null || selector.pauseType() != null) {
            List<Map<String, Object>> matches = pauses.stream()
                .filter(pause -> matchesGcId(pause, selector.gcId()))
                .filter(pause -> matchesText(pause.get("cause"), selector.cause()))
                .filter(pause -> matchesText(pause.get("pauseType"), selector.pauseType()))
                .toList();
            if (!matches.isEmpty()) {
                return gcEventWindowsResult(
                    indexedContext,
                    matches,
                    gcPauseLabel(selector),
                    gcPauseSliceId(selector),
                    contentOffset,
                    contentChars
                );
            }
        }

        if (selector.phase() != null || selector.phaseKind() != null) {
            List<Map<String, Object>> matches = phaseSamples.stream()
                .filter(sample -> matchesGcId(sample, selector.gcId()))
                .filter(sample -> matchesText(sample.get("phase"), selector.phase()))
                .filter(sample -> matchesText(sample.get("phaseKind"), selector.phaseKind()))
                .toList();
            if (!matches.isEmpty()) {
                return gcEventWindowsResult(
                    indexedContext,
                    matches,
                    gcPhaseLabel(selector),
                    gcPhaseSliceId(selector),
                    contentOffset,
                    contentChars
                );
            }
        }

        if (selector.signalType() != null) {
            List<Map<String, Object>> matches = failureSignals.stream()
                .filter(signal -> matchesGcId(signal, selector.gcId()))
                .filter(signal -> matchesText(signal.get("signalType"), selector.signalType())
                    || matchesText(signal.get("signal"), selector.signalType()))
                .toList();
            if (!matches.isEmpty()) {
                return gcEventWindowsResult(
                    indexedContext,
                    matches,
                    gcSignalLabel(selector),
                    gcSignalSliceId(selector),
                    contentOffset,
                    contentChars
                );
            }
        }

        return null;
    }

    private DiagnosticToolResult retrieveGcIncidentContext(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        Integer contentOffset,
        Integer contentChars,
        Set<String> seenSliceIds
    ) {
        for (String candidateSliceId : gcIncidentSliceCandidates(indexedContext, selector.incident())) {
            DiagnosticToolResult result = sliceById(
                indexedContext,
                candidateSliceId,
                contentOffset,
                contentChars,
                false,
                seenSliceIds
            );
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private List<String> gcIncidentSliceCandidates(IndexedArtifactDiagnosticContext indexedContext, String incident) {
        if (incident == null || incident.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalizedIncident = normalizeGcIncidentAlias(incident);
        List<String> collectorPrioritySlices = collectorPriorityGcIncidentSlices(indexedContext);

        switch (normalizedIncident) {
            case "dominant", "dominant-pressure", "dominant-window", "pressure", "pressure-window" -> {
                candidates.addAll(collectorPrioritySlices);
                candidates.add("gc-incident-densest-failure-region");
                candidates.add("gc-incident-longest-full-gc");
                candidates.add("gc-incident-peak-occupancy");
                candidates.add("gc-incident-longest-allocation-stall");
                candidates.add("gc-incident-tail");
            }
            case "failure", "failure-cluster", "distress", "distress-cluster" -> {
                candidates.add("gc-incident-densest-failure-region");
                candidates.addAll(collectorPrioritySlices);
                candidates.add("gc-incident-longest-full-gc");
                candidates.add("gc-incident-first-evacuation-failure");
            }
            case "peak", "peak-occupancy", "occupancy", "peak-retention" -> candidates.add("gc-incident-peak-occupancy");
            case "tail", "latest", "latest-tail", "recent" -> candidates.add("gc-incident-tail");
            case "longest-full-gc", "full-gc", "full-gc-window" -> candidates.add("gc-incident-longest-full-gc");
            case "allocation-stall", "stall", "zgc-stall" -> {
                candidates.add("gc-incident-longest-allocation-stall");
                candidates.addAll(collectorPrioritySlices);
            }
            case "humongous", "humongous-growth" -> candidates.add("gc-incident-peak-humongous-growth");
            case "evacuation-failure" -> candidates.add("gc-incident-first-evacuation-failure");
            case "concurrent-abort" -> candidates.add("gc-incident-first-concurrent-abort");
            case "full-compaction-attempt", "compaction-attempt" -> candidates.add("gc-incident-first-full-compaction-attempt");
            case "to-space-distress", "to-space-exhausted" -> candidates.add("gc-incident-first-to-space-distress");
            case "concurrent-mode-failure", "cms-fallback" -> {
                candidates.add("gc-incident-dominant-cms-fallback-window");
                candidates.add("gc-incident-first-concurrent-mode-failure");
            }
            case "longest-concurrent-phase" -> candidates.add("gc-incident-longest-concurrent-phase");
            default -> {
                if (normalizedIncident.startsWith("gc-incident-")) {
                    candidates.add(normalizedIncident);
                }
            }
        }

        return List.copyOf(candidates);
    }

    private List<String> collectorPriorityGcIncidentSlices(IndexedArtifactDiagnosticContext indexedContext) {
        String collector = indexedContext != null && indexedContext.parsedArtifact() != null
            ? stringValue(indexedContext.parsedArtifact().extractedData().get("collector"))
            : "";
        String normalizedCollector = collector.strip().toUpperCase(Locale.ROOT);
        return switch (normalizedCollector) {
            case "G1" -> List.of("gc-incident-dominant-g1-distress-window");
            case "CMS" -> List.of("gc-incident-dominant-cms-fallback-window");
            case "SERIAL", "PARALLEL" -> List.of("gc-incident-dominant-full-gc-window");
            case "ZGC" -> List.of("gc-incident-dominant-zgc-stall-window");
            default -> List.of();
        };
    }

    private String normalizeGcIncidentAlias(String incident) {
        if (incident == null || incident.isBlank()) {
            return "";
        }
        return incident.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private DiagnosticToolResult retrieveGcStreakContext(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        Integer contentOffset,
        Integer contentChars
    ) {
        String streakKind = selector.streakKind();
        if (streakKind == null || streakKind.isBlank()) {
            return null;
        }

        List<Map<String, Object>> pauses = listOfMaps(indexedContext.parsedArtifact().extractedData().get("pauses"));
        List<Map<String, Object>> failureSignals = listOfMaps(indexedContext.parsedArtifact().extractedData().get("failureSignals"));
        List<Map<String, Object>> distressEvents = gcDistressEvents(pauses, failureSignals);
        List<Map<String, Object>> events = switch (normalizeStreakKind(streakKind)) {
            case "full-gc" -> bestGcEventCluster(pauses.stream().filter(this::isFullGcPause).toList());
            case "evacuation-failure" -> bestGcEventCluster(pauses.stream().filter(this::isEvacuationFailurePause).toList());
            case "failure", "failure-signal" -> bestGcEventCluster(failureSignals);
            case "distress" -> bestGcEventCluster(distressEvents);
            default -> List.of();
        };
        if (events.isEmpty()) {
            return null;
        }
        return gcEventWindowsResult(
            indexedContext,
            representativeGcClusterEvents(events),
            gcStreakLabel(streakKind),
            gcStreakSliceId(streakKind),
            contentOffset,
            contentChars,
            false
        );
    }

    private DiagnosticToolResult retrieveGcTimeWindow(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        Integer contentOffset,
        Integer contentChars
    ) {
        GcWindowSelection selection = selectGcTimeWindow(indexedContext, selector);
        if (selection == null) {
            return null;
        }
        return rawResult(
            indexedContext,
            selection.sliceId(),
            selection.label(),
            renderRawLines(indexedContext.rawLines(), selection.startLineInclusive(), selection.endLineInclusive()),
            artifactPath(indexedContext) + " lines " + selection.startLineInclusive() + "-" + selection.endLineInclusive(),
            contentOffset,
            contentChars,
            false
        );
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
            : new JfrSelector(null, null, null, null, null, null, null, null, null, null, null, null);
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
        if (effectiveSelector.incident() != null) {
            DiagnosticToolResult incidentWindow = jfrIncidentWindow(
                parsedArtifact,
                effectiveSelector.incident(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (incidentWindow != null) {
                return incidentWindow;
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
        if (effectiveSelector.threadName() != null) {
            DiagnosticToolResult threadFocus = jfrByThread(
                parsedArtifact,
                effectiveSelector.threadName(),
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (threadFocus != null) {
                return threadFocus;
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
            DiagnosticToolResult timeWindow = jfrTimeWindowContext(
                parsedArtifact,
                effectiveSelector,
                indexedContext,
                effectiveSelector.contentOffset(),
                effectiveSelector.contentChars()
            );
            if (timeWindow != null) {
                return timeWindow;
            }
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
        DiagnosticToolResult exactEventTypeDetail = jfrExactEventTypeDetail(
            parsedArtifact,
            eventType,
            indexedContext,
            contentOffset,
            contentChars
        );
        if (exactEventTypeDetail != null) {
            return exactEventTypeDetail;
        }

        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        List<Map<String, Object>> observedEventTypes = listOfMaps(parsedArtifact.extractedData().get("observedEventTypes"));
        List<Map<String, Object>> declaredEventTypes = listOfMaps(parsedArtifact.extractedData().get("declaredEventTypes"));
        if (!timelineEvents.isEmpty() || !observedEventTypes.isEmpty() || !declaredEventTypes.isEmpty()) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.eventTypeSlice(
                eventType,
                timelineEvents,
                incidentWindows,
                observedEventTypes,
                declaredEventTypes
            );
            if (!focusedSlice.isEmpty()) {
                return derivedResult(
                    indexedContext,
                    "jfr-event-family-" + sanitizeId(eventType),
                    "JFR event-family context for " + eventType,
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + extractedData.observedEventTypes + extractedData.declaredEventTypes",
                    contentOffset,
                    contentChars,
                    focusedSlice.moreAvailable()
                );
            }
        }

        String normalized = eventType.toLowerCase(Locale.ROOT);
        Map<String, String> aliases = Map.ofEntries(
            Map.entry("gc", "gcSummary"),
            Map.entry("lock", "lockSummary"),
            Map.entry("monitorwait", "monitorWaitSummary"),
            Map.entry("threadpark", "threadParkSummary"),
            Map.entry("cpuload", "cpuLoadSummary"),
            Map.entry("io", "ioSummary"),
            Map.entry("exception", "exceptionSummary"),
            Map.entry("safepoint", "safepointSummary"),
            Map.entry("allocation", "allocationFieldSummary"),
            Map.entry("oldobject", "oldObjectFieldSummary"),
            Map.entry("execution", "executionHotspotSummary"),
            Map.entry("runtime", "runtimeHotspotSummary")
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

    private DiagnosticToolResult jfrIncidentWindow(
        ParsedArtifact parsedArtifact,
        String incident,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        String normalizedIncident = normalizeJfrIncidentAlias(incident);
        if (normalizedIncident.isBlank()) {
            return null;
        }

        Map<String, Object> incidentWindowSummary = mapValue(parsedArtifact.extractedData().get("incidentWindowSummary"));
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        List<Map<String, Object>> chronologyHighlights = listOfMaps(parsedArtifact.extractedData().get("chronologyHighlights"));
        if (incidentWindowSummary.isEmpty() && incidentWindows.isEmpty() && chronologyHighlights.isEmpty()) {
            return null;
        }

        if (normalizedIncident.equals("chronology")
            || normalizedIncident.equals("timeline")
            || normalizedIncident.equals("chronology-highlights")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("chronologyHighlights", chronologyHighlights);
            if (!incidentWindowSummary.isEmpty()) {
                payload.put("incidentWindowSummary", incidentWindowSummary);
            }
            return derivedResult(
                indexedContext,
                "jfr-chronology-highlights",
                "JFR chronology highlights",
                payload,
                "extractedData.chronologyHighlights + extractedData.incidentWindowSummary",
                contentOffset,
                contentChars,
                false
            );
        }

        if (normalizedIncident.equals("summary")
            || normalizedIncident.equals("window-summary")
            || normalizedIncident.equals("incident-summary")
            || normalizedIncident.equals("windows")) {
            return derivedResult(
                indexedContext,
                "jfr-incident-window-summary",
                "JFR incident-window summary",
                incidentWindowSummary,
                "extractedData.incidentWindowSummary",
                contentOffset,
                contentChars,
                false
            );
        }

        String requestedWindowId = null;
        if (normalizedIncident.equals("dominant") || normalizedIncident.equals("primary") || normalizedIncident.equals("dominant-window")) {
            requestedWindowId = stringValue(incidentWindowSummary.get("primaryIncident.windowId"));
            if (requestedWindowId.isBlank()) {
                requestedWindowId = stringValue(mapValue(incidentWindowSummary.get("primaryIncident")).get("windowId"));
            }
        }
        final String finalRequestedWindowId = requestedWindowId;

        Map<String, Object> matchedWindow = incidentWindows.stream()
            .filter(window -> matchesJfrIncidentWindow(window, normalizedIncident, finalRequestedWindowId))
            .findFirst()
            .orElse(null);
        if (matchedWindow == null) {
            return null;
        }

        String windowId = firstNonBlank(stringValue(matchedWindow.get("windowId")), incident);
        String label = firstNonBlank(stringValue(matchedWindow.get("label")), "JFR incident window");
        return derivedResult(
            indexedContext,
            "jfr-incident-" + sanitizeId(windowId),
            label,
            matchedWindow,
            "extractedData.incidentWindows",
            contentOffset,
            contentChars,
            false
        );
    }

    private boolean matchesJfrIncidentWindow(Map<String, Object> window, String normalizedIncident, String requestedWindowId) {
        String windowId = normalizeJfrIncidentAlias(stringValue(window.get("windowId")));
        String focus = normalizeJfrIncidentAlias(stringValue(window.get("focus")));
        String label = normalizeJfrIncidentAlias(stringValue(window.get("label")));
        if (requestedWindowId != null && !requestedWindowId.isBlank()) {
            return windowId.equals(normalizeJfrIncidentAlias(requestedWindowId));
        }
        return windowId.equals(normalizedIncident)
            || focus.equals(normalizedIncident)
            || label.contains(normalizedIncident)
            || (normalizedIncident.startsWith("runtime") && focus.equals("runtime"))
            || (normalizedIncident.startsWith("allocation") && focus.equals("allocation"))
            || ((normalizedIncident.startsWith("retained") || normalizedIncident.startsWith("retention")
            || normalizedIncident.startsWith("old-object") || normalizedIncident.startsWith("oldobject")) && focus.equals("retention"));
    }

    private String normalizeJfrIncidentAlias(String incident) {
        if (incident == null || incident.isBlank()) {
            return "";
        }
        return incident.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private DiagnosticToolResult jfrTimeWindowContext(
        ParsedArtifact parsedArtifact,
        JfrSelector selector,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        List<Map<String, Object>> chronologyHighlights = listOfMaps(parsedArtifact.extractedData().get("chronologyHighlights"));
        Instant availableStart = instantValue(summary.get("startTime"));
        Instant availableEnd = instantValue(summary.get("endTime"));
        Instant requestedStart = resolveJfrTimeToken(selector.timeWindowStart(), availableStart);
        Instant requestedEnd = resolveJfrTimeToken(selector.timeWindowEnd(), availableStart);
        if (requestedStart == null && requestedEnd == null) {
            return null;
        }
        if (requestedStart == null) {
            requestedStart = availableStart;
        }
        if (requestedEnd == null) {
            requestedEnd = availableEnd;
        }
        if (requestedStart == null || requestedEnd == null) {
            return null;
        }
        if (requestedEnd.isBefore(requestedStart)) {
            Instant swap = requestedStart;
            requestedStart = requestedEnd;
            requestedEnd = swap;
        }
        final Instant finalRequestedStart = requestedStart;
        final Instant finalRequestedEnd = requestedEnd;

        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> overlappingIncidentWindows = incidentWindows.stream()
            .filter(window -> jfrWindowOverlaps(window, finalRequestedStart, finalRequestedEnd))
            .toList();
        List<Map<String, Object>> overlappingHighlights = chronologyHighlights.stream()
            .filter(highlight -> jfrHighlightWithinWindow(highlight, finalRequestedStart, finalRequestedEnd))
            .toList();

        if (!timelineEvents.isEmpty()) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.timeWindowSlice(
                availableStart,
                availableEnd,
                finalRequestedStart,
                finalRequestedEnd,
                timelineEvents,
                incidentWindows,
                chronologyHighlights
            );
            if (!focusedSlice.isEmpty()) {
                long startMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedStart).toMillis()) : 0L;
                long endMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedEnd).toMillis()) : startMs;
                return derivedResult(
                    indexedContext,
                    "jfr-time-window-" + sanitizeId(String.valueOf(startMs)) + "-" + sanitizeId(String.valueOf(endMs)),
                    String.format(Locale.ROOT, "JFR time-window context +%.3fs to +%.3fs", startMs / 1000.0d, endMs / 1000.0d),
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + extractedData.chronologyHighlights",
                    contentOffset,
                    contentChars,
                    focusedSlice.moreAvailable()
                );
            }
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedStart", finalRequestedStart.toString());
        payload.put("requestedEnd", finalRequestedEnd.toString());
        if (availableStart != null) {
            payload.put("availableStart", availableStart.toString());
            payload.put("requestedRelativeStartMs", Math.max(0L, java.time.Duration.between(availableStart, finalRequestedStart).toMillis()));
            payload.put("requestedRelativeEndMs", Math.max(0L, java.time.Duration.between(availableStart, finalRequestedEnd).toMillis()));
        }
        if (availableEnd != null) {
            payload.put("availableEnd", availableEnd.toString());
        }
        if (summary.containsKey("durationMs")) {
            payload.put("durationMs", summary.get("durationMs"));
        }
        payload.put("chronologyHighlights", overlappingHighlights);
        payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
        if (overlappingIncidentWindows.isEmpty() && overlappingHighlights.isEmpty()) {
            payload.put("note", "No compact JFR incident window or chronology highlight overlapped the requested time range.");
        }

        long startMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedStart).toMillis()) : 0L;
        long endMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedEnd).toMillis()) : startMs;
        return derivedResult(
            indexedContext,
            "jfr-time-window-" + sanitizeId(String.valueOf(startMs)) + "-" + sanitizeId(String.valueOf(endMs)),
            String.format(Locale.ROOT, "JFR time-window context +%.3fs to +%.3fs", startMs / 1000.0d, endMs / 1000.0d),
            payload,
            "extractedData.incidentWindows + extractedData.chronologyHighlights",
            contentOffset,
            contentChars,
            false
        );
    }

    private boolean jfrWindowOverlaps(Map<String, Object> window, Instant requestedStart, Instant requestedEnd) {
        Instant startTime = instantValue(window.get("startTime"));
        Instant endTime = instantValue(window.get("endTime"));
        if (startTime == null || endTime == null) {
            return false;
        }
        return !startTime.isAfter(requestedEnd) && !endTime.isBefore(requestedStart);
    }

    private boolean jfrHighlightWithinWindow(Map<String, Object> highlight, Instant requestedStart, Instant requestedEnd) {
        Instant startTime = instantValue(highlight.get("startTime"));
        Instant endTime = instantValue(highlight.get("endTime"));
        if (startTime == null) {
            return false;
        }
        Instant effectiveEnd = endTime != null ? endTime : startTime;
        return !startTime.isAfter(requestedEnd) && !effectiveEnd.isBefore(requestedStart);
    }

    private Instant resolveJfrTimeToken(String token, Instant recordingStart) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim();
        try {
            return Instant.parse(normalized);
        } catch (RuntimeException ignored) {
        }
        if (recordingStart == null) {
            return null;
        }
        if (normalized.endsWith("ms")) {
            try {
                long millis = Long.parseLong(normalized.substring(0, normalized.length() - 2).trim());
                return recordingStart.plusMillis(Math.max(0L, millis));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (normalized.endsWith("s")) {
            try {
                double seconds = Double.parseDouble(normalized.substring(0, normalized.length() - 1).trim());
                return recordingStart.plusMillis(Math.max(0L, Math.round(seconds * 1000.0d)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            double seconds = Double.parseDouble(normalized);
            return recordingStart.plusMillis(Math.max(0L, Math.round(seconds * 1000.0d)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Instant instantValue(Object value) {
        String text = stringValue(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private DiagnosticToolResult jfrExactEventTypeDetail(
        ParsedArtifact parsedArtifact,
        String eventType,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        List<Map<String, Object>> eventTypeDetails = listOfMaps(parsedArtifact.extractedData().get("eventTypeDetails"));
        if (eventTypeDetails.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalizeEventTypeToken(eventType);
        if (normalizedQuery.isBlank()) {
            return null;
        }

        List<Map<String, Object>> matches = eventTypeDetails.stream()
            .filter(detail -> matchesExactEventTypeDetail(detail, normalizedQuery))
            .toList();
        if (matches.size() != 1) {
            return null;
        }

        Map<String, Object> matchedDetail = matches.getFirst();
        String eventTypeName = stringValue(matchedDetail.get("name"));
        String eventTypeLabel = firstNonBlank(
            stringValue(matchedDetail.get("label")),
            eventTypeName,
            eventType
        );

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventTypeDetail", matchedDetail);
        Map<String, Object> declaredMetadata = declaredEventTypeDetail(parsedArtifact, eventTypeName);
        if (!declaredMetadata.isEmpty()) {
            payload.put("declaredMetadata", declaredMetadata);
        }

        return derivedResult(
            indexedContext,
            "jfr-event-detail-" + sanitizeId(eventTypeName != null ? eventTypeName : eventType),
            "JFR event detail for " + eventTypeLabel,
            payload,
            "extractedData.eventTypeDetails + extractedData.declaredEventTypes",
            contentOffset,
            contentChars,
            false
        );
    }

    private boolean matchesExactEventTypeDetail(Map<String, Object> detail, String normalizedQuery) {
        String name = stringValue(detail.get("name"));
        String label = stringValue(detail.get("label"));
        return normalizedQuery.equals(normalizeEventTypeToken(name))
            || normalizedQuery.equals(normalizeEventTypeToken(label))
            || normalizedQuery.equals(normalizeEventTypeToken(simpleEventTypeName(name)));
    }

    private Map<String, Object> declaredEventTypeDetail(ParsedArtifact parsedArtifact, String eventTypeName) {
        if (eventTypeName == null || eventTypeName.isBlank()) {
            return Map.of();
        }
        return listOfMaps(parsedArtifact.extractedData().get("declaredEventTypes")).stream()
            .filter(item -> eventTypeName.equals(stringValue(item.get("name"))))
            .findFirst()
            .map(Map::copyOf)
            .orElse(Map.of());
    }

    private String normalizeEventTypeToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private String simpleEventTypeName(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int separator = value.lastIndexOf('.');
        return separator >= 0 && separator + 1 < value.length()
            ? value.substring(separator + 1)
            : value;
    }

    private DiagnosticToolResult jfrByThread(
        ParsedArtifact parsedArtifact,
        String threadName,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        if (timelineEvents.isEmpty()) {
            return null;
        }

        JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.threadSlice(
            threadName,
            timelineEvents,
            incidentWindows
        );
        if (focusedSlice.isEmpty()) {
            return null;
        }

        return derivedResult(
            indexedContext,
            "jfr-thread-" + sanitizeId(threadName),
            "JFR thread context for " + threadName,
            focusedSlice.payload(),
            "extractedData.timelineEvents + extractedData.incidentWindows",
            contentOffset,
            contentChars,
            focusedSlice.moreAvailable()
        );
    }

    private DiagnosticToolResult jfrByHotspot(
        ParsedArtifact parsedArtifact,
        String hotspotKey,
        IndexedArtifactDiagnosticContext indexedContext,
        Integer contentOffset,
        Integer contentChars
    ) {
        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        Map<String, Object> executionHotspotSummary = mapValue(parsedArtifact.extractedData().get("executionHotspotSummary"));
        Map<String, Object> runtimeHotspotSummary = mapValue(parsedArtifact.extractedData().get("runtimeHotspotSummary"));
        Map<String, Object> allocationHotspotSummary = mapValue(parsedArtifact.extractedData().get("allocationHotspotSummary"));
        if (!timelineEvents.isEmpty()) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.hotspotSlice(
                hotspotKey,
                timelineEvents,
                incidentWindows,
                executionHotspotSummary,
                runtimeHotspotSummary,
                allocationHotspotSummary
            );
            if (!focusedSlice.isEmpty()) {
                return derivedResult(
                    indexedContext,
                    "jfr-hotspot-" + sanitizeId(hotspotKey),
                    "JFR hotspot context for " + hotspotKey,
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + hotspot summaries",
                    contentOffset,
                    contentChars,
                    focusedSlice.moreAvailable()
                );
            }
        }

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
        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("allocationFieldSummary"));
        if (!timelineEvents.isEmpty()) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.allocationSlice(
                allocationClass,
                timelineEvents,
                incidentWindows,
                summary
            );
            if (!focusedSlice.isEmpty()) {
                return derivedResult(
                    indexedContext,
                    "jfr-allocation-class-" + sanitizeId(allocationClass),
                    "JFR allocation-path context for " + allocationClass,
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.allocationFieldSummary + extractedData.incidentWindows",
                    contentOffset,
                    contentChars,
                    focusedSlice.moreAvailable()
                );
            }
        }

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
        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(parsedArtifact);
        List<Map<String, Object>> incidentWindows = listOfMaps(parsedArtifact.extractedData().get("incidentWindows"));
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("oldObjectFieldSummary"));
        if (!timelineEvents.isEmpty()) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.oldObjectSlice(
                oldObjectFocus,
                timelineEvents,
                incidentWindows,
                summary
            );
            if (!focusedSlice.isEmpty()) {
                return derivedResult(
                    indexedContext,
                    "jfr-old-object-" + sanitizeId(oldObjectFocus),
                    "JFR retained-object context for " + oldObjectFocus,
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.oldObjectFieldSummary + extractedData.incidentWindows",
                    contentOffset,
                    contentChars,
                    focusedSlice.moreAvailable()
                );
            }
        }

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
            if (entry.getValue().toLowerCase(Locale.ROOT).contains(normalized)) {
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

    private DiagnosticToolResult gcEventWindowsResult(
        IndexedArtifactDiagnosticContext indexedContext,
        List<Map<String, Object>> events,
        String label,
        String sliceId,
        Integer contentOffset,
        Integer contentChars
    ) {
        return gcEventWindowsResult(indexedContext, events, label, sliceId, contentOffset, contentChars, true);
    }

    private DiagnosticToolResult gcEventWindowsResult(
        IndexedArtifactDiagnosticContext indexedContext,
        List<Map<String, Object>> events,
        String label,
        String sliceId,
        Integer contentOffset,
        Integer contentChars,
        boolean sortChronologically
    ) {
        if (indexedContext.rawLines().isEmpty() || events.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> sortedEvents = new ArrayList<>(events);
        if (sortChronologically) {
            sortedEvents.sort(gcEventComparator());
        }

        StringBuilder builder = new StringBuilder();
        List<String> traceabilityRanges = new ArrayList<>();
        int windowsAdded = 0;
        for (Map<String, Object> event : sortedEvents) {
            IndexedArtifactDiagnosticContext.LineRange range = gcEventLineRange(indexedContext, event);
            String rangeKey = range.startLineInclusive() + "-" + range.endLineInclusive();
            if (traceabilityRanges.contains(rangeKey)) {
                continue;
            }
            traceabilityRanges.add(rangeKey);
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("Window ").append(windowsAdded + 1).append(" around ");
            long gcId = longValue(event.get("gcId"));
            if (gcId > 0L) {
                builder.append("GC(").append(gcId).append(')');
            } else {
                builder.append("matching line ").append(longValue(event.get("lineNumber")));
            }
            long lineNumber = longValue(event.get("lineNumber"));
            if (lineNumber > 0L) {
                builder.append(" line ").append(lineNumber);
            }
            builder.append(":\n");
            builder.append(renderRawLines(indexedContext.rawLines(), range.startLineInclusive(), range.endLineInclusive()));
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
            events.size() > windowsAdded
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
            "Curated retrieval",
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

    private boolean matchesText(Object value, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return stringValue(value).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private boolean matchesGcId(Map<String, Object> event, String gcId) {
        if (gcId == null || gcId.isBlank()) {
            return true;
        }
        return stringValue(event.get("gcId")).equals(gcId.trim());
    }

    private IndexedArtifactDiagnosticContext.LineRange gcEventLineRange(
        IndexedArtifactDiagnosticContext indexedContext,
        Map<String, Object> event
    ) {
        int lineNumber = (int) Math.max(1L, longValue(event.get("lineNumber")));
        int start = Math.max(1, lineNumber - GC_EVENT_WINDOW_PADDING);
        int end = Math.min(indexedContext.rawLines().size(), lineNumber + MAX_SEARCH_WINDOW_LINES - 1);
        long gcId = longValue(event.get("gcId"));
        if (gcId <= 0L) {
            return new IndexedArtifactDiagnosticContext.LineRange(start, end);
        }

        String gcIdToken = "GC(" + gcId + ")";
        int searchStart = Math.max(1, lineNumber - MAX_SEARCH_WINDOW_LINES);
        int searchEnd = Math.min(indexedContext.rawLines().size(), lineNumber + MAX_SEARCH_WINDOW_LINES);
        int firstMatch = lineNumber;
        int lastMatch = lineNumber;
        for (int index = searchStart; index <= searchEnd; index++) {
            if (indexedContext.rawLines().get(index - 1).contains(gcIdToken)) {
                firstMatch = Math.min(firstMatch, index);
                lastMatch = Math.max(lastMatch, index);
            }
        }
        return new IndexedArtifactDiagnosticContext.LineRange(
            Math.max(1, Math.min(start, firstMatch - GC_EVENT_WINDOW_PADDING)),
            Math.min(indexedContext.rawLines().size(), Math.max(end, lastMatch + GC_EVENT_WINDOW_PADDING))
        );
    }

    private Comparator<Map<String, Object>> gcEventComparator() {
        return Comparator
            .comparingLong((Map<String, Object> event) -> {
                long lineNumber = longValue(event.get("lineNumber"));
                return lineNumber > 0L ? lineNumber : Long.MAX_VALUE;
            })
            .thenComparingDouble(event -> {
                Object elapsedSeconds = event.get("elapsedSeconds");
                return elapsedSeconds instanceof Number number ? number.doubleValue() : Double.MAX_VALUE;
            });
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private GcWindowSelection selectGcTimeWindow(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector
    ) {
        List<Map<String, Object>> timelineEvents = gcTimelineEvents(indexedContext);
        if (timelineEvents.isEmpty()) {
            return null;
        }

        Map<String, Object> anchorEvent = selector.gcId() != null ? anchorEventForGcId(timelineEvents, selector.gcId()) : null;
        Double startSeconds = resolveGcTimeToken(timelineEvents, selector.timestampStart(), true);
        Double endSeconds = resolveGcTimeToken(timelineEvents, selector.timestampEnd(), false);
        Double requestedWindowSeconds = selector.windowSeconds() != null && selector.windowSeconds() > 0
            ? selector.windowSeconds().doubleValue()
            : null;

        if (startSeconds == null && endSeconds == null && requestedWindowSeconds != null && hasElapsedSeconds(anchorEvent)) {
            double anchorSeconds = doubleValue(anchorEvent.get("elapsedSeconds"));
            startSeconds = Math.max(0.0d, anchorSeconds - (requestedWindowSeconds / 2.0d));
            endSeconds = anchorSeconds + (requestedWindowSeconds / 2.0d);
        } else if (requestedWindowSeconds != null) {
            if (startSeconds != null && endSeconds == null) {
                endSeconds = startSeconds + requestedWindowSeconds;
            } else if (endSeconds != null && startSeconds == null) {
                startSeconds = Math.max(0.0d, endSeconds - requestedWindowSeconds);
            }
        }

        if (startSeconds == null && endSeconds != null) {
            startSeconds = Math.max(0.0d, endSeconds - (requestedWindowSeconds != null ? requestedWindowSeconds : GC_DEFAULT_WINDOW_SECONDS));
        } else if (startSeconds != null && endSeconds == null) {
            endSeconds = startSeconds + (requestedWindowSeconds != null ? requestedWindowSeconds : GC_DEFAULT_WINDOW_SECONDS);
        }

        if (startSeconds != null && endSeconds != null && startSeconds > endSeconds) {
            double swap = startSeconds;
            startSeconds = endSeconds;
            endSeconds = swap;
        }

        if (startSeconds == null || endSeconds == null) {
            return null;
        }

        double windowStart = startSeconds;
        double windowEnd = endSeconds;
        List<Map<String, Object>> matchedEvents = timelineEvents.stream()
            .filter(this::hasElapsedSeconds)
            .filter(event -> {
                double elapsedSeconds = doubleValue(event.get("elapsedSeconds"));
                return elapsedSeconds >= windowStart && elapsedSeconds <= windowEnd;
            })
            .toList();
        if (matchedEvents.isEmpty()) {
            if (!hasElapsedSeconds(anchorEvent)) {
                return null;
            }
            matchedEvents = List.of(anchorEvent);
        }

        IndexedArtifactDiagnosticContext.LineRange lineRange = gcLineRangeForEvents(indexedContext, matchedEvents);
        String windowLabel = gcTimeWindowLabel(selector, windowStart, windowEnd);
        String windowSliceId = gcTimeWindowSliceId(selector, windowStart, windowEnd);
        return new GcWindowSelection(windowSliceId, windowLabel, lineRange.startLineInclusive(), lineRange.endLineInclusive());
    }

    private List<Map<String, Object>> gcTimelineEvents(IndexedArtifactDiagnosticContext indexedContext) {
        if (indexedContext == null || indexedContext.parsedArtifact() == null) {
            return List.of();
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (String key : List.of(
            "pauses",
            "gcCycles",
            "allocationStalls",
            "phaseSamples",
            "failureSignals",
            "workerSamples",
            "cpuSamples",
            "humongousRegionSamples"
        )) {
            events.addAll(listOfMaps(indexedContext.parsedArtifact().extractedData().get(key)));
        }
        events.sort(gcEventComparator());
        return List.copyOf(events);
    }

    private Map<String, Object> anchorEventForGcId(List<Map<String, Object>> events, String gcId) {
        if (events == null || events.isEmpty() || gcId == null || gcId.isBlank()) {
            return null;
        }
        for (Map<String, Object> event : events) {
            if (matchesGcId(event, gcId)) {
                return event;
            }
        }
        return null;
    }

    private Double resolveGcTimeToken(List<Map<String, Object>> events, String token, boolean preferFirstMatch) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Double explicitSeconds = parseSecondsToken(token);
        if (explicitSeconds != null) {
            return explicitSeconds;
        }

        Map<String, Object> matchedEvent = null;
        List<Map<String, Object>> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(gcEventComparator());
        if (!preferFirstMatch) {
            Collections.reverse(orderedEvents);
        }
        for (Map<String, Object> event : orderedEvents) {
            if (matchesTimeToken(event, token) && hasElapsedSeconds(event)) {
                matchedEvent = event;
                break;
            }
        }
        return matchedEvent != null ? doubleValue(matchedEvent.get("elapsedSeconds")) : null;
    }

    private Double parseSecondsToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("\\d+(?:\\.\\d+)?s?")) {
            return null;
        }
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean matchesTimeToken(Map<String, Object> event, String token) {
        if (event == null || token == null || token.isBlank()) {
            return false;
        }
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        return stringValue(event.get("absoluteTimestamp")).toLowerCase(Locale.ROOT).contains(normalizedToken)
            || stringValue(event.get("rawLine")).toLowerCase(Locale.ROOT).contains(normalizedToken);
    }

    private boolean hasElapsedSeconds(Map<String, Object> event) {
        return event != null && event.containsKey("elapsedSeconds");
    }

    private List<Map<String, Object>> gcDistressEvents(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> distressEvents = new ArrayList<>();
        for (Map<String, Object> pause : pauses) {
            if (isFullGcPause(pause) || isEvacuationFailurePause(pause)) {
                distressEvents.add(pause);
            }
        }
        distressEvents.addAll(failureSignals);
        distressEvents.sort(gcEventComparator());
        return List.copyOf(distressEvents);
    }

    private List<Map<String, Object>> bestGcEventCluster(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(gcEventComparator());
        List<Map<String, Object>> bestCluster = List.of();
        List<Map<String, Object>> currentCluster = new ArrayList<>();
        Map<String, Object> previous = null;
        for (Map<String, Object> event : orderedEvents) {
            if (currentCluster.isEmpty() || withinGcStreakGap(previous, event)) {
                currentCluster.add(event);
            } else {
                if (isBetterGcCluster(currentCluster, bestCluster)) {
                    bestCluster = List.copyOf(currentCluster);
                }
                currentCluster = new ArrayList<>();
                currentCluster.add(event);
            }
            previous = event;
        }
        if (isBetterGcCluster(currentCluster, bestCluster)) {
            bestCluster = List.copyOf(currentCluster);
        }
        return bestCluster;
    }

    private boolean withinGcStreakGap(Map<String, Object> previous, Map<String, Object> current) {
        if (previous == null || current == null) {
            return false;
        }
        if (hasElapsedSeconds(previous) && hasElapsedSeconds(current)) {
            double gapSeconds = Math.abs(doubleValue(current.get("elapsedSeconds")) - doubleValue(previous.get("elapsedSeconds")));
            if (gapSeconds <= GC_STREAK_MAX_GAP_SECONDS) {
                return true;
            }
        }
        long lineGap = Math.abs(longValue(current.get("lineNumber")) - longValue(previous.get("lineNumber")));
        return lineGap <= GC_STREAK_MAX_GAP_LINES;
    }

    private boolean isBetterGcCluster(List<Map<String, Object>> currentCluster, List<Map<String, Object>> bestCluster) {
        if (currentCluster == null || currentCluster.isEmpty()) {
            return false;
        }
        if (bestCluster == null || bestCluster.isEmpty()) {
            return true;
        }
        if (currentCluster.size() != bestCluster.size()) {
            return currentCluster.size() > bestCluster.size();
        }
        double currentWeight = gcClusterWeight(currentCluster);
        double bestWeight = gcClusterWeight(bestCluster);
        if (Double.compare(currentWeight, bestWeight) != 0) {
            return currentWeight > bestWeight;
        }
        return gcClusterSpanLines(currentCluster) < gcClusterSpanLines(bestCluster);
    }

    private double gcClusterWeight(List<Map<String, Object>> cluster) {
        double weight = 0.0d;
        for (Map<String, Object> event : cluster) {
            weight += gcEventWeight(event);
        }
        return weight;
    }

    private List<Map<String, Object>> representativeGcClusterEvents(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> orderedCluster = new ArrayList<>(cluster);
        orderedCluster.sort(gcEventComparator());
        Map<String, Object> first = orderedCluster.getFirst();
        Map<String, Object> heaviest = orderedCluster.stream()
            .max(Comparator.comparingDouble(this::gcEventWeight).thenComparing(gcEventComparator()))
            .orElse(first);
        Map<String, Object> last = orderedCluster.getLast();

        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        addClusterRepresentative(unique, heaviest);
        addClusterRepresentative(unique, first);
        addClusterRepresentative(unique, last);
        return List.copyOf(unique.values());
    }

    private void addClusterRepresentative(
        LinkedHashMap<String, Map<String, Object>> representatives,
        Map<String, Object> event
    ) {
        if (event == null) {
            return;
        }
        String key = longValue(event.get("lineNumber")) + ":" + longValue(event.get("gcId")) + ":" + stringValue(event.get("signalType"));
        representatives.putIfAbsent(key, event);
    }

    private double gcEventWeight(Map<String, Object> event) {
        if (event == null) {
            return 0.0d;
        }
        double pauseMs = doubleValue(event.get("pauseMs"));
        double durationMs = doubleValue(event.get("durationMs"));
        return Math.max(1.0d, Math.max(pauseMs, durationMs));
    }

    private long gcClusterSpanLines(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long startLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).min().orElse(0L);
        long endLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).max().orElse(0L);
        return endLine >= startLine ? endLine - startLine : Long.MAX_VALUE;
    }

    private IndexedArtifactDiagnosticContext.LineRange gcLineRangeForEvents(
        IndexedArtifactDiagnosticContext indexedContext,
        List<Map<String, Object>> events
    ) {
        int startLine = Integer.MAX_VALUE;
        int endLine = 0;
        for (Map<String, Object> event : events) {
            IndexedArtifactDiagnosticContext.LineRange eventRange = gcEventLineRange(indexedContext, event);
            startLine = Math.min(startLine, eventRange.startLineInclusive());
            endLine = Math.max(endLine, eventRange.endLineInclusive());
        }
        if (startLine == Integer.MAX_VALUE || endLine <= 0) {
            return new IndexedArtifactDiagnosticContext.LineRange(1, Math.min(indexedContext.rawLines().size(), MAX_SEARCH_WINDOW_LINES));
        }
        return new IndexedArtifactDiagnosticContext.LineRange(startLine, endLine);
    }

    private boolean isFullGcPause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("full");
    }

    private boolean isEvacuationFailurePause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("evacuation failure");
    }

    private String normalizeStreakKind(String streakKind) {
        if (streakKind == null || streakKind.isBlank()) {
            return "";
        }
        return streakKind.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private String gcStreakLabel(String streakKind) {
        return switch (normalizeStreakKind(streakKind)) {
            case "full-gc" -> "GC full-GC streak context";
            case "evacuation-failure" -> "GC evacuation-failure streak context";
            case "failure", "failure-signal" -> "GC failure-signal streak context";
            case "distress" -> "GC distress streak context";
            default -> "GC streak context";
        };
    }

    private String gcStreakSliceId(String streakKind) {
        String normalized = normalizeStreakKind(streakKind);
        return normalized.isBlank() ? "gc-streak-context" : "gc-streak-" + sanitizeId(normalized);
    }

    private String gcTimeWindowLabel(ContextSelector selector, double startSeconds, double endSeconds) {
        if (selector.gcId() != null && selector.windowSeconds() != null) {
            return "GC time-window context around GC(" + selector.gcId() + ")";
        }
        return "GC time-window context " + formatSeconds(startSeconds) + "s to " + formatSeconds(endSeconds) + "s";
    }

    private String gcTimeWindowSliceId(ContextSelector selector, double startSeconds, double endSeconds) {
        if (selector.gcId() != null && selector.windowSeconds() != null) {
            return "gc-time-window-gc-" + sanitizeId(selector.gcId()) + "-" + sanitizeId(String.valueOf(selector.windowSeconds()));
        }
        return "gc-time-window-" + sanitizeId(formatSeconds(startSeconds)) + "-" + sanitizeId(formatSeconds(endSeconds));
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private String gcPauseLabel(ContextSelector selector) {
        StringBuilder builder = new StringBuilder("GC pause context");
        if (selector.cause() != null) {
            builder.append(" for cause ").append(selector.cause());
        }
        if (selector.pauseType() != null) {
            builder.append(selector.cause() != null ? " and" : " for").append(" pause type ").append(selector.pauseType());
        }
        if (selector.gcId() != null) {
            builder.append(" in GC(").append(selector.gcId()).append(')');
        }
        return builder.toString();
    }

    private String gcPauseSliceId(ContextSelector selector) {
        if (selector.cause() != null) {
            return "gc-cause-" + sanitizeId(selector.cause()) + (selector.gcId() != null ? "-gc-" + sanitizeId(selector.gcId()) : "");
        }
        if (selector.pauseType() != null) {
            return "gc-pause-type-" + sanitizeId(selector.pauseType()) + (selector.gcId() != null ? "-gc-" + sanitizeId(selector.gcId()) : "");
        }
        return "gc-pause-context";
    }

    private String gcPhaseLabel(ContextSelector selector) {
        StringBuilder builder = new StringBuilder("GC phase context");
        if (selector.phase() != null) {
            builder.append(" for phase ").append(selector.phase());
        }
        if (selector.phaseKind() != null) {
            builder.append(selector.phase() != null ? " and" : " for").append(" phase kind ").append(selector.phaseKind());
        }
        if (selector.gcId() != null) {
            builder.append(" in GC(").append(selector.gcId()).append(')');
        }
        return builder.toString();
    }

    private String gcPhaseSliceId(ContextSelector selector) {
        if (selector.phase() != null) {
            return "gc-phase-" + sanitizeId(selector.phase()) + (selector.gcId() != null ? "-gc-" + sanitizeId(selector.gcId()) : "");
        }
        if (selector.phaseKind() != null) {
            return "gc-phase-kind-" + sanitizeId(selector.phaseKind()) + (selector.gcId() != null ? "-gc-" + sanitizeId(selector.gcId()) : "");
        }
        return "gc-phase-context";
    }

    private String gcSignalLabel(ContextSelector selector) {
        StringBuilder builder = new StringBuilder("GC failure-signal context for ").append(selector.signalType());
        if (selector.gcId() != null) {
            builder.append(" in GC(").append(selector.gcId()).append(')');
        }
        return builder.toString();
    }

    private String gcSignalSliceId(ContextSelector selector) {
        return "gc-signal-" + sanitizeId(selector.signalType()) + (selector.gcId() != null ? "-gc-" + sanitizeId(selector.gcId()) : "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    private record GcWindowSelection(
        String sliceId,
        String label,
        int startLineInclusive,
        int endLineInclusive
    ) { }

    private record ContentPage(String content, String traceabilityRange, boolean truncated, boolean moreAvailable) { }
}
