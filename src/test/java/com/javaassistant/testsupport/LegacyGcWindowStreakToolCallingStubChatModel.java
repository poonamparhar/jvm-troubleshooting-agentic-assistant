package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic legacy-GC chat stub that exercises time-window and streak-oriented tool usage for
 * larger JDK 8 GC logs that exceed the first-pass context.
 */
public class LegacyGcWindowStreakToolCallingStubChatModel implements ChatModel {

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
            if (!prompt.contains(ANALYZE_GC_PROMPT)) {
                return StubChatModelSupport.textResponse("");
            }

            ToolSpecification fetchSpec = StubChatModelSupport.toolSpecification(chatRequest, FETCH_TOOL_NAME);
            ToolSpecification computeSpec = StubChatModelSupport.toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
            Scenario scenario = scenario(prompt);
            return StubChatModelSupport.toolRequestResponse(
                StubChatModelSupport.toolRequest(
                    "legacy-gc-window-fetch",
                    FETCH_TOOL_NAME,
                    fetchSpec,
                    "",
                    scenario.fetchRequest()
                ),
                StubChatModelSupport.toolRequest(
                    "legacy-gc-streak-compute",
                    COMPUTE_TOOL_NAME,
                    computeSpec,
                    "",
                    scenario.computeRequest()
                )
            );
        }

        return StubChatModelSupport.textResponse(finalNarrative(prompt, toolResults));
    }

    public List<String> prompts() {
        return prompts;
    }

    private String finalNarrative(String prompt, List<ToolExecutionResultMessage> toolResults) {
        boolean sawFetch = toolResults.stream().anyMatch(result -> FETCH_TOOL_NAME.equals(result.toolName()));
        boolean sawCompute = toolResults.stream().anyMatch(result -> COMPUTE_TOOL_NAME.equals(result.toolName()));

        if (!sawFetch || !sawCompute) {
            return """
                Summary:
                The legacy GC log still needs the requested incident window and streak summary before the pressure pattern can be explained confidently.
                Key metrics:
                - retrievalSignals: partial
                - likelyPressure: high
                Likely issues:
                - The most important incident interval was not fully expanded.
                Recommended actions:
                1. Retrieve the intended GC time window again.
                2. Recompute the collector-specific streak summary.
                """;
        }

        return switch (scenario(prompt)) {
            case LEGACY_G1 -> """
                Summary:
                The legacy G1 log enters a concentrated distress window from 24.0s to 32.6s where to-space exhaustion is followed by repeated G1 compaction pauses and the heap stays nearly full.
                Key metrics:
                - incidentWindow: 24.0s to 32.6s
                - fullGcCount: 4
                - maxFullGcPauseMs: 320.0
                - peakHeapOccupancyRatio: 0.977
                Likely issues:
                - G1 is running out of free regions during evacuation, which points to retained old data or humongous-region pressure.
                - The repeated compaction pauses in the same window show that the collector is not recovering enough headroom after the distress point.
                Recommended actions:
                1. Capture a heap histogram or heap dump if it is safe, focusing on retained old objects and humongous allocations.
                2. Review heap sizing, region pressure, and workload changes around the 24s distress interval.
                """;
            case LEGACY_CMS -> """
                Summary:
                The legacy CMS log shows a repeated failure cluster where concurrent mark work is quickly followed by concurrent-mode-failure full GCs.
                Key metrics:
                - incidentWindow: 24.0s to 24.9s
                - failureSignalCount: 54
                - longestConcurrentPhaseMs: 450.0
                - fullGcCount: 54
                Likely issues:
                - CMS is not completing concurrent reclamation before old-generation pressure forces stop-the-world fallback collections.
                - The dense failure cluster points to sustained old-generation retention or fragmentation instead of an isolated spike.
                Recommended actions:
                1. Capture a heap histogram and inspect old-generation retention, promotion spikes, and allocation bursts.
                2. Revisit CMS headroom and tuning so concurrent work has enough time to finish before allocation pressure reaches failure mode.
                """;
            case LEGACY_SERIAL -> """
                Summary:
                The legacy Serial log is trapped in a continuous full-GC streak from 155.0s to 161.0s, with allocation failures repeating while post-GC occupancy stays close to the heap limit.
                Key metrics:
                - incidentWindow: 155.0s to 161.0s
                - fullGcStreakCount: 161
                - p95PauseMs: 300.0
                - peakHeapOccupancyRatio: 0.988
                Likely issues:
                - The live-data set is too large for the configured heap, so Serial keeps falling back to stop-the-world full collections.
                - Repeated allocation-failure full GCs with little recovery headroom point to sustained retained-heap pressure.
                Recommended actions:
                1. Capture a heap histogram or heap dump if it is safe, and inspect the largest retained classes.
                2. Revisit heap sizing and whether Serial GC is an acceptable fit for this workload and latency profile.
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

    private enum Scenario {
        LEGACY_G1("start=24s,end=32.6s", "streak=distress"),
        LEGACY_CMS("start=24.0s,end=24.9s", "streak=failure"),
        LEGACY_SERIAL("start=155s,end=161s", "streak=full-gc");

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
