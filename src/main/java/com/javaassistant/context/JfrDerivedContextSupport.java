package com.javaassistant.context;

import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

final class JfrDerivedContextSupport {

    private static final int MAX_TOP_ITEMS = 8;
    private static final int MAX_REPRESENTATIVE_EVENTS = 8;
    private static final int MAX_INCIDENT_WINDOWS = 4;

    private JfrDerivedContextSupport() {
    }

    static FocusedSlice timeWindowSlice(
        Instant availableStart,
        Instant availableEnd,
        Instant requestedStart,
        Instant requestedEnd,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows,
        List<Map<String, Object>> chronologyHighlights
    ) {
        if (requestedStart == null || requestedEnd == null) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> overlappingEvents = timelineEvents.stream()
            .filter(event -> overlaps(event, requestedStart, requestedEnd))
            .sorted(eventChronologyComparator())
            .toList();
        List<Map<String, Object>> overlappingIncidentWindows = incidentWindows.stream()
            .filter(window -> overlaps(window, requestedStart, requestedEnd))
            .limit(MAX_INCIDENT_WINDOWS)
            .toList();
        List<Map<String, Object>> overlappingHighlights = chronologyHighlights.stream()
            .filter(highlight -> overlaps(highlight, requestedStart, requestedEnd))
            .limit(MAX_TOP_ITEMS)
            .toList();

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedStart", requestedStart.toString());
        payload.put("requestedEnd", requestedEnd.toString());
        if (availableStart != null) {
            payload.put("availableStart", availableStart.toString());
            payload.put("requestedRelativeStartMs", Math.max(0L, Duration.between(availableStart, requestedStart).toMillis()));
            payload.put("requestedRelativeEndMs", Math.max(0L, Duration.between(availableStart, requestedEnd).toMillis()));
        }
        if (availableEnd != null) {
            payload.put("availableEnd", availableEnd.toString());
        }
        payload.put("eventCount", overlappingEvents.size());
        putIfPositiveLong(payload, "totalDurationMs", totalDurationMs(overlappingEvents));
        putIfPositiveLong(payload, "maxDurationMs", maxDurationMs(overlappingEvents));
        putIfPositiveLong(payload, "totalAllocatedBytes", totalAllocatedBytes(overlappingEvents));
        putIfPositiveLong(payload, "totalSampledObjectBytes", totalSampledObjectBytes(overlappingEvents));
        putIfPositiveLong(payload, "maxReferenceDepth", maxReferenceDepth(overlappingEvents));
        putIfPositiveLong(payload, "maxObjectAgeMs", maxObjectAgeMs(overlappingEvents));

        List<Map<String, Object>> signalFamilies = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("signalFamily")),
            event -> humanSignalFamily(stringValue(event.get("signalFamily"))),
            "signalFamily"
        );
        if (!signalFamilies.isEmpty()) {
            payload.put("signalFamilies", signalFamilies);
        }
        if (!overlappingEvents.isEmpty()) {
            payload.put("representativeEvents", representativeEvents(overlappingEvents));
        }
        payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
        payload.put("chronologyHighlights", overlappingHighlights);

        List<Map<String, Object>> topEventTypes = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("eventType")),
            event -> stringValue(event.get("label")),
            "eventType"
        );
        if (!topEventTypes.isEmpty()) {
            payload.put("topEventTypes", topEventTypes);
        }

        List<Map<String, Object>> topMethods = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("topMethod")),
            null,
            "method"
        );
        if (!topMethods.isEmpty()) {
            payload.put("topMethods", topMethods);
        }

        List<Map<String, Object>> topThreads = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("eventThread")),
            null,
            "thread"
        );
        if (!topThreads.isEmpty()) {
            payload.put("topThreads", topThreads);
        }

        List<Map<String, Object>> topClasses = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("className")),
            null,
            "className"
        );
        if (!topClasses.isEmpty()) {
            payload.put("topClasses", topClasses);
        }

        List<Map<String, Object>> topAllocators = rankedMetrics(
            overlappingEvents,
            event -> stringValue(event.get("allocator")),
            null,
            "allocator"
        );
        if (!topAllocators.isEmpty()) {
            payload.put("topAllocators", topAllocators);
        }

        List<Map<String, Object>> topRoots = rankedMetrics(
            overlappingEvents,
            JfrDerivedContextSupport::rootToken,
            null,
            "root"
        );
        if (!topRoots.isEmpty()) {
            payload.put("topRoots", topRoots);
        }
        if (overlappingEvents.isEmpty() && overlappingIncidentWindows.isEmpty() && overlappingHighlights.isEmpty()) {
            payload.put("note", "No derived JFR timeline events, incident windows, or chronology highlights overlapped the requested time range.");
        }

        boolean moreAvailable = overlappingEvents.size() > MAX_REPRESENTATIVE_EVENTS
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("signalFamily")))
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("eventType")))
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("topMethod")))
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("eventThread")))
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("className")))
            || hasMoreRankedItems(overlappingEvents, event -> stringValue(event.get("allocator")))
            || hasMoreRankedItems(overlappingEvents, JfrDerivedContextSupport::rootToken)
            || incidentWindows.size() > overlappingIncidentWindows.size()
            || chronologyHighlights.size() > overlappingHighlights.size();
        return new FocusedSlice(immutableOrderedMap(payload), moreAvailable);
    }

    static FocusedSlice eventTypeSlice(
        String eventTypeQuery,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows,
        List<Map<String, Object>> observedEventTypes,
        List<Map<String, Object>> declaredEventTypes
    ) {
        String normalizedQuery = normalizeCompactToken(eventTypeQuery);
        if (normalizedQuery.isBlank()) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> matchingEvents = timelineEvents.stream()
            .filter(event -> matchesEventTypeOrFamily(event, normalizedQuery))
            .sorted(eventImportanceComparator())
            .toList();
        List<Map<String, Object>> matchingObservedEventTypes = matchingCatalogEntries(observedEventTypes, normalizedQuery);
        List<Map<String, Object>> matchingDeclaredEventTypes = matchingCatalogEntries(declaredEventTypes, normalizedQuery);
        if (matchingEvents.isEmpty()) {
            return FocusedSlice.empty();
        }

        LinkedHashMap<String, Object> payload = baseFocusedPayload("eventTypeQuery", eventTypeQuery, matchingEvents);
        if (!matchingEvents.isEmpty()) {
            payload.put("representativeEvents", representativeEvents(matchingEvents));
            putIfNotEmpty(payload, "signalFamilies", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("signalFamily")),
                event -> humanSignalFamily(stringValue(event.get("signalFamily"))),
                "signalFamily"
            ));
            putIfNotEmpty(payload, "matchingEventTypes", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("eventType")),
                event -> stringValue(event.get("label")),
                "eventType"
            ));
            putIfNotEmpty(payload, "topThreads", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("eventThread")),
                null,
                "thread"
            ));
            putIfNotEmpty(payload, "topMethods", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("topMethod")),
                null,
                "method"
            ));
            putIfNotEmpty(payload, "topStacks", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("topStack")),
                null,
                "stack"
            ));
            putIfNotEmpty(payload, "topClasses", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("className")),
                null,
                "className"
            ));
            putIfNotEmpty(payload, "topRoots", rankedMetrics(
                matchingEvents,
                JfrDerivedContextSupport::rootToken,
                null,
                "root"
            ));
            List<Map<String, Object>> overlappingIncidentWindows = overlappingIncidentWindows(matchingEvents, incidentWindows);
            if (!overlappingIncidentWindows.isEmpty()) {
                payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
            }
        }
        if (!matchingObservedEventTypes.isEmpty()) {
            payload.put("matchingCatalogEventTypes", matchingObservedEventTypes);
        }
        if (!matchingDeclaredEventTypes.isEmpty()) {
            payload.put("matchingDeclaredEventTypes", matchingDeclaredEventTypes);
        }

        return new FocusedSlice(
            immutableOrderedMap(payload),
            matchingEvents.size() > MAX_REPRESENTATIVE_EVENTS
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("signalFamily")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("eventType")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("eventThread")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topMethod")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topStack")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("className")))
                || hasMoreRankedItems(matchingEvents, JfrDerivedContextSupport::rootToken)
                || observedEventTypes.size() > matchingObservedEventTypes.size()
                || declaredEventTypes.size() > matchingDeclaredEventTypes.size()
        );
    }

    static FocusedSlice threadSlice(
        String threadQuery,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows
    ) {
        String normalizedQuery = normalizeCompactToken(threadQuery);
        if (normalizedQuery.isBlank()) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> matchingEvents = timelineEvents.stream()
            .filter(event -> containsCompactNormalized(stringValue(event.get("eventThread")), normalizedQuery))
            .sorted(eventImportanceComparator())
            .toList();
        if (matchingEvents.isEmpty()) {
            return FocusedSlice.empty();
        }

        LinkedHashMap<String, Object> payload = baseFocusedPayload("threadQuery", threadQuery, matchingEvents);
        putIfPositiveLong(payload, "totalAllocatedBytes", totalAllocatedBytes(matchingEvents));
        putIfPositiveLong(payload, "totalSampledObjectBytes", totalSampledObjectBytes(matchingEvents));
        putIfPositiveLong(payload, "maxReferenceDepth", maxReferenceDepth(matchingEvents));
        putIfPositiveLong(payload, "maxObjectAgeMs", maxObjectAgeMs(matchingEvents));
        payload.put("representativeEvents", representativeEvents(matchingEvents));
        putIfNotEmpty(payload, "matchingThreads", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("eventThread")),
            null,
            "thread"
        ));
        putIfNotEmpty(payload, "signalFamilies", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("signalFamily")),
            event -> humanSignalFamily(stringValue(event.get("signalFamily"))),
            "signalFamily"
        ));
        putIfNotEmpty(payload, "topEventTypes", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("eventType")),
            event -> stringValue(event.get("label")),
            "eventType"
        ));
        putIfNotEmpty(payload, "topMethods", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("topMethod")),
            null,
            "method"
        ));
        putIfNotEmpty(payload, "topStacks", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("topStack")),
            null,
            "stack"
        ));
        putIfNotEmpty(payload, "topClasses", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("className")),
            null,
            "className"
        ));
        putIfNotEmpty(payload, "topAllocators", rankedMetrics(
            matchingEvents,
            event -> stringValue(event.get("allocator")),
            null,
            "allocator"
        ));
        putIfNotEmpty(payload, "topRoots", rankedMetrics(
            matchingEvents,
            JfrDerivedContextSupport::rootToken,
            null,
            "root"
        ));
        List<Map<String, Object>> overlappingIncidentWindows = overlappingIncidentWindows(matchingEvents, incidentWindows);
        if (!overlappingIncidentWindows.isEmpty()) {
            payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
        }

        return new FocusedSlice(
            immutableOrderedMap(payload),
            matchingEvents.size() > MAX_REPRESENTATIVE_EVENTS
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("signalFamily")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("eventType")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topMethod")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topStack")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("className")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("allocator")))
                || hasMoreRankedItems(matchingEvents, JfrDerivedContextSupport::rootToken)
        );
    }

    static FocusedSlice hotspotSlice(
        String hotspotQuery,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows,
        Map<String, Object> executionHotspotSummary,
        Map<String, Object> runtimeHotspotSummary,
        Map<String, Object> allocationHotspotSummary
    ) {
        String normalizedQuery = normalizeTextToken(hotspotQuery);
        if (normalizedQuery.isBlank()) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> matchingEvents = timelineEvents.stream()
            .filter(event -> containsNormalized(stringValue(event.get("topMethod")), normalizedQuery)
                || containsNormalized(stringValue(event.get("topStack")), normalizedQuery))
            .sorted(eventImportanceComparator())
            .toList();
        if (matchingEvents.isEmpty()
            && !containsText(executionHotspotSummary, normalizedQuery)
            && !containsText(runtimeHotspotSummary, normalizedQuery)
            && !containsText(allocationHotspotSummary, normalizedQuery)) {
            return FocusedSlice.empty();
        }

        LinkedHashMap<String, Object> payload = baseFocusedPayload("hotspotQuery", hotspotQuery, matchingEvents);
        if (!matchingEvents.isEmpty()) {
            putIfNotEmpty(payload, "signalFamilies", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("signalFamily")),
                event -> humanSignalFamily(stringValue(event.get("signalFamily"))),
                "signalFamily"
            ));
            putIfNotEmpty(payload, "topEventTypes", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("eventType")),
                event -> stringValue(event.get("label")),
                "eventType"
            ));
            putIfNotEmpty(payload, "topThreads", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("eventThread")),
                null,
                "thread"
            ));
            putIfNotEmpty(payload, "topClasses", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("className")),
                null,
                "className"
            ));
            putIfNotEmpty(payload, "topAllocators", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("allocator")),
                null,
                "allocator"
            ));
            putIfNotEmpty(payload, "topStacks", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("topStack")),
                null,
                "stack"
            ));
            payload.put("representativeEvents", representativeEvents(matchingEvents));
            List<Map<String, Object>> overlappingIncidentWindows = overlappingIncidentWindows(matchingEvents, incidentWindows);
            if (!overlappingIncidentWindows.isEmpty()) {
                payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
            }
        }

        List<Map<String, Object>> matchingHotspotSummaries = new ArrayList<>();
        addMatchingHotspotSummary(matchingHotspotSummaries, "execution", executionHotspotSummary, normalizedQuery);
        addMatchingHotspotSummary(matchingHotspotSummaries, "runtime", runtimeHotspotSummary, normalizedQuery);
        addMatchingHotspotSummary(matchingHotspotSummaries, "allocation", allocationHotspotSummary, normalizedQuery);
        if (!matchingHotspotSummaries.isEmpty()) {
            payload.put("matchingHotspotSummaries", List.copyOf(matchingHotspotSummaries));
        }

        return new FocusedSlice(
            immutableOrderedMap(payload),
            matchingEvents.size() > MAX_REPRESENTATIVE_EVENTS
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("eventType")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("eventThread")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("className")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topStack")))
        );
    }

    static FocusedSlice allocationSlice(
        String allocationClassQuery,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows,
        Map<String, Object> allocationFieldSummary
    ) {
        String normalizedQuery = normalizeTextToken(allocationClassQuery);
        if (normalizedQuery.isBlank()) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> matchingEvents = timelineEvents.stream()
            .filter(event -> "allocation".equals(stringValue(event.get("signalFamily"))))
            .filter(event -> containsNormalized(stringValue(event.get("className")), normalizedQuery))
            .sorted(eventImportanceComparator())
            .toList();
        if (matchingEvents.isEmpty() && !containsText(allocationFieldSummary, normalizedQuery)) {
            return FocusedSlice.empty();
        }

        LinkedHashMap<String, Object> payload = baseFocusedPayload("allocationClassQuery", allocationClassQuery, matchingEvents);
        putIfPositiveLong(payload, "totalAllocatedBytes", totalAllocatedBytes(matchingEvents));
        putIfPositiveLong(payload, "maxAllocatedBytes", maxAllocatedBytes(matchingEvents));
        if (!matchingEvents.isEmpty()) {
            putIfNotEmpty(payload, "matchingClasses", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("className")),
                null,
                "className"
            ));
            putIfNotEmpty(payload, "eventTypes", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("eventType")),
                event -> stringValue(event.get("label")),
                "eventType"
            ));
            putIfNotEmpty(payload, "topMethods", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("topMethod")),
                null,
                "method"
            ));
            putIfNotEmpty(payload, "topStacks", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("topStack")),
                null,
                "stack"
            ));
            putIfNotEmpty(payload, "topAllocators", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("allocator")),
                null,
                "allocator"
            ));
            payload.put("representativeEvents", representativeEvents(matchingEvents));
            List<Map<String, Object>> overlappingIncidentWindows = overlappingIncidentWindows(matchingEvents, incidentWindows);
            if (!overlappingIncidentWindows.isEmpty()) {
                payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
            }
        }

        List<Map<String, Object>> topAllocatingClasses = listOfMaps(allocationFieldSummary.get("topAllocatingClasses")).stream()
            .filter(item -> containsNormalized(stringValue(item.get("className")), normalizedQuery))
            .limit(MAX_TOP_ITEMS)
            .toList();
        if (!topAllocatingClasses.isEmpty()) {
            payload.put("matchingSummaryClasses", topAllocatingClasses);
        }

        return new FocusedSlice(
            immutableOrderedMap(payload),
            matchingEvents.size() > MAX_REPRESENTATIVE_EVENTS
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("className")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topMethod")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("topStack")))
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("allocator")))
        );
    }

    static FocusedSlice oldObjectSlice(
        String focusQuery,
        List<Map<String, Object>> timelineEvents,
        List<Map<String, Object>> incidentWindows,
        Map<String, Object> oldObjectFieldSummary
    ) {
        String normalizedQuery = normalizeTextToken(focusQuery);
        if (normalizedQuery.isBlank()) {
            return FocusedSlice.empty();
        }

        List<Map<String, Object>> matchingEvents = timelineEvents.stream()
            .filter(event -> "oldObject".equals(stringValue(event.get("signalFamily"))))
            .filter(event -> containsNormalized(stringValue(event.get("className")), normalizedQuery)
                || containsNormalized(stringValue(event.get("rootType")), normalizedQuery)
                || containsNormalized(stringValue(event.get("rootSystem")), normalizedQuery)
                || containsNormalized(stringValue(event.get("rootDescription")), normalizedQuery)
                || containsNormalized(stringValue(event.get("objectDescription")), normalizedQuery))
            .sorted(eventImportanceComparator())
            .toList();
        if (matchingEvents.isEmpty() && !containsText(oldObjectFieldSummary, normalizedQuery)) {
            return FocusedSlice.empty();
        }

        LinkedHashMap<String, Object> payload = baseFocusedPayload("oldObjectFocusQuery", focusQuery, matchingEvents);
        putIfPositiveLong(payload, "totalSampledObjectBytes", totalSampledObjectBytes(matchingEvents));
        putIfPositiveLong(payload, "maxSampledObjectBytes", maxSampledObjectBytes(matchingEvents));
        putIfPositiveLong(payload, "maxReferenceDepth", maxReferenceDepth(matchingEvents));
        putIfPositiveLong(payload, "maxObjectAgeMs", maxObjectAgeMs(matchingEvents));
        double averageReferenceDepth = averageReferenceDepth(matchingEvents);
        if (averageReferenceDepth > 0.0d) {
            payload.put("averageReferenceDepth", averageReferenceDepth);
        }
        double averageObjectAgeMs = averageObjectAgeMs(matchingEvents);
        if (averageObjectAgeMs > 0.0d) {
            payload.put("averageObjectAgeMs", averageObjectAgeMs);
        }
        if (!matchingEvents.isEmpty()) {
            putIfNotEmpty(payload, "matchingClasses", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("className")),
                null,
                "className"
            ));
            putIfNotEmpty(payload, "matchingRoots", rankedMetrics(
                matchingEvents,
                JfrDerivedContextSupport::rootToken,
                null,
                "root"
            ));
            putIfNotEmpty(payload, "matchingDescriptions", rankedMetrics(
                matchingEvents,
                event -> stringValue(event.get("objectDescription")),
                null,
                "description"
            ));
            payload.put("representativeEvents", representativeEvents(matchingEvents));
            List<Map<String, Object>> overlappingIncidentWindows = overlappingIncidentWindows(matchingEvents, incidentWindows);
            if (!overlappingIncidentWindows.isEmpty()) {
                payload.put("overlappingIncidentWindows", overlappingIncidentWindows);
            }
        }

        List<Map<String, Object>> matchingSummaryClasses = listOfMaps(oldObjectFieldSummary.get("topOldObjectClasses")).stream()
            .filter(item -> containsNormalized(stringValue(item.get("className")), normalizedQuery))
            .limit(MAX_TOP_ITEMS)
            .toList();
        if (!matchingSummaryClasses.isEmpty()) {
            payload.put("matchingSummaryClasses", matchingSummaryClasses);
        }

        return new FocusedSlice(
            immutableOrderedMap(payload),
            matchingEvents.size() > MAX_REPRESENTATIVE_EVENTS
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("className")))
                || hasMoreRankedItems(matchingEvents, JfrDerivedContextSupport::rootToken)
                || hasMoreRankedItems(matchingEvents, event -> stringValue(event.get("objectDescription")))
        );
    }

    static List<Map<String, Object>> timelineEvents(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null) {
            return List.of();
        }
        return listOfMaps(parsedArtifact.extractedData().get("timelineEvents"));
    }

    private static LinkedHashMap<String, Object> baseFocusedPayload(
        String queryKey,
        String queryValue,
        List<Map<String, Object>> matchingEvents
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(queryKey, queryValue);
        payload.put("eventCount", matchingEvents.size());
        if (!matchingEvents.isEmpty()) {
            Instant firstSeen = matchingEvents.stream()
                .map(JfrDerivedContextSupport::startTime)
                .filter(instant -> instant != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
            Instant lastSeen = matchingEvents.stream()
                .map(JfrDerivedContextSupport::effectiveEndTime)
                .filter(instant -> instant != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
            if (firstSeen != null) {
                payload.put("firstSeen", firstSeen.toString());
            }
            if (lastSeen != null) {
                payload.put("lastSeen", lastSeen.toString());
            }
            putIfPositiveLong(payload, "totalDurationMs", totalDurationMs(matchingEvents));
            putIfPositiveLong(payload, "maxDurationMs", maxDurationMs(matchingEvents));
        }
        return payload;
    }

    private static void addMatchingHotspotSummary(
        List<Map<String, Object>> target,
        String kind,
        Map<String, Object> hotspotSummary,
        String normalizedQuery
    ) {
        if (hotspotSummary == null || hotspotSummary.isEmpty() || !containsText(hotspotSummary, normalizedQuery)) {
            return;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.putAll(hotspotSummary);
        target.add(immutableOrderedMap(payload));
    }

    private static List<Map<String, Object>> representativeEvents(List<Map<String, Object>> events) {
        List<Map<String, Object>> sorted = events.stream()
            .sorted(eventChronologyComparator())
            .toList();
        if (sorted.size() <= MAX_REPRESENTATIVE_EVENTS) {
            return sorted.stream().map(JfrDerivedContextSupport::compactRepresentativeEvent).toList();
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        selected.add(sorted.getFirst());
        selected.add(sorted.getLast());
        sorted.stream()
            .sorted(eventImportanceComparator())
            .limit(Math.max(0, MAX_REPRESENTATIVE_EVENTS - 2))
            .forEach(selected::add);

        LinkedHashMap<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Map<String, Object> event : selected) {
            deduplicated.putIfAbsent(eventKey(event), event);
        }
        return deduplicated.values().stream()
            .sorted(eventChronologyComparator())
            .limit(MAX_REPRESENTATIVE_EVENTS)
            .map(JfrDerivedContextSupport::compactRepresentativeEvent)
            .toList();
    }

    private static Map<String, Object> compactRepresentativeEvent(Map<String, Object> event) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        copyIfPresent(payload, "relativeStartMs", event.get("relativeStartMs"));
        copyIfPresent(payload, "relativeEndMs", event.get("relativeEndMs"));
        copyIfPresent(payload, "signalFamily", event.get("signalFamily"));
        copyIfPresent(payload, "eventType", event.get("eventType"));
        copyIfPresent(payload, "label", event.get("label"));
        copyIfPresent(payload, "durationMs", event.get("durationMs"));
        copyIfPresent(payload, "className", event.get("className"));
        copyIfPresent(payload, "topMethod", event.get("topMethod"));
        copyIfPresent(payload, "eventThread", event.get("eventThread"));
        copyIfPresent(payload, "allocatedBytes", event.get("allocatedBytes"));
        copyIfPresent(payload, "sampledObjectBytes", event.get("sampledObjectBytes"));
        copyIfPresent(payload, "rootType", event.get("rootType"));
        copyIfPresent(payload, "rootSystem", event.get("rootSystem"));
        copyIfPresent(payload, "referenceDepth", event.get("referenceDepth"));
        copyIfPresent(payload, "objectAgeMs", event.get("objectAgeMs"));
        return immutableOrderedMap(payload);
    }

    private static List<Map<String, Object>> overlappingIncidentWindows(
        List<Map<String, Object>> events,
        List<Map<String, Object>> incidentWindows
    ) {
        if (events.isEmpty() || incidentWindows.isEmpty()) {
            return List.of();
        }
        Instant start = events.stream()
            .map(JfrDerivedContextSupport::startTime)
            .filter(instant -> instant != null)
            .min(Comparator.naturalOrder())
            .orElse(null);
        Instant end = events.stream()
            .map(JfrDerivedContextSupport::effectiveEndTime)
            .filter(instant -> instant != null)
            .max(Comparator.naturalOrder())
            .orElse(null);
        if (start == null || end == null) {
            return List.of();
        }
        return incidentWindows.stream()
            .filter(window -> overlaps(window, start, end))
            .limit(MAX_INCIDENT_WINDOWS)
            .toList();
    }

    private static List<Map<String, Object>> rankedMetrics(
        List<Map<String, Object>> events,
        Function<Map<String, Object>, String> keyExtractor,
        Function<Map<String, Object>, String> labelExtractor,
        String keyName
    ) {
        if (events.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, MetricAccumulator> metrics = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String key = keyExtractor != null ? normalizeBlank(keyExtractor.apply(event)) : "";
            if (key.isBlank()) {
                continue;
            }
            MetricAccumulator accumulator = metrics.computeIfAbsent(key, ignored -> new MetricAccumulator());
            accumulator.record(event, labelExtractor != null ? normalizeBlank(labelExtractor.apply(event)) : "");
        }
        if (metrics.isEmpty()) {
            return List.of();
        }

        long totalCount = events.size();
        long totalBytes = totalBytes(events);
        long totalDurationMs = totalDurationMs(events);
        return metrics.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue().count, left.getValue().count);
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(right.getValue().totalBytes, left.getValue().totalBytes);
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(right.getValue().totalDurationMs, left.getValue().totalDurationMs);
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .limit(MAX_TOP_ITEMS)
            .map(entry -> metricMap(keyName, entry.getKey(), entry.getValue(), totalCount, totalDurationMs, totalBytes))
            .toList();
    }

    private static Map<String, Object> metricMap(
        String keyName,
        String key,
        MetricAccumulator accumulator,
        long totalCount,
        long totalDurationMs,
        long totalBytes
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(keyName, key);
        if (!accumulator.label.isBlank() && !accumulator.label.equals(key)) {
            payload.put("label", accumulator.label);
        }
        payload.put("count", accumulator.count);
        payload.put("share", ratio(accumulator.count, totalCount));
        putIfPositiveLong(payload, "totalDurationMs", accumulator.totalDurationMs);
        if (accumulator.totalDurationMs > 0L && totalDurationMs > 0L) {
            payload.put("durationShare", ratio(accumulator.totalDurationMs, totalDurationMs));
        }
        putIfPositiveLong(payload, "maxDurationMs", accumulator.maxDurationMs);
        putIfPositiveLong(payload, "totalBytes", accumulator.totalBytes);
        if (accumulator.totalBytes > 0L && totalBytes > 0L) {
            payload.put("byteShare", ratio(accumulator.totalBytes, totalBytes));
        }
        putIfPositiveLong(payload, "maxBytes", accumulator.maxBytes);
        putIfPositiveLong(payload, "maxReferenceDepth", accumulator.maxReferenceDepth);
        if (accumulator.totalReferenceDepth > 0L && accumulator.count > 0L) {
            payload.put("averageReferenceDepth", (double) accumulator.totalReferenceDepth / (double) accumulator.count);
        }
        putIfPositiveLong(payload, "maxObjectAgeMs", accumulator.maxObjectAgeMs);
        if (accumulator.totalObjectAgeMs > 0L && accumulator.count > 0L) {
            payload.put("averageObjectAgeMs", (double) accumulator.totalObjectAgeMs / (double) accumulator.count);
        }
        return immutableOrderedMap(payload);
    }

    private static boolean hasMoreRankedItems(
        List<Map<String, Object>> events,
        Function<Map<String, Object>, String> keyExtractor
    ) {
        if (events.isEmpty()) {
            return false;
        }
        return events.stream()
            .map(keyExtractor)
            .map(JfrDerivedContextSupport::normalizeBlank)
            .filter(value -> !value.isBlank())
            .distinct()
            .count() > MAX_TOP_ITEMS;
    }

    private static boolean overlaps(Map<String, Object> eventOrWindow, Instant requestedStart, Instant requestedEnd) {
        Instant start = startTime(eventOrWindow);
        Instant end = effectiveEndTime(eventOrWindow);
        if (start == null || end == null) {
            return false;
        }
        return !start.isAfter(requestedEnd) && !end.isBefore(requestedStart);
    }

    private static Instant startTime(Map<String, Object> event) {
        return instantValue(event.get("startTime"));
    }

    private static Instant effectiveEndTime(Map<String, Object> event) {
        Instant end = instantValue(event.get("endTime"));
        return end != null ? end : startTime(event);
    }

    private static Comparator<Map<String, Object>> eventChronologyComparator() {
        return Comparator
            .comparing(JfrDerivedContextSupport::startTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(event -> stringValue(event.get("eventType")));
    }

    private static Comparator<Map<String, Object>> eventImportanceComparator() {
        return Comparator
            .comparingDouble(JfrDerivedContextSupport::eventImportance)
            .reversed()
            .thenComparing(JfrDerivedContextSupport::startTime, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static double eventImportance(Map<String, Object> event) {
        double score = 1.0d;
        score += Math.min(12.0d, longValue(event.get("durationMs")) / 50.0d);
        score += Math.min(12.0d, totalBytes(event) / 1_000_000.0d);
        score += Math.min(6.0d, longValue(event.get("referenceDepth")) / 2.0d);
        score += switch (stringValue(event.get("signalFamily"))) {
            case "gcPause" -> 3.0d;
            case "lockContention" -> 2.6d;
            case "threadPark", "ioLatency", "safepointPause" -> 2.0d;
            case "allocation" -> 2.2d;
            case "oldObject" -> 2.4d;
            case "exceptionBurst" -> 1.5d;
            case "executionSample" -> 1.0d;
            default -> 0.5d;
        };
        if (!stringValue(event.get("topMethod")).isBlank()) {
            score += 0.35d;
        }
        if (!stringValue(event.get("eventThread")).isBlank()) {
            score += 0.15d;
        }
        return score;
    }

    private static String eventKey(Map<String, Object> event) {
        return String.join(
            "|",
            stringValue(event.get("startTime")),
            stringValue(event.get("eventType")),
            String.valueOf(longValue(event.get("durationMs"))),
            stringValue(event.get("topMethod")),
            stringValue(event.get("className")),
            stringValue(event.get("rootType")),
            stringValue(event.get("rootDescription"))
        );
    }

    private static long totalDurationMs(List<Map<String, Object>> events) {
        long total = 0L;
        for (Map<String, Object> event : events) {
            total += longValue(event.get("durationMs"));
        }
        return total;
    }

    private static long maxDurationMs(List<Map<String, Object>> events) {
        long max = 0L;
        for (Map<String, Object> event : events) {
            max = Math.max(max, longValue(event.get("durationMs")));
        }
        return max;
    }

    private static long totalAllocatedBytes(List<Map<String, Object>> events) {
        long total = 0L;
        for (Map<String, Object> event : events) {
            total += longValue(event.get("allocatedBytes"));
        }
        return total;
    }

    private static long totalSampledObjectBytes(List<Map<String, Object>> events) {
        long total = 0L;
        for (Map<String, Object> event : events) {
            total += longValue(event.get("sampledObjectBytes"));
        }
        return total;
    }

    private static long totalBytes(List<Map<String, Object>> events) {
        long total = 0L;
        for (Map<String, Object> event : events) {
            total += totalBytes(event);
        }
        return total;
    }

    private static long totalBytes(Map<String, Object> event) {
        return Math.max(longValue(event.get("allocatedBytes")), longValue(event.get("sampledObjectBytes")));
    }

    private static long maxAllocatedBytes(List<Map<String, Object>> events) {
        long max = 0L;
        for (Map<String, Object> event : events) {
            max = Math.max(max, longValue(event.get("allocatedBytes")));
        }
        return max;
    }

    private static long maxSampledObjectBytes(List<Map<String, Object>> events) {
        long max = 0L;
        for (Map<String, Object> event : events) {
            max = Math.max(max, longValue(event.get("sampledObjectBytes")));
        }
        return max;
    }

    private static long maxReferenceDepth(List<Map<String, Object>> events) {
        long max = 0L;
        for (Map<String, Object> event : events) {
            max = Math.max(max, longValue(event.get("referenceDepth")));
        }
        return max;
    }

    private static long maxObjectAgeMs(List<Map<String, Object>> events) {
        long max = 0L;
        for (Map<String, Object> event : events) {
            max = Math.max(max, longValue(event.get("objectAgeMs")));
        }
        return max;
    }

    private static double averageReferenceDepth(List<Map<String, Object>> events) {
        long total = 0L;
        long count = 0L;
        for (Map<String, Object> event : events) {
            long referenceDepth = longValue(event.get("referenceDepth"));
            if (referenceDepth > 0L) {
                total += referenceDepth;
                count++;
            }
        }
        return count > 0L ? (double) total / (double) count : 0.0d;
    }

    private static double averageObjectAgeMs(List<Map<String, Object>> events) {
        long total = 0L;
        long count = 0L;
        for (Map<String, Object> event : events) {
            long objectAgeMs = longValue(event.get("objectAgeMs"));
            if (objectAgeMs > 0L) {
                total += objectAgeMs;
                count++;
            }
        }
        return count > 0L ? (double) total / (double) count : 0.0d;
    }

    private static String rootToken(Map<String, Object> event) {
        return firstNonBlank(
            stringValue(event.get("rootType")),
            stringValue(event.get("rootSystem")),
            stringValue(event.get("rootDescription"))
        );
    }

    private static String humanSignalFamily(String signalFamily) {
        return switch (signalFamily == null ? "" : signalFamily) {
            case "gcPause" -> "GC pauses";
            case "lockContention" -> "monitor blocks";
            case "threadPark" -> "thread parks";
            case "ioLatency" -> "I/O latency";
            case "exceptionBurst" -> "exceptions";
            case "safepointPause" -> "safepoints";
            case "allocation" -> "allocations";
            case "oldObject" -> "retained objects";
            case "executionSample" -> "execution samples";
            default -> "activity";
        };
    }

    private static void putIfPositiveLong(Map<String, Object> target, String key, long value) {
        if (value > 0L) {
            target.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, List<Map<String, Object>> value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    private static void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Number number && number.doubleValue() == 0.0d) {
            return;
        }
        target.put(key, value);
    }

    private static boolean containsText(Object value, String normalizedQuery) {
        if (value == null || normalizedQuery == null || normalizedQuery.isBlank()) {
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
        return normalizeTextToken(String.valueOf(value)).contains(normalizedQuery);
    }

    private static List<Map<String, Object>> matchingCatalogEntries(
        List<Map<String, Object>> entries,
        String normalizedQuery
    ) {
        if (entries.isEmpty() || normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        return entries.stream()
            .filter(entry -> matchesCatalogEntry(entry, normalizedQuery))
            .limit(MAX_TOP_ITEMS)
            .toList();
    }

    private static boolean matchesCatalogEntry(Map<String, Object> entry, String normalizedQuery) {
        return containsCompactNormalized(stringValue(entry.get("name")), normalizedQuery)
            || containsCompactNormalized(stringValue(entry.get("label")), normalizedQuery)
            || containsCompactNormalized(simpleEventTypeName(stringValue(entry.get("name"))), normalizedQuery);
    }

    private static boolean matchesEventTypeOrFamily(Map<String, Object> event, String normalizedQuery) {
        return containsCompactNormalized(stringValue(event.get("eventType")), normalizedQuery)
            || containsCompactNormalized(stringValue(event.get("label")), normalizedQuery)
            || containsCompactNormalized(simpleEventTypeName(stringValue(event.get("eventType"))), normalizedQuery)
            || signalFamilyMatchesQuery(stringValue(event.get("signalFamily")), normalizedQuery);
    }

    private static boolean signalFamilyMatchesQuery(String signalFamily, String normalizedQuery) {
        if (signalFamily == null || signalFamily.isBlank() || normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return switch (signalFamily) {
            case "gcPause" -> containsAnyAlias(normalizedQuery, "gc", "gcpause", "garbagecollection");
            case "lockContention" -> containsAnyAlias(normalizedQuery, "lock", "monitor", "blocked", "contention", "javamonitorblocked");
            case "threadPark" -> containsAnyAlias(normalizedQuery, "park", "threadpark");
            case "ioLatency" -> containsAnyAlias(normalizedQuery, "io", "iolatency", "socket", "file", "read", "write");
            case "exceptionBurst" -> containsAnyAlias(normalizedQuery, "exception", "throw");
            case "safepointPause" -> containsAnyAlias(normalizedQuery, "safepoint", "pause");
            case "allocation" -> containsAnyAlias(normalizedQuery, "allocation", "allocate", "objectallocation");
            case "oldObject" -> containsAnyAlias(normalizedQuery, "oldobject", "retained", "retention");
            case "executionSample" -> containsAnyAlias(normalizedQuery, "execution", "sample", "cpu", "hotspot");
            default -> false;
        };
    }

    private static boolean containsAnyAlias(String normalizedQuery, String... aliases) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizeCompactToken(alias);
            if (!normalizedAlias.isBlank()
                && (normalizedAlias.contains(normalizedQuery) || normalizedQuery.contains(normalizedAlias))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNormalized(String value, String normalizedQuery) {
        return normalizeTextToken(value).contains(normalizedQuery);
    }

    private static boolean containsCompactNormalized(String value, String normalizedQuery) {
        return normalizeCompactToken(value).contains(normalizedQuery);
    }

    private static String normalizeTextToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeCompactToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String simpleEventTypeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int separator = value.lastIndexOf('.');
        return separator >= 0 && separator < value.length() - 1 ? value.substring(separator + 1) : value;
    }

    private static String normalizeBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
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

    private static Instant instantValue(Object value) {
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

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0L ? 0.0d : (double) numerator / (double) denominator;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Map<String, Object> immutableOrderedMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    static final class MetricAccumulator {
        private long count;
        private long totalDurationMs;
        private long maxDurationMs;
        private long totalBytes;
        private long maxBytes;
        private long totalReferenceDepth;
        private long maxReferenceDepth;
        private long totalObjectAgeMs;
        private long maxObjectAgeMs;
        private String label = "";

        private void record(Map<String, Object> event, String labelCandidate) {
            count++;
            long durationMs = longValue(event.get("durationMs"));
            totalDurationMs += durationMs;
            maxDurationMs = Math.max(maxDurationMs, durationMs);
            long bytes = totalBytes(event);
            totalBytes += bytes;
            maxBytes = Math.max(maxBytes, bytes);
            long referenceDepth = longValue(event.get("referenceDepth"));
            totalReferenceDepth += referenceDepth;
            maxReferenceDepth = Math.max(maxReferenceDepth, referenceDepth);
            long objectAgeMs = longValue(event.get("objectAgeMs"));
            totalObjectAgeMs += objectAgeMs;
            maxObjectAgeMs = Math.max(maxObjectAgeMs, objectAgeMs);
            if (label.isBlank() && labelCandidate != null && !labelCandidate.isBlank()) {
                label = labelCandidate;
            }
        }
    }

    static record FocusedSlice(Map<String, Object> payload, boolean moreAvailable) {
        static FocusedSlice empty() {
            return new FocusedSlice(Map.of(), false);
        }

        boolean isEmpty() {
            return payload == null || payload.isEmpty();
        }
    }
}
