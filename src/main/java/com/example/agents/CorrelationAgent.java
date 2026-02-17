package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
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

    @Agent(name = "correlationAgent", description = "Correlate diagnostic data from multiple JVM sources, mapping information to timestamps for integrated analysis.")
    @SystemMessage("""
        You are an expert in correlating JVM diagnostic data across multiple types. Your role is to analyze provided data from different sources (e.g., GC logs, NMT, pmap, heap histograms), map information to timestamps where available, and identify correlations such as memory usage changes during GC events, heap growth patterns, or native memory pressures. Use any available tools to extract and correlate quantitative data. Focus on detecting cross-source issues like memory leaks, GC inefficiencies, or configuration problems, and provide actionable recommendations with priority (HIGH, MEDIUM, LOW).
        """)
    @UserMessage("""
            Analyze the following combined diagnostic data from multiple sources:
            {{combinedContent}}

            Each section is marked with === FILE: path TYPE: type === followed by its content.

            Steps:
            1. Parse each section by type and extract key information, including timestamps if present.
            2. Identify correlations across data types, e.g., GC pauses aligning with memory spikes in NMT or pmap, or heap growth in histograms.
            3. Detect integrated issues like memory leaks evidenced by growing heaps and high native memory, or GC tuning needs based on correlated metrics.
            4. Assess overall JVM health from the combined perspective.
            5. Provide specific recommendations for tuning (e.g., heap sizes, GC parameters) or further investigation.
            6. Rate your confidence in the correlation analysis based on data completeness and timestamp availability.
            7. Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("combinedContent") String combinedContent);
}
