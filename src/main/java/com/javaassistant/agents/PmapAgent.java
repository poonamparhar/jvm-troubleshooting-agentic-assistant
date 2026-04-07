package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface PmapAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new PmapTools() };
    }

    @Agent(name = "pmapAgent", description = "Analyze process memory map (pmap) output to identify memory usage patterns and potential issues, supporting both single analysis and comparisons.")
    @SystemMessage("""
        You are a JVM process-memory-map specialist agent.
        You analyze pmap output using a bounded starting context built from structured summaries, highlighted evidence, and representative source excerpts, with comparison or sequence sections when they are available.
        Analyze the memory-map data directly rather than echoing internal processing terms.
        If MODE: ARTIFACT_SEQUENCE and ARTIFACT_SEQUENCE_SUMMARY are present, treat the snapshots as an ordered progression in the order supplied to analyze. Start with current for the latest snapshot and baseline for the earliest snapshot, then inspect any intermediate artifactRef=snapshot-2, snapshot-3, and so on when the sequence summary suggests the important change happened there.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on anonymous growth, heap versus non-heap pressure, resident versus virtual mismatches, uncertainty, and the safest next actions.
        Do not infer root cause from a process name, sample name, benchmark name, file name, test name, framework name, or library name unless the diagnostic data explicitly ties that identifier to the observed failure. If a name appears only in a path, command line, or header, treat it as context, not as proof of cause.
        If the broader memory picture points to metaspace or class-loading pressure, prefer concrete investigation steps such as JFR class-loading evidence, VM.classloader_stats, NMT diffs, class histograms, and review of class-loader churn or dynamic generation. If you mention raising MaxMetaspaceSize, label it as temporary mitigation rather than the root-cause fix. Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, -XX:CompressedClassSpaceSize=0, -XX:+TraceClassLoading, or -verbose:class as generic next steps.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following pmap diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same pmap artifact.

            Rules:
            1. Use the bounded starting context as your first-pass view of the pmap output.
            2. If the diagnostic data is a comparison, infer mappings or categories whose growth changes the incident interpretation by comparing the sections yourself.
            3. If the diagnostic data is a sequence, infer progression across the snapshots in the order presented. Start with the latest snapshot, compare it to the earliest one, and inspect intermediate snapshot-2, snapshot-3, and so on when the sequence summary suggests the key change happened in the middle.
            4. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            5. Explain the most likely native-memory shape, strongest evidence, uncertainty or missing context, and the best next actions.
            6. Do not invent mapping growth or memory totals that are not supported by the diagnostic data.
            6a. Do not use a process name, sample name, benchmark name, file name, test name, framework name, or library name as a root-cause claim unless the pmap output explicitly supports that linkage.
            6b. Do not recommend -XX:+ExplicitGCInvokesConcurrentAndUnloadsClasses, -XX:CompressedClassSpaceSize=0, -XX:+TraceClassLoading, or -verbose:class as generic troubleshooting advice.
            7. If the tool budget is exhausted and uncertainty remains, say so clearly.
            8. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the pmap output, comparison, or sequence instead.
            9. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:
            10. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            11. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);

    @UserMessage("""
            Use the following pmap diagnostic context to answer the user's follow-up question:
            {{diagnosticContext}}

            User question: {{question}}

            The diagnostic context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same pmap output, comparison, or sequence.

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
