package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;
import java.time.Instant;
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
    private static final double GC_DEFAULT_WINDOW_SECONDS = 15.0d;
    private static final double GC_STREAK_MAX_GAP_SECONDS = 2.0d;
    private static final int GC_STREAK_MAX_GAP_LINES = 160;

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
        ContextSelector selector = ContextSelector.fromQuery(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pauses = listOfMaps(indexedContext.parsedArtifact().extractedData().get("pauses"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gcCycles = listOfMaps(indexedContext.parsedArtifact().extractedData().get("gcCycles"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocationStalls = listOfMaps(indexedContext.parsedArtifact().extractedData().get("allocationStalls"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workerSamples = listOfMaps(indexedContext.parsedArtifact().extractedData().get("workerSamples"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cpuSamples = listOfMaps(indexedContext.parsedArtifact().extractedData().get("cpuSamples"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> humongousRegionSamples = listOfMaps(indexedContext.parsedArtifact().extractedData().get("humongousRegionSamples"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phaseSamples = listOfMaps(indexedContext.parsedArtifact().extractedData().get("phaseSamples"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failureSignals = listOfMaps(indexedContext.parsedArtifact().extractedData().get("failureSignals"));
        Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));
        Map<String, Object> pauseBreakdown = mapValue(indexedContext.parsedArtifact().extractedData().get("pauseBreakdown"));
        Map<String, Object> collectorPressureSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("collectorPressureSummary"));
        Map<String, Object> recoverySummary = mapValue(indexedContext.parsedArtifact().extractedData().get("recoverySummary"));
        Map<String, Object> g1CycleProgressionSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> failureSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("failureSummary"));
        Map<String, Object> phaseSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("phaseSummary"));
        Map<String, Object> concurrentSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("concurrentSummary"));
        Map<String, Object> workerSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("workerSummary"));
        Map<String, Object> cpuSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("cpuSummary"));
        Map<String, Object> humongousSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("humongousSummary"));
        Map<String, Object> metaspaceSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("metaspace"));

        if (selector.incident() != null || request.contains("dominant-window")) {
            return computeGcIncidentWindowSummary(
                indexedContext,
                selector,
                pauses,
                gcCycles,
                allocationStalls,
                phaseSamples,
                failureSignals
            );
        }

        if (selector.streakKind() != null || request.contains("streak")) {
            return computeGcStreakSummary(indexedContext, selector, pauses, failureSignals, summary);
        }

        if (selector.timestampStart() != null || selector.timestampEnd() != null || selector.windowSeconds() != null) {
            return computeGcTimeWindowSummary(
                indexedContext,
                selector,
                pauses,
                gcCycles,
                allocationStalls,
                phaseSamples,
                failureSignals
            );
        }

        if (selector.cause() != null || selector.pauseType() != null) {
            return computeGcPauseFocus(indexedContext, selector, pauses);
        }

        if (selector.phase() != null || selector.phaseKind() != null) {
            return computeGcPhaseFocus(indexedContext, selector, phaseSamples);
        }

        if (selector.signalType() != null) {
            return computeGcSignalFocus(indexedContext, selector, failureSignals);
        }

        if (selector.gcId() != null) {
            return computeGcIdFocus(
                indexedContext,
                selector.gcId(),
                pauses,
                gcCycles,
                allocationStalls,
                workerSamples,
                cpuSamples,
                humongousRegionSamples,
                phaseSamples,
                failureSignals
            );
        }

        if (request.contains("collector-pressure")
            || request.contains("collector-summary")
            || request.contains("g1-pressure")
            || request.contains("cms-pressure")
            || request.contains("serial-pressure")
            || request.contains("parallel-pressure")
            || request.contains("zgc-pressure")) {
            if (collectorPressureSummary.isEmpty()) {
                return unavailable(indexedContext, "No collector-focused GC pressure summary was available for focused computation.");
            }
            return derived(
                indexedContext,
                "gc-collector-pressure-summary",
                "GC collector-focused pressure summary",
                collectorPressureSummary,
                "extractedData.collectorPressureSummary",
                false
            );
        }

        if (request.contains("cause")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            if (pauseBreakdown.get("causeCounts") instanceof Map<?, ?> causeCounts && !causeCounts.isEmpty()) {
                payload.put("causeCounts", causeCounts);
            } else {
                Map<String, Long> causeDistribution = new LinkedHashMap<>();
                for (Map<String, Object> pause : pauses) {
                    String cause = stringValue(pause.get("cause"));
                    if (!cause.isBlank()) {
                        causeDistribution.merge(cause, 1L, Long::sum);
                    }
                }
                if (!causeDistribution.isEmpty()) {
                    payload.put("causeCounts", causeDistribution);
                }
            }
            if (pauseBreakdown.containsKey("causeTotalPauseMs")) {
                payload.put("causeTotalPauseMs", pauseBreakdown.get("causeTotalPauseMs"));
            }
            if (pauseBreakdown.containsKey("causeMaxPauseMs")) {
                payload.put("causeMaxPauseMs", pauseBreakdown.get("causeMaxPauseMs"));
            }
            if (pauseBreakdown.containsKey("dominantPauseCauseByCount")) {
                payload.put("dominantPauseCauseByCount", pauseBreakdown.get("dominantPauseCauseByCount"));
            }
            if (pauseBreakdown.containsKey("dominantPauseCauseByTotalPauseMs")) {
                payload.put("dominantPauseCauseByTotalPauseMs", pauseBreakdown.get("dominantPauseCauseByTotalPauseMs"));
            }
            if (payload.isEmpty()) {
                return unavailable(indexedContext, "No GC pause causes were available for focused computation.");
            }
            return derived(
                indexedContext,
                "gc-cause-distribution",
                "GC cause distribution",
                payload,
                "extractedData.pauseBreakdown",
                false
            );
        }

        if (request.contains("recovery") || request.contains("headroom")) {
            if (recoverySummary.isEmpty()) {
                return unavailable(indexedContext, "No GC recovery summary was available for focused computation.");
            }
            return derived(
                indexedContext,
                "gc-recovery-summary",
                "GC recovery and headroom summary",
                recoverySummary,
                "extractedData.recoverySummary",
                false
            );
        }

        if (request.contains("g1-cycle") || request.contains("mixed-phase") || request.contains("full-recovery")) {
            if (g1CycleProgressionSummary.isEmpty()) {
                return unavailable(indexedContext, "No G1 cycle-progression summary was available for focused computation.");
            }
            return derived(
                indexedContext,
                "g1-cycle-progression-summary",
                "G1 cycle-progression summary",
                g1CycleProgressionSummary,
                "extractedData.g1CycleProgressionSummary",
                false
            );
        }

        if (request.contains("metaspace")) {
            return derived(indexedContext, "gc-metaspace-summary", "GC metaspace summary", metaspaceSummary, "extractedData.metaspace", false);
        }

        if (request.contains("failure") || request.contains("evacuation") || request.contains("metadata")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("failureSummary", failureSummary);
            payload.put(
                "topFailureSignals",
                failureSignals.stream()
                    .sorted(gcChronologyComparator())
                    .limit(8)
                    .toList()
            );
            return derived(
                indexedContext,
                "gc-failure-summary",
                "GC failure and pressure signals",
                payload,
                "extractedData.failureSummary + extractedData.failureSignals",
                failureSignals.size() > 8
            );
        }

        if (request.contains("phase") || request.contains("concurrent")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("phaseSummary", phaseSummary);
            payload.put("concurrentSummary", concurrentSummary);
            payload.put(
                "topPhaseSamples",
                phaseSamples.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("durationMs")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(10)
                    .toList()
            );
            return derived(
                indexedContext,
                "gc-phase-summary",
                "GC phase timing summary",
                payload,
                "extractedData.phaseSummary + extractedData.concurrentSummary + extractedData.phaseSamples",
                phaseSamples.size() > 10
            );
        }

        if (request.contains("worker")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("workerSummary", workerSummary);
            payload.put(
                "topWorkerSamples",
                workerSamples.stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> sample) -> -longValue(sample.get("activeWorkers")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(8)
                    .toList()
            );
            return derived(
                indexedContext,
                "gc-worker-summary",
                "GC worker summary",
                payload,
                "extractedData.workerSummary + extractedData.workerSamples",
                workerSamples.size() > 8
            );
        }

        if (request.contains("cpu")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("cpuSummary", cpuSummary);
            payload.put(
                "topCpuSamples",
                cpuSamples.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("realSeconds")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(8)
                    .toList()
            );
            return derived(
                indexedContext,
                "gc-cpu-summary",
                "GC CPU and wall-clock summary",
                payload,
                "extractedData.cpuSummary + extractedData.cpuSamples",
                cpuSamples.size() > 8
            );
        }

        if (request.contains("humongous")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("humongousSummary", humongousSummary);
            payload.put(
                "topHumongousSamples",
                humongousRegionSamples.stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> sample) -> -longValue(sample.get("afterRegions")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(8)
                    .toList()
            );
            return derived(
                indexedContext,
                "gc-humongous-summary",
                "GC humongous-region summary",
                payload,
                "extractedData.humongousSummary + extractedData.humongousRegionSamples",
                humongousRegionSamples.size() > 8
            );
        }

        if (request.contains("breakdown")) {
            return derived(
                indexedContext,
                "gc-pause-breakdown",
                "GC pause breakdown",
                pauseBreakdown,
                "extractedData.pauseBreakdown",
                false
            );
        }

        if (request.contains("occupancy")) {
            List<Map<String, Object>> occupancy = pauses.stream()
                .filter(pause -> pause.get("afterHeapMb") != null && pause.get("heapCapacityMb") != null)
                .sorted(gcChronologyComparator())
                .limit(10)
                .map(pause -> {
                    LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
                    sample.put("lineNumber", longValue(pause.get("lineNumber")));
                    sample.put("gcId", longValue(pause.get("gcId")));
                    sample.put("pauseType", pause.get("pauseType"));
                    sample.put("cause", pause.get("cause"));
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
            payload.put("totalAllocationStallMs", summary.get("totalAllocationStallMs"));
            payload.put("maxAllocationStallMs", summary.get("maxAllocationStallMs"));
            payload.put("topAllocationStalls", allocationStalls.stream().limit(8).toList());
            return derived(indexedContext, "gc-allocation-stalls", "GC allocation stall summary", payload, "extractedData.allocationStalls", allocationStalls.size() > 8);
        }

        if (request.contains("cycle")) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("gcCycleCount", gcCycles.size());
            if (!g1CycleProgressionSummary.isEmpty()) {
                payload.put("g1CycleProgressionSummary", g1CycleProgressionSummary);
            }
            payload.put(
                "topCycles",
                gcCycles.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> cycle) -> -doubleValue(cycle.get("durationMs")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(8)
                    .toList()
            );
            return derived(indexedContext, "gc-cycle-summary", "GC cycle summary", payload, "extractedData.gcCycles", gcCycles.size() > 8);
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
        percentiles.put("evacuationFailurePauseCount", summary.get("evacuationFailurePauseCount"));
        percentiles.put("concurrentMarkAbortCount", summary.get("concurrentMarkAbortCount"));
        return derived(indexedContext, "gc-pause-percentiles", "GC pause percentile summary", percentiles, "extractedData.pauses", false);
    }

    private DiagnosticToolResult computeGcPauseFocus(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> pauses
    ) {
        List<Map<String, Object>> matchingPauses = pauses.stream()
            .filter(pause -> matchesGcId(pause, selector.gcId()))
            .filter(pause -> matchesText(pause.get("cause"), selector.cause()))
            .filter(pause -> matchesText(pause.get("pauseType"), selector.pauseType()))
            .sorted(Comparator.comparingDouble((Map<String, Object> pause) -> -doubleValue(pause.get("pauseMs")))
                .thenComparing(gcChronologyComparator()))
            .toList();
        if (matchingPauses.isEmpty()) {
            return unavailable(indexedContext, "No GC pause samples matched the requested cause or pause type.");
        }

        List<Double> pauseDurations = matchingPauses.stream()
            .map(pause -> doubleValue(pause.get("pauseMs")))
            .filter(duration -> duration > 0.0d)
            .sorted()
            .toList();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (selector.cause() != null) {
            payload.put("requestedCause", selector.cause());
        }
        if (selector.pauseType() != null) {
            payload.put("requestedPauseType", selector.pauseType());
        }
        if (selector.gcId() != null) {
            payload.put("gcId", selector.gcId());
        }
        payload.put("matchingPauseCount", matchingPauses.size());
        payload.put("p50PauseMs", percentile(pauseDurations, 50.0d));
        payload.put("p95PauseMs", percentile(pauseDurations, 95.0d));
        payload.put("p99PauseMs", percentile(pauseDurations, 99.0d));
        payload.put("maxPauseMs", pauseDurations.isEmpty() ? 0.0d : pauseDurations.getLast());
        payload.put("matchingPauses", matchingPauses.stream().limit(8).toList());

        return derived(
            indexedContext,
            selector.cause() != null ? "gc-cause-focus-" + sanitizeId(selector.cause()) : "gc-pause-type-focus-" + sanitizeId(selector.pauseType()),
            selector.cause() != null ? "GC cause-focused view" : "GC pause-type-focused view",
            payload,
            "extractedData.pauses",
            matchingPauses.size() > 8
        );
    }

    private DiagnosticToolResult computeGcPhaseFocus(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> phaseSamples
    ) {
        List<Map<String, Object>> matchingPhases = phaseSamples.stream()
            .filter(sample -> matchesGcId(sample, selector.gcId()))
            .filter(sample -> matchesText(sample.get("phase"), selector.phase()))
            .filter(sample -> matchesText(sample.get("phaseKind"), selector.phaseKind()))
            .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("durationMs")))
                .thenComparing(gcChronologyComparator()))
            .toList();
        if (matchingPhases.isEmpty()) {
            return unavailable(indexedContext, "No GC phase samples matched the requested phase or phase kind.");
        }

        double totalDurationMs = matchingPhases.stream()
            .mapToDouble(sample -> doubleValue(sample.get("durationMs")))
            .sum();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (selector.phase() != null) {
            payload.put("requestedPhase", selector.phase());
        }
        if (selector.phaseKind() != null) {
            payload.put("requestedPhaseKind", selector.phaseKind());
        }
        if (selector.gcId() != null) {
            payload.put("gcId", selector.gcId());
        }
        payload.put("matchingPhaseCount", matchingPhases.size());
        payload.put("averageDurationMs", matchingPhases.isEmpty() ? 0.0d : totalDurationMs / matchingPhases.size());
        payload.put("maxDurationMs", matchingPhases.stream().mapToDouble(sample -> doubleValue(sample.get("durationMs"))).max().orElse(0.0d));
        payload.put("matchingPhases", matchingPhases.stream().limit(10).toList());

        return derived(
            indexedContext,
            selector.phase() != null ? "gc-phase-focus-" + sanitizeId(selector.phase()) : "gc-phase-kind-focus-" + sanitizeId(selector.phaseKind()),
            selector.phase() != null ? "GC phase-focused view" : "GC phase-kind-focused view",
            payload,
            "extractedData.phaseSamples",
            matchingPhases.size() > 10
        );
    }

    private DiagnosticToolResult computeGcSignalFocus(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> matchingSignals = failureSignals.stream()
            .filter(signal -> matchesGcId(signal, selector.gcId()))
            .filter(signal -> matchesText(signal.get("signalType"), selector.signalType())
                || matchesText(signal.get("signal"), selector.signalType()))
            .sorted(gcChronologyComparator())
            .toList();
        if (matchingSignals.isEmpty()) {
            return unavailable(indexedContext, "No GC failure signals matched the requested signal type.");
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedSignalType", selector.signalType());
        if (selector.gcId() != null) {
            payload.put("gcId", selector.gcId());
        }
        payload.put("matchingSignalCount", matchingSignals.size());
        payload.put("matchingSignals", matchingSignals.stream().limit(10).toList());
        return derived(
            indexedContext,
            "gc-signal-focus-" + sanitizeId(selector.signalType()),
            "GC failure-signal-focused view",
            payload,
            "extractedData.failureSignals",
            matchingSignals.size() > 10
        );
    }

    private DiagnosticToolResult computeGcIdFocus(
        IndexedArtifactDiagnosticContext indexedContext,
        String gcId,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> workerSamples,
        List<Map<String, Object>> cpuSamples,
        List<Map<String, Object>> humongousRegionSamples,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> matchingPauses = filterByGcId(pauses, gcId);
        List<Map<String, Object>> matchingCycles = filterByGcId(gcCycles, gcId);
        List<Map<String, Object>> matchingStalls = filterByGcId(allocationStalls, gcId);
        List<Map<String, Object>> matchingWorkers = filterByGcId(workerSamples, gcId);
        List<Map<String, Object>> matchingCpu = filterByGcId(cpuSamples, gcId);
        List<Map<String, Object>> matchingHumongous = filterByGcId(humongousRegionSamples, gcId);
        List<Map<String, Object>> matchingPhases = filterByGcId(phaseSamples, gcId);
        List<Map<String, Object>> matchingSignals = filterByGcId(failureSignals, gcId);

        if (matchingPauses.isEmpty()
            && matchingCycles.isEmpty()
            && matchingStalls.isEmpty()
            && matchingWorkers.isEmpty()
            && matchingCpu.isEmpty()
            && matchingHumongous.isEmpty()
            && matchingPhases.isEmpty()
            && matchingSignals.isEmpty()) {
            return unavailable(indexedContext, "No parsed GC data matched the requested GC ID.");
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("gcId", gcId);
        if (!matchingPauses.isEmpty()) {
            payload.put("pauses", matchingPauses);
        }
        if (!matchingCycles.isEmpty()) {
            payload.put("cycles", matchingCycles);
        }
        if (!matchingSignals.isEmpty()) {
            payload.put("failureSignals", matchingSignals);
        }
        if (!matchingPhases.isEmpty()) {
            payload.put(
                "topPhaseSamples",
                matchingPhases.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("durationMs")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(8)
                    .toList()
            );
        }
        if (!matchingWorkers.isEmpty()) {
            payload.put("workerSamples", matchingWorkers);
        }
        if (!matchingCpu.isEmpty()) {
            payload.put("cpuSamples", matchingCpu);
        }
        if (!matchingHumongous.isEmpty()) {
            payload.put("humongousRegionSamples", matchingHumongous);
        }
        if (!matchingStalls.isEmpty()) {
            payload.put("allocationStalls", matchingStalls);
        }

        int totalMatchingItems = matchingPauses.size()
            + matchingCycles.size()
            + matchingSignals.size()
            + matchingPhases.size()
            + matchingWorkers.size()
            + matchingCpu.size()
            + matchingHumongous.size()
            + matchingStalls.size();
        return derived(
            indexedContext,
            "gc-id-focus-" + sanitizeId(gcId),
            "Focused GC ID view",
            payload,
            "extractedData.* filtered by gcId",
            totalMatchingItems > 16
        );
    }

    private DiagnosticToolResult computeGcTimeWindowSummary(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        GcTimeWindow gcTimeWindow = resolveGcTimeWindow(selector, pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        if (gcTimeWindow == null) {
            return unavailable(indexedContext, "No GC events matched the requested time window.");
        }

        List<Map<String, Object>> windowPauses = filterByGcWindow(pauses, gcTimeWindow);
        List<Map<String, Object>> windowCycles = filterByGcWindow(gcCycles, gcTimeWindow);
        List<Map<String, Object>> windowAllocationStalls = filterByGcWindow(allocationStalls, gcTimeWindow);
        List<Map<String, Object>> windowPhases = filterByGcWindow(phaseSamples, gcTimeWindow);
        List<Map<String, Object>> windowFailureSignals = filterByGcWindow(failureSignals, gcTimeWindow);

        List<Double> pauseDurations = windowPauses.stream()
            .map(pause -> doubleValue(pause.get("pauseMs")))
            .filter(duration -> duration > 0.0d)
            .sorted()
            .toList();
        double totalPauseMs = pauseDurations.stream().mapToDouble(Double::doubleValue).sum();
        double totalAllocationStallMs = windowAllocationStalls.stream()
            .mapToDouble(stall -> doubleValue(stall.get("stallMs")))
            .sum();
        double spanMs = Math.max(0.0d, (gcTimeWindow.endSeconds() - gcTimeWindow.startSeconds()) * 1_000.0d);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (selector.timestampStart() != null) {
            payload.put("requestedStart", selector.timestampStart());
        }
        if (selector.timestampEnd() != null) {
            payload.put("requestedEnd", selector.timestampEnd());
        }
        if (selector.windowSeconds() != null) {
            payload.put("requestedWindowSeconds", selector.windowSeconds());
        }
        if (selector.gcId() != null) {
            payload.put("anchorGcId", selector.gcId());
        }
        payload.put("resolvedStartElapsedSeconds", gcTimeWindow.startSeconds());
        payload.put("resolvedEndElapsedSeconds", gcTimeWindow.endSeconds());
        payload.put("windowSpanMs", spanMs);
        payload.put("pauseEventCount", windowPauses.size());
        payload.put("fullGcCount", windowPauses.stream().filter(this::isFullGcPause).count());
        payload.put("evacuationFailurePauseCount", windowPauses.stream().filter(this::isEvacuationFailurePause).count());
        payload.put("allocationStallCount", windowAllocationStalls.size());
        payload.put("gcCycleCount", windowCycles.size());
        payload.put("failureSignalCount", windowFailureSignals.size());
        payload.put("concurrentMarkAbortCount", windowFailureSignals.stream().filter(this::isConcurrentAbortSignal).count());
        payload.put("fullCompactionAttemptCount", windowFailureSignals.stream().filter(this::isFullCompactionAttemptSignal).count());
        payload.put("totalPauseMs", totalPauseMs);
        payload.put("p95PauseMs", percentile(pauseDurations, 95.0d));
        payload.put("maxPauseMs", pauseDurations.isEmpty() ? 0.0d : pauseDurations.getLast());
        payload.put(
            "stopTheWorldOverheadPct",
            spanMs > 0.0d ? Math.min(100.0d, ((totalPauseMs + totalAllocationStallMs) / spanMs) * 100.0d) : 0.0d
        );
        payload.put(
            "topPauses",
            windowPauses.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> pause) -> -doubleValue(pause.get("pauseMs")))
                    .thenComparing(gcChronologyComparator()))
                .limit(6)
                .toList()
        );
        payload.put(
            "topPhaseSamples",
            windowPhases.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("durationMs")))
                    .thenComparing(gcChronologyComparator()))
                .limit(6)
                .toList()
        );
        payload.put(
            "failureSignals",
            windowFailureSignals.stream()
                .sorted(gcChronologyComparator())
                .limit(6)
                .toList()
        );

        boolean moreAvailable = windowPauses.size() > 6 || windowPhases.size() > 6 || windowFailureSignals.size() > 6;
        return derived(
            indexedContext,
            "gc-time-window-summary",
            "GC time-window summary",
            payload,
            "GC events within the selected elapsed-time window",
            moreAvailable
        );
    }

    private DiagnosticToolResult computeGcStreakSummary(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> failureSignals,
        Map<String, Object> summary
    ) {
        List<Map<String, Object>> fullGcCluster = bestGcEventCluster(pauses.stream().filter(this::isFullGcPause).toList());
        List<Map<String, Object>> evacuationFailureCluster = bestGcEventCluster(pauses.stream().filter(this::isEvacuationFailurePause).toList());
        List<Map<String, Object>> failureSignalCluster = bestGcEventCluster(failureSignals);
        List<Map<String, Object>> distressCluster = bestGcEventCluster(gcDistressEvents(pauses, failureSignals));

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (selector.streakKind() != null) {
            payload.put("requestedStreakKind", selector.streakKind());
        }
        payload.put("longestFullGcStreak", gcPauseClusterSummary(fullGcCluster, "pauseMs"));
        payload.put("longestEvacuationFailureStreak", gcPauseClusterSummary(evacuationFailureCluster, "pauseMs"));
        payload.put("densestFailureSignalCluster", gcSignalClusterSummary(failureSignalCluster));
        payload.put("densestDistressCluster", gcDistressClusterSummary(distressCluster));
        if (summary != null && !summary.isEmpty()) {
            payload.put("logWindowMs", summary.get("logWindowMs"));
        }

        Object selectedPayload = payload;
        String sliceId = "gc-streak-summary";
        String label = "GC streak summary";
        boolean moreAvailable = false;
        if (selector.streakKind() != null) {
            String normalizedStreakKind = normalizeStreakKind(selector.streakKind());
            selectedPayload = switch (normalizedStreakKind) {
                case "full-gc" -> payload.get("longestFullGcStreak");
                case "evacuation-failure" -> payload.get("longestEvacuationFailureStreak");
                case "failure", "failure-signal" -> payload.get("densestFailureSignalCluster");
                case "distress" -> payload.get("densestDistressCluster");
                default -> payload;
            };
            sliceId = "gc-streak-" + sanitizeId(normalizedStreakKind);
            label = switch (normalizedStreakKind) {
                case "full-gc" -> "GC full-GC streak summary";
                case "evacuation-failure" -> "GC evacuation-failure streak summary";
                case "failure", "failure-signal" -> "GC failure-signal streak summary";
                case "distress" -> "GC distress-cluster summary";
                default -> "GC streak summary";
            };
        }

        if (selectedPayload instanceof Map<?, ?> selectedMap && selectedMap.isEmpty()) {
            return unavailable(indexedContext, "No GC streak matched the requested selector.");
        }

        return derived(
            indexedContext,
            sliceId,
            label,
            selectedPayload,
            "GC streak and clustering analysis across parsed pauses and failure signals",
            moreAvailable
        );
    }

    private DiagnosticToolResult computeGcIncidentWindowSummary(
        IndexedArtifactDiagnosticContext indexedContext,
        ContextSelector selector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        String collector = stringValue(indexedContext.parsedArtifact().extractedData().get("collector"));
        GcIncidentWindow incidentWindow = selectGcIncidentWindow(
            selector.incident(),
            collector,
            pauses,
            gcCycles,
            allocationStalls,
            phaseSamples,
            failureSignals
        );
        if (incidentWindow == null || incidentWindow.window() == null) {
            return unavailable(indexedContext, "No collector-aware GC incident window was available for focused computation.");
        }

        List<Map<String, Object>> windowPauses = filterByGcWindow(pauses, incidentWindow.window());
        List<Map<String, Object>> windowCycles = filterByGcWindow(gcCycles, incidentWindow.window());
        List<Map<String, Object>> windowAllocationStalls = filterByGcWindow(allocationStalls, incidentWindow.window());
        List<Map<String, Object>> windowPhases = filterByGcWindow(phaseSamples, incidentWindow.window());
        List<Map<String, Object>> windowFailureSignals = filterByGcWindow(failureSignals, incidentWindow.window());

        if (windowPauses.isEmpty()
            && windowCycles.isEmpty()
            && windowAllocationStalls.isEmpty()
            && windowPhases.isEmpty()
            && windowFailureSignals.isEmpty()) {
            return unavailable(indexedContext, "No parsed GC activity fell inside the selected incident window.");
        }

        List<Double> pauseDurations = windowPauses.stream()
            .map(pause -> doubleValue(pause.get("pauseMs")))
            .filter(duration -> duration > 0.0d)
            .sorted()
            .toList();
        double totalPauseMs = pauseDurations.stream().mapToDouble(Double::doubleValue).sum();
        double totalAllocationStallMs = windowAllocationStalls.stream()
            .mapToDouble(stall -> doubleValue(stall.get("stallMs")))
            .sum();
        double spanMs = Math.max(0.0d, (incidentWindow.window().endSeconds() - incidentWindow.window().startSeconds()) * 1_000.0d);

        List<Map<String, Object>> occupancyEvents = new ArrayList<>();
        occupancyEvents.addAll(windowPauses);
        occupancyEvents.addAll(windowCycles);
        List<Double> occupancyRatios = occupancyEvents.stream()
            .map(event -> doubleValue(event.get("afterOccupancyRatio")))
            .filter(ratio -> ratio > 0.0d)
            .sorted()
            .toList();

        LinkedHashMap<String, Double> causeTotalPauseMs = gcCauseTotalPauseMs(windowPauses);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (selector.incident() != null && !selector.incident().isBlank()) {
            payload.put("requestedIncident", selector.incident());
        }
        if (!collector.isBlank()) {
            payload.put("collector", collector);
        }
        payload.put("windowKind", incidentWindow.label());
        payload.put("resolvedStartElapsedSeconds", incidentWindow.window().startSeconds());
        payload.put("resolvedEndElapsedSeconds", incidentWindow.window().endSeconds());
        payload.put("windowSpanMs", spanMs);
        payload.put("anchorEventCount", incidentWindow.anchorEvents().size());
        payload.put("anchorStartGcId", firstNonBlankGcId(incidentWindow.anchorEvents()));
        payload.put("anchorEndGcId", lastNonBlankGcId(incidentWindow.anchorEvents()));
        payload.put("pauseEventCount", windowPauses.size());
        payload.put("fullGcCount", windowPauses.stream().filter(this::isFullGcPause).count());
        payload.put("evacuationFailurePauseCount", windowPauses.stream().filter(this::isEvacuationFailurePause).count());
        payload.put("allocationStallCount", windowAllocationStalls.size());
        payload.put("gcCycleCount", windowCycles.size());
        payload.put("failureSignalCount", windowFailureSignals.size());
        payload.put("concurrentPhaseCount", windowPhases.stream().filter(this::isConcurrentPhase).count());
        payload.put("totalPauseMs", totalPauseMs);
        payload.put("p95PauseMs", percentile(pauseDurations, 95.0d));
        payload.put("maxPauseMs", pauseDurations.isEmpty() ? 0.0d : pauseDurations.getLast());
        payload.put(
            "stopTheWorldOverheadPct",
            spanMs > 0.0d ? Math.min(100.0d, ((totalPauseMs + totalAllocationStallMs) / spanMs) * 100.0d) : 0.0d
        );
        payload.put(
            "peakPostGcOccupancyRatio",
            occupancyRatios.isEmpty() ? 0.0d : occupancyRatios.getLast()
        );
        payload.put(
            "averagePostGcOccupancyRatio",
            occupancyRatios.isEmpty() ? 0.0d : occupancyRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d)
        );
        if (!causeTotalPauseMs.isEmpty()) {
            payload.put("causeCounts", eventCountByTextKey(windowPauses, "cause"));
            payload.put("causeTotalPauseMs", causeTotalPauseMs);
            payload.put("dominantPauseCauseByTotalPauseMs", dominantPauseCause(causeTotalPauseMs));
        }
        if (!windowFailureSignals.isEmpty()) {
            payload.put("signalTypeCounts", eventCountByTextKey(windowFailureSignals, "signalType"));
        }
        payload.put(
            "topPauses",
            windowPauses.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> pause) -> -doubleValue(pause.get("pauseMs")))
                    .thenComparing(gcChronologyComparator()))
                .limit(6)
                .toList()
        );
        if (!windowAllocationStalls.isEmpty()) {
            payload.put(
                "topAllocationStalls",
                windowAllocationStalls.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> stall) -> -doubleValue(stall.get("stallMs")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(6)
                    .toList()
            );
        }
        if (!windowPhases.isEmpty()) {
            payload.put(
                "topPhaseSamples",
                windowPhases.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> sample) -> -doubleValue(sample.get("durationMs")))
                        .thenComparing(gcChronologyComparator()))
                    .limit(6)
                    .toList()
            );
        }
        if (!windowFailureSignals.isEmpty()) {
            payload.put(
                "failureSignals",
                windowFailureSignals.stream()
                    .sorted(gcChronologyComparator())
                    .limit(6)
                    .toList()
            );
        }

        boolean moreAvailable = windowPauses.size() > 6
            || windowAllocationStalls.size() > 6
            || windowPhases.size() > 6
            || windowFailureSignals.size() > 6;
        return derived(
            indexedContext,
            "gc-incident-window-summary-" + sanitizeId(incidentWindow.requestKey()),
            incidentWindow.label() + " summary",
            payload,
            "Collector-aware GC incident-window summary derived from parsed pauses, cycles, stalls, phases, and failure signals",
            moreAvailable
        );
    }

    private GcIncidentWindow selectGcIncidentWindow(
        String incident,
        String collector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        String normalizedIncident = normalizeGcIncidentAlias(incident);
        if (normalizedIncident.isBlank()
            || "dominant".equals(normalizedIncident)
            || "dominant-pressure".equals(normalizedIncident)
            || "dominant-window".equals(normalizedIncident)
            || "pressure".equals(normalizedIncident)
            || "pressure-window".equals(normalizedIncident)) {
            return dominantGcIncidentWindow(collector, pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        }

        return switch (normalizedIncident) {
            case "failure", "failure-cluster", "distress", "distress-cluster" -> incidentWindow(
                "failure-cluster",
                "GC failure cluster",
                bestGcEventCluster(gcDistressEvents(pauses, failureSignals))
            );
            case "peak", "peak-occupancy", "occupancy", "peak-retention" -> incidentWindow(
                "peak-occupancy",
                "Peak post-GC occupancy window",
                singleEvent(peakOccupancyEvent(pauses, gcCycles))
            );
            case "tail", "latest", "latest-tail", "recent" -> incidentWindow(
                "tail",
                "Latest GC activity window",
                latestGcEvents(pauses, gcCycles, allocationStalls, phaseSamples, failureSignals)
            );
            case "longest-full-gc", "full-gc", "full-gc-window" -> incidentWindow(
                "longest-full-gc",
                "Longest full-GC window",
                singleEvent(maxEventByDouble(pauses, this::isFullGcPause, "pauseMs"))
            );
            case "allocation-stall", "stall", "zgc-stall" -> incidentWindow(
                "allocation-stall",
                "Longest allocation-stall window",
                singleEvent(maxEventByDouble(allocationStalls, event -> true, "stallMs"))
            );
            case "evacuation-failure" -> incidentWindow(
                "evacuation-failure",
                "First evacuation-failure window",
                singleEvent(firstEvent(pauses, this::isEvacuationFailurePause))
            );
            case "concurrent-abort" -> incidentWindow(
                "concurrent-abort",
                "First concurrent-abort window",
                singleEvent(firstEvent(failureSignals, this::isConcurrentAbortSignal))
            );
            case "full-compaction-attempt", "compaction-attempt" -> incidentWindow(
                "full-compaction-attempt",
                "First full-compaction-attempt window",
                singleEvent(firstEvent(failureSignals, this::isFullCompactionAttemptSignal))
            );
            case "to-space-distress", "to-space-exhausted" -> incidentWindow(
                "to-space-distress",
                "First to-space-distress window",
                singleEvent(firstEvent(failureSignals, this::isToSpaceDistressSignal))
            );
            case "concurrent-mode-failure", "cms-fallback" -> firstNonNull(
                incidentWindow(
                    "cms-fallback",
                    "Dominant CMS fallback window",
                    bestGcEventCluster(combineGcEvents(
                        pauses.stream().filter(this::isFullGcPause).toList(),
                        failureSignals.stream().filter(this::isConcurrentModeFailureSignal).toList(),
                        phaseSamples.stream().filter(this::isConcurrentPhase).toList()
                    ))
                ),
                incidentWindow(
                    "concurrent-mode-failure",
                    "First concurrent-mode-failure window",
                    singleEvent(firstEvent(failureSignals, this::isConcurrentModeFailureSignal))
                )
            );
            case "longest-concurrent-phase" -> incidentWindow(
                "longest-concurrent-phase",
                "Longest concurrent-phase window",
                singleEvent(maxEventByDouble(phaseSamples, this::isConcurrentPhase, "durationMs"))
            );
            default -> dominantGcIncidentWindow(collector, pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        };
    }

    private GcIncidentWindow dominantGcIncidentWindow(
        String collector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        String normalizedCollector = collector == null ? "" : collector.strip().toUpperCase(Locale.ROOT);
        return switch (normalizedCollector) {
            case "G1" -> firstNonNull(
                incidentWindow(
                    "dominant-pressure",
                    "Dominant G1 distress window",
                    bestGcEventCluster(combineGcEvents(
                        pauses.stream().filter(pause -> isFullGcPause(pause) || isEvacuationFailurePause(pause)).toList(),
                        failureSignals
                    ))
                ),
                incidentWindow("dominant-pressure", "Dominant G1 pressure window", bestGcEventCluster(pauses))
            );
            case "CMS" -> firstNonNull(
                incidentWindow(
                    "dominant-pressure",
                    "Dominant CMS fallback window",
                    bestGcEventCluster(combineGcEvents(
                        pauses.stream().filter(this::isFullGcPause).toList(),
                        failureSignals.stream().filter(this::isConcurrentModeFailureSignal).toList(),
                        phaseSamples.stream().filter(this::isConcurrentPhase).toList()
                    ))
                ),
                incidentWindow(
                    "dominant-pressure",
                    "Dominant CMS pressure window",
                    bestGcEventCluster(combineGcEvents(pauses, phaseSamples.stream().filter(this::isConcurrentPhase).toList()))
                )
            );
            case "SERIAL", "PARALLEL" -> firstNonNull(
                incidentWindow(
                    "dominant-pressure",
                    "Dominant full-GC pressure window",
                    bestGcEventCluster(pauses.stream().filter(this::isFullGcPause).toList())
                ),
                incidentWindow("dominant-pressure", "Dominant pause-pressure window", bestGcEventCluster(pauses))
            );
            case "ZGC" -> firstNonNull(
                incidentWindow(
                    "dominant-pressure",
                    "Dominant ZGC stall window",
                    bestGcEventCluster(allocationStalls)
                ),
                incidentWindow(
                    "dominant-pressure",
                    "Dominant ZGC pressure window",
                    bestGcEventCluster(combineGcEvents(allocationStalls, gcCycles, pauses))
                )
            );
            default -> firstNonNull(
                incidentWindow(
                    "dominant-pressure",
                    "Dominant GC distress window",
                    bestGcEventCluster(gcDistressEvents(pauses, failureSignals))
                ),
                incidentWindow(
                    "dominant-pressure",
                    "Dominant GC pressure window",
                    bestGcEventCluster(combineGcEvents(pauses, failureSignals, allocationStalls, phaseSamples))
                )
            );
        };
    }

    private DiagnosticToolResult computeJfr(IndexedArtifactDiagnosticContext indexedContext, String request) {
        JfrSelector selector = JfrSelector.fromQuery(request);
        Map<String, Object> summary = mapValue(indexedContext.parsedArtifact().extractedData().get("summary"));
        List<Map<String, Object>> observedEventTypes = listOfMaps(indexedContext.parsedArtifact().extractedData().get("observedEventTypes"));
        List<Map<String, Object>> declaredEventTypes = listOfMaps(indexedContext.parsedArtifact().extractedData().get("declaredEventTypes"));
        Map<String, Object> incidentWindowSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("incidentWindowSummary"));
        List<Map<String, Object>> incidentWindows = listOfMaps(indexedContext.parsedArtifact().extractedData().get("incidentWindows"));
        List<Map<String, Object>> chronologyHighlights = listOfMaps(indexedContext.parsedArtifact().extractedData().get("chronologyHighlights"));
        List<Map<String, Object>> timelineEvents = JfrDerivedContextSupport.timelineEvents(indexedContext.parsedArtifact());
        Map<String, Object> executionHotspotSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("executionHotspotSummary"));
        Map<String, Object> runtimeHotspotSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("runtimeHotspotSummary"));
        Map<String, Object> allocationHotspotSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("allocationHotspotSummary"));
        Map<String, Object> allocationFieldSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("allocationFieldSummary"));
        Map<String, Object> oldObjectFieldSummary = mapValue(indexedContext.parsedArtifact().extractedData().get("oldObjectFieldSummary"));
        if (request.contains("chronology")) {
            return computeJfrChronologySummary(indexedContext, chronologyHighlights, incidentWindowSummary);
        }
        if (selector.eventType() != null) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.eventTypeSlice(
                selector.eventType(),
                timelineEvents,
                incidentWindows,
                observedEventTypes,
                declaredEventTypes
            );
            if (!focusedSlice.isEmpty()) {
                return derived(
                    indexedContext,
                    "jfr-event-family-" + sanitizeId(selector.eventType()),
                    "JFR event-family computation view",
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + extractedData.observedEventTypes + extractedData.declaredEventTypes",
                    focusedSlice.moreAvailable()
                );
            }
        }
        if (selector.threadName() != null) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.threadSlice(
                selector.threadName(),
                timelineEvents,
                incidentWindows
            );
            if (!focusedSlice.isEmpty()) {
                return derived(
                    indexedContext,
                    "jfr-thread-" + sanitizeId(selector.threadName()),
                    "JFR thread-focused computation view",
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows",
                    focusedSlice.moreAvailable()
                );
            }
        }
        if (selector.hotspotKey() != null) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.hotspotSlice(
                selector.hotspotKey(),
                timelineEvents,
                incidentWindows,
                executionHotspotSummary,
                runtimeHotspotSummary,
                allocationHotspotSummary
            );
            if (!focusedSlice.isEmpty()) {
                return derived(
                    indexedContext,
                    "jfr-hotspot-" + sanitizeId(selector.hotspotKey()),
                    "JFR hotspot computation view",
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + hotspot summaries",
                    focusedSlice.moreAvailable()
                );
            }
        }
        if (selector.allocationClass() != null) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.allocationSlice(
                selector.allocationClass(),
                timelineEvents,
                incidentWindows,
                allocationFieldSummary
            );
            if (!focusedSlice.isEmpty()) {
                return derived(
                    indexedContext,
                    "jfr-allocation-class-" + sanitizeId(selector.allocationClass()),
                    "JFR allocation-path computation view",
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.allocationFieldSummary + extractedData.incidentWindows",
                    focusedSlice.moreAvailable()
                );
            }
        }
        if (selector.oldObjectFocus() != null) {
            JfrDerivedContextSupport.FocusedSlice focusedSlice = JfrDerivedContextSupport.oldObjectSlice(
                selector.oldObjectFocus(),
                timelineEvents,
                incidentWindows,
                oldObjectFieldSummary
            );
            if (!focusedSlice.isEmpty()) {
                return derived(
                    indexedContext,
                    "jfr-old-object-" + sanitizeId(selector.oldObjectFocus()),
                    "JFR retained-object computation view",
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.oldObjectFieldSummary + extractedData.incidentWindows",
                    focusedSlice.moreAvailable()
                );
            }
        }
        if (selector.incident() != null || request.contains("incident")) {
            return computeJfrIncidentSummary(indexedContext, request, selector, incidentWindowSummary, incidentWindows, chronologyHighlights);
        }
        if (selector.timeWindowStart() != null || selector.timeWindowEnd() != null || request.contains("time-window")) {
            return computeJfrTimeWindowSummary(indexedContext, selector, summary, incidentWindowSummary, incidentWindows, chronologyHighlights, timelineEvents);
        }
        if (request.contains("allocation")) {
            return derived(
                indexedContext,
                "jfr-allocation-summary",
                "JFR allocation computation view",
                allocationFieldSummary,
                "extractedData.allocationFieldSummary",
                false
            );
        }
        if (request.contains("old") || request.contains("retained")) {
            return derived(
                indexedContext,
                "jfr-old-object-summary",
                "JFR old-object computation view",
                oldObjectFieldSummary,
                "extractedData.oldObjectFieldSummary",
                false
            );
        }
        if (request.contains("runtime") || request.contains("wait")) {
            return derived(
                indexedContext,
                "jfr-runtime-hotspots",
                "JFR runtime hotspot computation view",
                runtimeHotspotSummary,
                "extractedData.runtimeHotspotSummary",
                false
            );
        }
        if (request.contains("execution") || request.contains("cpu") || request.contains("hotspot")) {
            return derived(
                indexedContext,
                "jfr-execution-hotspots",
                "JFR execution hotspot computation view",
                executionHotspotSummary,
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
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("observedEventTypes", observedEventTypes);
        payload.put("declaredEventTypes", declaredEventTypes);
        payload.put("executionHotspotSummary", indexedContext.parsedArtifact().extractedData().get("executionHotspotSummary"));
        payload.put("runtimeHotspotSummary", indexedContext.parsedArtifact().extractedData().get("runtimeHotspotSummary"));
        if (!incidentWindowSummary.isEmpty()) {
            payload.put("incidentWindowSummary", incidentWindowSummary);
        }
        if (!chronologyHighlights.isEmpty()) {
            payload.put("chronologyHighlights", chronologyHighlights);
        }
        return derived(
            indexedContext,
            "jfr-focus-summary",
            "JFR focused summary",
            payload,
            "extractedData.observedEventTypes + extractedData.declaredEventTypes + hotspot summaries + incident window context",
            false
        );
    }

    private DiagnosticToolResult computeJfrChronologySummary(
        IndexedArtifactDiagnosticContext indexedContext,
        List<Map<String, Object>> chronologyHighlights,
        Map<String, Object> incidentWindowSummary
    ) {
        if (chronologyHighlights.isEmpty() && incidentWindowSummary.isEmpty()) {
            return unavailable(indexedContext, "No compact JFR chronology summary was available for focused computation.");
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("chronologyHighlights", chronologyHighlights);
        if (!incidentWindowSummary.isEmpty()) {
            payload.put("incidentWindowSummary", incidentWindowSummary);
        }
        return derived(
            indexedContext,
            "jfr-chronology-summary",
            "JFR chronology summary",
            payload,
            "extractedData.chronologyHighlights + extractedData.incidentWindowSummary",
            false
        );
    }

    private DiagnosticToolResult computeJfrIncidentSummary(
        IndexedArtifactDiagnosticContext indexedContext,
        String request,
        JfrSelector selector,
        Map<String, Object> incidentWindowSummary,
        List<Map<String, Object>> incidentWindows,
        List<Map<String, Object>> chronologyHighlights
    ) {
        if (incidentWindowSummary.isEmpty() && incidentWindows.isEmpty()) {
            return unavailable(indexedContext, "No JFR incident windows were available for focused computation.");
        }

        String focus = normalizedJfrIncidentFocus(request, selector);
        if (focus.isBlank()) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("incidentWindowSummary", incidentWindowSummary);
            if (!chronologyHighlights.isEmpty()) {
                payload.put("chronologyHighlights", chronologyHighlights);
            }
            return derived(
                indexedContext,
                "jfr-incident-window-summary",
                "JFR incident-window summary",
                payload,
                "extractedData.incidentWindowSummary + extractedData.chronologyHighlights",
                false
            );
        }

        Map<String, Object> matchedWindow = incidentWindows.stream()
            .filter(window -> matchesJfrIncidentWindow(window, focus))
            .findFirst()
            .orElse(Map.of());
        if (matchedWindow.isEmpty()) {
            return unavailable(indexedContext, "No JFR incident window matched the requested focus.");
        }

        String sliceId = "jfr-" + focus + "-incident-summary";
        String label = switch (focus) {
            case "runtime" -> "JFR runtime incident summary";
            case "allocation" -> "JFR allocation incident summary";
            case "retention" -> "JFR retained-object incident summary";
            default -> "JFR incident summary";
        };
        return derived(
            indexedContext,
            sliceId,
            label,
            matchedWindow,
            "extractedData.incidentWindows",
            false
        );
    }

    private DiagnosticToolResult computeJfrTimeWindowSummary(
        IndexedArtifactDiagnosticContext indexedContext,
        JfrSelector selector,
        Map<String, Object> summary,
        Map<String, Object> incidentWindowSummary,
        List<Map<String, Object>> incidentWindows,
        List<Map<String, Object>> chronologyHighlights,
        List<Map<String, Object>> timelineEvents
    ) {
        Instant availableStart = instantValue(summary.get("startTime"));
        Instant availableEnd = instantValue(summary.get("endTime"));
        Instant requestedStart = resolveJfrTimeToken(selector.timeWindowStart(), availableStart);
        Instant requestedEnd = resolveJfrTimeToken(selector.timeWindowEnd(), availableStart);

        if ((requestedStart == null || requestedEnd == null) && selector.timeWindowStart() == null && selector.timeWindowEnd() == null) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("startTime", summary.get("startTime"));
            payload.put("endTime", summary.get("endTime"));
            payload.put("durationMs", summary.get("durationMs"));
            if (!incidentWindowSummary.isEmpty()) {
                payload.put("incidentWindowSummary", incidentWindowSummary);
            }
            if (!chronologyHighlights.isEmpty()) {
                payload.put("chronologyHighlights", chronologyHighlights);
            }
            return derived(
                indexedContext,
                "jfr-time-window-summary",
                "JFR time-window summary",
                payload,
                "extractedData.summary + extractedData.incidentWindowSummary + extractedData.chronologyHighlights",
                false
            );
        }

        if (requestedStart == null) {
            requestedStart = availableStart;
        }
        if (requestedEnd == null) {
            requestedEnd = availableEnd;
        }
        if (requestedStart == null || requestedEnd == null) {
            return unavailable(indexedContext, "No JFR recording bounds were available to resolve the requested time window.");
        }
        if (requestedEnd.isBefore(requestedStart)) {
            Instant swap = requestedStart;
            requestedStart = requestedEnd;
            requestedEnd = swap;
        }
        final Instant finalRequestedStart = requestedStart;
        final Instant finalRequestedEnd = requestedEnd;

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
                return derived(
                    indexedContext,
                    "jfr-time-window-" + sanitizeId(String.valueOf(startMs)) + "-" + sanitizeId(String.valueOf(endMs)),
                    String.format(Locale.ROOT, "JFR time-window summary +%.3fs to +%.3fs", startMs / 1000.0d, endMs / 1000.0d),
                    focusedSlice.payload(),
                    "extractedData.timelineEvents + extractedData.incidentWindows + extractedData.chronologyHighlights",
                    focusedSlice.moreAvailable()
                );
            }
        }

        List<Map<String, Object>> overlappingIncidentWindows = incidentWindows.stream()
            .filter(window -> jfrWindowOverlaps(window, finalRequestedStart, finalRequestedEnd))
            .toList();
        List<Map<String, Object>> overlappingHighlights = chronologyHighlights.stream()
            .filter(highlight -> jfrHighlightWithinWindow(highlight, finalRequestedStart, finalRequestedEnd))
            .toList();

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
            payload.put("note", "No compact incident window or chronology highlight overlapped the requested JFR time range.");
        }

        long startMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedStart).toMillis()) : 0L;
        long endMs = availableStart != null ? Math.max(0L, java.time.Duration.between(availableStart, finalRequestedEnd).toMillis()) : startMs;
        return derived(
            indexedContext,
            "jfr-time-window-" + sanitizeId(String.valueOf(startMs)) + "-" + sanitizeId(String.valueOf(endMs)),
            String.format(Locale.ROOT, "JFR time-window summary +%.3fs to +%.3fs", startMs / 1000.0d, endMs / 1000.0d),
            payload,
            "extractedData.incidentWindows + extractedData.chronologyHighlights",
            false
        );
    }

    private String normalizedJfrIncidentFocus(String request, JfrSelector selector) {
        String incident = selector != null ? selector.incident() : null;
        String normalized = normalizeJfrIncidentAlias(incident);
        if (!normalized.isBlank()) {
            if (normalized.startsWith("runtime")) {
                return "runtime";
            }
            if (normalized.startsWith("allocation")) {
                return "allocation";
            }
            if (normalized.startsWith("retained")
                || normalized.startsWith("retention")
                || normalized.startsWith("old-object")
                || normalized.startsWith("oldobject")) {
                return "retention";
            }
        }

        String normalizedRequest = request == null ? "" : request;
        if (normalizedRequest.contains("runtime-incident")) {
            return "runtime";
        }
        if (normalizedRequest.contains("allocation-incident")) {
            return "allocation";
        }
        if (normalizedRequest.contains("retention-incident")
            || normalizedRequest.contains("retained-incident")
            || normalizedRequest.contains("old-object-incident")) {
            return "retention";
        }
        return "";
    }

    private boolean matchesJfrIncidentWindow(Map<String, Object> window, String focus) {
        String normalizedFocus = normalizeJfrIncidentAlias(focus);
        return normalizeJfrIncidentAlias(stringValue(window.get("focus"))).equals(normalizedFocus)
            || normalizeJfrIncidentAlias(stringValue(window.get("windowId"))).startsWith(normalizedFocus);
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

    private String normalizeJfrIncidentAlias(String incident) {
        if (incident == null || incident.isBlank()) {
            return "";
        }
        return incident.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
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

    @SafeVarargs
    private final List<Map<String, Object>> combineGcEvents(List<Map<String, Object>>... eventGroups) {
        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (List<Map<String, Object>> eventGroup : eventGroups) {
            if (eventGroup == null) {
                continue;
            }
            for (Map<String, Object> event : eventGroup) {
                if (event == null || event.isEmpty()) {
                    continue;
                }
                String uniqueKey = longValue(event.get("lineNumber"))
                    + ":"
                    + stringValue(event.get("gcId"))
                    + ":"
                    + stringValue(event.get("event"))
                    + ":"
                    + stringValue(event.get("signalType"))
                    + ":"
                    + stringValue(event.get("phase"));
                unique.putIfAbsent(uniqueKey, event);
            }
        }
        return List.copyOf(unique.values());
    }

    private List<Map<String, Object>> singleEvent(Map<String, Object> event) {
        return event == null || event.isEmpty() ? List.of() : List.of(event);
    }

    private Map<String, Object> firstEvent(List<Map<String, Object>> events, java.util.function.Predicate<Map<String, Object>> predicate) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (Map<String, Object> event : events.stream().sorted(gcChronologyComparator()).toList()) {
            if (predicate.test(event)) {
                return event;
            }
        }
        return null;
    }

    private Map<String, Object> maxEventByDouble(
        List<Map<String, Object>> events,
        java.util.function.Predicate<Map<String, Object>> predicate,
        String metricKey
    ) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        Map<String, Object> selected = null;
        double bestMetric = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> event : events) {
            if (!predicate.test(event)) {
                continue;
            }
            double metric = doubleValue(event.get(metricKey));
            if (selected == null || metric > bestMetric) {
                selected = event;
                bestMetric = metric;
            }
        }
        return selected;
    }

    private Map<String, Object> peakOccupancyEvent(List<Map<String, Object>> pauses, List<Map<String, Object>> gcCycles) {
        Map<String, Object> selected = null;
        double bestRatio = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> event : combineGcEvents(pauses, gcCycles)) {
            double ratio = doubleValue(event.get("afterOccupancyRatio"));
            if (selected == null || ratio > bestRatio) {
                selected = event;
                bestRatio = ratio;
            }
        }
        return selected;
    }

    private List<Map<String, Object>> latestGcEvents(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> timelineEvents = gcTimelineEvents(pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        if (timelineEvents.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, timelineEvents.size() - 6);
        return List.copyOf(timelineEvents.subList(fromIndex, timelineEvents.size()));
    }

    private GcIncidentWindow incidentWindow(
        String requestKey,
        String label,
        List<Map<String, Object>> anchorEvents
    ) {
        if (anchorEvents == null || anchorEvents.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> orderedAnchorEvents = anchorEvents.stream()
            .filter(this::hasElapsedSeconds)
            .sorted(gcChronologyComparator())
            .toList();
        if (orderedAnchorEvents.isEmpty()) {
            return null;
        }

        double startSeconds = orderedAnchorEvents.stream()
            .mapToDouble(event -> doubleValue(event.get("elapsedSeconds")))
            .min()
            .orElse(0.0d);
        double endSeconds = orderedAnchorEvents.stream()
            .mapToDouble(event -> doubleValue(event.get("elapsedSeconds")))
            .max()
            .orElse(startSeconds);
        double paddingSeconds = orderedAnchorEvents.size() == 1
            ? Math.max(0.5d, Math.max(doubleValue(orderedAnchorEvents.getFirst().get("pauseMs")), doubleValue(orderedAnchorEvents.getFirst().get("durationMs"))) / 1_000.0d)
            : 0.5d;

        return new GcIncidentWindow(
            requestKey,
            label,
            new GcTimeWindow(Math.max(0.0d, startSeconds - paddingSeconds), endSeconds + paddingSeconds),
            orderedAnchorEvents
        );
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private String normalizeGcIncidentAlias(String incident) {
        if (incident == null || incident.isBlank()) {
            return "";
        }
        return incident.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private LinkedHashMap<String, Double> gcCauseTotalPauseMs(List<Map<String, Object>> pauses) {
        LinkedHashMap<String, Double> totals = new LinkedHashMap<>();
        for (Map<String, Object> pause : pauses) {
            String cause = stringValue(pause.get("cause"));
            if (!cause.isBlank()) {
                totals.merge(cause, doubleValue(pause.get("pauseMs")), Double::sum);
            }
        }
        return totals;
    }

    private String dominantPauseCause(Map<String, Double> causeTotals) {
        String dominantCause = "";
        double dominantPauseMs = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : causeTotals.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > dominantPauseMs) {
                dominantCause = entry.getKey();
                dominantPauseMs = entry.getValue();
            }
        }
        return dominantCause;
    }

    private String firstNonBlankGcId(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) {
            String gcId = stringValue(event.get("gcId"));
            if (!gcId.isBlank()) {
                return gcId;
            }
        }
        return "";
    }

    private String lastNonBlankGcId(List<Map<String, Object>> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            String gcId = stringValue(events.get(index).get("gcId"));
            if (!gcId.isBlank()) {
                return gcId;
            }
        }
        return "";
    }

    private DiagnosticToolResult derived(
        IndexedArtifactDiagnosticContext indexedContext,
        String sliceId,
        String label,
        Object payload,
        String traceability,
        boolean moreAvailable
    ) {
        String rendered = DiagnosticContextRenderSupport.renderFullValue(payload);
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
            "Focused computation",
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

    private List<Map<String, Object>> filterByGcId(List<Map<String, Object>> events, String gcId) {
        return events.stream()
            .filter(event -> matchesGcId(event, gcId))
            .sorted(gcChronologyComparator())
            .toList();
    }

    private String sanitizeId(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private GcTimeWindow resolveGcTimeWindow(
        ContextSelector selector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> timelineEvents = gcTimelineEvents(pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        if (timelineEvents.isEmpty()) {
            return null;
        }

        Map<String, Object> anchorEvent = selector.gcId() != null ? selectAnchorEventForGcId(timelineEvents, selector.gcId()) : null;
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
        return new GcTimeWindow(startSeconds, endSeconds);
    }

    private List<Map<String, Object>> gcTimelineEvents(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> timelineEvents = new ArrayList<>();
        timelineEvents.addAll(pauses);
        timelineEvents.addAll(gcCycles);
        timelineEvents.addAll(allocationStalls);
        timelineEvents.addAll(phaseSamples);
        timelineEvents.addAll(failureSignals);
        timelineEvents.sort(gcChronologyComparator());
        return List.copyOf(timelineEvents);
    }

    private List<Map<String, Object>> filterByGcWindow(List<Map<String, Object>> events, GcTimeWindow gcTimeWindow) {
        if (events == null || events.isEmpty() || gcTimeWindow == null) {
            return List.of();
        }
        return events.stream()
            .filter(this::hasElapsedSeconds)
            .filter(event -> {
                double elapsedSeconds = doubleValue(event.get("elapsedSeconds"));
                return elapsedSeconds >= gcTimeWindow.startSeconds() && elapsedSeconds <= gcTimeWindow.endSeconds();
            })
            .sorted(gcChronologyComparator())
            .toList();
    }

    private boolean hasElapsedSeconds(Map<String, Object> event) {
        return event != null && event.containsKey("elapsedSeconds");
    }

    private Double resolveGcTimeToken(List<Map<String, Object>> events, String token, boolean preferFirstMatch) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Double explicitSeconds = parseSecondsToken(token);
        if (explicitSeconds != null) {
            return explicitSeconds;
        }

        List<Map<String, Object>> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(gcChronologyComparator());
        if (!preferFirstMatch) {
            Collections.reverse(orderedEvents);
        }
        for (Map<String, Object> event : orderedEvents) {
            if (matchesTimeToken(event, token) && hasElapsedSeconds(event)) {
                return doubleValue(event.get("elapsedSeconds"));
            }
        }
        return null;
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

    private Map<String, Object> selectAnchorEventForGcId(List<Map<String, Object>> events, String gcId) {
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
        distressEvents.sort(gcChronologyComparator());
        return List.copyOf(distressEvents);
    }

    private List<Map<String, Object>> bestGcEventCluster(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(gcChronologyComparator());
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
            weight += Math.max(1.0d, Math.max(doubleValue(event.get("pauseMs")), doubleValue(event.get("durationMs"))));
        }
        return weight;
    }

    private long gcClusterSpanLines(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long startLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).min().orElse(0L);
        long endLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).max().orElse(0L);
        return endLine >= startLine ? endLine - startLine : Long.MAX_VALUE;
    }

    private Map<String, Object> gcPauseClusterSummary(List<Map<String, Object>> cluster, String durationKey) {
        if (cluster == null || cluster.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("streakEventCount", cluster.size());
        payload.put("startGcId", cluster.getFirst().get("gcId"));
        payload.put("endGcId", cluster.getLast().get("gcId"));
        payload.put("startLine", cluster.getFirst().get("lineNumber"));
        payload.put("endLine", cluster.getLast().get("lineNumber"));
        payload.put("startElapsedSeconds", cluster.getFirst().get("elapsedSeconds"));
        payload.put("endElapsedSeconds", cluster.getLast().get("elapsedSeconds"));
        payload.put("totalPauseMs", cluster.stream().mapToDouble(event -> doubleValue(event.get(durationKey))).sum());
        payload.put("maxPauseMs", cluster.stream().mapToDouble(event -> doubleValue(event.get(durationKey))).max().orElse(0.0d));
        payload.put("causeCounts", eventCountByTextKey(cluster, "cause"));
        payload.put("events", cluster.stream().limit(8).toList());
        return Collections.unmodifiableMap(payload);
    }

    private Map<String, Object> gcSignalClusterSummary(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("signalCount", cluster.size());
        payload.put("startGcId", cluster.getFirst().get("gcId"));
        payload.put("endGcId", cluster.getLast().get("gcId"));
        payload.put("startLine", cluster.getFirst().get("lineNumber"));
        payload.put("endLine", cluster.getLast().get("lineNumber"));
        payload.put("startElapsedSeconds", cluster.getFirst().get("elapsedSeconds"));
        payload.put("endElapsedSeconds", cluster.getLast().get("elapsedSeconds"));
        payload.put("signalTypeCounts", eventCountByTextKey(cluster, "signalType"));
        payload.put("signals", cluster.stream().limit(10).toList());
        return Collections.unmodifiableMap(payload);
    }

    private Map<String, Object> gcDistressClusterSummary(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("distressEventCount", cluster.size());
        payload.put("startGcId", cluster.getFirst().get("gcId"));
        payload.put("endGcId", cluster.getLast().get("gcId"));
        payload.put("startLine", cluster.getFirst().get("lineNumber"));
        payload.put("endLine", cluster.getLast().get("lineNumber"));
        payload.put("startElapsedSeconds", cluster.getFirst().get("elapsedSeconds"));
        payload.put("endElapsedSeconds", cluster.getLast().get("elapsedSeconds"));
        payload.put("fullGcCount", cluster.stream().filter(this::isFullGcPause).count());
        payload.put("evacuationFailurePauseCount", cluster.stream().filter(this::isEvacuationFailurePause).count());
        payload.put("failureSignalCount", cluster.stream().filter(event -> stringValue(event.get("signalType")).length() > 0).count());
        payload.put("events", cluster.stream().limit(10).toList());
        return Collections.unmodifiableMap(payload);
    }

    private Map<String, Long> eventCountByTextKey(List<Map<String, Object>> events, String key) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String value = stringValue(event.get(key));
            if (!value.isBlank()) {
                counts.merge(value, 1L, Long::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private boolean isFullGcPause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("full");
    }

    private boolean isEvacuationFailurePause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("evacuation failure");
    }

    private boolean isConcurrentPhase(Map<String, Object> sample) {
        return "CONCURRENT".equalsIgnoreCase(stringValue(sample.get("phaseKind")));
    }

    private boolean isConcurrentAbortSignal(Map<String, Object> event) {
        return "CONCURRENT_ABORT".equalsIgnoreCase(stringValue(event.get("signalType")));
    }

    private boolean isFullCompactionAttemptSignal(Map<String, Object> event) {
        return "FULL_COMPACTION_ATTEMPT".equalsIgnoreCase(stringValue(event.get("signalType")));
    }

    private boolean isToSpaceDistressSignal(Map<String, Object> event) {
        return "TO_SPACE_EXHAUSTED".equalsIgnoreCase(stringValue(event.get("signalType")));
    }

    private boolean isConcurrentModeFailureSignal(Map<String, Object> event) {
        return "CONCURRENT_MODE_FAILURE".equalsIgnoreCase(stringValue(event.get("signalType")));
    }

    private String normalizeStreakKind(String streakKind) {
        if (streakKind == null || streakKind.isBlank()) {
            return "";
        }
        return streakKind.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private Comparator<Map<String, Object>> gcChronologyComparator() {
        return Comparator
            .comparingInt((Map<String, Object> event) -> longValue(event.get("lineNumber")) > 0L ? 0 : 1)
            .thenComparingLong(event -> longValue(event.get("lineNumber")))
            .thenComparingDouble(event -> doubleValue(event.get("elapsedSeconds")));
    }

    private record GcIncidentWindow(String requestKey, String label, GcTimeWindow window, List<Map<String, Object>> anchorEvents) {
    }

    private record GcTimeWindow(double startSeconds, double endSeconds) {
    }
}
