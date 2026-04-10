package com.javaassistant.testsupport;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Deterministic scenario-specific chat stub used by ScenarioLab reference bundles.
 */
public final class ScenarioLabChatModel implements ChatModel {

    private final String scenarioId;

    public ScenarioLabChatModel(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = StubChatModelSupport.renderPrompt(chatRequest);
        String response = switch (scenarioId) {
            case "control-healthy-g1-baseline" -> """
                Summary:
                This GC log looks like a healthy baseline for the captured window, with short young-generation pauses and comfortable heap headroom after collection.
                Key metrics:
                - fullGcCount: 0
                - maxPauseMs: 15.4
                - peakPostGcOccupancyPct: 14
                - collector: G1
                Likely issues:
                - No clear GC saturation, full-GC escalation, or metaspace pressure is visible in this capture.
                - Nothing in this log alone suggests that GC behavior is the source of an application incident.
                Recommended actions:
                1. Keep this log as a healthy baseline so later incident captures can be compared against it.
                2. If users still saw symptoms, look at thread dumps, JFR, or downstream dependency timing instead of tuning GC from this snapshot alone.
                """;
            case "control-healthy-jfr-baseline" -> """
                Summary:
                This JFR recording looks like a healthy runtime baseline, with light routine activity and no dominant hotspot, pause pattern, or allocation signal.
                Key metrics:
                - executionSampleCount: 3
                - threadParkSignals: minimal
                - gcPauseSignals: none
                - allocationPressure: low
                Likely issues:
                - No clear runtime bottleneck stands out in this recording.
                - The capture is better suited as a baseline than as evidence of an active JVM incident.
                Recommended actions:
                1. Keep this recording as a comparison baseline for future incident captures.
                2. If symptoms were present outside this window, capture a longer recording or line it up with a matching thread dump and GC log from the affected interval.
                """;
            case "control-healthy-thread-dump-idle" -> """
                Summary:
                This thread dump looks like an idle or low-stress baseline, with workers waiting normally for work and no deadlock or blocked-thread hotspot.
                Key metrics:
                - deadlockDetected: false
                - blockedThreadCount: 0
                - runnableWorkerPressure: low
                - idlePoolState: present
                Likely issues:
                - No thread-level stall or lock-contention incident is visible in this snapshot.
                - The dump is more useful as a healthy reference point than as proof of an active JVM problem.
                Recommended actions:
                1. Keep this dump as a baseline for comparing future stalls or blocked-thread incidents.
                2. If the application was slow, capture another dump during the symptom window because this snapshot does not show an active thread problem.
                """;
            case "control-healthy-native-memory-baseline" -> """
                Summary:
                These native-memory artifacts look like a healthy baseline, with modest committed footprint and no sign of resident-memory concentration or runaway native growth.
                Key metrics:
                - totalCommittedKb: 146432
                - metaspaceCommittedKb: 7168
                - threadCount: 14
                - rssKb: 98816
                Likely issues:
                - No obvious native leak, metaspace surge, or resident-memory hotspot is visible in this capture.
                - The NMT and process-map snapshots are better used as a baseline than as evidence of an active native-memory incident.
                Recommended actions:
                1. Keep these snapshots as a baseline so later NMT or pmap captures can be compared against a known healthy footprint.
                2. If users still saw pressure outside this window, capture another NMT and pmap pair closer to the symptom interval rather than changing native-memory settings from this snapshot alone.
                """;
            case "ambiguity-low-signal-single-snapshot" -> """
                Summary:
                This single NMT snapshot does not show a clearly saturated category, but it also does not provide enough time context to support a confident native-memory diagnosis.
                Key metrics:
                - totalCommittedKb: 173824
                - metaspaceCommittedKb: 9216
                - classCount: 18240
                - threadCount: 22
                Likely issues:
                - No single native-memory category is obviously distressed in this snapshot by itself.
                - Without a second snapshot or diff, this view cannot tell you whether any category is stable, growing, or leaking.
                Recommended actions:
                1. Capture a second NMT snapshot or an NMT diff from the symptom window so growth can be measured rather than guessed.
                2. Pair the next NMT capture with a GC log or pmap snapshot if native or metaspace pressure is still suspected.
                """;
            case "ambiguity-time-skewed-or-conflicting-correlation" -> """
                Summary:
                The artifacts do not line up closely enough in time to support one confident incident-level conclusion, so this analysis should stay cautious.
                Key metrics:
                - crossArtifactTiming: conflicting
                - gcAndJfrAlignment: no overlap
                - threadDumpCaptureWindow: different from JFR and GC
                - confidence: limited
                Likely issues:
                - The artifacts may represent different runtime windows rather than one shared JVM incident.
                - Any stronger diagnosis would be uncertain because the timing context is inconsistent across the bundle.
                Recommended actions:
                1. Re-capture JFR, GC logs, thread dumps, and native-memory snapshots from the same symptom window so the evidence can be correlated reliably.
                2. Avoid making a single root-cause claim from this bundle alone because the remaining uncertainty is about timing, not just missing detail.
                """;
            case "nmt-internal-or-arena-growth" -> """
                Summary:
                This NMT data shows native-memory growth concentrated in Internal, Unknown, and Arena Chunk categories rather than in class metadata, thread stacks, or heap-related areas.
                Key metrics:
                - totalCommittedDeltaKb: +47104
                - internalLikeCommittedDeltaKb: +43008
                - dominantCategory: Internal +24576
                - supportingCategories: Unknown +10240, Arena Chunk +8192
                Likely issues:
                - The native-memory change is centered in internal or arena-backed categories, which fits native allocator or off-heap pressure more than Java heap pressure.
                - NMT alone does not identify the exact subsystem, so the remaining work is to isolate which native component is driving the growth.
                Recommended actions:
                1. Capture another NMT diff or detail.diff to confirm whether Internal, Unknown, and Arena Chunk continue to grow.
                2. Correlate the same interval with pmap anonymous growth and any off-heap buffer, JNI, native-library, or allocator-heavy subsystems that were active during the increase.
                """;
            case "reserved-vs-committed-native-mismatch" -> """
                Summary:
                These diagnostics show a large reserved address-space footprint that is not matched by committed or resident memory, so the dominant signal is reservation mismatch rather than active native RAM growth.
                Key metrics:
                - reservedGap: large
                - committedGrowth: limited
                - residentGrowth: limited
                - dominantInterpretation: reservation-heavy footprint
                Likely issues:
                - The mapped or reserved footprint overstates active native consumption, so these artifacts do not support calling this a resident-memory leak by themselves.
                - The important next question is whether committed or resident memory starts to follow the reservations over time.
                Recommended actions:
                1. Compare pmap RSS with NMT committed categories and focus on categories whose committed or resident memory is actually growing.
                2. Capture a later snapshot or diff to see whether the reservations stay mostly non-resident or start converting into committed or resident memory.
                """;
            case "active-native-growth-or-off-heap-pressure" -> """
                Summary:
                These diagnostics represent real native-memory growth rather than reservation-only expansion, with committed native memory rising and anonymous resident mappings materially larger.
                Key metrics:
                - committedNativeGrowthKb: +65536
                - dominantNativeDelta: Internal +61440
                - anonymousResidentKb: 491520
                - totalResidentKb: 495616
                Likely issues:
                - Native or off-heap allocations are becoming committed and resident, so the process is under active native-memory pressure rather than just mapping additional virtual space.
                - The remaining work is to identify which off-heap subsystem or allocator path is driving the resident growth.
                Recommended actions:
                1. Correlate the interval with pmap RSS growth, NMT category deltas, and any off-heap buffer, JNI, native-library, or allocator-heavy activity.
                2. Capture another NMT diff and pmap snapshot to confirm whether the same committed categories and resident anonymous mappings continue to grow together.
                """;
            case "sequence-native-memory-growth" -> """
                Summary:
                Across these snapshots, the native-memory footprint is growing progressively rather than in a one-off jump, with each step larger than the last.
                Key metrics:
                - baselineToMidShift: positive
                - midToCurrentShift: positive
                - firstToLastTrend: worsening
                - progression: worsening across all snapshots
                Likely issues:
                - The repeating upward trend fits sustained native or off-heap pressure more than a reservation-only change or a one-time transient spike.
                - The remaining question is which native subsystem, category, or mapping continues to add footprint at each step.
                Recommended actions:
                1. Compare the middle and current snapshots to isolate which categories or mappings keep growing at each step, not just from first to last.
                2. Capture another later snapshot and correlate it with off-heap buffers, JNI activity, native libraries, or other native-memory evidence if the process is still live.
                """;
            case "sequence-gc-pressure-worsening" -> """
                Summary:
                Across these GC logs, pressure worsens step by step from routine young collections into G1 evacuation distress, repeated full compaction, and almost no post-GC headroom.
                Key metrics:
                - fullGcCountTrend: 0 -> 2 -> 3
                - maxPauseMsTrend: 30 -> 318 -> 681
                - postGcHeadroomTrend: comfortable -> low -> nearly exhausted
                - g1DistressSignals: absent -> present -> worsening
                Likely issues:
                - The sequence fits a real worsening heap-pressure incident rather than normal collector noise.
                - The important next question is what changed between the middle and current windows to push G1 from early distress into sustained compaction pressure.
                Recommended actions:
                1. Compare the middle and current logs first to isolate what changed in allocation pressure, retained live set, or recovery behavior at the point the incident became severe.
                2. Capture a heap histogram, JFR recording, or workload-change evidence from the same worsening interval before making GC tuning changes in isolation.
                """;
            case "gc-g1-evacuation-failure-to-space-exhaustion" -> """
                Summary:
                This GC log shows classic G1 evacuation distress, with evacuation failure, to-space exhaustion, full-compaction attempts, and repeated full GCs that recover almost no heap headroom.
                Key metrics:
                - evacuationFailurePauseCount: 2
                - toSpaceExhaustedCount: 2
                - fullCompactionAttemptCount: 2
                - maxFullGcPauseMs: 681.585
                Likely issues:
                - G1 has lost enough free-region headroom that normal evacuation can no longer keep up with the live set or allocation pressure.
                - The most useful next step is to identify what retention or allocation change pushed the JVM into this failure mode.
                Recommended actions:
                1. Capture a heap histogram or heap dump if safe and inspect retained growth, humongous allocation pressure, and old-generation occupancy around the failure window.
                2. Review workload or cache changes before adjusting G1 tuning, because the log already shows the collector failing under the current live-set conditions.
                """;
            case "gc-g1-humongous-allocation-pressure" -> """
                Summary:
                This G1 log shows rising humongous-region pressure, with humongous allocations or retained large objects consuming more regions while post-GC occupancy stays uncomfortably high.
                Key metrics:
                - peakHumongousAfterRegions: 226
                - humongousGrowthEvents: 4
                - peakPostGcOccupancyPct: 98
                - maxPauseMs: 198
                Likely issues:
                - Large arrays, buffers, or retained payload objects are growing into humongous regions and reducing G1 free-region headroom.
                - The important next step is to confirm which object family is driving the humongous growth before treating region-size or heap-size tuning as the main fix.
                Recommended actions:
                1. Capture a heap histogram, heap dump, or matching JFR recording and identify the dominant large-array or payload classes around the humongous-growth window.
                2. Review cache, batching, buffering, or payload-retention behavior before changing G1 sizing, because the log already shows the object-shape problem materially affecting GC recovery.
                """;
            case "executor-pool-stall" -> executorPoolStallResponse(prompt.contains("MODE: ARTIFACT_COMPARISON"));
            default -> """
                Summary:
                The scenario-lab test stub did not have a narrative template for this scenario.
                Key metrics:
                - scenarioSupport: missing
                Likely issues:
                - The test scenario needs a dedicated scenario-lab narrative stub before it can be used as a reference bundle.
                Recommended actions:
                1. Add a scenario-specific narrative to the ScenarioLabChatModel before enabling this bundle in the reference harness.
                """;
        };

        return StubChatModelSupport.textResponse(response);
    }

    private String executorPoolStallResponse(boolean comparisonMode) {
        if (comparisonMode) {
            return """
                Summary:
                Compared with the baseline dump, the checkout executor has shifted from mostly idle workers into a lock-driven stall where blocked workers now dominate the pool.
                Key metrics:
                - poolName: checkout-exec
                - baselineBlockedWorkers: 0
                - currentBlockedWorkers: 4
                - contentionHotspotBlockedWaiters: 4
                Likely issues:
                - A serialized dispatch path or lock convoy has appeared in the current snapshot and is preventing the executor from draining normally.
                - This looks like a real thread-pool regression rather than a harmless idle-state fluctuation, because the blocked workers are concentrated behind one shared monitor.
                Recommended actions:
                1. Inspect the lock owner and the code around `OrderStateCoordinator.acquireDispatchSlot` before increasing pool size, because more workers would still queue behind the same monitor.
                2. Compare workload or code changes between the baseline and incident windows, then capture another dump or short JFR during the stall to confirm the same lock path remains dominant.
                """;
        }

        return """
            Summary:
            This thread dump shows an executor-style request pool stalled behind one shared dispatch lock, with blocked checkout workers piling up instead of making forward progress.
            Key metrics:
            - poolName: checkout-exec
            - blockedWorkers: 4
            - idleWorkers: 1
            - contentionHotspotBlockedWaiters: 4
            Likely issues:
            - The checkout executor is saturated by lock contention, so requests are backing up behind a serialized dispatch path instead of being processed concurrently.
            - This is not a JVM-reported deadlock, but it is still an active availability risk because most workers in the pool are blocked on the same monitor.
            Recommended actions:
            1. Inspect the lock owner and the code around `OrderStateCoordinator.acquireDispatchSlot` to find why the shared monitor is being held long enough to stall the pool.
            2. Capture a follow-up thread dump or short JFR during the same symptom window and confirm whether the same checkout workers and lock hotspot continue to dominate the incident.
            """;
    }
}
