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
 * Deterministic legacy-GC chat stub that simulates one round of collector-aware tool usage before
 * returning a troubleshooting narrative for larger JDK 8 GC logs that exceed first-pass context.
 */
public class LegacyGcToolCallingStubChatModel implements ChatModel {

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
            if (!prompt.contains(ANALYZE_GC_PROMPT)) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(""))
                    .build();
            }

            ToolSpecification fetchSpec = toolSpecification(chatRequest, FETCH_TOOL_NAME);
            ToolSpecification computeSpec = toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
            Scenario scenario = scenario(prompt);
            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(List.of(
                    ToolExecutionRequest.builder()
                        .id("legacy-gc-fetch")
                        .name(FETCH_TOOL_NAME)
                        .arguments(argumentsJson(fetchSpec, "", scenario.fetchRequest()))
                        .build(),
                    ToolExecutionRequest.builder()
                        .id("legacy-gc-compute")
                        .name(COMPUTE_TOOL_NAME)
                        .arguments(argumentsJson(computeSpec, "", scenario.computeRequest()))
                        .build()
                )))
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
        boolean sawFetch = toolResults.stream().anyMatch(result -> FETCH_TOOL_NAME.equals(result.toolName()));
        boolean sawCompute = toolResults.stream().anyMatch(result -> COMPUTE_TOOL_NAME.equals(result.toolName()));

        if (!sawFetch || !sawCompute) {
            return """
                Summary:
                The legacy GC log still needs more focused context before the failure mode can be stated confidently.
                Key metrics:
                - retrievalSignals: partial
                - likelyPressure: high
                Likely issues:
                - The available tool results did not cover the most important pressure signals yet.
                Recommended actions:
                1. Retrieve the dominant incident window again.
                2. Recompute the collector-specific summary that best explains the pressure pattern.
                Next steps:
                Re-run the analysis after the missing legacy GC context is available.
                """;
        }

        return switch (scenario(prompt)) {
            case LEGACY_G1 -> """
                Summary:
                The legacy G1 log shows evacuation distress from to-space exhaustion followed by repeated G1 compaction pauses with the heap still near full.
                Key metrics:
                - toSpaceExhaustedCount: 1
                - fullGcCount: 4
                - maxFullGcPauseMs: 320.0
                - peakHeapOccupancyRatio: 0.977
                Likely issues:
                - G1 is running out of free regions during evacuation, which points to retained old data or humongous-region pressure.
                - The follow-on compaction pauses suggest the collector is recovering too little memory after the distress point.
                Recommended actions:
                1. Capture a heap histogram and inspect large retained objects, caches, or humongous allocations.
                2. Review heap sizing, region pressure, and workload changes around the to-space exhaustion event.
                Next steps:
                Correlate this legacy G1 incident with heap histogram or JFR allocation data so you can confirm whether the fix reduces retained occupancy after compaction.
                """;
            case LEGACY_CMS -> """
                Summary:
                The legacy CMS log shows repeated concurrent-mode failures, so CMS is not finishing concurrent reclamation before old-generation pressure forces full GCs.
                Key metrics:
                - concurrentModeFailureCount: 54
                - longestConcurrentPhaseMs: 450.0
                - fullGcCount: 54
                - peakHeapOccupancyRatio: 0.982
                Likely issues:
                - Old-generation occupancy stays too high for CMS to keep ahead of allocation and promotion pressure.
                - The repeated fallback full GCs suggest fragmentation or sustained live-data retention in old gen.
                Recommended actions:
                1. Capture a heap histogram and review old-generation retention, promotion spikes, and allocation bursts.
                2. Review CMS sizing and tuning, especially old-generation headroom and whether CMS has enough time to finish concurrent work.
                Next steps:
                Correlate the CMS failures with heap histogram, NMT, or pmap data so you can distinguish heap retention from mixed native pressure.
                """;
            case LEGACY_SERIAL -> """
                Summary:
                The legacy Serial log shows repeated full GCs on allocation failure, with stop-the-world pauses staying elevated while the heap remains almost full after collection.
                Key metrics:
                - fullGcCount: 161
                - p95PauseMs: 300.0
                - averagePauseMs: 259.9
                - peakHeapOccupancyRatio: 0.988
                Likely issues:
                - The heap is too tight for the live-data set, so Serial keeps falling back to full stop-the-world collections.
                - Post-GC occupancy staying near the heap limit means these collections are not reclaiming enough headroom to stabilize the application.
                Recommended actions:
                1. Capture a heap histogram or heap dump if it is safe, and review the largest retained classes.
                2. Revisit heap sizing and whether Serial GC is an acceptable fit for this workload and latency profile.
                Next steps:
                Correlate the repeated allocation-failure full GCs with heap histogram, NMT, or pmap data so you can confirm whether retained heap data is the dominant constraint.
                """;
        };
    }

    private Scenario scenario(String prompt) {
        if (prompt.contains("collector: CMS")) {
            return Scenario.LEGACY_CMS;
        }
        if (prompt.contains("collector: Serial")) {
            return Scenario.LEGACY_SERIAL;
        }
        return Scenario.LEGACY_G1;
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

    private enum Scenario {
        LEGACY_G1("signalType=TO_SPACE_EXHAUSTED", "cause=G1 Compaction Pause"),
        LEGACY_CMS("signalType=CONCURRENT_MODE_FAILURE", "phaseKind=CONCURRENT"),
        LEGACY_SERIAL("cause=Allocation Failure", "recovery-summary");

        private final String fetchRequest;
        private final String computeRequest;

        Scenario(String fetchRequest, String computeRequest) {
            this.fetchRequest = fetchRequest;
            this.computeRequest = computeRequest;
        }

        private String fetchRequest() {
            return fetchRequest;
        }

        private String computeRequest() {
            return computeRequest;
        }
    }
}
