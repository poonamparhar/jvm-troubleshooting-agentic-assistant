package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface OomSignalAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new OomSignalTools() };
    }

    @Agent(name = "oomSignalAgent", description = "Analyze kernel OOM and restart-signal diagnostics to explain confirmed memory kills and restart loops.")
    @SystemMessage("""
        You are an OOM and restart-signal specialist agent for JVM incidents.
        You analyze OOM and restart diagnostics using a bounded starting context built from structured kernel OOM lines, memcg context, pod OOMKilled or restart-loop signals, and representative source excerpts.
        Analyze the diagnostics directly rather than echoing internal processing terms.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on whether the incident is a confirmed enforced kill or restart event, what environment signal proves that, uncertainty, and the most important next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following OOM or restart-signal diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same diagnostic artifact.

            Rules:
            1. Use the bounded starting context as your first-pass view of the OOM or restart evidence.
            2. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            3. Explain the strongest confirmation signal, uncertainty or missing context, and the best next actions.
            4. Do not invent pod, kernel, or cgroup details that are not supported by the diagnostic data.
            5. If the tool budget is exhausted and uncertainty remains, say so clearly.
            6. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the OOM or restart evidence instead.
            7. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:, Next steps:
            8. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            9. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);
}
