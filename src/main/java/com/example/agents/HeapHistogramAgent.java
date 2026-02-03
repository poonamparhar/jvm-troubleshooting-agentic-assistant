package com.example.agents;

import com.example.data.AnalysisResult;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface HeapHistogramAgent {

    @Agent(name = "heapHistogramAgent", description = "Analyze and compare JVM heap histograms to identify memory leaks and growth patterns.")
    @SystemMessage("""
        You are an expert in JVM heap analysis and memory leak detection. Your role is to analyze heap histogram comparisons to identify what is causing increased Java heap usage and report potential leak suspects. Use the provided tools to parse histograms, calculate growth statistics, and identify problematic classes with unusual accumulation patterns.

        Focus on:
        - Classes showing disproportionate instance/byte growth
        - Collection classes accumulating entries (HashMap, ArrayList, etc.)
        - Cache implementations holding onto objects
        - Custom application classes with unexpected growth
        - Overall heap growth trends and distribution changes
        """)
    @UserMessage("""
            Analyze the following JVM heap histogram comparison:
            {{histogramContent}}

            Steps:
            1. Parse the baseline and current histograms (separated by "=== CURRENT HISTOGRAM ===" marker)
            2. Compare the histograms to calculate growth rates for instances and bytes per class
            3. Identify classes with significant growth that may indicate memory leaks:
               - Instance growth > 50% with large absolute deltas
               - Byte growth > 100% indicating memory accumulation
               - Collection and cache classes with abnormal growth patterns
            4. Calculate total heap growth and analyze distribution changes
            5. Report potential leak suspects with specific evidence (growth rates, absolute changes)
            6. Provide recommendations for leak investigation:
               - Code review for the suspect classes
               - Heap dump analysis for detailed object graphs
               - Profiling to identify allocation hotspots
               - Potential fixes like clearing collections or fixing cache eviction
            7. Rate your confidence in the leak identification based on the comparison data
            8. Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("histogramContent") String histogramContent);
}
