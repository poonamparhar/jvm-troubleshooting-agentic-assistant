package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Focused derived views for agents. These computations summarize diagnostic data without turning it into conclusions.
 */
public class DiagnosticComputationService {

    private static final int MAX_RESULT_CHARS = 4200;

    public DiagnosticToolResult compute(IndexedArtifactDiagnosticContext indexedContext, String request) {
        if (indexedContext == null || indexedContext.parsedArtifact() == null) {
            return unavailable(indexedContext, "No parsed artifact data was available for focused computation.");
        }

        String normalizedRequest = request == null ? "" : request.toLowerCase(Locale.ROOT).trim();
        ArtifactType artifactType = indexedContext.parsedArtifact().type();
        return switch (artifactType) {
            case GC_LOG -> computeGc(indexedContext, normalizedRequest);
            case JFR -> computeJfr(indexedContext, normalizedRequest);
            case THREAD_DUMP -> computeThreadDump(indexedContext, normalizedRequest);
            case HS_ERR_LOG -> computeHsErr(indexedContext, normalizedRequest);
            case NMT -> computeNmt(indexedContext, normalizedRequest);
            case HEAP_HISTOGRAM -> computeHeapHistogram(indexedContext, normalizedRequest);
            case PMAP -> computePmap(indexedContext, normalizedRequest);
            case CONTAINER_MEMORY -> computeContainerMemory(indexedContext, normalizedRequest);
            case OOM_SIGNAL -> computeOomSignal(indexedContext, normalizedRequest);
            default -> unavailable(indexedContext, "No focused computation is implemented for " + artifactType + '.');
        };
    }

    private DiagnosticToolResult computeGc(IndexedArtifactDiagnosticContext indexedContext, String request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = listOfMaps(indexedContext.parsedArtifact().extractedData().get("pauses"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gcCycles = listOfMaps(indexedContext.parsedArtifact().extractedData().get("gcCycles"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocationStalls = listOfMaps(indexedContext.parsedArtifact().extractedData().get("allocationStalls"));
        Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));

        if (request.contains("cause")) {
            Map<String, Long> causeDistribution = new LinkedHashMap<>();
            for (Map<String, Object> pause : pauses) {
                String cause = stringValue(pause.get("cause"));
                if (!cause.isBlank()) {
                    causeDistribution.merge(cause, 1L, Long::sum);
                }
            }
            return derived(indexedContext, "gc-cause-distribution", "GC cause distribution", causeDistribution, "extractedData.pauses", false);
        }

        if (request.contains("occupancy")) {
            List<Map<String, Object>> occupancy = pauses.stream()
                .filter(pause -> pause.get("afterHeapMb") != null && pause.get("heapCapacityMb") != null)
                .sorted(Comparator.comparingLong(pause -> longValue(pause.get("lineNumber"))))
                .limit(8)
                .map(pause -> {
                    LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
                    sample.put("lineNumber", longValue(pause.get("lineNumber")));
                    sample.put("afterHeapMb", longValue(pause.get("afterHeapMb")));
                    sample.put("heapCapacityMb", longValue(pause.get("heapCapacityMb")));
                    sample.put("occupancyRatio", ratio(longValue(pause.get("afterHeapMb")), longValue(pause.get("heapCapacityMb"))));
                    return Map.<String, Object>copyOf(sample);
                })
                .toList();
            return derived(indexedContext, "gc-occupancy-progression", "GC occupancy progression", occupancy, "extractedData.pauses", pauses.size() > occupancy.size());
        }

        if (request.contains("stall")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("allocationStallCount", allocationStalls.size());
            payload.put("topAllocationStalls", allocationStalls.stream().limit(6).toList());
            return derived(indexedContext, "gc-allocation-stalls", "GC allocation stall summary", payload, "extractedData.allocationStalls", allocationStalls.size() > 6);
        }

        if (request.contains("cycle")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("gcCycleCount", gcCycles.size());
            payload.put("topCycles", gcCycles.stream().limit(6).toList());
            return derived(indexedContext, "gc-cycle-summary", "GC cycle summary", payload, "extractedData.gcCycles", gcCycles.size() > 6);
        }

        List<Double> pauseMs = pauses.stream()
            .map(pause -> doubleValue(pause.get("pauseMs")))
            .filter(value -> value > 0.0d)
            .sorted()
            .toList();
        LinkedHashMap<String, Object> percentiles = new LinkedHashMap<>();
        percentiles.put("pauseEventCount", pauseMs.size());
        percentiles.put("p50PauseMs", percentile(pauseMs, 50));
        percentiles.put("p95PauseMs", percentile(pauseMs, 95));
        percentiles.put("p99PauseMs", percentile(pauseMs, 99));
        percentiles.put("maxPauseMs", summary.get("maxPauseMs"));
        percentiles.put("fullGcCount", summary.get("fullGcCount"));
        return derived(indexedContext, "gc-pause-percentiles", "GC pause percentile summary", percentiles, "extractedData.pauses", false);
    }

    private DiagnosticToolResult computeJfr(IndexedArtifactDiagnosticContext indexedContext, String request) {
        Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));
        Object observedEventTypes = indexedContext.parsedArtifact().extractedData().get("observedEventTypes");
        Object declaredEventTypes = indexedContext.parsedArtifact().extractedData().get("declaredEventTypes");
        if (request.contains("allocation")) {
            return derived(
                indexedContext,
                "jfr-allocation-summary",
                "JFR allocation computation view",
                indexedContext.parsedArtifact().extractedData().get("allocationFieldSummary"),
                "extractedData.allocationFieldSummary",
                false
            );
        }
        if (request.contains("old") || request.contains("retained")) {
            return derived(
                indexedContext,
                "jfr-old-object-summary",
                "JFR old-object computation view",
                indexedContext.parsedArtifact().extractedData().get("oldObjectFieldSummary"),
                "extractedData.oldObjectFieldSummary",
                false
            );
        }
        if (request.contains("runtime") || request.contains("wait")) {
            return derived(
                indexedContext,
                "jfr-runtime-hotspots",
                "JFR runtime hotspot computation view",
                indexedContext.parsedArtifact().extractedData().get("runtimeHotspotSummary"),
                "extractedData.runtimeHotspotSummary",
                false
            );
        }
        if (request.contains("execution") || request.contains("cpu") || request.contains("hotspot")) {
            return derived(
                indexedContext,
                "jfr-execution-hotspots",
                "JFR execution hotspot computation view",
                indexedContext.parsedArtifact().extractedData().get("executionHotspotSummary"),
                "extractedData.executionHotspotSummary",
                false
            );
        }
        if (request.contains("event") || request.contains("catalog")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("observedEventTypes", observedEventTypes);
            payload.put("declaredEventTypes", declaredEventTypes);
            return derived(
                indexedContext,
                "jfr-event-catalog",
                "JFR event catalog view",
                payload,
                "extractedData.observedEventTypes + extractedData.declaredEventTypes",
                false
            );
        }
        if (request.contains("time") || request.contains("window")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("startTime", summary.get("startTime"));
            payload.put("endTime", summary.get("endTime"));
            payload.put("durationMs", summary.get("durationMs"));
            payload.put("observedEventTypes", observedEventTypes);
            payload.put("note", "This focused view summarizes the available recording window. Use retrieval to expand event-family, hotspot, allocation, or old-object detail when needed.");
            return derived(indexedContext, "jfr-time-window-summary", "JFR time-window summary", payload, "extractedData.summary + extractedData.observedEventTypes", false);
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("observedEventTypes", observedEventTypes);
        payload.put("declaredEventTypes", declaredEventTypes);
        payload.put("executionHotspotSummary", indexedContext.parsedArtifact().extractedData().get("executionHotspotSummary"));
        payload.put("runtimeHotspotSummary", indexedContext.parsedArtifact().extractedData().get("runtimeHotspotSummary"));
        return derived(
            indexedContext,
            "jfr-focus-summary",
            "JFR focused summary",
            payload,
            "extractedData.observedEventTypes + extractedData.declaredEventTypes + hotspot summaries",
            false
        );
    }

    private DiagnosticToolResult computeThreadDump(IndexedArtifactDiagnosticContext indexedContext, String request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> threads = listOfMaps(indexedContext.parsedArtifact().extractedData().get("threads"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentionHotspots = listOfMaps(indexedContext.parsedArtifact().extractedData().get("contentionHotspots"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> poolSummaries = listOfMaps(indexedContext.parsedArtifact().extractedData().get("poolSummaries"));
        Map<String, Object> deadlock = mapValue(indexedContext.parsedArtifact().extractedData().get("deadlock"));

        if (request.contains("pool")) {
            return derived(indexedContext, "thread-pool-summary", "Thread pool summary", poolSummaries, "extractedData.poolSummaries", poolSummaries.size() > 6);
        }
        if (request.contains("deadlock")) {
            return derived(indexedContext, "thread-deadlock-summary", "Thread deadlock summary", deadlock, "extractedData.deadlock", false);
        }
        if (request.contains("cluster") || request.contains("blocked")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("blockedThreadCount", indexedContext.parsedArtifact().extractedData().get("blockedThreadCount"));
            payload.put("contentionHotspots", contentionHotspots.stream().limit(6).toList());
            return derived(indexedContext, "thread-blocked-clusters", "Blocked-thread cluster summary", payload, "extractedData.contentionHotspots", contentionHotspots.size() > 6);
        }

        Map<String, Long> stateCounts = new LinkedHashMap<>();
        for (Map<String, Object> thread : threads) {
            String state = stringValue(thread.get("state"));
            if (!state.isBlank()) {
                stateCounts.merge(state, 1L, Long::sum);
            }
        }
        return derived(indexedContext, "thread-state-summary", "Thread-state summary", stateCounts, "extractedData.threads", false);
    }

    private DiagnosticToolResult computeHsErr(IndexedArtifactDiagnosticContext indexedContext, String request) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (request.contains("vm") || request.contains("argument")) {
            payload.put("vm", indexedContext.parsedArtifact().extractedData().get("vm"));
            payload.put("commandLine", indexedContext.parsedArtifact().extractedData().get("commandLine"));
            return derived(indexedContext, "hs-err-vm-context", "hs_err VM context", payload, "extractedData.vm + extractedData.commandLine", false);
        }
        if (request.contains("thread")) {
            payload.put("currentThreadName", indexedContext.parsedArtifact().extractedData().get("currentThreadName"));
            payload.put("currentThread", indexedContext.parsedArtifact().extractedData().get("currentThread"));
            return derived(indexedContext, "hs-err-thread-context", "hs_err current-thread context", payload, "extractedData.currentThread", false);
        }
        payload.put("signal", indexedContext.parsedArtifact().extractedData().get("signal"));
        payload.put("crashType", indexedContext.parsedArtifact().extractedData().get("crashType"));
        payload.put("problematicFrame", indexedContext.parsedArtifact().extractedData().get("problematicFrame"));
        payload.put("nativeAllocationFailure", indexedContext.parsedArtifact().extractedData().get("nativeAllocationFailure"));
        return derived(indexedContext, "hs-err-crash-summary", "hs_err crash summary", payload, "extractedData.signal/crashType/problematicFrame", false);
    }

    private DiagnosticToolResult computeNmt(IndexedArtifactDiagnosticContext indexedContext, String request) {
        Object metaspace = indexedContext.parsedArtifact().extractedData().get("metaspaceSummary");
        Object threadSummary = indexedContext.parsedArtifact().extractedData().get("threadSummary");
        Object categories = indexedContext.parsedArtifact().extractedData().get("categories");
        Object categoryDeltas = indexedContext.parsedArtifact().extractedData().get("categoryDeltas");

        if (request.contains("metaspace")) {
            return derived(indexedContext, "nmt-metaspace-summary", "NMT metaspace summary", metaspace, "extractedData.metaspaceSummary", false);
        }
        if (request.contains("thread")) {
            return derived(indexedContext, "nmt-thread-summary", "NMT thread summary", threadSummary, "extractedData.threadSummary", false);
        }
        if (request.contains("delta") || request.contains("growth")) {
            return derived(indexedContext, "nmt-category-deltas", "NMT category delta summary", categoryDeltas, "extractedData.categoryDeltas", false);
        }
        return derived(indexedContext, "nmt-category-summary", "NMT category summary", categories, "extractedData.categories", false);
    }

    private DiagnosticToolResult computeHeapHistogram(IndexedArtifactDiagnosticContext indexedContext, String request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = listOfMaps(indexedContext.parsedArtifact().extractedData().get("entries"));
        if (request.contains("growth") || request.contains("diff")) {
            List<Map<String, Object>> topGrowth = entries.stream()
                .sorted(Comparator.comparingLong(entry -> -longValue(entry.get("bytes"))))
                .limit(10)
                .toList();
            return derived(indexedContext, "heap-growth-ranking", "Heap growth ranking view", topGrowth, "extractedData.entries", entries.size() > topGrowth.size());
        }
        if (request.contains("retention") || request.contains("family")) {
            Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("cacheLikeBytes", summary.get("cacheLikeBytes"));
            payload.put("collectionBytes", summary.get("collectionBytes"));
            payload.put("payloadBytes", summary.get("payloadBytes"));
            payload.put("referenceBytes", summary.get("referenceBytes"));
            payload.put("threadBytes", summary.get("threadBytes"));
            return derived(indexedContext, "heap-retention-families", "Heap retention-family summary", payload, "extractedData.summary", false);
        }
        return derived(indexedContext, "heap-top-consumers", "Heap top-consumer summary", indexedContext.parsedArtifact().extractedData().get("topConsumers"), "extractedData.topConsumers", false);
    }

    private DiagnosticToolResult computePmap(IndexedArtifactDiagnosticContext indexedContext, String request) {
        Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));
        Object categorySummaries = indexedContext.parsedArtifact().extractedData().get("categorySummaries");
        Object largestResidentMappings = indexedContext.parsedArtifact().extractedData().get("largestResidentMappings");
        if (request.contains("resident") || request.contains("rss")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", summary);
            payload.put("largestResidentMappings", largestResidentMappings);
            return derived(indexedContext, "pmap-resident-summary", "pmap resident summary", payload, "extractedData.summary + extractedData.largestResidentMappings", false);
        }
        if (request.contains("category") || request.contains("concentration")) {
            return derived(indexedContext, "pmap-category-summary", "pmap category summary", categorySummaries, "extractedData.categorySummaries", false);
        }
        return derived(indexedContext, "pmap-summary", "pmap summary", summary, "extractedData.summary", false);
    }

    private DiagnosticToolResult computeContainerMemory(IndexedArtifactDiagnosticContext indexedContext, String request) {
        if (request.contains("pressure")) {
            return derived(indexedContext, "container-pressure-summary", "Container pressure summary", indexedContext.parsedArtifact().extractedData().get("pressure"), "extractedData.pressure", false);
        }
        if (request.contains("event") || request.contains("oom")) {
            return derived(indexedContext, "container-event-summary", "Container memory.events summary", indexedContext.parsedArtifact().extractedData().get("events"), "extractedData.events", false);
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", indexedContext.parsedArtifact().extractedData().get("summary"));
        payload.put("stat", indexedContext.parsedArtifact().extractedData().get("stat"));
        return derived(indexedContext, "container-budget-summary", "Container memory budget summary", payload, "extractedData.summary + extractedData.stat", false);
    }

    private DiagnosticToolResult computeOomSignal(IndexedArtifactDiagnosticContext indexedContext, String request) {
        if (request.contains("kernel")) {
            return derived(indexedContext, "oom-kernel-summary", "Kernel OOM summary", indexedContext.parsedArtifact().extractedData().get("kernelEvents"), "extractedData.kernelEvents", false);
        }
        if (request.contains("restart") || request.contains("pod")) {
            return derived(indexedContext, "oom-pod-summary", "Pod OOM or restart summary", indexedContext.parsedArtifact().extractedData().get("podSignals"), "extractedData.podSignals", false);
        }
        return derived(indexedContext, "oom-signal-summary", "OOM and restart summary", indexedContext.parsedArtifact().extractedData().get("summary"), "extractedData.summary", false);
    }

    private DiagnosticToolResult derived(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        String label,
        Object payload,
        String traceability,
        boolean moreAvailable
    ) {
        String rendered = DiagnosticContextRenderSupport.renderValue(payload);
        boolean truncated = rendered.length() > MAX_RESULT_CHARS;
        return new DiagnosticToolResult(
            indexedContext.parsedArtifact().type(),
            artifactPath(indexedContext),
            sliceId,
            "computed",
            label,
            truncated ? DiagnosticContextRenderSupport.truncateBlock(rendered, MAX_RESULT_CHARS) : rendered,
            traceability,
            truncated,
            moreAvailable
        );
    }

    private DiagnosticToolResult unavailable(IndexedArtifactDiagnosticContext indexedContext, String reason) {
        return new DiagnosticToolResult(
            indexedContext != null && indexedContext.parsedArtifact() != null ? indexedContext.parsedArtifact().type() : null,
            artifactPath(indexedContext),
            "unavailable",
            "notice",
            "No focused computation",
            reason,
            "internal-focused-computation",
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            converted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(converted);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0d;
        }
        int index = Math.min(sortedValues.size() - 1, (int) Math.ceil((percentile / 100.0d) * sortedValues.size()) - 1);
        return sortedValues.get(Math.max(0, index));
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0L ? 0.0d : (double) numerator / (double) denominator;
    }
}
