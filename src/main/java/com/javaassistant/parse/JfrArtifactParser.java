package com.javaassistant.parse;

import com.javaassistant.context.DiagnosticContextRenderSupport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

public class JfrArtifactParser implements ArtifactParser {

    private static final int MAX_GENERIC_EVENT_SAMPLES = 4;
    private static final int MAX_GENERIC_SAMPLE_FIELDS = 8;
    private static final int MAX_GENERIC_OBJECT_FIELDS = 6;
    private static final int MAX_GENERIC_LIST_ITEMS = 4;
    private static final int MAX_GENERIC_TOP_THREADS = 6;
    private static final int MAX_GENERIC_RENDERED_STRING = 160;
    private static final int MAX_INCIDENT_WINDOWS = 4;
    private static final int MAX_WINDOW_TOP_ITEMS = 6;
    private static final int MAX_WINDOW_CHRONOLOGY_EVENTS = 6;
    private static final int MAX_CHRONOLOGY_HIGHLIGHTS = 18;
    private static final long INCIDENT_CLUSTER_GAP_MS = 250L;
    private static final String JVM_INFORMATION_EVENT_NAME = "jdk.JVMInformation";

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.JFR;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Path recordingPath = resolveRecordingPath(artifact);
        if (recordingPath == null || !Files.exists(recordingPath)) {
            return failedParseArtifact(
                artifact,
                recordingPath,
                "JFR recording file is not available on disk for structured parsing."
            );
        }

        try {
        long recordingSizeBytes = fileSize(artifact, recordingPath);
        List<EventType> declaredJfrEventTypes = readEventTypes(recordingPath);
        Map<String, EventSummaryAccumulator> eventTypeAccumulators = new LinkedHashMap<>();
        Map<String, GenericEventTypeAccumulator> genericEventAccumulators = new LinkedHashMap<>();
        StackHotspotAccumulator overallHotspots = new StackHotspotAccumulator();
        StackHotspotAccumulator executionHotspots = new StackHotspotAccumulator();
        StackHotspotAccumulator runtimeHotspots = new StackHotspotAccumulator();
        ClassLoadingAnalyticsAccumulator classLoadingAnalytics = new ClassLoadingAnalyticsAccumulator();
        CodeCacheAnalyticsAccumulator codeCacheAnalytics = new CodeCacheAnalyticsAccumulator();
        CpuLoadAnalyticsAccumulator cpuLoadAnalytics = new CpuLoadAnalyticsAccumulator();
        AllocationAnalyticsAccumulator allocationAnalytics = new AllocationAnalyticsAccumulator();
        OldObjectAnalyticsAccumulator oldObjectAnalytics = new OldObjectAnalyticsAccumulator();
        JvmRuntimeInfoAccumulator jvmRuntimeInfo = new JvmRuntimeInfoAccumulator();
        List<JfrTimelineEvent> timelineEvents = new ArrayList<>();
        Instant startTime = null;
        Instant endTime = null;
        long eventCount = 0L;

        try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                eventCount++;

                String eventTypeName = event.getEventType().getName();
                jvmRuntimeInfo.record(event);
                EventSummaryAccumulator accumulator = eventTypeAccumulators.get(eventTypeName);
                if (accumulator == null) {
                    accumulator = new EventSummaryAccumulator(eventTypeName, event.getEventType().getLabel());
                }
                eventTypeAccumulators.put(eventTypeName, accumulator.record(event));

                GenericEventTypeAccumulator genericEventAccumulator = genericEventAccumulators.get(eventTypeName);
                if (genericEventAccumulator == null) {
                    genericEventAccumulator = new GenericEventTypeAccumulator(event.getEventType());
                    genericEventAccumulators.put(eventTypeName, genericEventAccumulator);
                }

                StackFingerprint stackFingerprint = stackFingerprint(event.getStackTrace());
                if (stackFingerprint != null) {
                    overallHotspots.record(stackFingerprint);
                    if (isExecutionSampleEvent(eventTypeName)) {
                        executionHotspots.record(stackFingerprint);
                    }
                    if (isRuntimeHotspotEvent(eventTypeName)) {
                        runtimeHotspots.record(stackFingerprint);
                    }
                }
                if (isAllocationEvent(eventTypeName)) {
                    allocationAnalytics.record(event, stackFingerprint);
                }
                if (isOldObjectSampleEvent(eventTypeName)) {
                    oldObjectAnalytics.record(event);
                }
                if (isClassLoadingEvent(eventTypeName)) {
                    classLoadingAnalytics.record(event, eventTypeName);
                }
                if (isCodeCacheEvent(eventTypeName)) {
                    codeCacheAnalytics.record(event, eventTypeName);
                }
                if (isCpuLoadEvent(eventTypeName)) {
                    cpuLoadAnalytics.record(event, eventTypeName);
                }
                genericEventAccumulator.record(event, stackFingerprint);
                JfrTimelineEvent timelineEvent = timelineEvent(event, eventTypeName, stackFingerprint);
                if (timelineEvent != null) {
                    timelineEvents.add(timelineEvent);
                }

                Instant eventStartTime = event.getStartTime();
                Instant eventEndTime = event.getEndTime();
                if (eventStartTime != null && (startTime == null || eventStartTime.isBefore(startTime))) {
                    startTime = eventStartTime;
                }
                if (eventEndTime != null && (endTime == null || eventEndTime.isAfter(endTime))) {
                    endTime = eventEndTime;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse JFR recording: " + e.getMessage(), e);
        }

        long durationMs = startTime != null && endTime != null ? Math.max(0L, Duration.between(startTime, endTime).toMillis()) : 0L;
        List<Map<String, Object>> observedEventTypes = eventTypeAccumulators.values().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.count(), left.count());
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(right.totalDurationMs(), left.totalDurationMs());
                if (compare != 0) {
                    return compare;
                }
                return left.name().compareTo(right.name());
            })
            .map(EventSummaryAccumulator::toCanonicalMap)
            .toList();
        List<Map<String, Object>> topEventTypes = observedEventTypes.stream()
            .limit(10)
            .toList();
        List<Map<String, Object>> declaredEventTypes = declaredEventTypeMetadata(declaredJfrEventTypes);
        List<Map<String, Object>> eventTypeDetails = genericEventAccumulators.values().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.eventCount(), left.eventCount());
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(right.totalDurationMs(), left.totalDurationMs());
                if (compare != 0) {
                    return compare;
                }
                return left.name().compareTo(right.name());
            })
            .map(GenericEventTypeAccumulator::toCanonicalMap)
            .toList();

        SignalSummary lockSummary = summarize(eventTypeAccumulators.values(), this::isLockContentionEvent);
        SignalSummary gcSummary = summarize(eventTypeAccumulators.values(), this::isGcPauseEvent);
        SignalSummary monitorWaitSummary = summarize(eventTypeAccumulators.values(), this::isMonitorWaitEvent);
        SignalSummary threadParkSummary = summarize(eventTypeAccumulators.values(), this::isThreadParkEvent);
        SignalSummary ioSummary = summarize(eventTypeAccumulators.values(), this::isIoLatencyEvent);
        SignalSummary exceptionSummary = summarize(eventTypeAccumulators.values(), this::isExceptionEvent);
        SignalSummary safepointSummary = summarize(eventTypeAccumulators.values(), this::isSafepointEvent);
        SignalSummary allocationSummary = summarize(eventTypeAccumulators.values(), this::isAllocationEvent);
        SignalSummary oldObjectSummary = summarize(eventTypeAccumulators.values(), this::isOldObjectSampleEvent);
        SignalSummary executionSummary = summarize(eventTypeAccumulators.values(), this::isExecutionSampleEvent);
        Map<String, Object> classLoadingSummary = classLoadingAnalytics.toCanonicalMap();
        Map<String, Object> codeCacheSummary = codeCacheAnalytics.toCanonicalMap();
        Map<String, Object> cpuLoadSummary = cpuLoadAnalytics.toCanonicalMap();
        Map<String, Object> allocationFieldSummary = allocationAnalytics.toFieldCanonicalMap();
        Map<String, Object> allocationHotspotSummary = allocationAnalytics.toHotspotCanonicalMap();
        Map<String, Object> oldObjectFieldSummary = oldObjectAnalytics.toCanonicalMap();
        Instant recordingStartTime = startTime;
        Map<String, Object> jvmRuntimeInfoSummary = jvmRuntimeInfo.toCanonicalMap(recordingStartTime);
        List<JfrIncidentWindow> incidentWindowModels = incidentWindows(recordingStartTime, timelineEvents);
        List<Map<String, Object>> timelineEventIndex = timelineEvents.stream()
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .map(event -> timelineEventIndexMap(recordingStartTime, event))
            .toList();
        List<Map<String, Object>> incidentWindows = incidentWindowModels.stream()
            .map(window -> incidentWindowCanonicalMap(recordingStartTime, window))
            .toList();
        Map<String, Object> incidentWindowSummary = incidentWindowSummary(recordingStartTime, incidentWindowModels);
        List<Map<String, Object>> chronologyHighlights = chronologyHighlights(recordingStartTime, incidentWindowModels, timelineEvents);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordingSizeBytes", recordingSizeBytes);
        summary.put("declaredEventTypeCount", declaredJfrEventTypes.size());
        summary.put("observedEventTypeCount", eventTypeAccumulators.size());
        summary.put("eventCount", eventCount);
        summary.put("durationMs", durationMs);
        if (startTime != null) {
            summary.put("startTime", startTime.toString());
        }
        if (endTime != null) {
            summary.put("endTime", endTime.toString());
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("lockEventsPresent", lockSummary.eventCount() > 0L);
        coverage.put("gcEventsPresent", gcSummary.eventCount() > 0L);
        coverage.put("monitorWaitEventsPresent", monitorWaitSummary.eventCount() > 0L);
        coverage.put("threadParkEventsPresent", threadParkSummary.eventCount() > 0L);
        coverage.put("ioEventsPresent", ioSummary.eventCount() > 0L);
        coverage.put("exceptionEventsPresent", exceptionSummary.eventCount() > 0L);
        coverage.put("safepointEventsPresent", safepointSummary.eventCount() > 0L);
        coverage.put("classLoadingEventsPresent", !classLoadingSummary.isEmpty());
        coverage.put("codeCacheEventsPresent", !codeCacheSummary.isEmpty());
        coverage.put("cpuLoadEventsPresent", !cpuLoadSummary.isEmpty());
        coverage.put("allocationEventsPresent", allocationSummary.eventCount() > 0L);
        coverage.put("oldObjectSamplingPresent", oldObjectSummary.eventCount() > 0L);
        coverage.put("executionSamplesPresent", executionSummary.eventCount() > 0L);
        coverage.put("stackHotspotsPresent", overallHotspots.stackEventCount() > 0L);
        coverage.put("executionHotspotsPresent", executionHotspots.stackEventCount() > 0L);
        coverage.put("runtimeHotspotsPresent", runtimeHotspots.stackEventCount() > 0L);
        coverage.put("allocationFieldDetailsPresent", allocationAnalytics.fieldRichEventCount() > 0L);
        coverage.put("allocationHotspotsPresent", allocationAnalytics.stackEventCount() > 0L);
        coverage.put("oldObjectFieldDetailsPresent", oldObjectAnalytics.fieldRichEventCount() > 0L);
        coverage.put("oldObjectRootDetailsPresent", oldObjectAnalytics.rootedEventCount() > 0L);
        coverage.put("oldObjectDepthDetailsPresent", oldObjectAnalytics.depthEventCount() > 0L);
        coverage.put("declaredEventMetadataPresent", !declaredEventTypes.isEmpty());
        coverage.put("observedEventCatalogPresent", !observedEventTypes.isEmpty());
        coverage.put("genericEventDetailsPresent", !eventTypeDetails.isEmpty());
        coverage.put("timelineEventsPresent", !timelineEventIndex.isEmpty());
        coverage.put("incidentWindowsPresent", !incidentWindows.isEmpty());
        coverage.put("incidentWindowSummaryPresent", !incidentWindowSummary.isEmpty());
        coverage.put("chronologyHighlightsPresent", !chronologyHighlights.isEmpty());

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", Map.copyOf(summary));
        extractedData.put("coverage", Map.copyOf(coverage));
        extractedData.put("timelineEvents", timelineEventIndex);
        extractedData.put("incidentWindowSummary", incidentWindowSummary);
        extractedData.put("chronologyHighlights", chronologyHighlights);
        extractedData.put("incidentWindows", incidentWindows);
        extractedData.put("observedEventTypes", observedEventTypes);
        extractedData.put("topEventTypes", topEventTypes);
        extractedData.put("declaredEventTypes", declaredEventTypes);
        extractedData.put("eventTypeDetails", eventTypeDetails);
        extractedData.put("lockSummary", lockSummary.toCanonicalMap());
        extractedData.put("gcSummary", gcSummary.toCanonicalMap());
        extractedData.put("monitorWaitSummary", monitorWaitSummary.toCanonicalMap());
        extractedData.put("threadParkSummary", threadParkSummary.toCanonicalMap());
        extractedData.put("ioSummary", ioSummary.toCanonicalMap());
        extractedData.put("exceptionSummary", exceptionSummary.toCanonicalMap());
        extractedData.put("safepointSummary", safepointSummary.toCanonicalMap());
        extractedData.put("classLoadingSummary", classLoadingSummary);
        extractedData.put("codeCacheSummary", codeCacheSummary);
        extractedData.put("cpuLoadSummary", cpuLoadSummary);
        extractedData.put("allocationSummary", allocationSummary.toCanonicalMap());
        extractedData.put("allocationFieldSummary", allocationFieldSummary);
        extractedData.put("allocationHotspotSummary", allocationHotspotSummary);
        extractedData.put("oldObjectSummary", oldObjectSummary.toCanonicalMap());
        extractedData.put("oldObjectFieldSummary", oldObjectFieldSummary);
        extractedData.put("executionSummary", executionSummary.toCanonicalMap());
        extractedData.put("overallHotspotSummary", overallHotspots.toCanonicalMap());
        extractedData.put("executionHotspotSummary", executionHotspots.toCanonicalMap());
        extractedData.put("runtimeHotspotSummary", runtimeHotspots.toCanonicalMap());
        if (!jvmRuntimeInfoSummary.isEmpty()) {
            extractedData.put("jvmRuntimeInfo", jvmRuntimeInfoSummary);
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence(
            "jfr-recording-summary",
            path(artifact),
            "JFR recording summary",
            "Top-level duration, size, and event coverage extracted from the JFR recording.",
            recordingPath.getFileName() != null ? recordingPath.getFileName().toString() : recordingPath.toString(),
            List.of(),
            Map.copyOf(summary)
        ));

        if (!topEventTypes.isEmpty()) {
            evidence.add(new Evidence(
                "jfr-top-event-types",
                path(artifact),
                "Top JFR event types",
                "Most frequent event types observed in the recording.",
                String.valueOf(topEventTypes.getFirst().get("name")),
                List.of(),
                Map.of("topEventTypes", topEventTypes)
            ));
        }

        addSignalEvidence(evidence, artifact, "jfr-lock-summary", "JFR lock contention events", "Monitor-blocked events observed in the recording.", lockSummary);
        addSignalEvidence(evidence, artifact, "jfr-gc-summary", "JFR GC pause events", "GC pause-related events observed in the recording.", gcSummary);
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-monitor-wait-summary",
            "JFR monitor wait events",
            "Monitor-wait backlog events observed in the recording.",
            monitorWaitSummary
        );
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-thread-park-summary",
            "JFR thread park events",
            "Thread park or scheduler-wait events observed in the recording.",
            threadParkSummary
        );
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-io-summary",
            "JFR file and socket I/O events",
            "File or socket latency events observed in the recording.",
            ioSummary
        );
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-exception-summary",
            "JFR exception events",
            "Exception-throw or exception-statistics events observed in the recording.",
            exceptionSummary
        );
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-safepoint-summary",
            "JFR safepoint events",
            "Safepoint or VM-operation pause events observed in the recording.",
            safepointSummary
        );
        addSignalEvidence(
            evidence,
            artifact,
            "jfr-memory-summary",
            "JFR memory coverage",
            "Allocation and old-object-sample event coverage observed in the recording.",
            SignalSummary.combine(allocationSummary, oldObjectSummary)
        );
        addClassLoadingEvidence(
            evidence,
            artifact,
            "jfr-class-loading-summary",
            "JFR class-loading summary",
            "Class-definition and class-loading activity extracted from the recording.",
            classLoadingAnalytics
        );
        addCodeCacheEvidence(
            evidence,
            artifact,
            "jfr-code-cache-summary",
            "JFR code-cache summary",
            "Compilation and code-cache pressure activity extracted from the recording.",
            codeCacheAnalytics
        );
        addCpuLoadEvidence(
            evidence,
            artifact,
            "jfr-cpu-load-summary",
            "JFR CPU load summary",
            "Process and thread CPU-load samples extracted from the recording.",
            cpuLoadAnalytics
        );
        addAllocationFieldEvidence(
            evidence,
            artifact,
            "jfr-allocation-field-summary",
            "JFR allocation field summary",
            "Allocation-related event fields, top allocating classes, and byte estimates extracted from the recording.",
            allocationAnalytics
        );
        addAllocationHotspotEvidence(
            evidence,
            artifact,
            "jfr-allocation-hotspots",
            "JFR allocation hot paths",
            "Dominant methods and stack signatures observed in stack-bearing allocation events.",
            allocationAnalytics
        );
        addOldObjectFieldEvidence(
            evidence,
            artifact,
            "jfr-old-object-field-summary",
            "JFR old-object sample summary",
            "Old-object sample fields, dominant long-lived classes, GC roots, and reference-depth details extracted from the recording.",
            oldObjectAnalytics
        );
        addHotspotEvidence(
            evidence,
            artifact,
            "jfr-overall-hotspots",
            "JFR stack hotspots",
            "Dominant stack-bearing methods and stack signatures observed across the recording.",
            overallHotspots
        );
        addHotspotEvidence(
            evidence,
            artifact,
            "jfr-execution-hotspots",
            "JFR execution-sample hotspots",
            "Dominant methods and stack signatures observed in execution-sample events.",
            executionHotspots
        );
        addHotspotEvidence(
            evidence,
            artifact,
            "jfr-runtime-hotspots",
            "JFR wait or latency hotspots",
            "Dominant methods and stack signatures observed in stack-bearing wait, latency, or exception events.",
            runtimeHotspots
        );

        List<String> warnings = new ArrayList<>();
        if (eventCount == 0L) {
            warnings.add("No events were extracted from the JFR recording.");
        }
        if (durationMs == 0L && eventCount > 0L) {
            warnings.add("Recording duration could not be determined from event timestamps.");
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "jfr-analytics-v12", extractedData, evidence, warnings);
        } catch (IllegalArgumentException exception) {
            return failedParseArtifact(artifact, recordingPath, exception.getMessage());
        }
    }

    private List<EventType> readEventTypes(Path recordingPath) {
        try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
            return List.copyOf(recordingFile.readEventTypes());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read JFR event types: " + e.getMessage(), e);
        }
    }

    private long fileSize(InputArtifact artifact, Path recordingPath) {
        if (artifact.metadata() != null && artifact.metadata().contentLength() > 0L) {
            return artifact.metadata().contentLength();
        }
        try {
            return Files.size(recordingPath);
        } catch (IOException e) {
            return 0L;
        }
    }

    private Path resolveRecordingPath(InputArtifact artifact) {
        if (artifact == null || artifact.metadata() == null || artifact.metadata().sourcePath() == null) {
            return null;
        }
        return Path.of(artifact.metadata().sourcePath());
    }

    private ParsedArtifact failedParseArtifact(InputArtifact artifact, Path recordingPath, String message) {
        String detail = message == null || message.isBlank()
            ? "Failed to parse the JFR recording."
            : message;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordingSizeBytes", recordingPath != null && Files.exists(recordingPath) ? fileSize(artifact, recordingPath) : 0L);
        summary.put("eventCount", 0L);
        summary.put("durationMs", 0L);

        Map<String, Object> coverage = Map.of("parseFailurePresent", true);
        Map<String, Object> parseFailure = new LinkedHashMap<>();
        parseFailure.put("message", detail);
        if (recordingPath != null) {
            parseFailure.put("recordingPath", recordingPath.toString());
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", Map.copyOf(summary));
        extractedData.put("coverage", coverage);
        extractedData.put("parseFailure", Map.copyOf(parseFailure));

        List<Evidence> evidence = List.of(new Evidence(
            "jfr-recording-summary",
            path(artifact),
            "JFR recording summary",
            "Top-level duration, size, and parse status extracted from the JFR recording.",
            recordingPath != null && recordingPath.getFileName() != null ? recordingPath.getFileName().toString() : path(artifact),
            List.of(),
            Map.of(
                "recordingSizeBytes", summary.get("recordingSizeBytes"),
                "eventCount", 0L,
                "durationMs", 0L,
                "parseFailure", true
            )
        ));
        List<String> warnings = List.of(
            "The JFR recording could not be parsed: " + detail,
            "Structured JFR analysis is unavailable because the recording appears corrupted, truncated, or otherwise unreadable."
        );
        return new ParsedArtifact(artifact.type(), artifact.metadata(), "jfr-analytics-failed-v1", extractedData, evidence, warnings);
    }

    private String path(InputArtifact artifact) {
        return artifact != null && artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }

    private List<Map<String, Object>> declaredEventTypeMetadata(List<EventType> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of();
        }
        return eventTypes.stream()
            .sorted(Comparator.comparing(EventType::getName))
            .map(this::declaredEventTypeCanonicalMap)
            .toList();
    }

    private Map<String, Object> declaredEventTypeCanonicalMap(EventType eventType) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("name", eventType.getName());
        if (eventType.getLabel() != null && !eventType.getLabel().isBlank()) {
            canonical.put("label", eventType.getLabel());
        }
        if (eventType.getDescription() != null && !eventType.getDescription().isBlank()) {
            canonical.put("description", eventType.getDescription());
        }
        if (eventType.getCategoryNames() != null && !eventType.getCategoryNames().isEmpty()) {
            canonical.put("categories", List.copyOf(eventType.getCategoryNames()));
        }
        List<Map<String, Object>> fieldDescriptors = eventType.getFields().stream()
            .map(this::fieldDescriptorCanonicalMap)
            .toList();
        canonical.put("fieldCount", fieldDescriptors.size());
        canonical.put("fields", fieldDescriptors);
        return immutableOrderedMap(canonical);
    }

    private Map<String, Object> fieldDescriptorCanonicalMap(ValueDescriptor field) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("name", field.getName());
        if (field.getLabel() != null && !field.getLabel().isBlank()) {
            canonical.put("label", field.getLabel());
        }
        if (field.getDescription() != null && !field.getDescription().isBlank()) {
            canonical.put("description", field.getDescription());
        }
        if (field.getTypeName() != null && !field.getTypeName().isBlank()) {
            canonical.put("typeName", field.getTypeName());
        }
        return Map.copyOf(canonical);
    }

    private void addSignalEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        SignalSummary signalSummary
    ) {
        if (signalSummary == null || signalSummary.eventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            signalSummary.primaryEventTypeName(),
            List.of(),
            signalSummary.toCanonicalMap()
        ));
    }

    private void addCpuLoadEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        CpuLoadAnalyticsAccumulator cpuLoadAnalytics
    ) {
        if (cpuLoadAnalytics == null || cpuLoadAnalytics.eventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            firstNonBlank(cpuLoadAnalytics.primaryThread(), "machineTotal"),
            List.of(),
            cpuLoadAnalytics.toCanonicalMap()
        ));
    }

    private void addHotspotEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        StackHotspotAccumulator hotspotAccumulator
    ) {
        if (hotspotAccumulator == null || hotspotAccumulator.stackEventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            hotspotAccumulator.primaryMethod(),
            List.of(),
            hotspotAccumulator.toCanonicalMap()
        ));
    }

    private void addAllocationFieldEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        AllocationAnalyticsAccumulator allocationAnalytics
    ) {
        if (allocationAnalytics == null || allocationAnalytics.allocationEventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            allocationAnalytics.primaryAllocationClass(),
            List.of(),
            allocationAnalytics.toFieldCanonicalMap()
        ));
    }

    private void addClassLoadingEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        ClassLoadingAnalyticsAccumulator classLoadingAnalytics
    ) {
        if (classLoadingAnalytics == null || classLoadingAnalytics.eventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            classLoadingAnalytics.primaryClassLoader(),
            List.of(),
            classLoadingAnalytics.toCanonicalMap()
        ));
    }

    private void addCodeCacheEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        CodeCacheAnalyticsAccumulator codeCacheAnalytics
    ) {
        if (codeCacheAnalytics == null || codeCacheAnalytics.eventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            codeCacheAnalytics.primaryCompiler(),
            List.of(),
            codeCacheAnalytics.toCanonicalMap()
        ));
    }

    private void addAllocationHotspotEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        AllocationAnalyticsAccumulator allocationAnalytics
    ) {
        if (allocationAnalytics == null || allocationAnalytics.stackEventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            allocationAnalytics.primaryAllocationMethod(),
            List.of(),
            allocationAnalytics.toHotspotCanonicalMap()
        ));
    }

    private void addOldObjectFieldEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String evidenceId,
        String label,
        String detail,
        OldObjectAnalyticsAccumulator oldObjectAnalytics
    ) {
        if (oldObjectAnalytics == null || oldObjectAnalytics.eventCount() == 0L) {
            return;
        }

        evidence.add(new Evidence(
            evidenceId,
            path(artifact),
            label,
            detail,
            oldObjectAnalytics.primaryOldObjectClass(),
            List.of(),
            oldObjectAnalytics.toCanonicalMap()
        ));
    }

    private SignalSummary summarize(Iterable<EventSummaryAccumulator> accumulators, Predicate<String> predicate) {
        long eventCount = 0L;
        long totalDurationMs = 0L;
        long maxDurationMs = 0L;
        List<String> eventTypeNames = new ArrayList<>();

        for (EventSummaryAccumulator accumulator : accumulators) {
            if (!predicate.test(accumulator.name())) {
                continue;
            }
            eventCount += accumulator.count();
            totalDurationMs += accumulator.totalDurationMs();
            maxDurationMs = Math.max(maxDurationMs, accumulator.maxDurationMs());
            eventTypeNames.add(accumulator.name());
        }

        return new SignalSummary(eventCount, totalDurationMs, maxDurationMs, List.copyOf(eventTypeNames));
    }

    private boolean isLockContentionEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("javamonitorblocked") || normalized.contains("monitorenter");
    }

    private boolean isMonitorWaitEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("javamonitorwait") || normalized.contains("monitorwait");
    }

    private boolean isGcPauseEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("garbagecollection") || normalized.contains("gcphasepause");
    }

    private boolean isAllocationEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("objectallocation") || normalized.contains("objectcountaftergc");
    }

    private boolean isClassLoadingEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("classload")
            || normalized.contains("classdefine")
            || normalized.contains("classloading")
            || normalized.contains("classunload");
    }

    private boolean isCodeCacheEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("codecache")
            || normalized.contains("compilation")
            || normalized.contains("compilerphase");
    }

    private boolean isCpuLoadEvent(String eventTypeName) {
        return normalize(eventTypeName).contains("cpuload");
    }

    private boolean isThreadCpuLoadEvent(String eventTypeName) {
        return normalize(eventTypeName).contains("threadcpuload");
    }

    private boolean isThreadParkEvent(String eventTypeName) {
        return normalize(eventTypeName).contains("threadpark");
    }

    private boolean isIoLatencyEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("socketread")
            || normalized.contains("socketwrite")
            || normalized.contains("fileread")
            || normalized.contains("filewrite");
    }

    private boolean isExceptionEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("exception") || normalized.contains("errorthrown");
    }

    private boolean isSafepointEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("safepoint") || normalized.contains("vmoperation");
    }

    private boolean isOldObjectSampleEvent(String eventTypeName) {
        return normalize(eventTypeName).contains("oldobjectsample");
    }

    private boolean isExecutionSampleEvent(String eventTypeName) {
        return normalize(eventTypeName).contains("executionsample");
    }

    private boolean isRuntimeHotspotEvent(String eventTypeName) {
        return isLockContentionEvent(eventTypeName)
            || isMonitorWaitEvent(eventTypeName)
            || isThreadParkEvent(eventTypeName)
            || isIoLatencyEvent(eventTypeName)
            || isExceptionEvent(eventTypeName)
            || isSafepointEvent(eventTypeName);
    }

    private String signalFamily(String eventTypeName) {
        if (isLockContentionEvent(eventTypeName)) {
            return "lockContention";
        }
        if (isMonitorWaitEvent(eventTypeName)) {
            return "monitorWait";
        }
        if (isGcPauseEvent(eventTypeName)) {
            return "gcPause";
        }
        if (isClassLoadingEvent(eventTypeName)) {
            return "classLoading";
        }
        if (isCpuLoadEvent(eventTypeName)) {
            return "cpuLoad";
        }
        if (isThreadParkEvent(eventTypeName)) {
            return "threadPark";
        }
        if (isIoLatencyEvent(eventTypeName)) {
            return "ioLatency";
        }
        if (isExceptionEvent(eventTypeName)) {
            return "exceptionBurst";
        }
        if (isSafepointEvent(eventTypeName)) {
            return "safepointPause";
        }
        if (isAllocationEvent(eventTypeName)) {
            return "allocation";
        }
        if (isOldObjectSampleEvent(eventTypeName)) {
            return "oldObject";
        }
        if (isExecutionSampleEvent(eventTypeName)) {
            return "executionSample";
        }
        return "generic";
    }

    private JfrTimelineEvent timelineEvent(RecordedEvent event, String eventTypeName, StackFingerprint stackFingerprint) {
        if (event == null || eventTypeName == null) {
            return null;
        }

        String signalFamily = signalFamily(eventTypeName);
        Instant eventStartTime = event.getStartTime();
        Instant eventEndTime = event.getEndTime();
        if (eventStartTime == null && eventEndTime == null) {
            return null;
        }

        long durationMs = event.getDuration() != null ? Math.max(0L, event.getDuration().toMillis()) : 0L;
        String topMethod = stackFingerprint != null ? stackFingerprint.primaryMethod() : null;
        String topStack = stackFingerprint != null ? stackFingerprint.stackSignature() : null;
        String eventThreadName = extractEventThreadName(event);
        String className = null;
        long sizeBytes = 0L;
        String allocator = null;
        String rootType = null;
        String rootSystem = null;
        String rootDescription = null;
        long referenceDepth = 0L;
        long objectAgeMs = 0L;
        String objectDescription = null;

        if ("allocation".equals(signalFamily)) {
            className = extractAllocationClass(event);
            sizeBytes = extractAllocationBytes(event);
            allocator = extractAllocator(event);
        } else if ("classLoading".equals(signalFamily)) {
            className = extractClassLoadingClass(event);
            sizeBytes = extractClassLoadingBytes(event);
            objectDescription = bestStringCandidate(event, List.of("reason", "cause", "phase"));
        } else if ("oldObject".equals(signalFamily)) {
            className = extractOldObjectClass(event);
            sizeBytes = extractOldObjectBytes(event);
            rootType = extractOldObjectRootType(event);
            rootSystem = extractOldObjectRootSystem(event);
            rootDescription = extractOldObjectRootDescription(event);
            Long depth = extractOldObjectReferenceDepth(event);
            referenceDepth = depth != null ? depth : 0L;
            Long age = extractOldObjectAgeMs(event);
            objectAgeMs = age != null ? age : 0L;
            objectDescription = extractOldObjectDescription(event);
        }

        if ("executionSample".equals(signalFamily) && topMethod == null) {
            return null;
        }
        if ("generic".equals(signalFamily)
            && durationMs == 0L
            && topMethod == null
            && eventThreadName == null
            && className == null
            && sizeBytes == 0L) {
            return null;
        }

        Instant effectiveStart = eventStartTime != null ? eventStartTime : eventEndTime;
        Instant effectiveEnd = eventEndTime != null ? eventEndTime : eventStartTime;
        String label = event.getEventType().getLabel();
        return new JfrTimelineEvent(
            eventTypeName,
            label != null && !label.isBlank() ? label : eventTypeName,
            signalFamily,
            effectiveStart,
            effectiveEnd,
            durationMs,
            topMethod,
            topStack,
            eventThreadName,
            className,
            sizeBytes,
            allocator,
            rootType,
            rootSystem,
            rootDescription,
            referenceDepth,
            objectAgeMs,
            objectDescription
        );
    }

    private List<JfrIncidentWindow> incidentWindows(Instant recordingStart, List<JfrTimelineEvent> timelineEvents) {
        if (recordingStart == null || timelineEvents == null || timelineEvents.isEmpty()) {
            return List.of();
        }

        List<JfrTimelineEvent> sortedEvents = timelineEvents.stream()
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .toList();
        List<JfrIncidentWindow> windows = new ArrayList<>();
        addIncidentWindow(windows, selectIncidentWindow(sortedEvents, "runtime", "runtime-pressure", "Runtime pressure window"));
        addIncidentWindow(windows, selectIncidentWindow(sortedEvents, "class-loading", "class-loading-pressure", "Class-loading pressure window"));
        addIncidentWindow(windows, selectIncidentWindow(sortedEvents, "allocation", "allocation-pressure", "Allocation pressure window"));
        addIncidentWindow(windows, selectIncidentWindow(sortedEvents, "retention", "retained-object-pressure", "Retained-object window"));
        if (windows.isEmpty()) {
            addIncidentWindow(windows, selectIncidentWindow(sortedEvents, "activity", "recording-activity", "Recording activity window"));
        }
        windows.sort((left, right) -> {
            int compare = Double.compare(right.severityScore(), left.severityScore());
            if (compare != 0) {
                return compare;
            }
            compare = Integer.compare(incidentFocusPriority(left.focus()), incidentFocusPriority(right.focus()));
            if (compare != 0) {
                return compare;
            }
            compare = left.startTime().compareTo(right.startTime());
            if (compare != 0) {
                return compare;
            }
            return left.windowId().compareTo(right.windowId());
        });
        return List.copyOf(windows.stream().limit(MAX_INCIDENT_WINDOWS).toList());
    }

    private void addIncidentWindow(List<JfrIncidentWindow> windows, JfrIncidentWindow candidate) {
        if (candidate == null || candidate.events().isEmpty()) {
            return;
        }
        boolean duplicate = windows.stream().anyMatch(existing ->
            existing.startTime().equals(candidate.startTime())
                && existing.endTime().equals(candidate.endTime())
                && existing.events().size() == candidate.events().size()
        );
        if (!duplicate) {
            windows.add(candidate);
        }
    }

    private JfrIncidentWindow selectIncidentWindow(
        List<JfrTimelineEvent> allEvents,
        String focus,
        String windowId,
        String label
    ) {
        List<JfrTimelineEvent> focusEvents = allEvents.stream()
            .filter(event -> matchesIncidentFocus(event, focus))
            .toList();
        if (focusEvents.isEmpty()) {
            return null;
        }

        List<List<JfrTimelineEvent>> clusters = clusterTimelineEvents(focusEvents);
        List<JfrTimelineEvent> bestFocusCluster = List.of();
        double bestFocusScore = Double.NEGATIVE_INFINITY;
        for (List<JfrTimelineEvent> cluster : clusters) {
            double score = incidentFocusScore(focus, cluster);
            if (score > bestFocusScore) {
                bestFocusCluster = cluster;
                bestFocusScore = score;
            }
        }
        if (bestFocusCluster.isEmpty()) {
            return null;
        }

        Instant windowStart = bestFocusCluster.stream()
            .map(JfrTimelineEvent::startTime)
            .min(Comparator.naturalOrder())
            .orElse(null);
        Instant windowEnd = bestFocusCluster.stream()
            .map(JfrTimelineEvent::effectiveEndTime)
            .max(Comparator.naturalOrder())
            .orElse(windowStart);
        if (windowStart == null || windowEnd == null) {
            return null;
        }

        List<JfrTimelineEvent> expandedEvents = allEvents.stream()
            .filter(event -> overlapsIncidentWindow(event, windowStart, windowEnd))
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .toList();
        if (expandedEvents.isEmpty()) {
            expandedEvents = bestFocusCluster;
        }

        double severityScore = expandedEvents.stream()
            .mapToDouble(this::timelineEventImportance)
            .sum();
        return new JfrIncidentWindow(windowId, label, focus, List.copyOf(expandedEvents), severityScore);
    }

    private boolean matchesIncidentFocus(JfrTimelineEvent event, String focus) {
        if (event == null || focus == null) {
            return false;
        }
        String signalFamily = event.signalFamily();
        return switch (focus) {
            case "runtime" ->
                signalFamily.equals("gcPause")
                    || signalFamily.equals("lockContention")
                    || signalFamily.equals("monitorWait")
                    || signalFamily.equals("threadPark")
                    || signalFamily.equals("cpuLoad")
                    || signalFamily.equals("ioLatency")
                    || signalFamily.equals("exceptionBurst")
                    || signalFamily.equals("safepointPause");
            case "class-loading" -> signalFamily.equals("classLoading");
            case "allocation" -> signalFamily.equals("allocation");
            case "retention" -> signalFamily.equals("oldObject");
            case "activity" -> !signalFamily.equals("generic");
            default -> false;
        };
    }

    private List<List<JfrTimelineEvent>> clusterTimelineEvents(List<JfrTimelineEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<List<JfrTimelineEvent>> clusters = new ArrayList<>();
        List<JfrTimelineEvent> currentCluster = new ArrayList<>();
        Instant clusterEnd = null;
        for (JfrTimelineEvent event : events) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(event);
                clusterEnd = event.effectiveEndTime();
                continue;
            }

            Instant eventStart = event.startTime();
            boolean sameCluster = clusterEnd != null
                && eventStart != null
                && !eventStart.isAfter(clusterEnd.plusMillis(INCIDENT_CLUSTER_GAP_MS));
            if (sameCluster) {
                currentCluster.add(event);
                if (event.effectiveEndTime().isAfter(clusterEnd)) {
                    clusterEnd = event.effectiveEndTime();
                }
                continue;
            }

            clusters.add(List.copyOf(currentCluster));
            currentCluster = new ArrayList<>();
            currentCluster.add(event);
            clusterEnd = event.effectiveEndTime();
        }

        if (!currentCluster.isEmpty()) {
            clusters.add(List.copyOf(currentCluster));
        }
        return List.copyOf(clusters);
    }

    private double incidentFocusScore(String focus, List<JfrTimelineEvent> cluster) {
        return cluster.stream()
            .mapToDouble(event -> incidentEventScore(focus, event))
            .sum();
    }

    private double incidentEventScore(String focus, JfrTimelineEvent event) {
        double score = timelineEventImportance(event);
        if ("class-loading".equals(focus)) {
            return score + Math.min(12.0d, event.sizeBytes() / 1_000_000.0d);
        }
        if ("allocation".equals(focus)) {
            return score + Math.min(12.0d, event.sizeBytes() / 1_000_000.0d);
        }
        if ("retention".equals(focus)) {
            return score + Math.min(12.0d, event.sizeBytes() / 1_000_000.0d) + Math.min(6.0d, event.referenceDepth() / 2.0d);
        }
        if ("runtime".equals(focus) && event.durationMs() > 0L) {
            return score + Math.min(10.0d, event.durationMs() / 40.0d);
        }
        return score;
    }

    private double timelineEventImportance(JfrTimelineEvent event) {
        double score = 1.0d;
        score += Math.min(12.0d, event.durationMs() / 50.0d);
        if (event.sizeBytes() > 0L) {
            score += Math.min(12.0d, event.sizeBytes() / 1_000_000.0d);
        }
        if (event.referenceDepth() > 0L) {
            score += Math.min(6.0d, event.referenceDepth() / 2.0d);
        }
        score += switch (event.signalFamily()) {
            case "gcPause" -> 3.0d;
            case "lockContention" -> 2.6d;
            case "monitorWait" -> 2.3d;
            case "threadPark", "ioLatency", "safepointPause" -> 2.0d;
            case "cpuLoad" -> 1.8d;
            case "classLoading" -> 2.2d;
            case "allocation" -> 2.2d;
            case "oldObject" -> 2.4d;
            case "exceptionBurst" -> 1.5d;
            case "executionSample" -> 1.0d;
            default -> 0.5d;
        };
        if (event.topMethod() != null) {
            score += 0.35d;
        }
        if (event.eventThread() != null) {
            score += 0.15d;
        }
        return score;
    }

    private boolean overlapsIncidentWindow(JfrTimelineEvent event, Instant start, Instant end) {
        if (event == null || start == null || end == null) {
            return false;
        }
        Instant eventStart = event.startTime();
        Instant eventEnd = event.effectiveEndTime();
        return !eventStart.isAfter(end.plusMillis(INCIDENT_CLUSTER_GAP_MS))
            && !eventEnd.isBefore(start.minusMillis(INCIDENT_CLUSTER_GAP_MS));
    }

    private Map<String, Object> incidentWindowCanonicalMap(Instant recordingStart, JfrIncidentWindow window) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("windowId", window.windowId());
        canonical.put("label", window.label());
        canonical.put("focus", window.focus());
        canonical.put("startTime", window.startTime().toString());
        canonical.put("endTime", window.endTime().toString());
        canonical.put("relativeStartMs", relativeMillis(recordingStart, window.startTime()));
        canonical.put("relativeEndMs", relativeMillis(recordingStart, window.endTime()));
        canonical.put("durationMs", Math.max(0L, Duration.between(window.startTime(), window.endTime()).toMillis()));
        canonical.put("eventCount", window.events().size());
        canonical.put("summaryLine", incidentWindowSummaryLine(recordingStart, window));

        long totalDurationMs = window.events().stream()
            .mapToLong(JfrTimelineEvent::durationMs)
            .sum();
        if (totalDurationMs > 0L) {
            canonical.put("totalDurationMs", totalDurationMs);
        }

        long totalAllocatedBytes = window.events().stream()
            .filter(event -> "allocation".equals(event.signalFamily()))
            .mapToLong(JfrTimelineEvent::sizeBytes)
            .sum();
        if (totalAllocatedBytes > 0L) {
            canonical.put("totalAllocatedBytes", totalAllocatedBytes);
        }
        long totalMetadataBytes = window.events().stream()
            .filter(event -> "classLoading".equals(event.signalFamily()))
            .mapToLong(JfrTimelineEvent::sizeBytes)
            .sum();
        if (totalMetadataBytes > 0L) {
            canonical.put("totalMetadataBytes", totalMetadataBytes);
        }

        long totalSampledObjectBytes = window.events().stream()
            .filter(event -> "oldObject".equals(event.signalFamily()))
            .mapToLong(JfrTimelineEvent::sizeBytes)
            .sum();
        if (totalSampledObjectBytes > 0L) {
            canonical.put("totalSampledObjectBytes", totalSampledObjectBytes);
        }

        long maxReferenceDepth = window.events().stream()
            .mapToLong(JfrTimelineEvent::referenceDepth)
            .max()
            .orElse(0L);
        if (maxReferenceDepth > 0L) {
            canonical.put("maxReferenceDepth", maxReferenceDepth);
        }

        List<Map<String, Object>> dominantSignals = dominantSignalBreakdown(window.events());
        if (!dominantSignals.isEmpty()) {
            canonical.put("dominantSignals", dominantSignals);
        }

        List<Map<String, Object>> topEventTypes = rankedIncidentEventTypes(window.events());
        if (!topEventTypes.isEmpty()) {
            canonical.put("topEventTypes", topEventTypes);
        }

        Map<String, Long> methodCounts = new LinkedHashMap<>();
        Map<String, Long> threadCounts = new LinkedHashMap<>();
        Map<String, Long> classCounts = new LinkedHashMap<>();
        Map<String, Long> classBytes = new LinkedHashMap<>();
        Map<String, Long> rootCounts = new LinkedHashMap<>();
        for (JfrTimelineEvent event : window.events()) {
            if (event.topMethod() != null) {
                methodCounts.merge(event.topMethod(), 1L, Long::sum);
            }
            if (event.eventThread() != null) {
                threadCounts.merge(event.eventThread(), 1L, Long::sum);
            }
            if (event.className() != null) {
                classCounts.merge(event.className(), 1L, Long::sum);
                if (event.sizeBytes() > 0L) {
                    classBytes.merge(event.className(), event.sizeBytes(), Long::sum);
                }
            }
            String rootToken = firstNonBlank(event.rootType(), event.rootSystem());
            if (rootToken != null) {
                rootCounts.merge(rootToken, 1L, Long::sum);
            }
        }
        if (!methodCounts.isEmpty()) {
            canonical.put("topMethods", rankedHotspots(methodCounts, window.events().size(), "method").stream()
                .limit(MAX_WINDOW_TOP_ITEMS)
                .toList());
        }
        if (!threadCounts.isEmpty()) {
            canonical.put("topThreads", rankedHotspots(threadCounts, window.events().size(), "thread").stream()
                .limit(MAX_WINDOW_TOP_ITEMS)
                .toList());
        }
        if (!classCounts.isEmpty()) {
            canonical.put("topClasses", rankedEntities(classCounts, classBytes, window.events().size(), Math.max(1L, totalAllocatedBytes + totalSampledObjectBytes), "className").stream()
                .limit(MAX_WINDOW_TOP_ITEMS)
                .toList());
        }
        if (!rootCounts.isEmpty()) {
            canonical.put("topRoots", rankedHotspots(rootCounts, window.events().size(), "root").stream()
                .limit(MAX_WINDOW_TOP_ITEMS)
                .toList());
        }

        canonical.put(
            "chronology",
            representativeChronologyEvents(window.events(), MAX_WINDOW_CHRONOLOGY_EVENTS).stream()
                .map(event -> timelineEventCanonicalMap(recordingStart, event))
                .toList()
        );
        return Map.copyOf(canonical);
    }

    private Map<String, Object> incidentWindowSummary(Instant recordingStart, List<JfrIncidentWindow> windows) {
        if (recordingStart == null || windows == null || windows.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put(
            "summaryLines",
            windows.stream()
                .map(window -> incidentWindowSummaryLine(recordingStart, window))
                .toList()
        );
        summary.put("availableWindows", windows.stream().map(JfrIncidentWindow::windowId).toList());
        summary.put("primaryIncident", incidentWindowSnapshot(recordingStart, windows.getFirst()));
        for (JfrIncidentWindow window : windows) {
            summary.put(incidentSummaryKey(window.focus()), incidentWindowSnapshot(recordingStart, window));
        }
        return immutableOrderedMap(summary);
    }

    private String incidentSummaryKey(String focus) {
        return switch (focus) {
            case "runtime" -> "runtimePressure";
            case "class-loading" -> "classLoadingPressure";
            case "allocation" -> "allocationPressure";
            case "retention" -> "retainedObjectPressure";
            default -> focus + "Window";
        };
    }

    private Map<String, Object> incidentWindowSnapshot(Instant recordingStart, JfrIncidentWindow window) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("windowId", window.windowId());
        snapshot.put("focus", window.focus());
        snapshot.put("startTime", window.startTime().toString());
        snapshot.put("endTime", window.endTime().toString());
        snapshot.put("relativeStartMs", relativeMillis(recordingStart, window.startTime()));
        snapshot.put("relativeEndMs", relativeMillis(recordingStart, window.endTime()));
        snapshot.put("durationMs", Math.max(0L, Duration.between(window.startTime(), window.endTime()).toMillis()));
        snapshot.put("eventCount", window.events().size());
        List<Map<String, Object>> dominantSignals = dominantSignalBreakdown(window.events()).stream()
            .limit(3)
            .toList();
        if (!dominantSignals.isEmpty()) {
            snapshot.put("dominantSignals", dominantSignals);
        }

        String topMethod = window.events().stream()
            .map(JfrTimelineEvent::topMethod)
            .filter(method -> method != null && !method.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(method -> method, LinkedHashMap::new, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (topMethod != null) {
            snapshot.put("topMethod", topMethod);
        }

        String topClass = window.events().stream()
            .map(JfrTimelineEvent::className)
            .filter(className -> className != null && !className.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(className -> className, LinkedHashMap::new, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (topClass != null) {
            snapshot.put("topClass", topClass);
        }

        String topRoot = window.events().stream()
            .map(event -> firstNonBlank(event.rootType(), event.rootSystem()))
            .filter(root -> root != null && !root.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(root -> root, LinkedHashMap::new, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (topRoot != null) {
            snapshot.put("topRoot", topRoot);
        }

        snapshot.put("summaryLine", incidentWindowSummaryLine(recordingStart, window));
        return immutableOrderedMap(snapshot);
    }

    private String incidentWindowSummaryLine(Instant recordingStart, JfrIncidentWindow window) {
        long startOffsetMs = relativeMillis(recordingStart, window.startTime());
        long endOffsetMs = relativeMillis(recordingStart, window.endTime());
        List<Map<String, Object>> dominantSignals = dominantSignalBreakdown(window.events()).stream()
            .limit(3)
            .toList();
        String signals = dominantSignals.isEmpty()
            ? "mixed activity"
            : dominantSignals.stream()
                .map(signal -> humanSignalFamily(stringValue(signal.get("signalFamily")))
                    + ' '
                    + signal.get("count"))
                .collect(java.util.stream.Collectors.joining(", "));

        StringBuilder line = new StringBuilder();
        line.append(switch (window.focus()) {
            case "runtime" -> "Runtime incident";
            case "allocation" -> "Allocation incident";
            case "retention" -> "Retained-object incident";
            default -> "Recording activity";
        });
        line.append(": ");
        line.append(String.format(Locale.ROOT, "+%.3fs to +%.3fs", startOffsetMs / 1000.0d, endOffsetMs / 1000.0d));
        line.append("; ").append(window.events().size()).append(" event(s); ").append(signals);

        String topMethod = window.events().stream()
            .map(JfrTimelineEvent::topMethod)
            .filter(method -> method != null && !method.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(method -> method, LinkedHashMap::new, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (topMethod != null) {
            line.append("; hotspot ").append(topMethod);
        }

        String topClass = window.events().stream()
            .map(JfrTimelineEvent::className)
            .filter(className -> className != null && !className.isBlank())
            .collect(java.util.stream.Collectors.groupingBy(className -> className, LinkedHashMap::new, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (topClass != null) {
            line.append("; class ").append(topClass);
        }

        long totalBytes = window.events().stream()
            .mapToLong(JfrTimelineEvent::sizeBytes)
            .sum();
        if (totalBytes > 0L && ("allocation".equals(window.focus()) || "retention".equals(window.focus()))) {
            line.append("; about ").append(totalBytes).append(" bytes");
        }
        return line.toString() + '.';
    }

    private List<Map<String, Object>> dominantSignalBreakdown(List<JfrTimelineEvent> events) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> durationMs = new LinkedHashMap<>();
        for (JfrTimelineEvent event : events) {
            counts.merge(event.signalFamily(), 1L, Long::sum);
            if (event.durationMs() > 0L) {
                durationMs.merge(event.signalFamily(), event.durationMs(), Long::sum);
            }
        }

        return counts.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue(), left.getValue());
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(durationMs.getOrDefault(right.getKey(), 0L), durationMs.getOrDefault(left.getKey(), 0L));
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .limit(MAX_WINDOW_TOP_ITEMS)
            .map(entry -> {
                LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
                canonical.put("signalFamily", entry.getKey());
                canonical.put("label", humanSignalFamily(entry.getKey()));
                canonical.put("count", entry.getValue());
                long familyDurationMs = durationMs.getOrDefault(entry.getKey(), 0L);
                if (familyDurationMs > 0L) {
                    canonical.put("totalDurationMs", familyDurationMs);
                }
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private List<Map<String, Object>> rankedIncidentEventTypes(List<JfrTimelineEvent> events) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> durationMs = new LinkedHashMap<>();
        for (JfrTimelineEvent event : events) {
            counts.merge(event.eventTypeName(), 1L, Long::sum);
            if (event.durationMs() > 0L) {
                durationMs.merge(event.eventTypeName(), event.durationMs(), Long::sum);
            }
        }

        return counts.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue(), left.getValue());
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(durationMs.getOrDefault(right.getKey(), 0L), durationMs.getOrDefault(left.getKey(), 0L));
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .limit(MAX_WINDOW_TOP_ITEMS)
            .map(entry -> {
                LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
                canonical.put("eventType", entry.getKey());
                canonical.put("count", entry.getValue());
                long totalDurationMs = durationMs.getOrDefault(entry.getKey(), 0L);
                if (totalDurationMs > 0L) {
                    canonical.put("totalDurationMs", totalDurationMs);
                }
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private List<Map<String, Object>> chronologyHighlights(
        Instant recordingStart,
        List<JfrIncidentWindow> windows,
        List<JfrTimelineEvent> timelineEvents
    ) {
        if (recordingStart == null || timelineEvents == null || timelineEvents.isEmpty()) {
            return List.of();
        }

        List<JfrTimelineEvent> selected = new ArrayList<>();
        if (windows != null) {
            for (int index = 0; index < Math.min(MAX_INCIDENT_WINDOWS, windows.size()); index++) {
                selected.addAll(representativeChronologyEvents(windows.get(index).events(), MAX_WINDOW_CHRONOLOGY_EVENTS));
            }
        }
        if (selected.isEmpty()) {
            selected.addAll(representativeChronologyEvents(timelineEvents, MAX_CHRONOLOGY_HIGHLIGHTS));
        }

        LinkedHashMap<String, JfrTimelineEvent> deduplicated = new LinkedHashMap<>();
        for (JfrTimelineEvent event : selected) {
            deduplicated.putIfAbsent(eventKey(event), event);
        }
        return deduplicated.values().stream()
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .limit(MAX_CHRONOLOGY_HIGHLIGHTS)
            .map(event -> timelineEventCanonicalMap(recordingStart, event))
            .toList();
    }

    private List<JfrTimelineEvent> representativeChronologyEvents(List<JfrTimelineEvent> events, int limit) {
        List<JfrTimelineEvent> sorted = events.stream()
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .toList();
        if (sorted.size() <= limit) {
            return List.copyOf(sorted);
        }

        List<JfrTimelineEvent> selected = new ArrayList<>();
        selected.add(sorted.getFirst());
        selected.add(sorted.getLast());
        sorted.stream()
            .sorted(Comparator.comparingDouble(this::timelineEventImportance).reversed()
                .thenComparing(JfrTimelineEvent::startTime))
            .limit(Math.max(0, limit - 2))
            .forEach(selected::add);

        LinkedHashMap<String, JfrTimelineEvent> deduplicated = new LinkedHashMap<>();
        for (JfrTimelineEvent event : selected) {
            deduplicated.putIfAbsent(eventKey(event), event);
        }
        return deduplicated.values().stream()
            .sorted(Comparator.comparing(JfrTimelineEvent::startTime).thenComparing(JfrTimelineEvent::eventTypeName))
            .limit(limit)
            .toList();
    }

    private Map<String, Object> timelineEventCanonicalMap(Instant recordingStart, JfrTimelineEvent event) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("startTime", event.startTime().toString());
        canonical.put("endTime", event.effectiveEndTime().toString());
        canonical.put("relativeStartMs", relativeMillis(recordingStart, event.startTime()));
        canonical.put("relativeEndMs", relativeMillis(recordingStart, event.effectiveEndTime()));
        canonical.put("signalFamily", event.signalFamily());
        canonical.put("signalLabel", humanSignalFamily(event.signalFamily()));
        canonical.put("eventType", event.eventTypeName());
        if (event.label() != null && !event.label().isBlank()) {
            canonical.put("label", event.label());
        }
        if (event.durationMs() > 0L) {
            canonical.put("durationMs", event.durationMs());
        }
        if (event.topMethod() != null) {
            canonical.put("topMethod", event.topMethod());
        }
        if (event.topStack() != null) {
            canonical.put("topStack", event.topStack());
        }
        if (event.eventThread() != null) {
            canonical.put("eventThread", event.eventThread());
        }
        if (event.className() != null) {
            canonical.put("className", event.className());
        }
        if (event.sizeBytes() > 0L) {
            canonical.put(
                switch (event.signalFamily()) {
                    case "oldObject" -> "sampledObjectBytes";
                    case "classLoading" -> "metadataBytes";
                    default -> "allocatedBytes";
                },
                event.sizeBytes()
            );
        }
        if (event.allocator() != null) {
            canonical.put("allocator", event.allocator());
        }
        if (event.rootType() != null) {
            canonical.put("rootType", event.rootType());
        }
        if (event.rootSystem() != null) {
            canonical.put("rootSystem", event.rootSystem());
        }
        if (event.rootDescription() != null) {
            canonical.put("rootDescription", event.rootDescription());
        }
        if (event.referenceDepth() > 0L) {
            canonical.put("referenceDepth", event.referenceDepth());
        }
        if (event.objectAgeMs() > 0L) {
            canonical.put("objectAgeMs", event.objectAgeMs());
        }
        if (event.objectDescription() != null) {
            canonical.put("objectDescription", event.objectDescription());
        }
        canonical.put("summary", timelineEventSummary(event));
        return immutableOrderedMap(canonical);
    }

    private Map<String, Object> timelineEventIndexMap(Instant recordingStart, JfrTimelineEvent event) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("startTime", event.startTime().toString());
        canonical.put("endTime", event.effectiveEndTime().toString());
        canonical.put("relativeStartMs", relativeMillis(recordingStart, event.startTime()));
        canonical.put("relativeEndMs", relativeMillis(recordingStart, event.effectiveEndTime()));
        canonical.put("signalFamily", event.signalFamily());
        canonical.put("eventType", event.eventTypeName());
        if (event.label() != null && !event.label().isBlank()) {
            canonical.put("label", event.label());
        }
        if (event.durationMs() > 0L) {
            canonical.put("durationMs", event.durationMs());
        }
        if (event.topMethod() != null) {
            canonical.put("topMethod", event.topMethod());
        }
        if (event.topStack() != null) {
            canonical.put("topStack", event.topStack());
        }
        if (event.eventThread() != null) {
            canonical.put("eventThread", event.eventThread());
        }
        if (event.className() != null) {
            canonical.put("className", event.className());
        }
        if (event.sizeBytes() > 0L) {
            canonical.put(event.signalFamily().equals("oldObject") ? "sampledObjectBytes" : "allocatedBytes", event.sizeBytes());
        }
        if (event.allocator() != null) {
            canonical.put("allocator", event.allocator());
        }
        if (event.rootType() != null) {
            canonical.put("rootType", event.rootType());
        }
        if (event.rootSystem() != null) {
            canonical.put("rootSystem", event.rootSystem());
        }
        if (event.rootDescription() != null) {
            canonical.put("rootDescription", event.rootDescription());
        }
        if (event.referenceDepth() > 0L) {
            canonical.put("referenceDepth", event.referenceDepth());
        }
        if (event.objectAgeMs() > 0L) {
            canonical.put("objectAgeMs", event.objectAgeMs());
        }
        if (event.objectDescription() != null) {
            canonical.put("objectDescription", event.objectDescription());
        }
        return immutableOrderedMap(canonical);
    }

    private String timelineEventSummary(JfrTimelineEvent event) {
        String label = firstNonBlank(event.label(), event.eventTypeName(), humanSignalFamily(event.signalFamily()));
        StringBuilder summary = new StringBuilder(label);
        if (event.durationMs() > 0L) {
            summary.append(" ").append(event.durationMs()).append(" ms");
        }
        if (event.className() != null) {
            summary.append(" class ").append(event.className());
        }
        if (event.sizeBytes() > 0L) {
            summary.append(" ").append(event.sizeBytes()).append(" bytes");
        }
        if (event.rootType() != null || event.rootSystem() != null) {
            summary.append(" root ");
            summary.append(firstNonBlank(event.rootType(), "unknown"));
            if (event.rootSystem() != null) {
                summary.append('/').append(event.rootSystem());
            }
        }
        if (event.objectAgeMs() > 0L) {
            summary.append(" age ").append(event.objectAgeMs()).append(" ms");
        }
        if (event.topMethod() != null) {
            summary.append(" at ").append(event.topMethod());
        }
        if (event.eventThread() != null) {
            summary.append(" on ").append(event.eventThread());
        }
        return summary.toString();
    }

    private long relativeMillis(Instant recordingStart, Instant instant) {
        if (recordingStart == null || instant == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(recordingStart, instant).toMillis());
    }

    private String humanSignalFamily(String signalFamily) {
        return switch (signalFamily == null ? "" : signalFamily) {
            case "gcPause" -> "GC pauses";
            case "lockContention" -> "monitor blocks";
            case "monitorWait" -> "monitor waits";
            case "threadPark" -> "thread parks";
            case "cpuLoad" -> "CPU load";
            case "ioLatency" -> "I/O latency";
            case "exceptionBurst" -> "exceptions";
            case "safepointPause" -> "safepoints";
            case "classLoading" -> "class loading";
            case "allocation" -> "allocations";
            case "oldObject" -> "retained objects";
            case "executionSample" -> "execution samples";
            default -> "activity";
        };
    }

    private int incidentFocusPriority(String focus) {
        return switch (focus == null ? "" : focus) {
            case "runtime" -> 1;
            case "class-loading" -> 2;
            case "allocation" -> 3;
            case "retention" -> 4;
            default -> 5;
        };
    }

    private String eventKey(JfrTimelineEvent event) {
        return String.join(
            "|",
            event.startTime().toString(),
            event.eventTypeName(),
            String.valueOf(event.durationMs()),
            firstNonBlank(event.topMethod(), ""),
            firstNonBlank(event.className(), ""),
            firstNonBlank(event.rootType(), ""),
            String.valueOf(event.objectAgeMs())
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private StackFingerprint stackFingerprint(RecordedStackTrace stackTrace) {
        if (stackTrace == null || stackTrace.getFrames() == null || stackTrace.getFrames().isEmpty()) {
            return null;
        }

        List<String> javaMethods = new ArrayList<>();
        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (!frame.isJavaFrame()) {
                continue;
            }
            String methodName = methodName(frame.getMethod());
            if (methodName != null) {
                javaMethods.add(methodName);
            }
            if (javaMethods.size() >= 8) {
                break;
            }
        }

        if (javaMethods.isEmpty()) {
            return null;
        }

        String primaryMethod = javaMethods.getFirst();
        String stackSignature = String.join(" <- ", javaMethods.stream().limit(5).toList());
        return new StackFingerprint(primaryMethod, stackSignature, stackTrace.isTruncated());
    }

    private String methodName(RecordedMethod method) {
        if (method == null || method.getType() == null || method.getName() == null) {
            return null;
        }
        return method.getType().getName() + "." + method.getName();
    }

    private boolean isIgnoredAllocationField(String fieldName) {
        String normalized = normalize(fieldName);
        return normalized.equals("starttime")
            || normalized.equals("endtime")
            || normalized.equals("duration")
            || normalized.equals("eventthread")
            || normalized.equals("thread")
            || normalized.equals("sampledthread")
            || normalized.equals("stacktrace");
    }

    private boolean isIgnoredOldObjectField(String fieldName) {
        String normalized = normalize(fieldName);
        return normalized.equals("starttime")
            || normalized.equals("endtime")
            || normalized.equals("duration")
            || normalized.equals("eventthread")
            || normalized.equals("thread")
            || normalized.equals("sampledthread")
            || normalized.equals("stacktrace");
    }

    private String extractAllocationClass(RecordedEvent event) {
        String candidate = bestClassCandidate(event, List.of(
            "objectClass",
            "objectClassName",
            "allocationClass",
            "allocatedClass",
            "className",
            "class",
            "type"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredAllocationField(fieldName) || (!normalized.contains("class") && !normalized.contains("type"))) {
                continue;
            }
            candidate = classNameValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractClassLoadingClass(RecordedEvent event) {
        String candidate = bestClassCandidate(event, List.of(
            "definedClass",
            "loadedClass",
            "objectClass",
            "className",
            "definedClassName",
            "loadedClassName",
            "class",
            "type"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName) || !normalized.contains("class")) {
                continue;
            }
            candidate = classNameValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractClassLoader(RecordedEvent event) {
        for (String fieldName : List.of("loaderName", "classLoaderName", "loader", "classLoader")) {
            String candidate = objectNameValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || (!normalized.contains("loader") && !normalized.contains("module"))) {
                continue;
            }
            String candidate = objectNameValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private long extractClassLoadingBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "metadataBytes",
            "classBytes",
            "bytes",
            "weight",
            "size"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || (!normalized.contains("byte") && !normalized.endsWith("size") && !normalized.contains("weight"))) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private String extractCompiledMethod(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of(
            "compiledMethod",
            "compileTarget",
            "methodName",
            "method",
            "nmethod"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName) || (!normalized.contains("method") && !normalized.contains("target"))) {
                continue;
            }
            candidate = stringValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractCompilerName(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of("compiler", "compilerName"));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName) || !normalized.contains("compiler")) {
                continue;
            }
            candidate = stringValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private long extractCompilationQueueSize(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "compileQueueSize",
            "compilationQueueSize",
            "queueSize",
            "queuedMethods",
            "backlog"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || (!normalized.contains("queue") && !normalized.contains("backlog"))) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private long extractCodeCacheUsedBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "codeCacheUsedBytes",
            "usedBytes",
            "used"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName) || (!normalized.contains("codecache") && !normalized.equals("used") && !normalized.endsWith("usedbytes"))) {
                continue;
            }
            if (!normalized.contains("used")) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private long extractCodeCacheFreeBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "codeCacheFreeBytes",
            "freeBytes",
            "free"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName) || !normalized.contains("free")) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private long extractCodeCacheSizeBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "codeCacheSizeBytes",
            "capacityBytes",
            "sizeBytes",
            "reservedBytes",
            "codeCacheCapacityBytes"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || (!normalized.contains("size") && !normalized.contains("capacity") && !normalized.contains("reserved"))) {
                continue;
            }
            if (!normalized.contains("codecache") && !normalized.endsWith("sizebytes") && !normalized.endsWith("capacitybytes")) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private String extractCodeCacheReason(RecordedEvent event) {
        return bestStringCandidate(event, List.of("reason", "cause", "message"));
    }

    private boolean extractCompilerDisabled(RecordedEvent event) {
        Object value = extractFieldValue(event, "compilerDisabled");
        Boolean candidate = booleanValue(value);
        if (candidate != null) {
            return candidate;
        }
        String reason = extractCodeCacheReason(event);
        return reason != null && normalize(reason).contains("disabled");
    }

    private Double extractMachineCpuLoad(RecordedEvent event) {
        Double candidate = bestNonNegativeDoubleCandidate(event, List.of(
            "machineTotal",
            "machineCpuLoad",
            "machineLoad"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || !normalized.contains("machine")
                || (!normalized.contains("load") && !normalized.contains("total"))) {
                continue;
            }
            candidate = doubleValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Double extractJvmUserCpuLoad(RecordedEvent event) {
        Double candidate = bestNonNegativeDoubleCandidate(event, List.of(
            "jvmUser",
            "processUser",
            "userCpuLoad"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || !normalized.contains("user")
                || (!normalized.contains("jvm") && !normalized.contains("process"))) {
                continue;
            }
            candidate = doubleValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Double extractJvmSystemCpuLoad(RecordedEvent event) {
        Double candidate = bestNonNegativeDoubleCandidate(event, List.of(
            "jvmSystem",
            "processSystem",
            "systemCpuLoad"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredGenericEventField(fieldName)
                || !normalized.contains("system")
                || (!normalized.contains("jvm") && !normalized.contains("process"))) {
                continue;
            }
            candidate = doubleValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Double extractJvmTotalCpuLoad(RecordedEvent event) {
        Double candidate = bestNonNegativeDoubleCandidate(event, List.of(
            "jvmTotal",
            "processTotal",
            "processCpuLoad"
        ));
        if (candidate != null) {
            return candidate;
        }

        Double userLoad = extractJvmUserCpuLoad(event);
        Double systemLoad = extractJvmSystemCpuLoad(event);
        if (userLoad == null && systemLoad == null) {
            return null;
        }
        return Math.min(1.0d, (userLoad != null ? userLoad : 0.0d) + (systemLoad != null ? systemLoad : 0.0d));
    }

    private Double extractThreadUserCpuLoad(RecordedEvent event) {
        return bestNonNegativeDoubleCandidate(event, List.of(
            "user",
            "threadUser",
            "threadUserLoad"
        ));
    }

    private Double extractThreadSystemCpuLoad(RecordedEvent event) {
        return bestNonNegativeDoubleCandidate(event, List.of(
            "system",
            "threadSystem",
            "threadSystemLoad"
        ));
    }

    private Double extractThreadTotalCpuLoad(RecordedEvent event) {
        Double candidate = bestNonNegativeDoubleCandidate(event, List.of(
            "total",
            "threadTotal",
            "threadCpuLoad"
        ));
        if (candidate != null) {
            return candidate;
        }

        Double userLoad = extractThreadUserCpuLoad(event);
        Double systemLoad = extractThreadSystemCpuLoad(event);
        if (userLoad == null && systemLoad == null) {
            return null;
        }
        return Math.min(1.0d, (userLoad != null ? userLoad : 0.0d) + (systemLoad != null ? systemLoad : 0.0d));
    }

    private String extractCpuSampleThreadName(RecordedEvent event) {
        String candidate = extractEventThreadName(event);
        if (candidate != null) {
            return candidate;
        }
        return bestStringCandidate(event, List.of("sampledThreadName", "threadName"));
    }

    private long extractAllocationBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "allocationSize",
            "weight",
            "objectSize",
            "bytesAllocated",
            "bytes",
            "size"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredAllocationField(fieldName)) {
                continue;
            }
            if (normalized.equals("tlabsize")
                || (!normalized.contains("allocation") && !normalized.endsWith("size") && !normalized.endsWith("bytes"))) {
                continue;
            }
            candidate = numericValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return 0L;
    }

    private long extractTlabBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of("tlabSize", "tlabBytes"));
        return candidate != null ? candidate : 0L;
    }

    private String extractAllocator(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of(
            "allocator",
            "allocationContext",
            "allocationSource",
            "allocationPathLabel",
            "context"
        ));
        if (candidate != null) {
            return candidate;
        }

        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            String normalized = normalize(fieldName);
            if (isIgnoredAllocationField(fieldName)) {
                continue;
            }
            if (!normalized.contains("allocator") && !normalized.contains("context") && !normalized.contains("source")) {
                continue;
            }
            candidate = stringValue(extractFieldValue(event, fieldName));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractOldObjectClass(RecordedEvent event) {
        String candidate = bestClassCandidate(event, List.of(
            "objectClass",
            "objectType",
            "className",
            "class",
            "type"
        ));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject oldObject = recordedObjectValue(extractFieldValue(event, "object"));
        if (oldObject != null) {
            candidate = classNameValue(extractFieldValue(oldObject, "type"));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private long extractOldObjectBytes(RecordedEvent event) {
        Long candidate = bestNumericCandidate(event, List.of(
            "objectSize",
            "sampledObjectSize",
            "retainedSize",
            "totalSize",
            "size"
        ));
        return candidate != null ? candidate : 0L;
    }

    private Long extractOldObjectAgeMs(RecordedEvent event) {
        return bestDurationMillisCandidate(event, List.of(
            "objectAge",
            "age",
            "objectAgeMs",
            "ageMs"
        ));
    }

    private Instant extractOldObjectAllocationTime(RecordedEvent event) {
        return bestInstantCandidate(event, List.of(
            "allocationTime",
            "allocatedAt",
            "allocationTimestamp"
        ));
    }

    private Integer extractOldObjectArrayElements(RecordedEvent event) {
        Long candidate = bestNonNegativeNumericCandidate(event, List.of(
            "arrayElements",
            "arrayLength"
        ));
        return candidate != null ? candidate.intValue() : null;
    }

    private String extractOldObjectDescription(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of(
            "description",
            "objectDescription"
        ));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject oldObject = recordedObjectValue(extractFieldValue(event, "object"));
        if (oldObject != null) {
            candidate = stringValue(extractFieldValue(oldObject, "description"));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractOldObjectRootType(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of("rootType"));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject root = recordedObjectValue(extractFieldValue(event, "root"));
        return root != null ? stringValue(extractFieldValue(root, "type")) : null;
    }

    private String extractOldObjectRootSystem(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of("rootSystem"));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject root = recordedObjectValue(extractFieldValue(event, "root"));
        return root != null ? stringValue(extractFieldValue(root, "system")) : null;
    }

    private String extractOldObjectRootDescription(RecordedEvent event) {
        String candidate = bestStringCandidate(event, List.of("rootDescription"));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject root = recordedObjectValue(extractFieldValue(event, "root"));
        return root != null ? stringValue(extractFieldValue(root, "description")) : null;
    }

    private Long extractOldObjectReferenceDepth(RecordedEvent event) {
        Long candidate = bestNonNegativeNumericCandidate(event, List.of(
            "referenceDepth",
            "referrerDepth",
            "pathDepth",
            "depth"
        ));
        if (candidate != null) {
            return candidate;
        }

        RecordedObject oldObject = recordedObjectValue(extractFieldValue(event, "object"));
        if (oldObject == null) {
            return null;
        }
        return walkReferrerDepth(oldObject);
    }

    private long walkReferrerDepth(RecordedObject oldObject) {
        long depth = 0L;
        RecordedObject currentObject = oldObject;
        for (int guard = 0; guard < 64; guard++) {
            RecordedObject referrer = recordedObjectValue(extractFieldValue(currentObject, "referrer"));
            if (referrer == null) {
                return depth;
            }
            depth += 1L;
            Long skip = bestNonNegativeNumericCandidate(referrer, List.of("skip"));
            if (skip != null && skip > 0L) {
                depth += skip;
            }
            RecordedObject nextObject = recordedObjectValue(extractFieldValue(referrer, "object"));
            if (nextObject == null) {
                return depth;
            }
            currentObject = nextObject;
        }
        return depth;
    }

    private Object extractFieldValue(RecordedObject source, String fieldName) {
        if (source == null || fieldName == null || !source.hasField(fieldName)) {
            return null;
        }
        try {
            return source.getValue(fieldName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String bestClassCandidate(RecordedEvent event, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            String className = classNameValue(extractFieldValue(event, candidateFieldName));
            if (className != null) {
                return className;
            }
        }
        return null;
    }

    private Long bestNumericCandidate(RecordedEvent event, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            Long numeric = numericValue(extractFieldValue(event, candidateFieldName));
            if (numeric != null && numeric > 0L) {
                return numeric;
            }
        }
        return null;
    }

    private Double bestNonNegativeDoubleCandidate(RecordedObject source, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            Double numeric = doubleValue(extractFieldValue(source, candidateFieldName));
            if (numeric != null) {
                return numeric;
            }
        }
        return null;
    }

    private Long bestNonNegativeNumericCandidate(RecordedObject source, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            Long numeric = nonNegativeNumericValue(extractFieldValue(source, candidateFieldName));
            if (numeric != null) {
                return numeric;
            }
        }
        return null;
    }

    private Long bestDurationMillisCandidate(RecordedObject source, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            Long durationMs = durationMillisValue(source, candidateFieldName);
            if (durationMs != null) {
                return durationMs;
            }
        }
        return null;
    }

    private Instant bestInstantCandidate(RecordedObject source, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            Instant instant = instantValue(source, candidateFieldName);
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    private String bestStringCandidate(RecordedEvent event, List<String> candidateFieldNames) {
        for (String candidateFieldName : candidateFieldNames) {
            String text = stringValue(extractFieldValue(event, candidateFieldName));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String classNameValue(Object value) {
        if (value instanceof RecordedClass recordedClass) {
            return stringValue(recordedClass.getName());
        }
        if (value instanceof Class<?> javaClass) {
            return stringValue(javaClass.getName());
        }
        if (value instanceof RecordedObject recordedObject) {
            return stringValue(extractFieldValue(recordedObject, "name"));
        }
        return stringValue(value);
    }

    private String objectNameValue(Object value) {
        if (value instanceof RecordedObject recordedObject) {
            for (String fieldName : List.of("javaName", "osName", "name", "type", "description")) {
                String candidate = stringValue(extractFieldValue(recordedObject, fieldName));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return stringValue(value);
    }

    private String stringValue(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            String normalized = normalize(text);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        return null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return normalizeCpuRatio(number.doubleValue());
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return normalizeCpuRatio(Double.parseDouble(trimmed));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long numericValue(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            return longValue > 0L ? longValue : null;
        }
        return null;
    }

    private Double normalizeCpuRatio(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d) {
            return null;
        }
        if (value > 1.0d && value <= 100.0d) {
            return value / 100.0d;
        }
        return value;
    }

    private Long nonNegativeNumericValue(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            return longValue >= 0L ? longValue : null;
        }
        return null;
    }

    private Long durationMillisValue(RecordedObject source, String fieldName) {
        if (source == null || fieldName == null || !source.hasField(fieldName)) {
            return null;
        }
        try {
            Duration duration = source.getDuration(fieldName);
            if (duration != null && !duration.isNegative()) {
                return Math.max(0L, duration.toMillis());
            }
        } catch (IllegalArgumentException ignored) {
        }

        Object value = extractFieldValue(source, fieldName);
        if (value instanceof Duration duration && !duration.isNegative()) {
            return Math.max(0L, duration.toMillis());
        }
        return nonNegativeNumericValue(value);
    }

    private Instant instantValue(RecordedObject source, String fieldName) {
        if (source == null || fieldName == null || !source.hasField(fieldName)) {
            return null;
        }
        try {
            Instant instant = source.getInstant(fieldName);
            if (instant != null) {
                return instant;
            }
        } catch (IllegalArgumentException ignored) {
        }

        Object value = extractFieldValue(source, fieldName);
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private RecordedObject recordedObjectValue(Object value) {
        return value instanceof RecordedObject recordedObject ? recordedObject : null;
    }

    private Map.Entry<String, Long> topEntry(Map<String, Long> counts) {
        return counts.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue(), left.getValue());
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .findFirst()
            .orElse(null);
    }

    private double ratio(long value, long total) {
        return total > 0L ? (double) value / (double) total : 0.0d;
    }

    private Map<String, Object> immutableOrderedMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private List<Map<String, Object>> rankedHotspots(Map<String, Long> counts, long totalCount, String keyName) {
        return counts.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue(), left.getValue());
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .map(entry -> {
                Map<String, Object> canonical = new LinkedHashMap<>();
                canonical.put(keyName, entry.getKey());
                canonical.put("count", entry.getValue());
                canonical.put("share", totalCount > 0L ? (double) entry.getValue() / (double) totalCount : 0.0d);
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private List<Map<String, Object>> rankedFieldPresence(Map<String, FieldPresenceAccumulator> fieldPresenceAccumulators, long totalCount) {
        return fieldPresenceAccumulators.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue().count(), left.getValue().count());
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .map(entry -> {
                FieldPresenceAccumulator accumulator = entry.getValue();
                Map<String, Object> canonical = new LinkedHashMap<>();
                canonical.put("field", entry.getKey());
                if (accumulator.label() != null && !accumulator.label().isBlank()) {
                    canonical.put("label", accumulator.label());
                }
                if (accumulator.typeName() != null && !accumulator.typeName().isBlank()) {
                    canonical.put("typeName", accumulator.typeName());
                }
                canonical.put("eventCount", accumulator.count());
                canonical.put("share", ratio(accumulator.count(), totalCount));
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private List<Map<String, Object>> rankedEntities(
        Map<String, Long> counts,
        Map<String, Long> bytes,
        long totalCount,
        long totalBytes,
        String keyName
    ) {
        List<String> keys = new ArrayList<>(counts.keySet());
        for (String key : bytes.keySet()) {
            if (!counts.containsKey(key)) {
                keys.add(key);
            }
        }

        return keys.stream()
            .sorted((left, right) -> {
                int compare = Long.compare(bytes.getOrDefault(right, 0L), bytes.getOrDefault(left, 0L));
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(counts.getOrDefault(right, 0L), counts.getOrDefault(left, 0L));
                if (compare != 0) {
                    return compare;
                }
                return left.compareTo(right);
            })
            .map(key -> {
                long count = counts.getOrDefault(key, 0L);
                long allocatedBytes = bytes.getOrDefault(key, 0L);
                Map<String, Object> canonical = new LinkedHashMap<>();
                canonical.put(keyName, key);
                canonical.put("count", count);
                canonical.put("share", ratio(count, totalCount));
                canonical.put("allocatedBytes", allocatedBytes);
                canonical.put("allocatedByteShare", ratio(allocatedBytes, totalBytes));
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private List<Map<String, Object>> rankedWeightedHotspots(
        Map<String, Long> counts,
        Map<String, Long> bytes,
        long totalCount,
        long totalBytes,
        String keyName
    ) {
        return counts.entrySet().stream()
            .sorted((left, right) -> {
                int compare = Long.compare(right.getValue(), left.getValue());
                if (compare != 0) {
                    return compare;
                }
                compare = Long.compare(bytes.getOrDefault(right.getKey(), 0L), bytes.getOrDefault(left.getKey(), 0L));
                if (compare != 0) {
                    return compare;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .map(entry -> {
                long count = entry.getValue();
                long allocatedBytes = bytes.getOrDefault(entry.getKey(), 0L);
                Map<String, Object> canonical = new LinkedHashMap<>();
                canonical.put(keyName, entry.getKey());
                canonical.put("count", count);
                canonical.put("share", ratio(count, totalCount));
                canonical.put("allocatedBytes", allocatedBytes);
                canonical.put("allocatedByteShare", ratio(allocatedBytes, totalBytes));
                return Map.copyOf(canonical);
            })
            .toList();
    }

    private boolean isIgnoredGenericEventField(String fieldName) {
        String normalized = normalize(fieldName);
        return normalized.equals("starttime")
            || normalized.equals("endtime")
            || normalized.equals("duration")
            || normalized.equals("eventthread")
            || normalized.equals("stacktrace");
    }

    private String extractEventThreadName(RecordedEvent event) {
        String candidate = threadNameValue(extractFieldValue(event, "eventThread"));
        if (candidate != null) {
            return candidate;
        }
        candidate = threadNameValue(extractFieldValue(event, "sampledThread"));
        if (candidate != null) {
            return candidate;
        }
        return threadNameValue(extractFieldValue(event, "thread"));
    }

    private String threadNameValue(Object value) {
        if (value instanceof RecordedObject recordedObject) {
            for (String fieldName : List.of("javaName", "osName", "name")) {
                String candidate = stringValue(extractFieldValue(recordedObject, fieldName));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return stringValue(value);
    }

    private Map<String, Object> genericEventSample(
        RecordedEvent event,
        StackFingerprint stackFingerprint,
        String eventThreadName,
        long durationMs
    ) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        Instant startTime = event.getStartTime();
        if (startTime != null) {
            sample.put("startTime", startTime.toString());
        }
        if (durationMs > 0L) {
            sample.put("durationMs", durationMs);
        }
        if (eventThreadName != null) {
            sample.put("eventThread", eventThreadName);
        }

        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (ValueDescriptor field : event.getFields()) {
            String fieldName = field.getName();
            if (isIgnoredGenericEventField(fieldName)) {
                continue;
            }
            if (fields.size() >= MAX_GENERIC_SAMPLE_FIELDS) {
                break;
            }
            Object compactValue = compactGenericEventValue(extractFieldValue(event, fieldName), 0);
            if (hasMeaningfulGenericValue(compactValue)) {
                fields.put(fieldName, compactValue);
            }
        }
        if (!fields.isEmpty()) {
            sample.put("fields", Map.copyOf(fields));
        }

        if (stackFingerprint != null) {
            sample.put("topMethod", stackFingerprint.primaryMethod());
            sample.put("topStack", stackFingerprint.stackSignature());
            if (stackFingerprint.truncated()) {
                sample.put("truncatedStack", true);
            }
        }
        return Map.copyOf(sample);
    }

    private Object compactGenericEventValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return DiagnosticContextRenderSupport.truncateSingleLine(text, MAX_GENERIC_RENDERED_STRING);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Duration duration) {
            return Math.max(0L, duration.toMillis());
        }
        if (value instanceof RecordedClass recordedClass) {
            return recordedClass.getName();
        }
        if (value instanceof Class<?> javaClass) {
            return javaClass.getName();
        }
        if (value instanceof RecordedMethod recordedMethod) {
            return methodName(recordedMethod);
        }
        if (value instanceof RecordedFrame recordedFrame) {
            return methodName(recordedFrame.getMethod());
        }
        if (value instanceof RecordedStackTrace stackTrace) {
            return compactRecordedStackTrace(stackTrace);
        }
        if (depth >= 2) {
            return DiagnosticContextRenderSupport.truncateSingleLine(String.valueOf(value), MAX_GENERIC_RENDERED_STRING);
        }
        if (value instanceof RecordedObject recordedObject) {
            return compactRecordedObject(recordedObject, depth + 1);
        }
        if (value instanceof List<?> list) {
            return compactGenericList(list, depth + 1);
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = Math.min(MAX_GENERIC_LIST_ITEMS, java.lang.reflect.Array.getLength(value));
            for (int index = 0; index < length; index++) {
                items.add(java.lang.reflect.Array.get(value, index));
            }
            return compactGenericList(items, depth + 1);
        }
        return DiagnosticContextRenderSupport.truncateSingleLine(String.valueOf(value), MAX_GENERIC_RENDERED_STRING);
    }

    private Map<String, Object> compactRecordedStackTrace(RecordedStackTrace stackTrace) {
        if (stackTrace == null || stackTrace.getFrames() == null || stackTrace.getFrames().isEmpty()) {
            return Map.of();
        }

        List<String> javaMethods = new ArrayList<>();
        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (!frame.isJavaFrame()) {
                continue;
            }
            String methodName = methodName(frame.getMethod());
            if (methodName != null) {
                javaMethods.add(methodName);
            }
            if (javaMethods.size() >= MAX_GENERIC_LIST_ITEMS) {
                break;
            }
        }
        if (javaMethods.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
        compact.put("topMethods", List.copyOf(javaMethods));
        if (stackTrace.isTruncated()) {
            compact.put("truncated", true);
        }
        return Map.copyOf(compact);
    }

    private Object compactRecordedObject(RecordedObject recordedObject, int depth) {
        LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
        for (String fieldName : List.of("javaName", "osName", "name", "type", "system", "description")) {
            Object compactValue = compactGenericEventValue(extractFieldValue(recordedObject, fieldName), depth);
            if (hasMeaningfulGenericValue(compactValue)) {
                compact.put(fieldName, compactValue);
            }
            if (compact.size() >= MAX_GENERIC_OBJECT_FIELDS) {
                return Map.copyOf(compact);
            }
        }

        for (ValueDescriptor field : recordedObject.getFields()) {
            String fieldName = field.getName();
            if (compact.containsKey(fieldName) || isIgnoredGenericEventField(fieldName)) {
                continue;
            }
            Object compactValue = compactGenericEventValue(extractFieldValue(recordedObject, fieldName), depth);
            if (hasMeaningfulGenericValue(compactValue)) {
                compact.put(fieldName, compactValue);
            }
            if (compact.size() >= MAX_GENERIC_OBJECT_FIELDS) {
                break;
            }
        }

        if (compact.isEmpty()) {
            return null;
        }
        return Map.copyOf(compact);
    }

    private List<Object> compactGenericList(List<?> values, int depth) {
        List<Object> compactValues = new ArrayList<>();
        int limit = Math.min(MAX_GENERIC_LIST_ITEMS, values.size());
        for (int index = 0; index < limit; index++) {
            Object compactValue = compactGenericEventValue(values.get(index), depth);
            if (hasMeaningfulGenericValue(compactValue)) {
                compactValues.add(compactValue);
            }
        }
        return compactValues.isEmpty() ? List.of() : List.copyOf(compactValues);
    }

    private boolean hasMeaningfulGenericValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private String packageName(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        int packageSeparator = className.lastIndexOf('.');
        if (packageSeparator <= 0) {
            return null;
        }
        return className.substring(0, packageSeparator);
    }

    private record JfrTimelineEvent(
        String eventTypeName,
        String label,
        String signalFamily,
        Instant startTime,
        Instant endTime,
        long durationMs,
        String topMethod,
        String topStack,
        String eventThread,
        String className,
        long sizeBytes,
        String allocator,
        String rootType,
        String rootSystem,
        String rootDescription,
        long referenceDepth,
        long objectAgeMs,
        String objectDescription
    ) {
        private Instant effectiveEndTime() {
            return endTime != null ? endTime : startTime;
        }
    }

    private final class JvmRuntimeInfoAccumulator {

        private Instant jvmStartTime;
        private Long jvmStartTimeEpochMs;

        private void record(RecordedEvent event) {
            if (event == null || !JVM_INFORMATION_EVENT_NAME.equals(event.getEventType().getName())) {
                return;
            }
            Long epochMillis = bestNonNegativeNumericCandidate(event, List.of("jvmStartTime"));
            if (epochMillis == null || epochMillis <= 0L) {
                return;
            }
            if (jvmStartTimeEpochMs == null || epochMillis < jvmStartTimeEpochMs) {
                jvmStartTimeEpochMs = epochMillis;
                jvmStartTime = Instant.ofEpochMilli(epochMillis);
            }
        }

        private Map<String, Object> toCanonicalMap(Instant recordingStartTime) {
            if (jvmStartTime == null) {
                return Map.of();
            }
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("jvmStartTime", jvmStartTime.toString());
            if (jvmStartTimeEpochMs != null) {
                canonical.put("jvmStartTimeEpochMs", jvmStartTimeEpochMs);
            }
            if (recordingStartTime != null && !recordingStartTime.isBefore(jvmStartTime)) {
                canonical.put("recordingStartOffsetFromJvmStartMs", Duration.between(jvmStartTime, recordingStartTime).toMillis());
            }
            return Map.copyOf(canonical);
        }
    }

    private record JfrIncidentWindow(
        String windowId,
        String label,
        String focus,
        List<JfrTimelineEvent> events,
        double severityScore
    ) {
        private Instant startTime() {
            return events.stream()
                .map(JfrTimelineEvent::startTime)
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        }

        private Instant endTime() {
            return events.stream()
                .map(JfrTimelineEvent::effectiveEndTime)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        }
    }

    private final class GenericEventTypeAccumulator {
        private final String name;
        private final String label;
        private final String description;
        private final List<String> categories;
        private long eventCount;
        private long totalDurationMs;
        private long maxDurationMs;
        private long stackBearingEventCount;
        private long truncatedStackCount;
        private Instant firstStartTime;
        private Instant lastEndTime;
        private final Map<String, FieldPresenceAccumulator> observedFields = new LinkedHashMap<>();
        private final Map<String, Long> threadCounts = new LinkedHashMap<>();
        private final List<Map<String, Object>> sampleEvents = new ArrayList<>();

        private GenericEventTypeAccumulator(EventType eventType) {
            this.name = eventType.getName();
            this.label = eventType.getLabel();
            this.description = eventType.getDescription();
            this.categories = eventType.getCategoryNames() == null
                ? List.of()
                : List.copyOf(eventType.getCategoryNames());
        }

        private String name() {
            return name;
        }

        private long eventCount() {
            return eventCount;
        }

        private long totalDurationMs() {
            return totalDurationMs;
        }

        private void record(RecordedEvent event, StackFingerprint stackFingerprint) {
            eventCount++;
            long durationMs = event.getDuration() != null ? Math.max(0L, event.getDuration().toMillis()) : 0L;
            totalDurationMs += durationMs;
            maxDurationMs = Math.max(maxDurationMs, durationMs);

            Instant startTime = event.getStartTime();
            if (startTime != null && (firstStartTime == null || startTime.isBefore(firstStartTime))) {
                firstStartTime = startTime;
            }
            Instant endTime = event.getEndTime();
            if (endTime != null && (lastEndTime == null || endTime.isAfter(lastEndTime))) {
                lastEndTime = endTime;
            }

            if (stackFingerprint != null) {
                stackBearingEventCount++;
                if (stackFingerprint.truncated()) {
                    truncatedStackCount++;
                }
            }

            String eventThreadName = extractEventThreadName(event);
            if (eventThreadName != null) {
                threadCounts.merge(eventThreadName, 1L, Long::sum);
            }

            for (ValueDescriptor field : event.getFields()) {
                String fieldName = field.getName();
                if (isIgnoredGenericEventField(fieldName)) {
                    continue;
                }
                FieldPresenceAccumulator accumulator = observedFields.get(fieldName);
                if (accumulator == null) {
                    accumulator = new FieldPresenceAccumulator(field.getLabel(), field.getTypeName());
                }
                observedFields.put(fieldName, accumulator.record(field));
            }

            if (sampleEvents.size() < MAX_GENERIC_EVENT_SAMPLES) {
                sampleEvents.add(genericEventSample(event, stackFingerprint, eventThreadName, durationMs));
            }
        }

        private Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("name", name);
            if (label != null && !label.isBlank()) {
                canonical.put("label", label);
            }
            if (description != null && !description.isBlank()) {
                canonical.put("description", description);
            }
            if (!categories.isEmpty()) {
                canonical.put("categories", categories);
            }
            canonical.put("eventCount", eventCount);
            canonical.put("totalDurationMs", totalDurationMs);
            canonical.put("maxDurationMs", maxDurationMs);
            canonical.put("averageDurationMs", eventCount > 0L ? (double) totalDurationMs / (double) eventCount : 0.0d);
            if (firstStartTime != null) {
                canonical.put("firstSeen", firstStartTime.toString());
            }
            if (lastEndTime != null) {
                canonical.put("lastSeen", lastEndTime.toString());
            }
            canonical.put("stackBearingEventCount", stackBearingEventCount);
            canonical.put("truncatedStackCount", truncatedStackCount);
            if (!threadCounts.isEmpty()) {
                canonical.put("topThreads", rankedHotspots(threadCounts, eventCount, "thread").stream()
                    .limit(MAX_GENERIC_TOP_THREADS)
                    .toList());
            }
            if (!observedFields.isEmpty()) {
                canonical.put("observedFields", rankedFieldPresence(observedFields, eventCount));
            }
            if (!sampleEvents.isEmpty()) {
                canonical.put("sampleEvents", List.copyOf(sampleEvents));
            }
            return Map.copyOf(canonical);
        }
    }

    private final class ClassLoadingAnalyticsAccumulator {
        private long eventCount;
        private long classNamedEventCount;
        private long loaderTaggedEventCount;
        private long sizedEventCount;
        private long loadEventCount;
        private long unloadEventCount;
        private long totalMetadataBytes;
        private long maxMetadataBytes;
        private Instant firstSeen;
        private Instant lastSeen;
        private final Map<String, Long> eventTypeCounts = new LinkedHashMap<>();
        private final Map<String, Long> classCounts = new LinkedHashMap<>();
        private final Map<String, Long> loaderCounts = new LinkedHashMap<>();
        private final Map<String, Long> packageCounts = new LinkedHashMap<>();
        private final Map<String, Long> threadCounts = new LinkedHashMap<>();

        private void record(RecordedEvent event, String eventTypeName) {
            eventCount++;
            if (eventTypeName != null) {
                eventTypeCounts.merge(eventTypeName, 1L, Long::sum);
                if (normalize(eventTypeName).contains("classunload")) {
                    unloadEventCount++;
                } else {
                    loadEventCount++;
                }
            }

            Instant startTime = event.getStartTime();
            if (startTime != null && (firstSeen == null || startTime.isBefore(firstSeen))) {
                firstSeen = startTime;
            }
            Instant endTime = event.getEndTime();
            if (endTime != null && (lastSeen == null || endTime.isAfter(lastSeen))) {
                lastSeen = endTime;
            }

            String className = extractClassLoadingClass(event);
            if (className != null) {
                classNamedEventCount++;
                classCounts.merge(className, 1L, Long::sum);
                String packageName = packageName(className);
                if (packageName != null) {
                    packageCounts.merge(packageName, 1L, Long::sum);
                }
            }

            String loaderName = extractClassLoader(event);
            if (loaderName != null) {
                loaderTaggedEventCount++;
                loaderCounts.merge(loaderName, 1L, Long::sum);
            }

            long metadataBytes = extractClassLoadingBytes(event);
            if (metadataBytes > 0L) {
                sizedEventCount++;
                totalMetadataBytes += metadataBytes;
                maxMetadataBytes = Math.max(maxMetadataBytes, metadataBytes);
            }

            String threadName = extractEventThreadName(event);
            if (threadName != null) {
                threadCounts.merge(threadName, 1L, Long::sum);
            }
        }

        private long eventCount() {
            return eventCount;
        }

        private String primaryClassLoader() {
            Map.Entry<String, Long> topLoader = topEntry(loaderCounts);
            return topLoader != null ? topLoader.getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            if (eventCount == 0L) {
                return Map.of();
            }

            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", eventCount);
            canonical.put("classNamedEventCount", classNamedEventCount);
            canonical.put("loaderTaggedEventCount", loaderTaggedEventCount);
            canonical.put("sizedEventCount", sizedEventCount);
            canonical.put("definedClassCount", classCounts.size());
            canonical.put("loadEventCount", loadEventCount);
            canonical.put("unloadEventCount", unloadEventCount);
            if (totalMetadataBytes > 0L) {
                canonical.put("totalMetadataBytes", totalMetadataBytes);
                canonical.put("maxMetadataBytes", maxMetadataBytes);
            }
            if (firstSeen != null) {
                canonical.put("firstSeen", firstSeen.toString());
            }
            if (lastSeen != null) {
                canonical.put("lastSeen", lastSeen.toString());
            }

            Map.Entry<String, Long> topClass = topEntry(classCounts);
            if (topClass != null) {
                canonical.put("topClass", topClass.getKey());
                canonical.put("topClassEventCount", topClass.getValue());
                canonical.put("topClassShare", ratio(topClass.getValue(), eventCount));
            }

            Map.Entry<String, Long> topLoader = topEntry(loaderCounts);
            if (topLoader != null) {
                canonical.put("topLoader", topLoader.getKey());
                canonical.put("topLoaderEventCount", topLoader.getValue());
                canonical.put("topLoaderShare", ratio(topLoader.getValue(), eventCount));
            }

            Map.Entry<String, Long> topPackage = topEntry(packageCounts);
            if (topPackage != null) {
                canonical.put("topPackage", topPackage.getKey());
                canonical.put("topPackageEventCount", topPackage.getValue());
                canonical.put("topPackageShare", ratio(topPackage.getValue(), eventCount));
            }

            Map.Entry<String, Long> topThread = topEntry(threadCounts);
            if (topThread != null) {
                canonical.put("topThread", topThread.getKey());
                canonical.put("topThreadEventCount", topThread.getValue());
                canonical.put("topThreadShare", ratio(topThread.getValue(), eventCount));
            }

            if (!eventTypeCounts.isEmpty()) {
                canonical.put("topEventTypes", rankedHotspots(eventTypeCounts, eventCount, "eventType").stream().limit(4).toList());
            }
            if (!classCounts.isEmpty()) {
                canonical.put("topClasses", rankedHotspots(classCounts, eventCount, "className").stream().limit(6).toList());
            }
            if (!loaderCounts.isEmpty()) {
                canonical.put("topLoaders", rankedHotspots(loaderCounts, eventCount, "loader").stream().limit(4).toList());
            }
            if (!packageCounts.isEmpty()) {
                canonical.put("topPackages", rankedHotspots(packageCounts, eventCount, "packageName").stream().limit(4).toList());
            }
            if (!threadCounts.isEmpty()) {
                canonical.put("topThreads", rankedHotspots(threadCounts, eventCount, "thread").stream().limit(MAX_GENERIC_TOP_THREADS).toList());
            }
            return Map.copyOf(canonical);
        }
    }

    private final class CodeCacheAnalyticsAccumulator {
        private long eventCount;
        private long compilationEventCount;
        private long codeCacheFullEventCount;
        private long sizedEventCount;
        private long totalCompilationDurationMs;
        private long maxCompilationDurationMs;
        private long peakCodeCacheUsedBytes;
        private long latestCodeCacheUsedBytes;
        private long minCodeCacheFreeBytes = Long.MAX_VALUE;
        private long latestCodeCacheFreeBytes;
        private long peakCodeCacheCapacityBytes;
        private long latestCodeCacheCapacityBytes;
        private long maxCompilationQueueSize;
        private boolean compilerDisabled;
        private Instant firstSeen;
        private Instant lastSeen;
        private String latestReason;
        private final Map<String, Long> eventTypeCounts = new LinkedHashMap<>();
        private final Map<String, Long> compilerCounts = new LinkedHashMap<>();
        private final Map<String, Long> compiledMethodCounts = new LinkedHashMap<>();
        private final Map<String, Long> threadCounts = new LinkedHashMap<>();

        private void record(RecordedEvent event, String eventTypeName) {
            eventCount++;
            if (eventTypeName != null) {
                eventTypeCounts.merge(eventTypeName, 1L, Long::sum);
            }

            Instant startTime = event.getStartTime();
            if (startTime != null && (firstSeen == null || startTime.isBefore(firstSeen))) {
                firstSeen = startTime;
            }
            Instant endTime = event.getEndTime();
            if (endTime != null && (lastSeen == null || endTime.isAfter(lastSeen))) {
                lastSeen = endTime;
            }

            long durationMs = event.getDuration() != null ? Math.max(0L, event.getDuration().toMillis()) : 0L;
            if (durationMs > 0L) {
                totalCompilationDurationMs += durationMs;
                maxCompilationDurationMs = Math.max(maxCompilationDurationMs, durationMs);
            }

            String compiler = extractCompilerName(event);
            if (compiler != null) {
                compilerCounts.merge(compiler, 1L, Long::sum);
            }

            String compiledMethod = extractCompiledMethod(event);
            if (compiledMethod != null) {
                compiledMethodCounts.merge(compiledMethod, 1L, Long::sum);
            }

            String threadName = extractEventThreadName(event);
            if (threadName != null) {
                threadCounts.merge(threadName, 1L, Long::sum);
            }

            long compileQueueSize = extractCompilationQueueSize(event);
            if (compileQueueSize > 0L) {
                maxCompilationQueueSize = Math.max(maxCompilationQueueSize, compileQueueSize);
            }

            long usedBytes = extractCodeCacheUsedBytes(event);
            if (usedBytes > 0L) {
                sizedEventCount++;
                peakCodeCacheUsedBytes = Math.max(peakCodeCacheUsedBytes, usedBytes);
                latestCodeCacheUsedBytes = usedBytes;
            }

            long freeBytes = extractCodeCacheFreeBytes(event);
            if (freeBytes > 0L) {
                sizedEventCount++;
                minCodeCacheFreeBytes = Math.min(minCodeCacheFreeBytes, freeBytes);
                latestCodeCacheFreeBytes = freeBytes;
            }

            long capacityBytes = extractCodeCacheSizeBytes(event);
            if (capacityBytes > 0L) {
                sizedEventCount++;
                peakCodeCacheCapacityBytes = Math.max(peakCodeCacheCapacityBytes, capacityBytes);
                latestCodeCacheCapacityBytes = capacityBytes;
            }

            boolean fullEvent = normalize(eventTypeName).contains("codecache");
            String reason = extractCodeCacheReason(event);
            if (reason != null) {
                latestReason = reason;
                if (normalize(reason).contains("full")) {
                    fullEvent = true;
                }
            }
            if (extractCompilerDisabled(event)) {
                compilerDisabled = true;
                fullEvent = true;
            }

            if (fullEvent) {
                codeCacheFullEventCount++;
            } else {
                compilationEventCount++;
            }
        }

        private long eventCount() {
            return eventCount;
        }

        private String primaryCompiler() {
            Map.Entry<String, Long> topCompiler = topEntry(compilerCounts);
            return topCompiler != null ? topCompiler.getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            if (eventCount == 0L) {
                return Map.of();
            }

            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", eventCount);
            canonical.put("compilationEventCount", compilationEventCount);
            canonical.put("codeCacheFullEventCount", codeCacheFullEventCount);
            canonical.put("sizedEventCount", sizedEventCount);
            if (totalCompilationDurationMs > 0L) {
                canonical.put("totalCompilationDurationMs", totalCompilationDurationMs);
                canonical.put("maxCompilationDurationMs", maxCompilationDurationMs);
            }
            if (peakCodeCacheUsedBytes > 0L) {
                canonical.put("peakCodeCacheUsedBytes", peakCodeCacheUsedBytes);
                canonical.put("latestCodeCacheUsedBytes", latestCodeCacheUsedBytes);
            }
            if (minCodeCacheFreeBytes != Long.MAX_VALUE) {
                canonical.put("minCodeCacheFreeBytes", minCodeCacheFreeBytes);
                canonical.put("latestCodeCacheFreeBytes", latestCodeCacheFreeBytes);
            }
            if (peakCodeCacheCapacityBytes > 0L) {
                canonical.put("peakCodeCacheCapacityBytes", peakCodeCacheCapacityBytes);
                canonical.put("latestCodeCacheCapacityBytes", latestCodeCacheCapacityBytes);
            }
            if (peakCodeCacheUsedBytes > 0L && peakCodeCacheCapacityBytes > 0L) {
                canonical.put("peakUsageRatio", ratio(peakCodeCacheUsedBytes, peakCodeCacheCapacityBytes));
            }
            if (latestCodeCacheUsedBytes > 0L && latestCodeCacheCapacityBytes > 0L) {
                canonical.put("latestUsageRatio", ratio(latestCodeCacheUsedBytes, latestCodeCacheCapacityBytes));
            }
            if (maxCompilationQueueSize > 0L) {
                canonical.put("maxCompilationQueueSize", maxCompilationQueueSize);
            }
            if (compilerDisabled) {
                canonical.put("compilerDisabled", true);
            }
            if (latestReason != null) {
                canonical.put("latestReason", latestReason);
            }
            if (firstSeen != null) {
                canonical.put("firstSeen", firstSeen.toString());
            }
            if (lastSeen != null) {
                canonical.put("lastSeen", lastSeen.toString());
            }

            Map.Entry<String, Long> topCompiler = topEntry(compilerCounts);
            if (topCompiler != null) {
                canonical.put("topCompiler", topCompiler.getKey());
                canonical.put("topCompilerEventCount", topCompiler.getValue());
                canonical.put("topCompilerShare", ratio(topCompiler.getValue(), eventCount));
            }

            Map.Entry<String, Long> topMethod = topEntry(compiledMethodCounts);
            if (topMethod != null) {
                canonical.put("topCompilationMethod", topMethod.getKey());
                canonical.put("topCompilationMethodCount", topMethod.getValue());
                canonical.put("topCompilationMethodShare", ratio(topMethod.getValue(), eventCount));
            }

            Map.Entry<String, Long> topThread = topEntry(threadCounts);
            if (topThread != null) {
                canonical.put("topThread", topThread.getKey());
                canonical.put("topThreadEventCount", topThread.getValue());
                canonical.put("topThreadShare", ratio(topThread.getValue(), eventCount));
            }

            if (!eventTypeCounts.isEmpty()) {
                canonical.put("topEventTypes", rankedHotspots(eventTypeCounts, eventCount, "eventType").stream().limit(4).toList());
            }
            if (!compiledMethodCounts.isEmpty()) {
                canonical.put("topCompilationMethods", rankedHotspots(compiledMethodCounts, eventCount, "method").stream().limit(4).toList());
            }
            if (!threadCounts.isEmpty()) {
                canonical.put("topThreads", rankedHotspots(threadCounts, eventCount, "thread").stream().limit(MAX_GENERIC_TOP_THREADS).toList());
            }

            return Map.copyOf(canonical);
        }
    }

    private final class CpuLoadAnalyticsAccumulator {
        private long eventCount;
        private long cpuLoadEventCount;
        private long threadCpuLoadEventCount;
        private long machineSampleCount;
        private double machineTotalSum;
        private double peakMachineTotal;
        private long jvmTotalSampleCount;
        private double jvmTotalSum;
        private double peakJvmTotal;
        private long threadTotalSampleCount;
        private double threadTotalSum;
        private double peakThreadTotal;
        private Instant firstSeen;
        private Instant lastSeen;
        private final Map<String, Long> threadCounts = new LinkedHashMap<>();
        private final Map<String, Double> threadPeakTotals = new LinkedHashMap<>();
        private final Map<String, Double> threadTotalSums = new LinkedHashMap<>();

        private void record(RecordedEvent event, String eventTypeName) {
            eventCount++;
            if (isThreadCpuLoadEvent(eventTypeName)) {
                threadCpuLoadEventCount++;
            } else {
                cpuLoadEventCount++;
            }

            Instant startTime = event.getStartTime();
            if (startTime != null && (firstSeen == null || startTime.isBefore(firstSeen))) {
                firstSeen = startTime;
            }
            Instant endTime = event.getEndTime();
            if (endTime != null && (lastSeen == null || endTime.isAfter(lastSeen))) {
                lastSeen = endTime;
            }

            Double machineTotal = extractMachineCpuLoad(event);
            if (machineTotal != null) {
                machineSampleCount++;
                machineTotalSum += machineTotal;
                peakMachineTotal = Math.max(peakMachineTotal, machineTotal);
            }

            Double jvmTotal = extractJvmTotalCpuLoad(event);
            if (jvmTotal != null) {
                jvmTotalSampleCount++;
                jvmTotalSum += jvmTotal;
                peakJvmTotal = Math.max(peakJvmTotal, jvmTotal);
            }

            Double threadTotal = extractThreadTotalCpuLoad(event);
            String threadName = extractCpuSampleThreadName(event);
            if (threadTotal != null) {
                threadTotalSampleCount++;
                threadTotalSum += threadTotal;
                peakThreadTotal = Math.max(peakThreadTotal, threadTotal);
                if (threadName != null) {
                    threadCounts.merge(threadName, 1L, Long::sum);
                    threadPeakTotals.merge(threadName, threadTotal, Math::max);
                    threadTotalSums.merge(threadName, threadTotal, Double::sum);
                }
            }
        }

        private long eventCount() {
            return eventCount;
        }

        private String primaryThread() {
            return topCpuThreadEntry() != null ? topCpuThreadEntry().getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            if (eventCount == 0L) {
                return Map.of();
            }

            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", eventCount);
            canonical.put("cpuLoadEventCount", cpuLoadEventCount);
            canonical.put("threadCpuLoadEventCount", threadCpuLoadEventCount);
            if (machineSampleCount > 0L) {
                canonical.put("machineSampleCount", machineSampleCount);
                canonical.put("averageMachineTotal", machineTotalSum / (double) machineSampleCount);
                canonical.put("peakMachineTotal", peakMachineTotal);
            }
            if (jvmTotalSampleCount > 0L) {
                canonical.put("jvmTotalSampleCount", jvmTotalSampleCount);
                canonical.put("averageJvmTotal", jvmTotalSum / (double) jvmTotalSampleCount);
                canonical.put("peakJvmTotal", peakJvmTotal);
            }
            if (threadTotalSampleCount > 0L) {
                canonical.put("threadTotalSampleCount", threadTotalSampleCount);
                canonical.put("averageThreadTotal", threadTotalSum / (double) threadTotalSampleCount);
                canonical.put("peakThreadTotal", peakThreadTotal);
            }
            if (firstSeen != null) {
                canonical.put("firstSeen", firstSeen.toString());
            }
            if (lastSeen != null) {
                canonical.put("lastSeen", lastSeen.toString());
            }

            Map.Entry<String, Double> topThread = topCpuThreadEntry();
            if (topThread != null) {
                long topThreadCount = threadCounts.getOrDefault(topThread.getKey(), 0L);
                canonical.put("topThread", topThread.getKey());
                canonical.put("topThreadEventCount", topThreadCount);
                canonical.put("topThreadShare", threadCpuLoadEventCount > 0L ? ratio(topThreadCount, threadCpuLoadEventCount) : 0.0d);
                canonical.put("topThreadPeakTotal", topThread.getValue());
                double totalForThread = threadTotalSums.getOrDefault(topThread.getKey(), 0.0d);
                if (topThreadCount > 0L) {
                    canonical.put("topThreadAverageTotal", totalForThread / (double) topThreadCount);
                }
            }

            if (!threadCounts.isEmpty()) {
                canonical.put(
                    "topThreads",
                    threadCounts.entrySet().stream()
                        .sorted((left, right) -> {
                            int compare = Double.compare(
                                threadPeakTotals.getOrDefault(right.getKey(), 0.0d),
                                threadPeakTotals.getOrDefault(left.getKey(), 0.0d)
                            );
                            if (compare != 0) {
                                return compare;
                            }
                            compare = Long.compare(right.getValue(), left.getValue());
                            if (compare != 0) {
                                return compare;
                            }
                            return left.getKey().compareTo(right.getKey());
                        })
                        .limit(MAX_GENERIC_TOP_THREADS)
                        .map(entry -> {
                            LinkedHashMap<String, Object> thread = new LinkedHashMap<>();
                            thread.put("value", entry.getKey());
                            thread.put("count", entry.getValue());
                            thread.put("share", threadCpuLoadEventCount > 0L ? ratio(entry.getValue(), threadCpuLoadEventCount) : 0.0d);
                            thread.put("peakTotal", threadPeakTotals.getOrDefault(entry.getKey(), 0.0d));
                            thread.put("averageTotal", threadTotalSums.getOrDefault(entry.getKey(), 0.0d) / (double) entry.getValue());
                            return Map.copyOf(thread);
                        })
                        .toList()
                );
            }

            return Map.copyOf(canonical);
        }

        private Map.Entry<String, Double> topCpuThreadEntry() {
            return threadPeakTotals.entrySet().stream()
                .sorted((left, right) -> {
                    int compare = Double.compare(right.getValue(), left.getValue());
                    if (compare != 0) {
                        return compare;
                    }
                    compare = Long.compare(
                        threadCounts.getOrDefault(right.getKey(), 0L),
                        threadCounts.getOrDefault(left.getKey(), 0L)
                    );
                    if (compare != 0) {
                        return compare;
                    }
                    return left.getKey().compareTo(right.getKey());
                })
                .findFirst()
                .orElse(null);
        }
    }

    private record EventSummaryAccumulator(
        String name,
        String label,
        long count,
        long totalDurationMs,
        long maxDurationMs
    ) {
        private EventSummaryAccumulator(String name, String label) {
            this(name, label, 0L, 0L, 0L);
        }

        private EventSummaryAccumulator record(RecordedEvent event) {
            long durationMs = event.getDuration() != null ? Math.max(0L, event.getDuration().toMillis()) : 0L;
            return new EventSummaryAccumulator(
                name,
                label,
                count + 1L,
                totalDurationMs + durationMs,
                Math.max(maxDurationMs, durationMs)
            );
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("name", name);
            if (label != null && !label.isBlank()) {
                canonical.put("label", label);
            }
            canonical.put("count", count);
            canonical.put("totalDurationMs", totalDurationMs);
            canonical.put("maxDurationMs", maxDurationMs);
            canonical.put("averageDurationMs", count > 0L ? (double) totalDurationMs / (double) count : 0.0d);
            return Map.copyOf(canonical);
        }
    }

    private record SignalSummary(
        long eventCount,
        long totalDurationMs,
        long maxDurationMs,
        List<String> eventTypeNames
    ) {
        private static SignalSummary combine(SignalSummary left, SignalSummary right) {
            List<String> eventTypeNames = new ArrayList<>();
            if (left != null) {
                eventTypeNames.addAll(left.eventTypeNames());
            }
            if (right != null) {
                eventTypeNames.addAll(right.eventTypeNames());
            }
            return new SignalSummary(
                (left != null ? left.eventCount() : 0L) + (right != null ? right.eventCount() : 0L),
                (left != null ? left.totalDurationMs() : 0L) + (right != null ? right.totalDurationMs() : 0L),
                Math.max(left != null ? left.maxDurationMs() : 0L, right != null ? right.maxDurationMs() : 0L),
                List.copyOf(eventTypeNames.stream().distinct().toList())
            );
        }

        private String primaryEventTypeName() {
            return eventTypeNames.isEmpty() ? null : eventTypeNames.getFirst();
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", eventCount);
            canonical.put("totalDurationMs", totalDurationMs);
            canonical.put("maxDurationMs", maxDurationMs);
            canonical.put("eventTypeNames", eventTypeNames);
            canonical.put("averageDurationMs", eventCount > 0L ? (double) totalDurationMs / (double) eventCount : 0.0d);
            return Map.copyOf(canonical);
        }
    }

    private record FieldPresenceAccumulator(String label, String typeName, long count) {
        private FieldPresenceAccumulator(String label, String typeName) {
            this(label, typeName, 0L);
        }

        private FieldPresenceAccumulator record(ValueDescriptor field) {
            String nextLabel = label;
            if ((nextLabel == null || nextLabel.isBlank()) && field.getLabel() != null && !field.getLabel().isBlank()) {
                nextLabel = field.getLabel();
            }
            String nextTypeName = typeName;
            if ((nextTypeName == null || nextTypeName.isBlank()) && field.getTypeName() != null && !field.getTypeName().isBlank()) {
                nextTypeName = field.getTypeName();
            }
            return new FieldPresenceAccumulator(nextLabel, nextTypeName, count + 1L);
        }
    }

    private final class AllocationAnalyticsAccumulator {
        private long allocationEventCount;
        private long fieldRichEventCount;
        private long sizedEventCount;
        private long tlabSizedEventCount;
        private long classedEventCount;
        private long allocatorTaggedEventCount;
        private long totalAllocatedBytes;
        private long maxAllocatedBytes;
        private long totalTlabBytes;
        private long maxTlabBytes;
        private final Map<String, Long> allocationEventTypeCounts = new LinkedHashMap<>();
        private final Map<String, Long> allocationEventTypeBytes = new LinkedHashMap<>();
        private final Map<String, Long> allocationClassCounts = new LinkedHashMap<>();
        private final Map<String, Long> allocationClassBytes = new LinkedHashMap<>();
        private final Map<String, Long> allocatorCounts = new LinkedHashMap<>();
        private final Map<String, Long> allocatorBytes = new LinkedHashMap<>();
        private final Map<String, FieldPresenceAccumulator> observedFields = new LinkedHashMap<>();
        private final WeightedHotspotAccumulator hotspots = new WeightedHotspotAccumulator();

        private void record(RecordedEvent event, StackFingerprint fingerprint) {
            allocationEventCount++;
            String eventTypeName = event.getEventType().getName();
            allocationEventTypeCounts.merge(eventTypeName, 1L, Long::sum);

            for (ValueDescriptor field : event.getFields()) {
                if (isIgnoredAllocationField(field.getName())) {
                    continue;
                }
                FieldPresenceAccumulator accumulator = observedFields.get(field.getName());
                if (accumulator == null) {
                    accumulator = new FieldPresenceAccumulator(field.getLabel(), field.getTypeName());
                }
                observedFields.put(field.getName(), accumulator.record(field));
            }

            String allocationClass = extractAllocationClass(event);
            long allocationBytes = extractAllocationBytes(event);
            long tlabBytes = extractTlabBytes(event);
            String allocator = extractAllocator(event);

            boolean fieldRich = false;
            if (allocationClass != null) {
                classedEventCount++;
                allocationClassCounts.merge(allocationClass, 1L, Long::sum);
                if (allocationBytes > 0L) {
                    allocationClassBytes.merge(allocationClass, allocationBytes, Long::sum);
                }
                fieldRich = true;
            }
            if (allocationBytes > 0L) {
                sizedEventCount++;
                totalAllocatedBytes += allocationBytes;
                maxAllocatedBytes = Math.max(maxAllocatedBytes, allocationBytes);
                allocationEventTypeBytes.merge(eventTypeName, allocationBytes, Long::sum);
                fieldRich = true;
            }
            if (tlabBytes > 0L) {
                tlabSizedEventCount++;
                totalTlabBytes += tlabBytes;
                maxTlabBytes = Math.max(maxTlabBytes, tlabBytes);
                fieldRich = true;
            }
            if (allocator != null) {
                allocatorTaggedEventCount++;
                allocatorCounts.merge(allocator, 1L, Long::sum);
                if (allocationBytes > 0L) {
                    allocatorBytes.merge(allocator, allocationBytes, Long::sum);
                }
                fieldRich = true;
            }
            if (fieldRich) {
                fieldRichEventCount++;
            }
            if (fingerprint != null) {
                hotspots.record(fingerprint, allocationBytes);
            }
        }

        private long allocationEventCount() {
            return allocationEventCount;
        }

        private long fieldRichEventCount() {
            return fieldRichEventCount;
        }

        private long stackEventCount() {
            return hotspots.stackEventCount();
        }

        private String primaryAllocationClass() {
            Map.Entry<String, Long> topClass = topEntityEntry(allocationClassCounts, allocationClassBytes);
            return topClass != null ? topClass.getKey() : null;
        }

        private String primaryAllocationMethod() {
            return hotspots.primaryMethod();
        }

        private Map<String, Object> toFieldCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", allocationEventCount);
            canonical.put("fieldRichEventCount", fieldRichEventCount);
            canonical.put("sizedEventCount", sizedEventCount);
            canonical.put("tlabSizedEventCount", tlabSizedEventCount);
            canonical.put("classedEventCount", classedEventCount);
            canonical.put("allocatorTaggedEventCount", allocatorTaggedEventCount);
            canonical.put("totalAllocatedBytes", totalAllocatedBytes);
            canonical.put("maxAllocatedBytes", maxAllocatedBytes);
            canonical.put("averageAllocatedBytes", sizedEventCount > 0L ? (double) totalAllocatedBytes / (double) sizedEventCount : 0.0d);
            canonical.put("totalTlabBytes", totalTlabBytes);
            canonical.put("maxTlabBytes", maxTlabBytes);
            canonical.put("observedFieldNames", List.copyOf(observedFields.keySet()));
            canonical.put("observedFields", rankedFieldPresence(observedFields, allocationEventCount));
            canonical.put(
                "topAllocationEventTypes",
                rankedEntities(allocationEventTypeCounts, allocationEventTypeBytes, allocationEventCount, totalAllocatedBytes, "eventType")
            );
            canonical.put(
                "topAllocatingClasses",
                rankedEntities(allocationClassCounts, allocationClassBytes, allocationEventCount, totalAllocatedBytes, "className")
            );
            canonical.put("topAllocators", rankedEntities(allocatorCounts, allocatorBytes, allocationEventCount, totalAllocatedBytes, "allocator"));

            Map.Entry<String, Long> topAllocationEventType = topEntityEntry(allocationEventTypeCounts, allocationEventTypeBytes);
            if (topAllocationEventType != null) {
                canonical.put("topAllocationEventType", topAllocationEventType.getKey());
                canonical.put("topAllocationEventTypeCount", allocationEventTypeCounts.getOrDefault(topAllocationEventType.getKey(), 0L));
                canonical.put("topAllocationEventTypeShare", ratio(
                    allocationEventTypeCounts.getOrDefault(topAllocationEventType.getKey(), 0L),
                    allocationEventCount
                ));
                canonical.put(
                    "topAllocationEventTypeAllocatedBytes",
                    allocationEventTypeBytes.getOrDefault(topAllocationEventType.getKey(), 0L)
                );
                canonical.put(
                    "topAllocationEventTypeAllocatedByteShare",
                    ratio(allocationEventTypeBytes.getOrDefault(topAllocationEventType.getKey(), 0L), totalAllocatedBytes)
                );
            }

            Map.Entry<String, Long> topAllocationClass = topEntityEntry(allocationClassCounts, allocationClassBytes);
            if (topAllocationClass != null) {
                canonical.put("topClass", topAllocationClass.getKey());
                canonical.put("topClassEventCount", allocationClassCounts.getOrDefault(topAllocationClass.getKey(), 0L));
                canonical.put("topClassEventShare", ratio(
                    allocationClassCounts.getOrDefault(topAllocationClass.getKey(), 0L),
                    allocationEventCount
                ));
                canonical.put("topClassAllocatedBytes", allocationClassBytes.getOrDefault(topAllocationClass.getKey(), 0L));
                canonical.put(
                    "topClassAllocatedByteShare",
                    ratio(allocationClassBytes.getOrDefault(topAllocationClass.getKey(), 0L), totalAllocatedBytes)
                );
            }

            Map.Entry<String, Long> topAllocator = topEntityEntry(allocatorCounts, allocatorBytes);
            if (topAllocator != null) {
                canonical.put("topAllocator", topAllocator.getKey());
                canonical.put("topAllocatorCount", allocatorCounts.getOrDefault(topAllocator.getKey(), 0L));
                canonical.put("topAllocatorShare", ratio(allocatorCounts.getOrDefault(topAllocator.getKey(), 0L), allocationEventCount));
                canonical.put("topAllocatorAllocatedBytes", allocatorBytes.getOrDefault(topAllocator.getKey(), 0L));
                canonical.put(
                    "topAllocatorAllocatedByteShare",
                    ratio(allocatorBytes.getOrDefault(topAllocator.getKey(), 0L), totalAllocatedBytes)
                );
            }

            return Map.copyOf(canonical);
        }

        private Map<String, Object> toHotspotCanonicalMap() {
            return hotspots.toCanonicalMap();
        }

        private Map.Entry<String, Long> topEntityEntry(Map<String, Long> counts, Map<String, Long> bytes) {
            String selectedKey = null;
            long selectedBytes = Long.MIN_VALUE;
            long selectedCount = Long.MIN_VALUE;
            for (String key : counts.keySet()) {
                long candidateBytes = bytes.getOrDefault(key, 0L);
                long candidateCount = counts.getOrDefault(key, 0L);
                if (selectedKey == null
                    || candidateBytes > selectedBytes
                    || (candidateBytes == selectedBytes && candidateCount > selectedCount)
                    || (candidateBytes == selectedBytes && candidateCount == selectedCount && key.compareTo(selectedKey) < 0)) {
                    selectedKey = key;
                    selectedBytes = candidateBytes;
                    selectedCount = candidateCount;
                }
            }
            return selectedKey != null ? Map.entry(selectedKey, selectedCount) : null;
        }
    }

    private final class OldObjectAnalyticsAccumulator {
        private long eventCount;
        private long fieldRichEventCount;
        private long sizedEventCount;
        private long classedEventCount;
        private long agedEventCount;
        private long arrayBackedEventCount;
        private long rootedEventCount;
        private long depthEventCount;
        private long describedEventCount;
        private long totalSampledObjectBytes;
        private long maxSampledObjectBytes;
        private long totalObjectAgeMs;
        private long maxObjectAgeMs;
        private long totalReferenceDepth;
        private long maxReferenceDepth;
        private Instant oldestAllocationTime;
        private Instant newestAllocationTime;
        private final Map<String, Long> oldObjectClassCounts = new LinkedHashMap<>();
        private final Map<String, Long> oldObjectClassBytes = new LinkedHashMap<>();
        private final Map<String, Long> rootTypeCounts = new LinkedHashMap<>();
        private final Map<String, Long> rootSystemCounts = new LinkedHashMap<>();
        private final Map<String, Long> rootDescriptionCounts = new LinkedHashMap<>();
        private final Map<String, FieldPresenceAccumulator> observedFields = new LinkedHashMap<>();

        private void record(RecordedEvent event) {
            eventCount++;

            for (ValueDescriptor field : event.getFields()) {
                if (isIgnoredOldObjectField(field.getName())) {
                    continue;
                }
                FieldPresenceAccumulator accumulator = observedFields.get(field.getName());
                if (accumulator == null) {
                    accumulator = new FieldPresenceAccumulator(field.getLabel(), field.getTypeName());
                }
                observedFields.put(field.getName(), accumulator.record(field));
            }

            String oldObjectClass = extractOldObjectClass(event);
            long objectBytes = extractOldObjectBytes(event);
            Long objectAgeMs = extractOldObjectAgeMs(event);
            Instant allocationTime = extractOldObjectAllocationTime(event);
            Integer arrayElements = extractOldObjectArrayElements(event);
            String description = extractOldObjectDescription(event);
            String rootType = extractOldObjectRootType(event);
            String rootSystem = extractOldObjectRootSystem(event);
            String rootDescription = extractOldObjectRootDescription(event);
            Long referenceDepth = extractOldObjectReferenceDepth(event);

            boolean fieldRich = false;
            if (oldObjectClass != null) {
                classedEventCount++;
                oldObjectClassCounts.merge(oldObjectClass, 1L, Long::sum);
                if (objectBytes > 0L) {
                    oldObjectClassBytes.merge(oldObjectClass, objectBytes, Long::sum);
                }
                fieldRich = true;
            }
            if (objectBytes > 0L) {
                sizedEventCount++;
                totalSampledObjectBytes += objectBytes;
                maxSampledObjectBytes = Math.max(maxSampledObjectBytes, objectBytes);
                fieldRich = true;
            }
            if (objectAgeMs != null) {
                agedEventCount++;
                totalObjectAgeMs += objectAgeMs;
                maxObjectAgeMs = Math.max(maxObjectAgeMs, objectAgeMs);
                fieldRich = true;
            }
            if (allocationTime != null) {
                if (oldestAllocationTime == null || allocationTime.isBefore(oldestAllocationTime)) {
                    oldestAllocationTime = allocationTime;
                }
                if (newestAllocationTime == null || allocationTime.isAfter(newestAllocationTime)) {
                    newestAllocationTime = allocationTime;
                }
                fieldRich = true;
            }
            if (arrayElements != null) {
                arrayBackedEventCount++;
                fieldRich = true;
            }
            if (description != null) {
                describedEventCount++;
                fieldRich = true;
            }

            boolean rooted = false;
            if (rootType != null) {
                rootTypeCounts.merge(rootType, 1L, Long::sum);
                rooted = true;
                fieldRich = true;
            }
            if (rootSystem != null) {
                rootSystemCounts.merge(rootSystem, 1L, Long::sum);
                rooted = true;
                fieldRich = true;
            }
            if (rootDescription != null) {
                rootDescriptionCounts.merge(rootDescription, 1L, Long::sum);
                rooted = true;
                fieldRich = true;
            }
            if (rooted) {
                rootedEventCount++;
            }

            if (referenceDepth != null) {
                depthEventCount++;
                totalReferenceDepth += referenceDepth;
                maxReferenceDepth = Math.max(maxReferenceDepth, referenceDepth);
                fieldRich = true;
            }

            if (fieldRich) {
                fieldRichEventCount++;
            }
        }

        private long eventCount() {
            return eventCount;
        }

        private long fieldRichEventCount() {
            return fieldRichEventCount;
        }

        private long rootedEventCount() {
            return rootedEventCount;
        }

        private long depthEventCount() {
            return depthEventCount;
        }

        private String primaryOldObjectClass() {
            Map.Entry<String, Long> topClass = topEntityEntry(oldObjectClassCounts, oldObjectClassBytes);
            return topClass != null ? topClass.getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("eventCount", eventCount);
            canonical.put("fieldRichEventCount", fieldRichEventCount);
            canonical.put("sizedEventCount", sizedEventCount);
            canonical.put("classedEventCount", classedEventCount);
            canonical.put("agedEventCount", agedEventCount);
            canonical.put("arrayBackedEventCount", arrayBackedEventCount);
            canonical.put("rootedEventCount", rootedEventCount);
            canonical.put("depthEventCount", depthEventCount);
            canonical.put("describedEventCount", describedEventCount);
            canonical.put("totalSampledObjectBytes", totalSampledObjectBytes);
            canonical.put("maxSampledObjectBytes", maxSampledObjectBytes);
            canonical.put(
                "averageSampledObjectBytes",
                sizedEventCount > 0L ? (double) totalSampledObjectBytes / (double) sizedEventCount : 0.0d
            );
            canonical.put("totalObjectAgeMs", totalObjectAgeMs);
            canonical.put("maxObjectAgeMs", maxObjectAgeMs);
            canonical.put("averageObjectAgeMs", agedEventCount > 0L ? (double) totalObjectAgeMs / (double) agedEventCount : 0.0d);
            canonical.put("totalReferenceDepth", totalReferenceDepth);
            canonical.put("maxReferenceDepth", maxReferenceDepth);
            canonical.put(
                "averageReferenceDepth",
                depthEventCount > 0L ? (double) totalReferenceDepth / (double) depthEventCount : 0.0d
            );
            canonical.put("observedFieldNames", List.copyOf(observedFields.keySet()));
            canonical.put("observedFields", rankedFieldPresence(observedFields, eventCount));
            canonical.put(
                "topOldObjectClasses",
                rankedEntities(oldObjectClassCounts, oldObjectClassBytes, eventCount, totalSampledObjectBytes, "className")
            );
            canonical.put("topRootTypes", rankedHotspots(rootTypeCounts, rootedEventCount, "rootType"));
            canonical.put("topRootSystems", rankedHotspots(rootSystemCounts, rootedEventCount, "rootSystem"));
            canonical.put("topRootDescriptions", rankedHotspots(rootDescriptionCounts, rootedEventCount, "rootDescription"));

            Map.Entry<String, Long> topClass = topEntityEntry(oldObjectClassCounts, oldObjectClassBytes);
            if (topClass != null) {
                canonical.put("topClass", topClass.getKey());
                canonical.put("topClassEventCount", oldObjectClassCounts.getOrDefault(topClass.getKey(), 0L));
                canonical.put("topClassEventShare", ratio(oldObjectClassCounts.getOrDefault(topClass.getKey(), 0L), eventCount));
                canonical.put("topClassSampledObjectBytes", oldObjectClassBytes.getOrDefault(topClass.getKey(), 0L));
                canonical.put(
                    "topClassSampledObjectByteShare",
                    ratio(oldObjectClassBytes.getOrDefault(topClass.getKey(), 0L), totalSampledObjectBytes)
                );
            }

            Map.Entry<String, Long> topRootType = topEntry(rootTypeCounts);
            if (topRootType != null) {
                canonical.put("topRootType", topRootType.getKey());
                canonical.put("topRootTypeCount", topRootType.getValue());
                canonical.put("topRootTypeShare", ratio(topRootType.getValue(), rootedEventCount));
            }

            Map.Entry<String, Long> topRootSystem = topEntry(rootSystemCounts);
            if (topRootSystem != null) {
                canonical.put("topRootSystem", topRootSystem.getKey());
                canonical.put("topRootSystemCount", topRootSystem.getValue());
                canonical.put("topRootSystemShare", ratio(topRootSystem.getValue(), rootedEventCount));
            }

            Map.Entry<String, Long> topRootDescription = topEntry(rootDescriptionCounts);
            if (topRootDescription != null) {
                canonical.put("topRootDescription", topRootDescription.getKey());
                canonical.put("topRootDescriptionCount", topRootDescription.getValue());
                canonical.put("topRootDescriptionShare", ratio(topRootDescription.getValue(), rootedEventCount));
            }

            if (oldestAllocationTime != null) {
                canonical.put("oldestAllocationTime", oldestAllocationTime.toString());
            }
            if (newestAllocationTime != null) {
                canonical.put("newestAllocationTime", newestAllocationTime.toString());
            }

            return Map.copyOf(canonical);
        }

        private Map.Entry<String, Long> topEntityEntry(Map<String, Long> counts, Map<String, Long> bytes) {
            String selectedKey = null;
            long selectedBytes = Long.MIN_VALUE;
            long selectedCount = Long.MIN_VALUE;
            for (String key : counts.keySet()) {
                long candidateBytes = bytes.getOrDefault(key, 0L);
                long candidateCount = counts.getOrDefault(key, 0L);
                if (selectedKey == null
                    || candidateBytes > selectedBytes
                    || (candidateBytes == selectedBytes && candidateCount > selectedCount)
                    || (candidateBytes == selectedBytes && candidateCount == selectedCount && key.compareTo(selectedKey) < 0)) {
                    selectedKey = key;
                    selectedBytes = candidateBytes;
                    selectedCount = candidateCount;
                }
            }
            return selectedKey != null ? Map.entry(selectedKey, selectedCount) : null;
        }

        private Map.Entry<String, Long> topEntry(Map<String, Long> counts) {
            return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int compare = Long.compare(right.getValue(), left.getValue());
                    if (compare != 0) {
                        return compare;
                    }
                    return left.getKey().compareTo(right.getKey());
                })
                .findFirst()
                .orElse(null);
        }
    }

    private final class StackHotspotAccumulator {
        private long stackEventCount;
        private long truncatedStackCount;
        private final Map<String, Long> methodCounts = new LinkedHashMap<>();
        private final Map<String, Long> stackCounts = new LinkedHashMap<>();

        private void record(StackFingerprint fingerprint) {
            stackEventCount++;
            if (fingerprint.truncated()) {
                truncatedStackCount++;
            }
            methodCounts.merge(fingerprint.primaryMethod(), 1L, Long::sum);
            stackCounts.merge(fingerprint.stackSignature(), 1L, Long::sum);
        }

        private long stackEventCount() {
            return stackEventCount;
        }

        private String primaryMethod() {
            return topEntry(methodCounts) != null ? topEntry(methodCounts).getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("stackEventCount", stackEventCount);
            canonical.put("truncatedStackCount", truncatedStackCount);
            if (!methodCounts.isEmpty()) {
                Map.Entry<String, Long> topMethod = topEntry(methodCounts);
                canonical.put("topMethod", topMethod.getKey());
                canonical.put("topMethodCount", topMethod.getValue());
                canonical.put("topMethodShare", stackEventCount > 0L ? (double) topMethod.getValue() / (double) stackEventCount : 0.0d);
                canonical.put("topMethods", rankedHotspots(methodCounts, stackEventCount, "method"));
            }
            if (!stackCounts.isEmpty()) {
                Map.Entry<String, Long> topStack = topEntry(stackCounts);
                canonical.put("topStack", topStack.getKey());
                canonical.put("topStackCount", topStack.getValue());
                canonical.put("topStackShare", stackEventCount > 0L ? (double) topStack.getValue() / (double) stackEventCount : 0.0d);
                canonical.put("topStacks", rankedHotspots(stackCounts, stackEventCount, "stack"));
            }
            return Map.copyOf(canonical);
        }

        private Map.Entry<String, Long> topEntry(Map<String, Long> counts) {
            return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int compare = Long.compare(right.getValue(), left.getValue());
                    if (compare != 0) {
                        return compare;
                    }
                    return left.getKey().compareTo(right.getKey());
                })
                .findFirst()
                .orElse(null);
        }
    }

    private record StackFingerprint(String primaryMethod, String stackSignature, boolean truncated) {
    }

    private final class WeightedHotspotAccumulator {
        private long stackEventCount;
        private long truncatedStackCount;
        private long sizedStackEventCount;
        private long totalAllocatedBytes;
        private final Map<String, Long> methodCounts = new LinkedHashMap<>();
        private final Map<String, Long> methodBytes = new LinkedHashMap<>();
        private final Map<String, Long> stackCounts = new LinkedHashMap<>();
        private final Map<String, Long> stackBytes = new LinkedHashMap<>();

        private void record(StackFingerprint fingerprint, long allocationBytes) {
            stackEventCount++;
            if (fingerprint.truncated()) {
                truncatedStackCount++;
            }
            methodCounts.merge(fingerprint.primaryMethod(), 1L, Long::sum);
            stackCounts.merge(fingerprint.stackSignature(), 1L, Long::sum);
            if (allocationBytes > 0L) {
                sizedStackEventCount++;
                totalAllocatedBytes += allocationBytes;
                methodBytes.merge(fingerprint.primaryMethod(), allocationBytes, Long::sum);
                stackBytes.merge(fingerprint.stackSignature(), allocationBytes, Long::sum);
            }
        }

        private long stackEventCount() {
            return stackEventCount;
        }

        private String primaryMethod() {
            return topEntry(methodCounts) != null ? topEntry(methodCounts).getKey() : null;
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("stackEventCount", stackEventCount);
            canonical.put("truncatedStackCount", truncatedStackCount);
            canonical.put("sizedStackEventCount", sizedStackEventCount);
            canonical.put("totalAllocatedBytes", totalAllocatedBytes);
            if (!methodCounts.isEmpty()) {
                Map.Entry<String, Long> topMethod = topEntry(methodCounts);
                canonical.put("topMethod", topMethod.getKey());
                canonical.put("topMethodCount", topMethod.getValue());
                canonical.put("topMethodShare", ratio(topMethod.getValue(), stackEventCount));
                canonical.put("topMethodAllocatedBytes", methodBytes.getOrDefault(topMethod.getKey(), 0L));
                canonical.put("topMethodAllocatedByteShare", ratio(methodBytes.getOrDefault(topMethod.getKey(), 0L), totalAllocatedBytes));
                canonical.put("topMethods", rankedWeightedHotspots(methodCounts, methodBytes, stackEventCount, totalAllocatedBytes, "method"));
                if (!methodBytes.isEmpty()) {
                    Map.Entry<String, Long> topMethodByBytes = topEntry(methodBytes);
                    canonical.put("topMethodByBytes", topMethodByBytes.getKey());
                    canonical.put("topMethodByBytesAllocatedBytes", topMethodByBytes.getValue());
                    canonical.put("topMethodByBytesShare", ratio(topMethodByBytes.getValue(), totalAllocatedBytes));
                }
            }
            if (!stackCounts.isEmpty()) {
                Map.Entry<String, Long> topStack = topEntry(stackCounts);
                canonical.put("topStack", topStack.getKey());
                canonical.put("topStackCount", topStack.getValue());
                canonical.put("topStackShare", ratio(topStack.getValue(), stackEventCount));
                canonical.put("topStackAllocatedBytes", stackBytes.getOrDefault(topStack.getKey(), 0L));
                canonical.put("topStackAllocatedByteShare", ratio(stackBytes.getOrDefault(topStack.getKey(), 0L), totalAllocatedBytes));
                canonical.put("topStacks", rankedWeightedHotspots(stackCounts, stackBytes, stackEventCount, totalAllocatedBytes, "stack"));
                if (!stackBytes.isEmpty()) {
                    Map.Entry<String, Long> topStackByBytes = topEntry(stackBytes);
                    canonical.put("topStackByBytes", topStackByBytes.getKey());
                    canonical.put("topStackByBytesAllocatedBytes", topStackByBytes.getValue());
                    canonical.put("topStackByBytesShare", ratio(topStackByBytes.getValue(), totalAllocatedBytes));
                }
            }
            return Map.copyOf(canonical);
        }
    }
}
