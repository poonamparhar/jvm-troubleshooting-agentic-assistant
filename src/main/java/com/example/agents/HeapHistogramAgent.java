package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
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

    @Agent(name = "heapHistogramAgent", description = "Analyze JVM heap histograms for single analysis or comparisons to identify memory usage patterns and leaks.")
    @SystemMessage("""
        You are an expert in JVM heap analysis and memory leak detection. Your role is to analyze heap histograms to identify memory usage patterns and potential leaks. Use the provided tools to parse histograms and identify problematic classes.

        Focus on:
        - Classes with high memory consumption
        - Collection classes holding large numbers of objects
        - Cache implementations and their memory usage
        - Custom application classes with unusual object counts
        - Overall heap distribution and usage patterns
        """)
    @UserMessage("""
            Analyze the following JVM heap histogram:
            {{histogramContent}}

            IMPORTANT: Check if this content contains "=== CURRENT HISTOGRAM ===" marker.

            If the marker IS present (comparison analysis):
            - Parse both baseline and current histograms before and after the marker
            - Calculate growth rates and differences for instances and bytes per class
            - Identify classes with significant growth that may indicate memory leaks
            - Focus on changes over time and leak patterns

            If the marker is NOT present (single file analysis):
            - Analyze only the current heap histogram
            - Do NOT attempt to compare or calculate differences/growth rates
            - Focus on current memory usage patterns and potential issues
            - Identify classes with high memory consumption or unusual patterns

            Steps for analysis:
            1. Parse the histogram(s) and identify major memory consumers by class
            2. Look for problematic patterns appropriate to the analysis type
            3. Calculate relevant statistics (current usage vs growth rates)
            4. Provide recommendations based on findings
            5. Rate your confidence in the analysis

            Do not mention any marker strings (for example, '=== CURRENT HISTOGRAM ===') in your response.
            Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("histogramContent") String histogramContent);
}
