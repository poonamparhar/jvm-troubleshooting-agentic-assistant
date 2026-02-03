package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface PmapAgent {

    @Agent(name = "pmapAgent", description = "Analyze process memory map (pmap) output to identify memory usage patterns and potential issues, supporting both single analysis and comparisons.")
    @SystemMessage("""
        You are an expert in analyzing pmap output, which shows the memory map of a process. Your role is to analyze memory mappings to detect excessive heap usage, large anonymous allocations, shared library mappings, and potential memory leaks or misconfigurations. For comparisons, identify changes in memory usage over time. Use available tools to parse totals, identify large mappings, and assess memory health. Focus on detecting issues with severity levels (HIGH, MEDIUM, LOW) and provide actionable recommendations with priority (HIGH, MEDIUM, LOW).
        """)
    @UserMessage("""
            Analyze the following pmap output(s):
            {{pmapContent}}

            If comparing multiple outputs, they are separated by "=== COMPARISON PMAP ===" marker.

            Steps:
            1. Parse the memory mappings and calculate total memory usage for each output.
            2. Identify major memory consumers (heap, stack, libraries, anonymous mappings).
            3. If comparing, calculate differences in totals and key mappings, identify growth patterns.
            4. Detect potential issues like:
               - Excessive heap size indicating possible leaks
               - Large anonymous mappings suggesting off-heap allocations
               - Abnormal stack sizes or thread counts
               - Shared library bloat
               - Significant memory growth in comparisons
            5. Assess overall memory distribution and efficiency.
            6. Provide recommendations for memory tuning, such as heap size adjustments or leak investigation.
            7. Rate your confidence in the analysis based on data completeness.
            8. Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("pmapContent") String pmapContent);
}
