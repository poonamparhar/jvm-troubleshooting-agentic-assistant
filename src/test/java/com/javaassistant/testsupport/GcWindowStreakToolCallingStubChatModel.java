package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic GC chat stub that simulates time-window and streak-focused tool usage.
 */
public class GcWindowStreakToolCallingStubChatModel implements ChatModel {

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
                    StubChatModelSupport.toolRequest("gc-window-1", FETCH_TOOL_NAME, fetchSpec, "", "start=6.6s,end=7.35s"),
                    StubChatModelSupport.toolRequest("gc-distress-streak", COMPUTE_TOOL_NAME, computeSpec, "", "streak=distress")
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
        boolean sawWindow = toolResults.stream().anyMatch(result -> FETCH_TOOL_NAME.equals(result.toolName()));
        boolean sawDistressSummary = toolResults.stream().anyMatch(result -> COMPUTE_TOOL_NAME.equals(result.toolName()));

        if (sawWindow && sawDistressSummary) {
            return """
                Summary:
                The GC log enters a dense distress interval where repeated full compactions keep the heap pinned near capacity.
                Key metrics:
                - incidentWindow: 6.6s to 7.35s
                - maxPauseMs: 681.585
                - fullGcCount: 19
                - distressClusterSignals: elevated
                Likely issues:
                - The JVM is spending a concentrated stretch of time in repeated stop-the-world recovery work instead of regaining usable headroom.
                - The distress cluster suggests sustained allocation or retention pressure rather than a single isolated pause.
                Recommended actions:
                1. Capture a heap histogram or heap dump if it is safe, with special attention to data retained through the longest full-GC window.
                2. Compare the distress interval with workload, cache, or allocation spikes to identify what changed before the cluster formed.
                """;
        }

        return """
            Summary:
            The GC log still looks unhealthy, but the incident window and streak summary were not both available.
            Key metrics:
            - retrievalSignals: partial
            - likelyPressure: high
            Likely issues:
            - The most important GC distress interval still needs clearer context.
            Recommended actions:
            1. Retrieve the hottest GC time window again.
            2. Recompute the distress streak summary.
            """;
    }

}
