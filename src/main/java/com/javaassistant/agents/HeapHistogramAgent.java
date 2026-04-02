package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface HeapHistogramAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new HeapHistogramTools() };
    }

    @Agent(name = "heapHistogramAgent", description = "Analyze JVM heap histograms for single analysis or comparisons to identify memory usage patterns and leaks.")
    @SystemMessage("""
        You are a JVM heap-histogram specialist agent.
        You analyze heap histograms using a bounded starting context built from structured summaries, top-class breakdowns, highlighted evidence, and representative source excerpts, with baseline and current sections when a comparison is available.
        Analyze the heap data directly rather than echoing internal processing terms.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on dominant classes, leak-like growth, retention patterns, uncertainty, and the most useful next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following heap histogram diagnostic data:
            {{analysisPacket}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same heap histogram.

            Rules:
            1. Use the bounded starting context as your first-pass view of the heap histogram.
            2. If the diagnostic data is a comparison, infer classes whose growth changes the incident interpretation by comparing the sections yourself.
            3. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            4. Explain the most likely heap-retention problem, strongest evidence, uncertainty or missing context, and the best next actions.
            5. Do not invent class growth, object counts, or leak claims that are not supported by the diagnostic data.
            6. If the tool budget is exhausted and uncertainty remains, say so clearly.
            7. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the heap histogram or comparison instead.
            8. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:, Next steps:
            9. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            10. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("analysisPacket") String analysisPacket);
}
