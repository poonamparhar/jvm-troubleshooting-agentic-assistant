package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface NMTAgent {

    @Agent(name = "nmtAgent", description = "Analyze JVM Native Memory Tracking output to detect memory issues and provide recommendations.")
    @SystemMessage("""
        You are an expert in JVM Native Memory Tracking (NMT) analysis. Your role is to analyze NMT summary output to identify memory pressure and inefficiencies in key categories: metaspace, code cache, thread stacks, compiled code, and GC-related native memory. Use the provided tools to extract quantitative data and identify issues with severity levels (HIGH, MEDIUM, LOW). Focus on detecting memory leaks, over-allocation, and configuration problems. Provide actionable recommendations with priority (HIGH, MEDIUM, LOW) for tuning JVM parameters or application changes.
        """)
    @UserMessage("""
            Analyze the following JVM Native Memory Tracking output:
            {{nmtContent}}

            IMPORTANT: Check if this content contains "=== CURRENT DATA ===" marker.

            If the marker IS present (comparison analysis):
            - Parse both outputs before and after the marker
            - Calculate differences between the two snapshots
            - Focus on memory usage changes and trends

            If the marker is NOT present (single file analysis):
            - Analyze only the current NMT output
            - Do NOT attempt to compare or calculate differences
            - Focus on current memory health and utilization

            Steps for analysis:
            1. Use available tools to extract memory usage for Class (metaspace), Thread (stacks), Code (cache), and GC categories.
            2. Calculate utilization ratios and identify imbalances or excessive usage.
            3. Detect specific issues appropriate to the analysis type (current state vs changes over time).
            4. Assess overall memory health and potential for OutOfMemory errors.
            5. Provide specific recommendations based on findings.
            6. Rate your confidence in the analysis based on data completeness.
            7. Respond in plain English, no markdown, no extra text before or after the response.
            """)
    String analyze(@V("nmtContent") String nmtContent);
}
