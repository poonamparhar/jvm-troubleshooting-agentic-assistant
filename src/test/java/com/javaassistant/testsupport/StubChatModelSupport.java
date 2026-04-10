package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class StubChatModelSupport {

    private StubChatModelSupport() {
    }

    static ChatResponse textResponse(String responseText) {
        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(responseText))
            .build();
    }

    static ChatResponse toolRequestResponse(ToolExecutionRequest... requests) {
        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(Arrays.asList(requests)))
            .build();
    }

    static String renderPrompt(ChatRequest chatRequest) {
        return chatRequest.messages().stream()
            .map(StubChatModelSupport::renderMessage)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    static List<ToolExecutionResultMessage> toolResults(ChatRequest chatRequest) {
        return chatRequest.messages().stream()
            .filter(ToolExecutionResultMessage.class::isInstance)
            .map(ToolExecutionResultMessage.class::cast)
            .toList();
    }

    static ToolSpecification toolSpecification(ChatRequest chatRequest, String toolName) {
        return chatRequest.toolSpecifications().stream()
            .filter(specification -> toolName.equals(specification.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing tool specification for " + toolName));
    }

    static ToolExecutionRequest toolRequest(
        String requestId,
        String toolName,
        ToolSpecification specification,
        String firstValue,
        String secondValue
    ) {
        return ToolExecutionRequest.builder()
            .id(requestId)
            .name(toolName)
            .arguments(argumentsJson(specification, firstValue, secondValue))
            .build();
    }

    static String argumentsJson(ToolSpecification specification, String firstValue, String secondValue) {
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

    private static String renderMessage(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return "TOOL_RESULT[" + toolResult.toolName() + "]:\n" + toolResult.text();
        }
        return String.valueOf(message);
    }

    private static String toJson(Map<String, String> arguments) {
        return arguments.entrySet().stream()
            .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"")
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
