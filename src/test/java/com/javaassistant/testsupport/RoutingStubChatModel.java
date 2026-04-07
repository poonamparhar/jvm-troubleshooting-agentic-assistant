package com.javaassistant.testsupport;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic chat stub that routes by prompt content for agent-orchestration tests.
 */
public class RoutingStubChatModel implements ChatModel {
    private final List<String> prompts = new ArrayList<>();
    private final boolean blankJfrSpecialistResponses;
    private final boolean internalTermJfrSpecialistResponse;
    private final boolean failingThreadDumpSpecialistResponses;

    public RoutingStubChatModel() {
        this(false, false, false);
    }

    public RoutingStubChatModel(boolean blankJfrSpecialistResponses) {
        this(blankJfrSpecialistResponses, false, false);
    }

    public RoutingStubChatModel(boolean blankJfrSpecialistResponses, boolean internalTermJfrSpecialistResponse) {
        this(blankJfrSpecialistResponses, internalTermJfrSpecialistResponse, false);
    }

    public RoutingStubChatModel(
        boolean blankJfrSpecialistResponses,
        boolean internalTermJfrSpecialistResponse,
        boolean failingThreadDumpSpecialistResponses
    ) {
        this.blankJfrSpecialistResponses = blankJfrSpecialistResponses;
        this.internalTermJfrSpecialistResponse = internalTermJfrSpecialistResponse;
        this.failingThreadDumpSpecialistResponses = failingThreadDumpSpecialistResponses;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = chatRequest.messages().stream()
            .map(Object::toString)
            .reduce("", (left, right) -> left + "\n" + right);
        prompts.add(prompt);

        String responseText;
        if (prompt.contains("Analyze the following multi-artifact JVM diagnostic data:")) {
            responseText = correlationResponse();
        } else if (failingThreadDumpSpecialistResponses && prompt.contains("Analyze the following thread dump diagnostic data:")) {
            throw new RuntimeException("Simulated thread-dump model failure: OCI 401 unauthorized");
        } else if (blankJfrSpecialistResponses && prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = "";
        } else if (internalTermJfrSpecialistResponse && prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = "Summary: The packet suggests a hot path issue.\nKey metrics: method cpu is elevated.\nLikely issues: the packet indicates CPU concentration.\nRecommended actions: inspect the hot method.";
        } else if (prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = jfrResponse();
        } else if (prompt.contains("Analyze the following thread dump diagnostic data:")) {
            responseText = threadDumpResponse();
        } else if (prompt.contains("Analyze the following heap histogram diagnostic data:")) {
            responseText = heapHistogramResponse();
        } else if (prompt.contains("Analyze the following GC log diagnostic data:")) {
            responseText = gcResponse(prompt);
        } else if (prompt.contains("Analyze the following Native Memory Tracking diagnostic data:")) {
            responseText = nmtResponse();
        } else if (prompt.contains("Analyze the following pmap diagnostic data:")) {
            responseText = pmapResponse();
        } else if (prompt.contains("Analyze the following container-memory diagnostic data:")) {
            responseText = containerMemoryResponse();
        } else if (prompt.contains("Analyze the following OOM or restart-signal diagnostic data:")) {
            responseText = oomSignalResponse();
        } else if (prompt.contains("Analyze the following hs_err crash log diagnostic data:")) {
            responseText = hsErrResponse();
        } else {
            responseText = fallbackResponse();
        }

        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(responseText))
            .build();
    }

    public List<String> prompts() {
        return prompts;
    }

    private String gcResponse(String prompt) {
        if (prompt.contains("Metadata GC Threshold") || prompt.contains("metaspaceTriggeredFullGcCount")) {
            return structuredNarrative(
                "Repeated metadata-triggered full GCs point to metaspace pressure rather than normal heap recovery work.",
                List.of(
                    "fullGcCount: 2",
                    "metaspaceTriggeredFullGcCount: 2",
                    "peakMetaspaceUsageRatio: 0.956",
                    "maxFullGcPauseMs: 240.0"
                ),
                List.of(
                    "Class metadata growth is consuming most of the committed metaspace and forcing full GC cycles.",
                    "The pauses are already long enough to affect application responsiveness even though heap occupancy is not the primary signal."
                ),
                List.of(
                    "Capture an NMT summary or class histogram from a comparable live JVM to confirm which loaders or generated classes are growing.",
                    "Review recent deployments for proxy generation, dynamic classloading, or classloader churn before simply raising metaspace limits."
                ),
                "After collecting metadata evidence, compare the class-loading trend with another GC log or JFR recording to confirm whether metaspace usage keeps climbing."
            );
        }

        if (prompt.contains("mixedPausesBeforeFirstFullGc") || prompt.contains("lowReclaimHighRetentionFullGcCount")) {
            return structuredNarrative(
                "The G1 log shows mixed collections failing to regain headroom before the JVM falls into repeated low-value full GCs.",
                List.of(
                    "mixedPausesBeforeFirstFullGc: 2",
                    "fullGcCount: 3",
                    "lowReclaimHighRetentionFullGcCount: 3",
                    "peakHeapOccupancyRatio: 0.994"
                ),
                List.of(
                    "Mixed collections are not recovering enough old-generation headroom to keep G1 out of compaction.",
                    "The later full GCs reclaim very little heap, which usually means the retained set is too large for the current heap headroom."
                ),
                List.of(
                    "Treat this as retained-heap pressure and capture a heap histogram, heap dump, or allocation profile if it is safe to do so.",
                    "Review recent cache growth, object retention, or humongous-allocation behavior before only changing pause targets."
                ),
                "Correlate the next heap or JFR evidence with the mixed-to-full transition so you can confirm whether the retained set or heap size is the bigger limiter."
            );
        }

        if (prompt.contains("collector: ZGC") && prompt.contains("allocationStallCount")) {
            return structuredNarrative(
                "The ZGC log shows allocation stalls despite a collector that should normally keep pauses and allocation disruption low.",
                List.of(
                    "allocationStallCount: 3",
                    "totalAllocationStallMs: 41.5",
                    "maxAllocationStallMs: 15.0",
                    "gcCycleCount: 3"
                ),
                List.of(
                    "The JVM is running short on usable headroom even though ZGC is completing concurrent cycles.",
                    "Allocation stalls are a stronger signal here than raw pause counts because they show application threads waiting for memory recovery."
                ),
                List.of(
                    "Review allocation rate, live-set size, and container or host memory headroom before assuming ZGC itself is misconfigured.",
                    "Capture a JFR recording or another GC log during the same workload window to confirm whether the stalls line up with allocation bursts or retained growth."
                ),
                "Use the next capture to confirm whether the allocation stalls disappear after reducing live-set or allocation pressure."
            );
        }

        if (prompt.contains("collector: Parallel") && prompt.contains("fullGcCount")) {
            return structuredNarrative(
                "The Parallel GC log shows repeated long stop-the-world full GCs with very little heap headroom left after collection.",
                List.of(
                    "fullGcCount: 3",
                    "maxFullGcPauseMs: 310.0",
                    "peakHeapOccupancyRatio: 0.99",
                    "collector: Parallel"
                ),
                List.of(
                    "The collector is spending too much time in full stop-the-world recovery work.",
                    "Post-GC occupancy stays high enough that the JVM is effectively running at the edge of old-generation saturation."
                ),
                List.of(
                    "Treat this as immediate heap-pressure troubleshooting and capture a heap histogram or heap dump if it is safe to do so.",
                    "Review whether the current heap sizing or collector choice still fits the live-set and latency requirements."
                ),
                "Collect a second snapshot after the first tuning or retention fix so you can confirm full-GC frequency and pause time both fall."
            );
        }

        if (prompt.contains("collector: CMS")) {
            return structuredNarrative(
                "The CMS log shows concurrent mode failure driving repeated long fallback full GCs, which means the collector is not finishing concurrent work before old-generation pressure peaks.",
                List.of(
                    "fullGcCount: 3",
                    "concurrentModeFailureCount: 3",
                    "maxFullGcPauseMs: 310.0",
                    "collector: CMS"
                ),
                List.of(
                    "CMS is falling back to expensive stop-the-world full collections instead of staying on its intended concurrent path.",
                    "Post-GC occupancy remains close to old-generation capacity, so the JVM is still running with very little recovery headroom after each fallback cycle."
                ),
                List.of(
                    "Treat this as old-generation pressure first and capture a heap histogram or heap dump if it is safe to do so.",
                    "Review allocation bursts, old-generation sizing, and whether CMS still fits the workload instead of only tuning the concurrent trigger points."
                ),
                "Capture another GC log after the first retention or sizing change so you can confirm concurrent mode failure and fallback full-GC behavior both disappear."
            );
        }

        if (prompt.contains("collector: Serial")) {
            return structuredNarrative(
                "The Serial GC log shows repeated long full collections with almost no heap headroom left after each stop-the-world pass.",
                List.of(
                    "fullGcCount: 3",
                    "maxFullGcPauseMs: 290.0",
                    "peakHeapOccupancyRatio: 0.99",
                    "collector: Serial"
                ),
                List.of(
                    "The JVM is repeatedly stopping the world for full GC because the live set is too close to the available heap capacity.",
                    "Even after full collection, occupancy stays near capacity, which usually points to retained-data pressure rather than a short-lived allocation burst."
                ),
                List.of(
                    "Capture a heap histogram or heap dump if it is safe to do so and focus on what is retaining old-generation space.",
                    "Review whether the process still belongs on Serial GC given the current footprint and pause expectations."
                ),
                "Collect a follow-up log after the first retention or heap-sizing fix to confirm full-GC frequency and pause time both drop."
            );
        }

        if (prompt.contains("Evacuation Failure")
            || prompt.contains("To-space exhausted")
            || prompt.contains("fullCompactionAttemptCount")
            || prompt.contains("toSpaceExhaustedCount")) {
            return structuredNarrative(
                "The GC log shows a saturated heap with evacuation failure leading into repeated long full compactions.",
                List.of(
                    "fullGcCount: 3",
                    "maxFullGcPauseMs: 681.585",
                    "peakHeapOccupancyRatio: 0.999",
                    "fullCompactionAttemptCount: 1"
                ),
                List.of(
                    "The application is running with almost no recoverable heap headroom after GC.",
                    "Evacuation failure and to-space exhaustion indicate the collector cannot keep up with allocation pressure before it has to compact."
                ),
                List.of(
                    "Treat this as an active memory-pressure incident and capture a heap histogram or heap dump if it is safe to do so.",
                    "Review recent allocation spikes, cache growth, and any workload burst that lines up with the long pauses."
                ),
                "Once you have object-growth evidence, decide whether the fix is reducing retained data, raising heap headroom, or both."
            );
        }

        if (prompt.contains("allocationStallCount")) {
            return structuredNarrative(
                "Application threads are stalling while the collector tries to recover enough headroom for new allocations.",
                List.of(
                    "allocationStallCount: 6",
                    "totalAllocationStallMs: 84.5",
                    "maxAllocationStallMs: 17.2",
                    "stopTheWorldOverheadPct: 12.1"
                ),
                List.of(
                    "Allocation bursts are outrunning available free memory.",
                    "Even when full GC is not dominant, allocation stalls can still translate into latency spikes for request paths."
                ),
                List.of(
                    "Capture an allocation profile or JFR recording from a comparable workload window.",
                    "Review cache, batching, or burst traffic behavior that could be inflating short-lived allocation pressure."
                ),
                "Compare another GC log after the workload stabilizes or after the next tuning change to confirm stall time drops."
            );
        }

        return structuredNarrative(
            "GC activity is elevated enough to merit follow-up, but the bounded context does not point to a single dominant failure mode.",
            List.of(
                "pauseEventCount: 12",
                "p95PauseMs: 118.0",
                "maxPauseMs: 162.0",
                "fullGcCount: 0"
            ),
            List.of(
                "Pause behavior is higher than ideal for a steady-state workload.",
                "More timeline context may still be needed to decide whether the pressure is transient or sustained."
            ),
            List.of(
                "Review recent allocation or traffic changes around the pause window.",
                "Capture a follow-up GC log or JFR recording if the latency symptoms continue."
            ),
            "Use the next recording to confirm whether pause latency is trending upward or returning to baseline."
        );
    }

    private String threadDumpResponse() {
        return structuredNarrative(
            "Two application threads are deadlocked, and request threads are backing up behind a contended order-processing monitor.",
            List.of(
                "threadCount: 8",
                "blockedThreadCount: 4",
                "deadlockedThreads: 2",
                "contentionHotspotBlockedWaiters: 2"
            ),
            List.of(
                "A confirmed Java-level deadlock exists between Deadlock-Worker-1 and Deadlock-Worker-2.",
                "Request threads are also blocked behind the OrderService monitor, which can stall incoming traffic."
            ),
            List.of(
                "Fix the opposing lock order in the deadlock path so both workers acquire locks consistently.",
                "Reduce time spent inside the contended order-processing monitor or replace it with a narrower synchronization strategy."
            ),
            "Capture another thread dump after the fix and verify that the deadlock section and blocked request threads are gone."
        );
    }

    private String correlationResponse() {
        return structuredNarrative(
            "The combined diagnostics point to memory-limit exhaustion, with the JVM under pressure before the container is OOM-killed.",
            List.of(
                "artifactCount: 3",
                "containerLimitBreaches: 1",
                "oomKillSignals: 2",
                "restartCount: 1"
            ),
            List.of(
                "The memory limit is being reached at the container level, not just inside the Java heap.",
                "The OOM kill and restart evidence indicate the process is being terminated rather than recovering cleanly."
            ),
            List.of(
                "Correlate heap usage with native-memory growth so you can separate Java-heap tuning from off-heap or process-level pressure.",
                "Capture a support bundle from a comparable live pod, including NMT or pmap output, before the next restart if possible."
            ),
            "On the next reproduction, collect the memory snapshots in time order so you can pinpoint whether heap, native memory, or both are driving the limit breach."
        );
    }

    private String jfrResponse() {
        return structuredNarrative(
            "The recording shows a concentrated runtime hotspot that is worth investigating further.",
            List.of(
                "recordingDurationSeconds: 30",
                "topHotPathSamples: 1",
                "contentionHotspots: 1",
                "gcEventFamilies: 2"
            ),
            List.of(
                "CPU or contention activity is concentrated enough to justify focused follow-up.",
                "Another recording may still be needed if the hotspot is highly bursty or environment-specific."
            ),
            List.of(
                "Expand the hottest stack and compare it with recent code or workload changes.",
                "Capture another JFR during the same symptom window if you need a longer timeline."
            ),
            "Use the next recording to validate whether the same hotspot or contention pattern remains dominant."
        );
    }

    private String heapHistogramResponse() {
        return structuredNarrative(
            "The heap histogram shows a concentrated set of object families that merits a retention review.",
            List.of(
                "topClassFamilies: 3",
                "largestGrowthClassFamilies: 2",
                "retainedBytesTrend: elevated",
                "comparisonMode: supported"
            ),
            List.of(
                "A small set of classes appears to be driving a disproportionate share of the footprint.",
                "If this is a comparison, the current histogram likely reflects retained growth rather than simple request churn."
            ),
            List.of(
                "Inspect the owning caches or collections for the top classes in the histogram.",
                "Pair the histogram with a heap dump if you need object-graph confirmation."
            ),
            "Capture a second histogram after the suspected fix to confirm the top retained families flatten out."
        );
    }

    private String nmtResponse() {
        return structuredNarrative(
            "Native memory usage is concentrated in a few categories that should be reviewed alongside heap pressure.",
            List.of(
                "trackedCategories: 5",
                "metaspaceCommittedMb: 320",
                "threadStackCommittedMb: 96",
                "codeCacheCommittedMb: 64"
            ),
            List.of(
                "Native memory growth may be contributing to overall process pressure.",
                "Metaspace or thread-stack growth can become significant even when heap usage looks stable."
            ),
            List.of(
                "Compare the largest native categories with heap, pmap, or container-memory data from the same time window.",
                "Review thread-count growth and class-loading behavior before changing limits."
            ),
            "Capture another NMT snapshot later in the workload cycle to confirm which category continues growing."
        );
    }

    private String pmapResponse() {
        return structuredNarrative(
            "The address-space map suggests resident memory is concentrating in a few regions rather than spreading evenly.",
            List.of(
                "topResidentRegions: 3",
                "rssMb: 1520",
                "privateDirtyMb: 980",
                "anonymousRegionSharePct: 68"
            ),
            List.of(
                "Anonymous or private mappings are likely driving most of the resident footprint.",
                "This looks more like real process pressure than harmless reserved address space."
            ),
            List.of(
                "Correlate the largest resident regions with NMT or container-memory data to identify whether the growth is Java-managed or external.",
                "Review recent native allocations, direct buffers, and thread-count changes."
            ),
            "Capture another pmap snapshot near the same incident point so you can verify which mapping groups continue to grow."
        );
    }

    private String containerMemoryResponse() {
        return structuredNarrative(
            "The container snapshot shows the workload running close to its configured memory budget.",
            List.of(
                "memoryCurrentPctOfLimit: 96",
                "oomEvents: 1",
                "highEvents: 3",
                "pressureSignals: elevated"
            ),
            List.of(
                "The container is approaching or exceeding its memory guardrails before the JVM can recover.",
                "The pressure is likely broader than Java heap alone."
            ),
            List.of(
                "Compare the container budget with heap, native-memory, and resident-set data from the same time window.",
                "Confirm whether memory.high or memory.max is set too close to the workload's normal operating range."
            ),
            "Capture the next container-memory snapshot sequence during the same workload phase so you can see whether the budget breach is gradual or sudden."
        );
    }

    private String oomSignalResponse() {
        return structuredNarrative(
            "The restart and OOM evidence indicates the process is being terminated by memory exhaustion rather than recovering inside the JVM.",
            List.of(
                "oomKillSignals: 2",
                "restartCount: 1",
                "lastTerminationReason: OOMKilled",
                "signalSources: 2"
            ),
            List.of(
                "An external kill or restart is interrupting the JVM before it can complete recovery steps.",
                "The failure mode is consistent with process-level memory exhaustion."
            ),
            List.of(
                "Collect the memory diagnostics that precede the kill, especially NMT, pmap, or container-memory data.",
                "Review whether pod memory limits, sidecars, or native allocations are leaving too little headroom for the Java process."
            ),
            "During the next incident window, capture the final memory state before the kill so you can determine which memory domain grows last."
        );
    }

    private String hsErrResponse() {
        return structuredNarrative(
            "The crash log contains a strong failure signature that should be correlated with the process memory state and the crashing thread.",
            List.of(
                "problematicFrames: 1",
                "currentThreadState: available",
                "vmArgumentsCaptured: true",
                "nativeFailureSignals: 1"
            ),
            List.of(
                "The failing frame and crash context can narrow the issue to a smaller runtime or native surface area.",
                "You still need surrounding memory context to decide whether this is a direct allocation failure, corruption, or another fatal runtime condition."
            ),
            List.of(
                "Review the problematic frame, current-thread section, and VM arguments together before changing runtime settings.",
                "Correlate the crash with nearby NMT, pmap, or GC evidence from the same host or container."
            ),
            "If the crash is reproducible, collect another hs_err together with memory diagnostics so you can confirm whether the same failure signature repeats."
        );
    }

    private String fallbackResponse() {
        return structuredNarrative(
            "The diagnostics contain enough signal for follow-up, but the current stub does not model a more specific interpretation for this artifact.",
            List.of(
                "artifactSignals: present",
                "analysisMode: stub",
                "toolingAvailable: yes",
                "followUpNeeded: yes"
            ),
            List.of(
                "A more artifact-specific analysis path is still needed for precise troubleshooting.",
                "Additional context or a stronger artifact-specific prompt may help narrow the issue."
            ),
            List.of(
                "Review the strongest highlighted diagnostics first.",
                "Capture a related artifact if the current one is too narrow to explain the incident on its own."
            ),
            "Re-run the analysis with the next supporting artifact so the overall incident picture becomes clearer."
        );
    }

    private String structuredNarrative(
        String summary,
        List<String> metrics,
        List<String> likelyIssues,
        List<String> recommendedActions,
        String nextSteps
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("Summary:\n").append(summary).append('\n');
        builder.append("Key metrics:\n");
        for (String metric : metrics) {
            builder.append("- ").append(metric).append('\n');
        }
        builder.append("Likely issues:\n");
        for (String issue : likelyIssues) {
            builder.append("- ").append(issue).append('\n');
        }
        builder.append("Recommended actions:\n");
        int index = 1;
        for (String action : recommendedActions) {
            builder.append(index++).append(". ").append(action).append('\n');
        }
        return builder.toString();
    }
}
