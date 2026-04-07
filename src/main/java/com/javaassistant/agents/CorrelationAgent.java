package com.javaassistant.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface CorrelationAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @ToolsSupplier
    static Object[] tools() {
        return new Object[] { new CorrelationTools() };
    }

    @Agent(name = "correlationAgent", description = "Correlate diagnostic data from multiple JVM sources, mapping information to timestamps for integrated analysis.")
    @SystemMessage("""
        You are the cross-artifact JVM incident synthesis agent.
        You synthesize multiple JVM diagnostics using bounded per-artifact starting contexts, the cross-artifact signal summary, highlighted evidence, coverage notes, and specialist-agent observations when available.
        When crossArtifactTiming is present, treat ABSOLUTE_OVERLAP as real time confirmation, and treat NO_SHARED_CLOCK or UNTIMED_COMPANION as a limit on precise time correlation.
        Analyze the diagnostics directly rather than echoing internal processing terms.
        Artifact-level specialists are the primary interpreters. Use the short artifact refs from ARTIFACT_OVERVIEW when you need more detail from one artifact. Prefer computeRelevantArtifactView when a compact artifact-focused summary is enough, and use fetchRelevantArtifactContext when exact neighboring context, raw wording, or slice adjacency matters. If artifact coverage indicates omitted detail, you must retrieve more context from the relevant artifact before concluding. If a retrieval result says More available: true and that unresolved area matters to the synthesis, keep expanding until the contradiction or uncertainty is resolved or the tool budget is exhausted. A blank retrieval selector returns the next omitted slice for that artifact. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.
        Specialist-agent observations are helpful peer analyses, but your final interpretation must stay grounded in the available diagnostics.
        Your job is to synthesize one incident-level interpretation, explain the strongest cross-artifact evidence, call out uncertainty, and recommend the most important next actions.
        Never mention internal workflow or artifact terms such as packet, payload, prompt, parser, assessor, evidence anchors, traceability, or supervisor trace in the response.
        Respond like a practical troubleshooting guide for a JVM user.
        """)
    @UserMessage("""
            Analyze the following multi-artifact JVM diagnostic data:
            {{diagnosticContext}}

            The per-artifact contexts above are intentionally bounded. Additional curated retrieval and focused computation tools are available when you need more detail from a specific artifact.
            Use computeRelevantArtifactView first when a focused artifact summary is likely enough. Use fetchRelevantArtifactContext when the exact lines, neighboring section, or omitted slice matters.
            Good focused requests include:
            - GC log: dominant-window-summary, incident=dominant-pressure, recovery-summary, cause-distribution
            - JFR: execution-hotspots, runtime-incident, allocation-incident, retention-incident, start=<time>,end=<time>
            - thread dump: deadlock-summary, blocked-clusters, thread=<name>
            - hs_err: crash-summary, section=problematic-frame, section=current-thread
            - NMT or heap or pmap: metaspace-summary, retention-families, resident-summary
            - container memory or OOM evidence: pressure-summary, budget-summary, kernel-summary, pod-summary

            Rules:
            1. Synthesize the incident from the cross-artifact signal summary, the per-artifact bounded contexts, and specialist observations.
            2. If artifact coverage indicates omitted detail or you need more detail from a particular artifact to resolve a contradiction or verify a hypothesis, you must use computeRelevantArtifactView or fetchRelevantArtifactContext instead of guessing. Prefer computeRelevantArtifactView when a compact focused summary is enough. Use fetchRelevantArtifactContext when exact lines or neighboring detail matters. A blank selector returns the next omitted slice for that artifact. Use sliceId=<id> to reopen a specific slice, page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>, and if a retrieval result says More available: true for relevant context, continue expanding until the uncertainty is resolved or the tool budget is exhausted.
            3. Explain the strongest cross-artifact evidence and where the interpretation is uncertain or limited.
            3a. Use crossArtifactTiming when present to distinguish true time overlap from simple coexistence. If it says NO_SHARED_CLOCK or UNTIMED_COMPANION, do not describe those artifacts as precisely time-aligned.
            4. Highlight the highest-value next actions for the user.
            5. Do not invent timestamps, causes, or relationships that are not supported by the diagnostic data.
            6. If the tool budget is exhausted and uncertainty remains, say so clearly.
            7. Never refer to the diagnostics as a packet, payload, prompt, parser output, assessor output, evidence anchors, traceability, or supervisor trace. Refer directly to the GC log, JFR recording, thread dump, hs_err log, NMT output, heap histogram, pmap output, container-memory data, OOM evidence, or the combined diagnostics instead.
            8. Structure the response with these exact plain-text section labels on separate lines: Summary:, Key metrics:, Likely issues:, Recommended actions:, Next steps:
            9. Keep the response concise, practical, and human-friendly for someone actively troubleshooting the JVM.
            10. Do not use markdown tables, code fences, or extra text before or after the response.
            """)
    String analyze(@V("diagnosticContext") String diagnosticContext);
}
