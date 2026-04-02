package com.javaassistant.parse;

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

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.JFR;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Path recordingPath = resolveRecordingPath(artifact);
        if (recordingPath == null || !Files.exists(recordingPath)) {
            throw new IllegalArgumentException("JFR recording file is not available on disk for structured parsing.");
        }

        long recordingSizeBytes = fileSize(artifact, recordingPath);
        List<EventType> declaredJfrEventTypes = readEventTypes(recordingPath);
        Map<String, EventSummaryAccumulator> eventTypeAccumulators = new LinkedHashMap<>();
        StackHotspotAccumulator overallHotspots = new StackHotspotAccumulator();
        StackHotspotAccumulator executionHotspots = new StackHotspotAccumulator();
        StackHotspotAccumulator runtimeHotspots = new StackHotspotAccumulator();
        AllocationAnalyticsAccumulator allocationAnalytics = new AllocationAnalyticsAccumulator();
        OldObjectAnalyticsAccumulator oldObjectAnalytics = new OldObjectAnalyticsAccumulator();
        Instant startTime = null;
        Instant endTime = null;
        long eventCount = 0L;

        try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                eventCount++;

                String eventTypeName = event.getEventType().getName();
                EventSummaryAccumulator accumulator = eventTypeAccumulators.get(eventTypeName);
                if (accumulator == null) {
                    accumulator = new EventSummaryAccumulator(eventTypeName, event.getEventType().getLabel());
                }
                eventTypeAccumulators.put(eventTypeName, accumulator.record(event));

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

        SignalSummary lockSummary = summarize(eventTypeAccumulators.values(), this::isLockContentionEvent);
        SignalSummary gcSummary = summarize(eventTypeAccumulators.values(), this::isGcPauseEvent);
        SignalSummary threadParkSummary = summarize(eventTypeAccumulators.values(), this::isThreadParkEvent);
        SignalSummary ioSummary = summarize(eventTypeAccumulators.values(), this::isIoLatencyEvent);
        SignalSummary exceptionSummary = summarize(eventTypeAccumulators.values(), this::isExceptionEvent);
        SignalSummary safepointSummary = summarize(eventTypeAccumulators.values(), this::isSafepointEvent);
        SignalSummary allocationSummary = summarize(eventTypeAccumulators.values(), this::isAllocationEvent);
        SignalSummary oldObjectSummary = summarize(eventTypeAccumulators.values(), this::isOldObjectSampleEvent);
        SignalSummary executionSummary = summarize(eventTypeAccumulators.values(), this::isExecutionSampleEvent);
        Map<String, Object> allocationFieldSummary = allocationAnalytics.toFieldCanonicalMap();
        Map<String, Object> allocationHotspotSummary = allocationAnalytics.toHotspotCanonicalMap();
        Map<String, Object> oldObjectFieldSummary = oldObjectAnalytics.toCanonicalMap();

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
        coverage.put("threadParkEventsPresent", threadParkSummary.eventCount() > 0L);
        coverage.put("ioEventsPresent", ioSummary.eventCount() > 0L);
        coverage.put("exceptionEventsPresent", exceptionSummary.eventCount() > 0L);
        coverage.put("safepointEventsPresent", safepointSummary.eventCount() > 0L);
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

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", Map.copyOf(summary));
        extractedData.put("coverage", Map.copyOf(coverage));
        extractedData.put("observedEventTypes", observedEventTypes);
        extractedData.put("topEventTypes", topEventTypes);
        extractedData.put("declaredEventTypes", declaredEventTypes);
        extractedData.put("lockSummary", lockSummary.toCanonicalMap());
        extractedData.put("gcSummary", gcSummary.toCanonicalMap());
        extractedData.put("threadParkSummary", threadParkSummary.toCanonicalMap());
        extractedData.put("ioSummary", ioSummary.toCanonicalMap());
        extractedData.put("exceptionSummary", exceptionSummary.toCanonicalMap());
        extractedData.put("safepointSummary", safepointSummary.toCanonicalMap());
        extractedData.put("allocationSummary", allocationSummary.toCanonicalMap());
        extractedData.put("allocationFieldSummary", allocationFieldSummary);
        extractedData.put("allocationHotspotSummary", allocationHotspotSummary);
        extractedData.put("oldObjectSummary", oldObjectSummary.toCanonicalMap());
        extractedData.put("oldObjectFieldSummary", oldObjectFieldSummary);
        extractedData.put("executionSummary", executionSummary.toCanonicalMap());
        extractedData.put("overallHotspotSummary", overallHotspots.toCanonicalMap());
        extractedData.put("executionHotspotSummary", executionHotspots.toCanonicalMap());
        extractedData.put("runtimeHotspotSummary", runtimeHotspots.toCanonicalMap());

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

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "jfr-analytics-v6", extractedData, evidence, warnings);
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
        return Map.copyOf(canonical);
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

    private boolean isGcPauseEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("garbagecollection") || normalized.contains("gcphasepause");
    }

    private boolean isAllocationEvent(String eventTypeName) {
        String normalized = normalize(eventTypeName);
        return normalized.contains("objectallocation") || normalized.contains("objectcountaftergc");
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
            || isThreadParkEvent(eventTypeName)
            || isIoLatencyEvent(eventTypeName)
            || isExceptionEvent(eventTypeName)
            || isSafepointEvent(eventTypeName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private Long numericValue(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            return longValue > 0L ? longValue : null;
        }
        return null;
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
