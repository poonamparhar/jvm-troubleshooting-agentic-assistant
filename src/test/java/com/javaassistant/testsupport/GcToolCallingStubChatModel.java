package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic GC-focused chat stub that simulates one round of tool calling before returning
 * a troubleshooting narrative for large GC logs that exceed first-pass context.
 */
public class GcToolCallingStubChatModel implements ChatModel {

    private static final String ANALYZE_GC_PROMPT = "Analyze the following GC log diagnostic data:";
    private static final String FETCH_TOOL_NAME = "fetchGcContext";
    private static final String COMPUTE_TOOL_NAME = "computeGcView";

    private final List<String> prompts = new ArrayList<>();

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = StubChatModelSupport.renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = StubChatModelSupport.toolResults(chatRequest);

        if (toolResults.isEmpty()) {
            if (prompt.contains(ANALYZE_GC_PROMPT)) {
                ToolSpecification fetchSpec = StubChatModelSupport.toolSpecification(chatRequest, FETCH_TOOL_NAME);
                ToolSpecification computeSpec = StubChatModelSupport.toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
                return StubChatModelSupport.toolRequestResponse(
                    StubChatModelSupport.toolRequest("gc-fetch-45", FETCH_TOOL_NAME, fetchSpec, "", "gcId=45"),
                    StubChatModelSupport.toolRequest("gc-failure-summary", COMPUTE_TOOL_NAME, computeSpec, "", "failure-summary")
                );
            }

            return StubChatModelSupport.textResponse("");
        }

        return StubChatModelSupport.textResponse(finalNarrative(toolResults));
    }

    public List<String> prompts() {
        return prompts;
    }

    private String finalNarrative(List<ToolExecutionResultMessage> toolResults) {
        boolean sawFullGcWindow = toolResults.stream()
            .anyMatch(result -> FETCH_TOOL_NAME.equals(result.toolName()));
        boolean sawFailureSummary = toolResults.stream()
            .anyMatch(result -> COMPUTE_TOOL_NAME.equals(result.toolName()));

        if (sawFullGcWindow && sawFailureSummary) {
            return """
                Summary:
                The large GC log shows a saturated heap that escalates from evacuation failure into repeated long full compactions.
                Key metrics:
                - fullGcCount: 19
                - maxFullGcPauseMs: 681.585
                - peakHeapOccupancyRatio: 0.999
                - fullCompactionAttemptCount: 1
                Likely issues:
                - The JVM is running with almost no recoverable heap headroom after GC.
                - Evacuation failure and full compaction attempts indicate the collector cannot maintain enough free regions for normal progress.
                Recommended actions:
                1. Treat this as active memory pressure and capture a heap histogram or heap dump if it is safe to do so.
                2. Review recent allocation spikes, cache growth, and workload changes around the long full-GC window.
                """;
        }

        return """
            Summary:
            The GC log still looks unhealthy, but the retrieved context was not sufficient to confirm the dominant failure mode.
            Key metrics:
            - retrievalSignals: partial
            - likelyPressure: high
            Likely issues:
            - The large GC log requires more focused context before the diagnosis can be stated confidently.
            Recommended actions:
            1. Retrieve the longest full-GC event window and the failure summary again.
            2. Compare the full-GC window with occupancy progression or allocation-stall context.
            """;
    }

}
