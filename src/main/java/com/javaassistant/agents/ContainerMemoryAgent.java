package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ContainerMemoryAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new ContainerMemoryTools() };
    }

    @Agent(name = "containerMemoryAgent", description = "Analyze cgroup memory diagnostics to explain container budget pressure and OOM risk.")
    @SystemMessage("""
        You are a container-memory specialist agent for JVM workloads.
        You analyze cgroup memory diagnostics using a bounded starting context built from usage summaries, memory.events counters, memory.stat breakdowns, PSI pressure, and representative source excerpts.
        Analyze the container-memory diagnostics directly rather than echoing internal processing terms.
        The starting context is intentionally bounded but front-loads as much high-signal material as possible. If the context coverage says more detail is available, you must retrieve more context before concluding. If a retrieval result says More available: true and that unresolved area matters to the diagnosis, keep expanding until the relevant uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Focus on memory budget pressure, reclaim stress, OOM risk, what part of the cgroup footprint appears dominant, uncertainty, and the most useful next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following container-memory diagnostic data:
            {{diagnosticContext}}

            The starting context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same snapshot.

            Rules:
            1. Use the bounded starting context as your first-pass view of the container-memory diagnostics.
            2. If context coverage indicates omitted detail, you must retrieve more context before concluding. A blank selector returns the next omitted slice. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            3. Explain the most important container-memory problem, the strongest evidence behind it, uncertainty or missing context, and the best next actions.
            4. Do not invent cgroup counters, limits, or OOM risk claims that are not supported by the diagnostic data.
            5. If the tool budget is exhausted and uncertainty remains, say so clearly.
            6. Never refer to the diagnostic data as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the container-memory diagnostics instead.
            7. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:
            8. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            9. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);

    @UserMessage("""
            Use the following container-memory diagnostic context to answer the user's follow-up question:
            {{diagnosticContext}}

            User question: {{question}}

            The diagnostic context above is intentionally bounded. Additional curated retrieval and focused computation tools are available if you need more detail from the same diagnostic artifact.

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
