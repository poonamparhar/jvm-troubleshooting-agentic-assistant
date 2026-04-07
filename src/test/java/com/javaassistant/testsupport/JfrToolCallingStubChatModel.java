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
 * Deterministic JFR chat stub that simulates hotspot-focused retrieval for both single-artifact and compare flows.
 */
public class JfrToolCallingStubChatModel implements ChatModel {

    private static final String ANALYZE_JFR_PROMPT = "Analyze the following Java Flight Recorder diagnostic data:";
    private static final String BASELINE_ARTIFACT_MARKER = "BASELINE_ARTIFACT";
    private static final String CURRENT_ARTIFACT_MARKER = "CURRENT_ARTIFACT";
    private static final String FETCH_TOOL_NAME = "fetchJfrContext";
    private static final String COMPUTE_TOOL_NAME = "computeJfrView";

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
            if (prompt.contains(ANALYZE_JFR_PROMPT)) {
                ToolSpecification fetchSpec = toolSpecification(chatRequest, FETCH_TOOL_NAME);
                ToolSpecification computeSpec = toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
                if (isComparisonContext(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("jfr-compare-fetch-current-hotspot")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "current", "hotspot=checkoutService"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("jfr-compare-compute-current-execution")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "current", "execution-hotspots"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("jfr-compare-compute-baseline-execution")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "baseline", "execution-hotspots"))
                                .build()
                        )))
                        .build();
                }
                return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(List.of(
                        ToolExecutionRequest.builder()
                            .id("jfr-fetch-hotspot")
                            .name(FETCH_TOOL_NAME)
                            .arguments(argumentsJson(fetchSpec, "", "hotspot=checkoutService"))
                            .build(),
                        ToolExecutionRequest.builder()
                            .id("jfr-compute-execution")
                            .name(COMPUTE_TOOL_NAME)
                            .arguments(argumentsJson(computeSpec, "", "execution-hotspots"))
                            .build()
                    )))
                    .build();
            }

            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(""))
                .build();
        }

        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(finalNarrative(prompt, toolResults)))
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

    private String finalNarrative(String prompt, List<ToolExecutionResultMessage> toolResults) {
        boolean compareMode = isComparisonContext(prompt);
        boolean sawHotspotContext = toolResults.stream().anyMatch(result -> FETCH_TOOL_NAME.equals(result.toolName()));
        long executionSummaryCount = toolResults.stream().filter(result -> COMPUTE_TOOL_NAME.equals(result.toolName())).count();

        if (compareMode && sawHotspotContext && executionSummaryCount >= 2L) {
            return """
                Summary:
                Compared with the baseline, the newer recording shifts decisively toward the checkout execution hotspot and carries more surrounding runtime pressure.
                Key metrics:
                - dominantHotspot: checkoutService
                - hotspotShift: report path to checkout path
                - comparedRecordings: baseline and current
                - hotspotConfidence: high
                Likely issues:
                - The current slowdown is concentrating in the checkout path more than it did in the baseline recording.
                - The shifted hotspot is likely amplifying contention, waits, or downstream latency rather than representing isolated CPU work alone.
                Recommended actions:
                1. Inspect the checkout path and its dependent waits first, because that is the clearest regression lead in the newer recording.
                2. Compare the checkout hotspot with thread dumps, request metrics, or downstream timing from the same interval before tuning the JVM broadly.
                """;
        }

        if (!compareMode && sawHotspotContext && executionSummaryCount >= 1L) {
            return """
                Summary:
                The recording points to a concentrated checkout execution hotspot rather than broad JVM-wide contention.
                Key metrics:
                - dominantHotspot: checkoutService
                - hotspotType: execution
                - hotspotConfidence: high
                - correlatedSignals: contention and wait activity present
                Likely issues:
                - CPU or wall-clock time is concentrating in the checkout path enough to explain the observed slowdown.
                - The hotspot is likely amplified by surrounding waits or blocking rather than pure compute alone.
                Recommended actions:
                1. Inspect the checkout path and its dependent waits together instead of tuning the JVM in isolation.
                2. Capture a second JFR during the same symptom window if you need a longer view of whether the hotspot shifts over time.
                """;
        }

        if (compareMode) {
            return """
                Summary:
                The comparison still suggests a hotspot shift, but the targeted baseline or current expansion was incomplete.
                Key metrics:
                - hotspotShiftSignals: partial
                - comparisonConfidence: limited
                Likely issues:
                - The newer recording appears hotter than the baseline, but the focused compare context is still incomplete.
                Recommended actions:
                1. Re-fetch the current hotspot-specific context.
                2. Recompute both current and baseline hotspot views.
                """;
        }

        return """
            Summary:
            The JFR recording still suggests a hotspot, but the focused hotspot expansion was incomplete.
            Key metrics:
            - hotspotSignals: partial
            - likelyImpact: elevated
            Likely issues:
            - The key hotspot still needs additional context before the diagnosis can be stated confidently.
            Recommended actions:
            1. Re-fetch the hotspot-specific context.
            2. Recompute the execution-hotspot view.
            """;
    }

    private boolean isComparisonContext(String prompt) {
        return prompt.contains(BASELINE_ARTIFACT_MARKER) && prompt.contains(CURRENT_ARTIFACT_MARKER);
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
