package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface PmapAgent {

    @Agent(name = "pmapAgent", description = "Analyze process memory map (pmap) output to identify memory usage patterns and potential issues, supporting both single analysis and comparisons.")
    @SystemMessage("""
        You are an expert in analyzing pmap output, which shows the memory map of a process. Your role is to analyze memory mappings to detect excessive heap usage, large anonymous allocations, shared library mappings, and potential memory leaks or misconfigurations. Use available tools to parse totals, identify large mappings, and assess memory health. Focus on detecting issues with severity levels (HIGH, MEDIUM, LOW) and provide actionable recommendations with priority (HIGH, MEDIUM, LOW).
        """)
    @UserMessage("""
            Analyze the following pmap output:
            {{pmapContent}}

            IMPORTANT: Check if this content contains "=== COMPARISON PMAP ===" marker.

            If the marker IS present (comparison analysis):
            - Parse both memory mappings before and after the marker
            - Calculate differences in totals and key mappings
            - Identify significant growth patterns and memory increases
            - Focus on changes over time

            If the marker is NOT present (single file analysis):
            - Analyze only the current memory mapping
            - Do NOT attempt to compare or calculate differences
            - Focus on current memory health and usage patterns

            Steps for both cases:
            1. Parse the memory mappings and calculate total memory usage.
            2. Identify major memory consumers (heap, stack, libraries, anonymous mappings).
            3. Detect potential issues appropriate to the analysis type.
            4. Provide recommendations based on findings.
            5. Rate your confidence in the analysis.

            Do not mention any marker strings (for example, '=== COMPARISON PMAP ===') in your response.
            Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("pmapContent") String pmapContent);
}
