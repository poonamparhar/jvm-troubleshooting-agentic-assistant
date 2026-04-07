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
                            .id("gc-fetch-45")
                            .name(FETCH_TOOL_NAME)
                            .arguments(argumentsJson(fetchSpec, "", "gcId=45"))
                            .build(),
                        ToolExecutionRequest.builder()
                            .id("gc-failure-summary")
                            .name(COMPUTE_TOOL_NAME)
                            .arguments(argumentsJson(computeSpec, "", "failure-summary"))
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
