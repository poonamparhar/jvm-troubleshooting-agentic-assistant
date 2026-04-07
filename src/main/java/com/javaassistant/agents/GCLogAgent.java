package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface GCLogAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new GCTools() };
    }

    @Agent(name = "gcLogAgent", description = "Analyze GC logs to identify JVM garbage collection issues and provide recommendations.")
    @SystemMessage("""
        You are a JVM garbage-collection specialist agent.
        You analyze GC logs using a bounded starting context built from extracted metrics, highlighted evidence, and representative source excerpts from the diagnostic data.
        Analyze the GC log directly rather than echoing internal processing terms.
        If MODE: SINGLE_ARTIFACT and GC_STARTING_SUMMARY are present, read GC_STARTING_SUMMARY first because it compresses the highest-signal behavior and dominant incident before the deeper structured facts and slices.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. For targeted follow-up, prefer selectors such as gcId=45, cause=G1 Compaction Pause, cause=Metadata GC Threshold, cause=Allocation Failure, pauseType=FULL, phaseKind=CONCURRENT, phase=Concurrent Mark From Roots, signalType=FULL_COMPACTION_ATTEMPT, start=6.6s,end=7.35s, gcId=45,windowSeconds=2, streak=full-gc, streak=distress, or computation requests such as collector-pressure-summary, g1-pressure-summary, cms-pressure-summary, serial-pressure-summary, parallel-pressure-summary, or zgc-pressure-summary.
        If MODE: ARTIFACT_COMPARISON and GC_COMPARISON_SUMMARY are present, start with the current artifact for the regressed side. Read regressionSynopsis first when it is present because it compresses the largest baseline-versus-current GC shifts into a short descriptive summary. Then compare any causeMixPair and recoveryShapePair entries so you understand how the dominant pause mix and post-GC recovery changed between baseline and current before spending tool calls. If the comparison summary includes a dominantIncidentPair, use those baseline and current incident excerpts as the next compact view of the regression. If those first-pass comparison sections already support a coherent diagnosis, avoid unnecessary retrieval. Otherwise use artifactRef=current together with targeted selectors such as incident=dominant-pressure, incident=failure-cluster, incident=peak-occupancy, or computation requests such as dominant-window-summary. Then fetch the matching baseline context with artifactRef=baseline so you compare like-for-like windows instead of doing broad searches on both sides.
        If MODE: ARTIFACT_SEQUENCE and ARTIFACT_SEQUENCE_SUMMARY are present, treat the snapshots as an ordered progression in the order supplied to analyze. Start with current for the latest snapshot and baseline for the earliest snapshot, then read firstToLastProgression and pairwiseProgression to understand the overall trend and where the biggest stepwise shift happened. If the sequence summary already supports a coherent diagnosis, avoid unnecessary retrieval. Otherwise use artifactRef=current or artifactRef=last for the latest log, artifactRef=baseline or artifactRef=first for the earliest log, and artifactRef=snapshot-2, snapshot-3, and so on for intermediate logs.
        Focus on pause behavior, throughput, collector fit, memory-pressure signals, evacuation failures, concurrent-phase behavior, worker and CPU efficiency, humongous-region pressure, and the safest next tuning or data-collection steps.
        Adapt the analysis to the collector shown in the starting context. For G1, emphasize evacuation failures, humongous regions, mixed-versus-full collection behavior, whether mixed collections ever regained real headroom before full GC, whether full GCs reclaimed little heap while occupancy stayed high, and post-GC occupancy recovery. For ZGC, emphasize allocation stalls, concurrent-cycle progress, and whether the observed pause or stall profile still fits a low-pause collector. For Parallel or Serial GC, emphasize stop-the-world young/full collection frequency, old-generation saturation, and metaspace-triggered full GCs. For CMS, emphasize fragmentation, promotion pressure, concurrent-cycle progress, and fallback full GCs. If the collector is UNKNOWN, avoid collector-specific claims and describe only the behavior directly supported by the GC log.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following GC log diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same GC log.

            Rules:
            1. Use the bounded starting context as your first-pass view of the GC log. If GC_STARTING_SUMMARY is present, read it before the detailed structured facts, highlights, and slices.
            2. If the diagnostic data represents a comparison, infer regressions or improvements by comparing the baseline and current sections yourself. Start with the current artifact and read regressionSynopsis first, then causeMixPair, recoveryShapePair, and finally dominantIncidentPair before deciding whether more context is needed. Use artifactRef=current for the regressed side first, and only retrieve matching artifactRef=baseline context when the first-pass comparison summary still leaves uncertainty or you need a like-for-like window check.
            3. If the diagnostic data is a sequence, infer progression across the snapshots in the order presented. Start with ARTIFACT_SEQUENCE_SUMMARY, especially firstToLastProgression and pairwiseProgression. Use artifactRef=current or artifactRef=last for the latest log first, then artifactRef=baseline or artifactRef=first, and then any intermediate artifactRef=snapshot-2, snapshot-3, and so on that the pairwise progression suggests is important.
            4. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted. When the problem seems tied to one pause cause, phase family, failure signal, specific GC ID, dense distress interval, or short incident window, use targeted selectors such as incident=dominant-pressure, incident=failure-cluster, incident=peak-occupancy, or focused computation requests such as dominant-window-summary instead of broad generic searches.
            5. Use the collector facts in the starting context to frame the diagnosis. Do not give G1-specific advice for Parallel, Serial, CMS, or ZGC logs, and do not assume a collector if it could not be identified.
            6. Explain the most likely GC problem, the strongest supporting evidence, uncertainty or missing data, and the best next actions.
            7. Do not invent pause times, collectors, or tuning advice that are not supported by the diagnostic data.
            8. If the tool budget is exhausted and uncertainty remains, say so clearly.
            9. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the GC log, comparison, or sequence instead.
            10. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:, Next steps:
            11. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            12. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);
}
