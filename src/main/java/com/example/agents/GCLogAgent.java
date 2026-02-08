package com.example.agents;

import com.example.data.AnalysisResult;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface GCLogAgent {

    @Agent(name = "gcLogAgent", description = "Analyze GC logs to identify JVM garbage collection issues and provide recommendations.")
    @SystemMessage("""
        You are an expert in JVM garbage collection GC Logs analysis. Your role is to analyze only GC logs to identify performance issues, such as long pause times, low throughput, inappropriate collector choice, or memory configuration problems. Use the provided tools to extract key metrics from the log. Based on the analysis, identify specific issues with severity levels (HIGH, MEDIUM, LOW) and provide actionable recommendations with priority (HIGH, MEDIUM, LOW). Be precise and base your conclusions on standard JVM GC best practices.
        """)
    @UserMessage("""
            Analyze the following GC log content:
            {{logContent}}

            Steps:
            1. Use available tools to extract key metrics such as pause times, frequency, throughput, heap usage. If you don't find a relevant tool, read and interpret the log file yourself.
            2. Identify any issues like excessive pauses (>200ms), low throughput (<90%), heap sizing problems.
            3. Suggest specific tuning recommendations, e.g., adjust -XX:MaxGCPauseMillis, change collector if available in the Oracle JVM version.
            4. Rate your confidence in the analysis based on log completeness.
            5. Respond in plain English, no markdown, no extra text before or after the text response.
            """)
    String analyze(@V("logContent") String logContent);
}
