package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic GC comparison chat stub that simulates targeted baseline/current tool usage before
 * returning a troubleshooting comparison narrative.
 */
public class GcComparisonToolCallingStubChatModel implements ChatModel {

    private static final String ANALYZE_GC_PROMPT = "Analyze the following GC log diagnostic data:";
    private static final String COMPARE_MODE = "MODE: ARTIFACT_COMPARISON";
    private static final String FETCH_TOOL_NAME = "fetchGcContext";
    private static final String COMPUTE_TOOL_NAME = "computeGcView";

    private final List<String> prompts = new ArrayList<>();

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = StubChatModelSupport.renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = StubChatModelSupport.toolResults(chatRequest);

        if (toolResults.isEmpty()) {
            if (!prompt.contains(ANALYZE_GC_PROMPT) || !prompt.contains(COMPARE_MODE)) {
                return StubChatModelSupport.textResponse("");
            }

            ToolSpecification fetchSpec = StubChatModelSupport.toolSpecification(chatRequest, FETCH_TOOL_NAME);
            ToolSpecification computeSpec = StubChatModelSupport.toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
            return StubChatModelSupport.toolRequestResponse(
                StubChatModelSupport.toolRequest(
                    "compare-gc-fetch-current-dominant",
                    FETCH_TOOL_NAME,
                    fetchSpec,
                    "current",
                    "incident=dominant-pressure"
                ),
                StubChatModelSupport.toolRequest(
                    "compare-gc-compute-current-window",
                    COMPUTE_TOOL_NAME,
                    computeSpec,
                    "current",
                    "dominant-window-summary"
                ),
                StubChatModelSupport.toolRequest(
                    "compare-gc-compute-baseline-window",
                    COMPUTE_TOOL_NAME,
                    computeSpec,
                    "baseline",
                    "dominant-window-summary"
                )
            );
        }

        return StubChatModelSupport.textResponse(finalNarrative(toolResults));
    }

    public List<String> prompts() {
        return prompts;
    }

    private String finalNarrative(List<ToolExecutionResultMessage> toolResults) {
        long retrievalCount = toolResults.stream()
            .filter(result -> FETCH_TOOL_NAME.equals(result.toolName()))
            .count();
        long computationCount = toolResults.stream()
            .filter(result -> COMPUTE_TOOL_NAME.equals(result.toolName()))
            .count();

        if (retrievalCount >= 1 && computationCount >= 2) {
            return """
                Summary:
                Compared with the baseline, the current GC log has regressed into repeated long full compactions with almost no post-GC headroom.
                Key metrics:
                - baselineFullGcCount: 0
                - currentFullGcCount: 19
                - currentP95PauseMs: 600.889
                - currentMaxPauseMs: 681.585
                - currentPeakPostGcOccupancyRatio: 0.999
                Likely issues:
                - The current heap is effectively saturated, so G1 is escalating from evacuation distress into repeated full compactions instead of recovering usable space.
                - The baseline does not show the same full-GC or retained-occupancy pattern, which points to a regression in retained heap pressure, allocation pressure, or available headroom.
                Recommended actions:
                1. Compare heap size, live-data growth, cache behavior, and workload changes between the baseline and current captures before changing GC flags.
                2. Capture a heap histogram or heap dump if it is safe so you can identify what remains live through the current full-GC window.
                """;
        }

        return """
            Summary:
            The GC comparison still needs matched baseline and current context before a reliable regression statement can be made.
            Key metrics:
            - comparisonSignals: partial
            - likelyRegression: unresolved
            Likely issues:
            - The current-versus-baseline GC windows were not both available.
            Recommended actions:
            1. Retrieve the current dominant pressure window again.
            2. Recompute the dominant-window summary for both current and baseline.
            """;
    }

}
