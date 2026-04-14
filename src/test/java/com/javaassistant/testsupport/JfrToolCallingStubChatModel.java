package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

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
        String prompt = StubChatModelSupport.renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = StubChatModelSupport.toolResults(chatRequest);

        if (toolResults.isEmpty()) {
            if (prompt.contains(ANALYZE_JFR_PROMPT)) {
                ToolSpecification fetchSpec = StubChatModelSupport.toolSpecification(chatRequest, FETCH_TOOL_NAME);
                ToolSpecification computeSpec = StubChatModelSupport.toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
                if (isComparisonContext(prompt)) {
                    return StubChatModelSupport.toolRequestResponse(
                        StubChatModelSupport.toolRequest(
                            "jfr-compare-fetch-current-hotspot",
                            FETCH_TOOL_NAME,
                            fetchSpec,
                            "current",
                            "hotspot=checkoutService"
                        ),
                        StubChatModelSupport.toolRequest(
                            "jfr-compare-compute-current-execution",
                            COMPUTE_TOOL_NAME,
                            computeSpec,
                            "current",
                            "execution-hotspots"
                        ),
                        StubChatModelSupport.toolRequest(
                            "jfr-compare-compute-baseline-execution",
                            COMPUTE_TOOL_NAME,
                            computeSpec,
                            "baseline",
                            "execution-hotspots"
                        )
                    );
                }
                return StubChatModelSupport.toolRequestResponse(
                    StubChatModelSupport.toolRequest("jfr-fetch-hotspot", FETCH_TOOL_NAME, fetchSpec, "", "hotspot=checkoutService"),
                    StubChatModelSupport.toolRequest("jfr-compute-execution", COMPUTE_TOOL_NAME, computeSpec, "", "execution-hotspots")
                );
            }

            return StubChatModelSupport.textResponse("");
        }

        return StubChatModelSupport.textResponse(finalNarrative(prompt, toolResults));
    }

    public List<String> prompts() {
        return prompts;
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

}
