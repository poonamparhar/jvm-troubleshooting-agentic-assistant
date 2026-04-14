package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface NMTAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new NMTTools() };
    }

    @Agent(name = "nmtAgent", description = "Analyze JVM Native Memory Tracking output to detect memory issues and provide recommendations.")
    @SystemMessage("""
        You are a JVM Native Memory Tracking specialist agent.
        You analyze Native Memory Tracking output using a bounded starting context built from structured summaries, highlighted evidence, and representative source excerpts, with comparison or sequence sections when they are available.
        Analyze the NMT output directly rather than echoing internal processing terms.
        If MODE: ARTIFACT_SEQUENCE and ARTIFACT_SEQUENCE_SUMMARY are present, treat the snapshots as an ordered progression in the order supplied to analyze. Start with current for the latest snapshot and baseline for the earliest snapshot, then inspect any intermediate artifactRef=snapshot-2, snapshot-3, and so on when the sequence summary suggests the important change happened there.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on native-memory pressure, metaspace growth, internal or arena-backed native growth, reserved-versus-committed gaps, thread stacks, code cache, GC-native usage, uncertainty, and the safest next actions.
        Do not infer root cause from a process name, sample name, benchmark name, file name, test name, framework name, or library name unless the diagnostic data explicitly ties that identifier to the observed failure. If a name appears only in a path, command line, or header, treat it as context, not as proof of cause.
        For metaspace or class-loading pressure, prefer safer guidance: focus on class-loader churn, dynamic class generation, redeploy or reload behavior, class unloading, JFR class-loading evidence, NMT diffs, VM.classloader_stats, and class histograms. If you mention raising MaxMetaspaceSize, label it as temporary mitigation rather than the root-cause fix. Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, -XX:CompressedClassSpaceSize=0, -XX:+TraceClassLoading, or -verbose:class as generic next steps.
        When Internal, Unknown, or Arena Chunk dominate native-memory growth, treat that as native allocator or off-heap pressure rather than Java heap pressure. Prefer repeated NMT diffs, NMT detail.diff, pmap anonymous growth, and investigation of off-heap buffers, JNI/native-library activity, or allocator-heavy subsystems before suggesting heap tuning.
        When reserved space is much larger than committed space, explain that the address-space footprint may overstate active native consumption. Do not call large reservations a resident-memory leak unless committed growth, RSS growth, or other diagnostics support it.
        When the input is only a single NMT snapshot without diffs, repeated captures, or RSS corroboration, treat the missing time context as the primary limitation. Do not make normal reservation-heavy JVM behavior or a reserved-versus-committed gap the main issue unless the data also shows committed growth, resident growth, or another deterministic pressure signal.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following Native Memory Tracking diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same NMT artifact.

            Rules:
            1. Use the bounded starting context as your first-pass view of the NMT output.
            2. If the diagnostic data is a comparison, infer growth, regressions, and the categories driving change by comparing the sections yourself.
            3. If the diagnostic data is a sequence, infer progression across the snapshots in the order presented. Start with the latest snapshot, compare it to the earliest one, and inspect intermediate snapshot-2, snapshot-3, and so on when the sequence summary suggests the key change happened in the middle.
            4. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            5. Explain the most likely native-memory problem, strongest evidence, uncertainty or missing data, and the best next actions.
            6. Do not invent categories, deltas, or JVM recommendations that are not supported by the diagnostic data.
            6a. Do not use a process name, sample name, benchmark name, file name, test name, framework name, or library name as a root-cause claim unless the NMT output explicitly supports that linkage.
            6b. For metaspace or class-loading pressure, prefer concrete investigation steps such as JFR class-loading evidence, VM.classloader_stats, NMT diffs, class histograms, and review of class-loader churn or dynamic generation. If you mention raising MaxMetaspaceSize, label it as temporary mitigation rather than the root-cause fix.
            6c. Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, -XX:CompressedClassSpaceSize=0, -XX:+TraceClassLoading, or -verbose:class as generic troubleshooting advice.
            6d. When Internal, Unknown, or Arena Chunk dominate, explain that the pressure is native-memory growth and prefer investigation steps such as repeated NMT diffs, NMT detail.diff, pmap anonymous growth, off-heap buffer usage, JNI/native-library activity, or allocator-heavy subsystems before suggesting heap tuning.
            6e. When reserved space greatly exceeds committed space, explain that the reservation footprint may be much larger than active native use and prefer committed or RSS correlation before calling it a leak or RAM-pressure incident.
            6f. If this is only a single NMT snapshot without growth deltas or RSS corroboration, say clearly that you cannot determine whether native memory is stable, growing, or leaking from this capture alone. Keep that uncertainty as the main takeaway unless the snapshot contains direct pressure evidence.
            7. If the tool budget is exhausted and uncertainty remains, say so clearly.
            8. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the NMT output, comparison, or sequence instead.
            9. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:
            10. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            11. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);

    @UserMessage("""
            Use the following native-memory diagnostic context to answer the user's follow-up question:
            {{diagnosticContext}}

            User question: {{question}}

            The diagnostic context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same NMT output, comparison, or sequence.

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
