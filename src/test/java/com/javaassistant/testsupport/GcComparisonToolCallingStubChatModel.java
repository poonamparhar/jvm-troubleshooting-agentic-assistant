package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        String prompt = renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = chatRequest.messages().stream()
            .filter(ToolExecutionResultMessage.class::isInstance)
            .map(ToolExecutionResultMessage.class::cast)
            .toList();

        if (toolResults.isEmpty()) {
            if (!prompt.contains(ANALYZE_GC_PROMPT) || !prompt.contains(COMPARE_MODE)) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(""))
                    .build();
            }

            ToolSpecification fetchSpec = toolSpecification(chatRequest, FETCH_TOOL_NAME);
            ToolSpecification computeSpec = toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(List.of(
                    ToolExecutionRequest.builder()
                        .id("compare-gc-fetch-current-dominant")
                        .name(FETCH_TOOL_NAME)
                        .arguments(argumentsJson(fetchSpec, "current", "incident=dominant-pressure"))
                        .build(),
                    ToolExecutionRequest.builder()
                        .id("compare-gc-compute-current-window")
                        .name(COMPUTE_TOOL_NAME)
                        .arguments(argumentsJson(computeSpec, "current", "dominant-window-summary"))
                        .build(),
                    ToolExecutionRequest.builder()
                        .id("compare-gc-compute-baseline-window")
                        .name(COMPUTE_TOOL_NAME)
                        .arguments(argumentsJson(computeSpec, "baseline", "dominant-window-summary"))
                        .build()
                )))
                .build();
        }

        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(finalNarrative(toolResults)))
            .build();
    }

    public List<String> prompts() {
        return prompts;
    }

    private String renderPrompt(ChatRequest chatRequest) {
        return chatRequest.messages().stream()
            .map(this::renderMessage)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    private String renderMessage(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return "TOOL_RESULT[" + toolResult.toolName() + "]:\n" + toolResult.text();
        }
        return String.valueOf(message);
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

    private ToolSpecification toolSpecification(ChatRequest chatRequest, String toolName) {
        return chatRequest.toolSpecifications().stream()
            .filter(specification -> toolName.equals(specification.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing tool specification for " + toolName));
    }

    private String argumentsJson(ToolSpecification specification, String firstValue, String secondValue) {
        List<String> keys = specification.parameters() != null
            ? new ArrayList<>(specification.parameters().properties().keySet())
            : List.of();
        if (keys.size() < 2) {
            throw new IllegalStateException("Expected at least two tool parameters for " + specification.name() + " but saw " + keys);
        }

        return toJson(Map.of(
            keys.get(0), firstValue,
            keys.get(1), secondValue
        ));
    }

    private String toJson(Map<String, String> arguments) {
        return arguments.entrySet().stream()
            .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"")
            .collect(Collectors.joining(",", "{", "}"));
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
