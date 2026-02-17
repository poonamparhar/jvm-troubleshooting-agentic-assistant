package com.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface HSErrLogAgent {

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return MessageWindowChatMemory.withMaxMessages(20);
    }

    @Agent(name = "hsErrLogAgent", description = "Analyze JVM crash logs known as hs_err logs to identify crash causes and provide recommendations.")
    @SystemMessage("""
        You are an expert in JVM crash hs_err log file analysis. Your role is to analyze only hs_err crash logs to identify the root cause of JVM crashes, such as OutOfMemoryError, segmentation faults, or other fatal errors. Extract key information including JVM version, memory configuration, thread stacks, heap usage, and system details. Based on the analysis, identify specific issues with severity levels (HIGH, MEDIUM, LOW) and provide actionable recommendations with priority (HIGH, MEDIUM, LOW) to prevent similar crashes. Be precise and base your conclusions on JVM crash analysis best practices.
        """)
    @UserMessage("""
            Analyze the following JVM crash hs_err log content:
            {{logContent}}

            Steps:
            1. Identify the crash type (OOM, segfault, etc.) and immediate cause from the summary section.
            2. Extract key JVM information: version, VM type, command line arguments, memory settings.
            3. Analyze memory usage: heap size, metaspace, GC history if available.
            4. Examine the stack trace to identify the problematic code path.
            5. Check system information and thread details for additional context.
            6. Identify potential root causes like memory leaks, heap sizing issues, or code problems.
            7. Provide specific recommendations to prevent this crash, such as adjusting memory settings, code fixes, or JVM tuning.
            8. Rate your confidence in the analysis based on log completeness.
            9. Respond in plain English, no markdown, no extra text before or after the text response.
            """)
    String analyze(@V("logContent") String logContent);
}
