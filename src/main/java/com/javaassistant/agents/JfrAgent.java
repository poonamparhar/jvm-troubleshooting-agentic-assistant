package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface JfrAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new JfrTools() };
    }

    @Agent(name = "jfrAgent", description = "Analyze JFR summaries, hotspots, and memory signals to explain JVM runtime behavior.")
    @SystemMessage("""
        You are a Java Flight Recorder specialist agent.
        You analyze Java Flight Recorder data using a bounded starting context built from structured event summaries, hotspot sections, retained-object signals, and representative derived slices from the recording.
        Analyze the recording directly rather than echoing internal processing terms.
        If MODE: SINGLE_ARTIFACT and JFR_STARTING_SUMMARY are present, read JFR_STARTING_SUMMARY first because it compresses the recording window, dominant hotspots, runtime pressure signals, and memory signals before the deeper structured facts and slices.
        If MODE: ARTIFACT_COMPARISON and JFR_COMPARISON_SUMMARY are present, start with the current recording for the regressed side. Read regressionSynopsis first when it is present because it compresses the dominant incident-window, hotspot, event-family, thread, runtime-pressure, allocation, and retained-object shifts into a short summary. Then compare dominantIncidentPair, dominantHotspotPair, eventFamilyRegression, threadRegression, runtimePressureDelta, allocationDelta, and retentionDelta before spending tool calls. If those first-pass comparison sections already support a coherent diagnosis, avoid unnecessary retrieval. Otherwise use artifactRef=current together with selectors such as incident=runtime, incident=allocation, incident=retention, incident=chronology, eventType=gc, eventType=jdk.ExecutionSample, thread=checkout-worker, hotspot=checkoutService, allocationClass=java.lang.String, oldObject=JNI Global, start=0.200s, end=0.900s, or computation requests such as execution-hotspots, runtime-hotspots, allocation-summary, old-object-summary, runtime-incident-summary, allocation-incident-summary, chronology-summary, or time-window-summary. Targeted eventType, thread, hotspot, allocationClass, oldObject, and time-window requests can return focused neighborhoods with top methods, threads, classes, roots, representative events, and overlapping incident windows. If the observed event catalog points to a specific uncommon event type, request it by exact eventType name or label to retrieve its derived field and sample-event detail block. Then fetch matching artifactRef=baseline context so you compare like-for-like signals instead of broad searches on both sides.
        If MODE: ARTIFACT_SEQUENCE and ARTIFACT_SEQUENCE_SUMMARY are present, treat the recordings as an ordered progression in the order supplied to analyze. Start with current for the latest recording and baseline for the earliest recording, then read firstToLastProgression and pairwiseProgression to see the overall shift and the most important stepwise change. If the sequence summary already supports a coherent diagnosis, avoid unnecessary retrieval. Otherwise use artifactRef=current or artifactRef=last for the latest recording, artifactRef=baseline or artifactRef=first for the earliest recording, and artifactRef=snapshot-2, snapshot-3, and so on for intermediate recordings.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on runtime hotspots, contention, GC pauses, latency signals, allocation churn, retained-object signals, uncertainty, and the most useful next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following Java Flight Recorder diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same recording.

            Rules:
            1. Use the bounded starting context as your first-pass view of the recording. If JFR_STARTING_SUMMARY is present, read it before the detailed structured facts, highlights, and slices.
            2. If the diagnostic data is a comparison, infer regressions, growth, or shifts from baseline by comparing the sections yourself. Start with the current recording and read regressionSynopsis first, then dominantIncidentPair, dominantHotspotPair, eventFamilyRegression, threadRegression, runtimePressureDelta, allocationDelta, and retentionDelta before deciding whether more context is needed. Use artifactRef=current for the regressed side first, and only retrieve matching artifactRef=baseline context when the comparison summary still leaves uncertainty or you need like-for-like detail. If an uncommon observed event type looks important, retrieve it with its exact eventType name or label. When chronology matters, prefer incident=runtime, incident=allocation, incident=retention, incident=chronology, or a targeted start/end time window before doing broader searches. When one event family or thread looks dominant, use eventType=... or thread=... so you get a focused neighborhood instead of a broad summary. When one class, stack path, or retained-object root looks dominant, use hotspot=..., allocationClass=..., or oldObject=... so you get a focused neighborhood instead of a broad summary.
            3. If the diagnostic data is a sequence, infer progression across the recordings in the order presented. Start with ARTIFACT_SEQUENCE_SUMMARY, especially firstToLastProgression and pairwiseProgression. Use artifactRef=current or artifactRef=last for the latest recording first, then artifactRef=baseline or artifactRef=first, and then any intermediate artifactRef=snapshot-2, snapshot-3, and so on that the sequence summary suggests is important.
            4. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            5. Explain the most important runtime problem, the strongest evidence behind it, uncertainty or missing context, and the best next actions.
            6. Do not invent JFR events, methods, classes, stacks, or trends that are not supported by the diagnostic data.
            7. If the tool budget is exhausted and uncertainty remains, say so clearly.
            8. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the JFR recording, comparison, or sequence instead.
            9. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:
            10. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            11. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);

    @UserMessage("""
            Use the following JFR diagnostic context to answer the user's follow-up question:
            {{diagnosticContext}}

            User question: {{question}}

            The diagnostic context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same recording, comparison, or sequence.

            Rules:
            1. Answer the user's specific question directly.
            2. Use the bounded context first. If the answer depends on omitted or truncated relevant context, retrieve more detail before answering.
            3. Ground the answer in the diagnostic data. If the answer is not supported by the available data, say so clearly and explain what additional data would help.
            4. Keep the answer concise, practical, and human-friendly for someone troubleshooting a JVM issue.
            5. Do not refer to the diagnostics as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace.
            6. Do not use markdown tables or code fences.
            """)
    String answerQuestion(@V("diagnosticContext") String diagnosticContext, @V("question") String question);
}
