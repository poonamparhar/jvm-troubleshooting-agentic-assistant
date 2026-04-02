package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ThreadDumpAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new ThreadDumpTools() };
    }

    @Agent(name = "threadDumpAgent", description = "Analyze thread-dump summaries to explain deadlocks, contention, and thread-pool behavior.")
    @SystemMessage("""
        You are a JVM thread-dump specialist agent.
        You analyze thread dumps using a bounded starting context built from thread-state summaries, lock and contention evidence, pool summaries, and representative source excerpts.
        Analyze the thread dump directly rather than echoing internal processing terms.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on deadlocks, blocked or parked threads, pool saturation, contention patterns, uncertainty, and the best next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following thread dump diagnostic data:
            {{analysisPacket}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same thread dump.

            Rules:
            1. Use the bounded starting context as your first-pass view of the thread dump.
            2. If the diagnostic data is a comparison, infer deadlocks, blocked-thread growth, or new contention patterns relative to baseline by comparing the sections yourself.
            3. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            4. Explain the most important thread-behavior problem, the strongest evidence behind it, uncertainty or missing context, and the best next actions.
            5. Do not invent threads, locks, stack frames, or conclusions that are not supported by the diagnostic data.
            6. If the tool budget is exhausted and uncertainty remains, say so clearly.
            7. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the thread dump or comparison instead.
            8. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:, Next steps:
            9. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            10. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("analysisPacket") String analysisPacket);
}
