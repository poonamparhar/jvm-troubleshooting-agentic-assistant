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
 * Deterministic GC chat stub that simulates time-window and streak-focused tool usage.
 */
public class GcWindowStreakToolCallingStubChatModel implements ChatModel {

    private static final String ANALYZE_GC_PROMPT = "Analyze the following GC log diagnostic data:";
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
            if (prompt.contains(ANALYZE_GC_PROMPT)) {
                ToolSpecification fetchSpec = toolSpecification(chatRequest, FETCH_TOOL_NAME);
                ToolSpecification computeSpec = toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
                return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(List.of(
                        ToolExecutionRequest.builder()
                            .id("gc-window-1")
                            .name(FETCH_TOOL_NAME)
                            .arguments(argumentsJson(fetchSpec, "", "start=6.6s,end=7.35s"))
                            .build(),
                        ToolExecutionRequest.builder()
                            .id("gc-distress-streak")
                            .name(COMPUTE_TOOL_NAME)
                            .arguments(argumentsJson(computeSpec, "", "streak=distress"))
                            .build()
                    )))
                    .build();
            }

            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(""))
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
