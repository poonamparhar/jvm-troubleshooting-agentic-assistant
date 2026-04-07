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
 * Deterministic correlation chat stub that exercises the bounded correlation tool surface.
 */
public class CorrelationToolCallingStubChatModel implements ChatModel {

    private static final String ANALYZE_CORRELATION_PROMPT = "Analyze the following multi-artifact JVM diagnostic data:";
    private static final String FETCH_TOOL_NAME = "fetchRelevantArtifactContext";
    private static final String COMPUTE_TOOL_NAME = "computeRelevantArtifactView";
    private static final String CONTAINER_PRESSURE_SAMPLE = "container_memory_pressure_snapshot.txt";
    private static final String KERNEL_OOM_SAMPLE = "kernel_oom_kill.log";
    private static final String POD_OOM_SAMPLE = "pod_oomkilled_describe.txt";
    private static final String GC_PRESSURE_SAMPLE = "g1_21_smallheap_fullgcs.log";
    private static final String NMT_SAMPLE = "java_nmt_summary_3391237.txt";
    private static final String PMAP_SAMPLE = "pmap_3391237.txt";
    private static final String HS_ERR_SAMPLE = "hs_err_pid2866366.log";
    private static final String JFR_GC_HEAP_JFR_SAMPLE = "correlate-jfr-gc-heap-recording.jfr";
    private static final String JFR_GC_HEAP_GC_SAMPLE = "correlate-jfr-gc-heap.log";
    private static final String JFR_GC_HEAP_HEAP_SAMPLE = "correlate-jfr-gc-heap.txt";
    private static final String JFR_THREAD_DUMP_JFR_SAMPLE = "correlate-jfr-thread-contention-recording.jfr";
    private static final String JFR_THREAD_DUMP_SAMPLE = "correlate-jfr-thread-contention.txt";

    private final List<String> prompts = new ArrayList<>();
    private final RoutingStubChatModel fallback = new RoutingStubChatModel();

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = chatRequest.messages().stream()
            .filter(ToolExecutionResultMessage.class::isInstance)
            .map(ToolExecutionResultMessage.class::cast)
            .toList();

        if (toolResults.isEmpty()) {
            if (prompt.contains(ANALYZE_CORRELATION_PROMPT)) {
                ToolSpecification computeSpec = toolSpecification(chatRequest, COMPUTE_TOOL_NAME);
                ToolSpecification fetchSpec = toolSpecification(chatRequest, FETCH_TOOL_NAME);
                if (isJfrGcHeapScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-jfr-runtime-incident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "runtime-incident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-gc-dominant-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-heap-linkedhashmap")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-3", "class=java.util.LinkedHashMap"))
                                .build()
                        )))
                        .build();
                }
                if (isJfrThreadDumpScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-jfr-runtime-incident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "runtime-incident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-thread-deadlock")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "deadlock-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-thread-worker")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "thread=Deadlock-Worker-1"))
                                .build()
                        )))
                        .build();
                }
                if (isContainerOomScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-container-pressure")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "pressure-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-kernel-oom")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "kernel-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-kernel-lines")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Killed process"))
                                .build()
                        )))
                        .build();
                }
                if (isGcMemoryPressureScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-gc-dominant-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-pmap-resident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "resident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-nmt-java-heap")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Java Heap"))
                                .build()
                        )))
                        .build();
                }
                if (isNativeOomScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-hs-err-crash")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "crash-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-nmt-internal")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Internal"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-pmap-resident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "resident-summary"))
                                .build()
                        )))
                        .build();
                }
            }
            return fallback.doChat(chatRequest);
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
        long computeCount = toolResults.stream().filter(result -> COMPUTE_TOOL_NAME.equals(result.toolName())).count();
        long fetchCount = toolResults.stream().filter(result -> FETCH_TOOL_NAME.equals(result.toolName())).count();

        if (isJfrGcHeapScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR recording, GC log, and heap histogram all point to the same retained-heap pressure incident, with LinkedHashMap-heavy retention lining up with a full-GC recovery window.",
                List.of(
                    "dominantRuntimeWindow: present",
                    "fullGcCount: 2",
                    "dominantHeapClass: java.util.LinkedHashMap",
                    "postGcHeadroom: low"
                ),
                List.of(
                    "The heap pressure looks driven by retained application objects rather than only a short-lived allocation burst.",
                    "The GC log is failing to recover enough headroom during the same period that the JFR recording and heap histogram highlight retained map growth."
                ),
                List.of(
                    "Inspect what owns the LinkedHashMap retention and whether it comes from a cache, session map, or request-scoped state that is not being released.",
                    "Capture a fresh heap dump or histogram during the same pressure window and compare it with the JFR retention path before changing GC settings alone."
                ),
                "During the next incident, capture JFR, GC logging, and a heap histogram close together so you can confirm whether the same retained map family keeps dominating the pressure window."
            );
        }

        if (isJfrThreadDumpScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR contention window and the thread dump describe the same lock-contention incident, and the thread dump shows it has already escalated into a Java-level deadlock affecting request threads.",
                List.of(
                    "deadlockedThreads: 2",
                    "blockedRequestThreads: 2",
                    "sharedThreadPool: http-nio-8080-exec",
                    "correlationConfidence: high"
                ),
                List.of(
                    "Request handling is stalling behind the same monitor contention that appears in the JFR runtime incident window.",
                    "This is no longer just transient contention: the deadlock means the application is unlikely to recover without intervention."
                ),
                List.of(
                    "Treat the deadlock as the immediate availability problem and inspect the lock ordering around the order-processing path before tuning the JVM.",
                    "Review synchronized scope and lock ordering for the blocked request flow, especially where OrderService.process is shared by worker and request threads."
                ),
                "Capture another thread dump and short JFR during the next stall to confirm whether the same executor threads and lock path reappear."
            );
        }

        if (isContainerOomScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The combined diagnostics show container memory pressure building into a kernel OOM kill of the JVM process.
                Key metrics:
                - memoryCurrentPctOfLimit: 96
                - oomEvents: 1
                - oomKillSignals: 2
                - lastTerminationReason: OOMKilled
                Likely issues:
                - The process is running too close to the container memory limit for the observed workload.
                - The failure is happening at the container or host boundary, not as a graceful in-JVM recovery path.
                Recommended actions:
                1. Treat the container memory pressure as the immediate constraint and compare it with heap, native-memory, and resident-set usage from the same incident.
                2. Review whether the container limit, sidecars, or native allocations are leaving too little headroom for the Java process.
                """;
        }

        if (isGcMemoryPressureScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The combined diagnostics point to broad JVM memory pressure, with a saturated GC window and resident-memory pressure outside the Java heap alone.
                Key metrics:
                - fullGcPressure: elevated
                - postGcHeadroom: low
                - residentMemoryPressure: elevated
                - correlationConfidence: high
                Likely issues:
                - The JVM is under sustained memory pressure rather than a short-lived spike.
                - The process likely needs both heap-side and native-memory investigation, because the resident footprint stays high while the GC log shows poor recovery.
                Recommended actions:
                1. Treat this as mixed JVM memory pressure and compare the dominant GC window with current native-memory and resident-set usage.
                2. Capture a heap histogram or dump if it is safe, then review NMT or pmap to determine whether retained heap, native growth, or both are driving the incident.
                """;
        }

        if (isNativeOomScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The crash diagnostics point to native memory exhaustion building into a fatal JVM crash under memory distress.
                Key metrics:
                - nativeFailureSignals: present
                - residentMemoryPressure: elevated
                - crashEvidence: present
                - correlationConfidence: high
                Likely issues:
                - The JVM is running into native-memory pressure severe enough to trigger a fatal allocation failure.
                - The crash should be treated as part of the same memory incident, not as an isolated code-path failure.
                Recommended actions:
                1. Investigate native-memory consumers first, including thread stacks, direct buffers, code cache, and any non-Java allocations visible in NMT or pmap.
                2. Review the crash context and surrounding memory footprint together before changing heap sizing alone.
                """;
        }

        return """
            Summary:
            The multi-artifact picture still suggests memory pressure, but the focused correlation context is incomplete.
            Key metrics:
            - correlatedSignals: partial
            - confidence: limited
            Likely issues:
            - The incident likely crosses the container boundary, but the focused correlation evidence is still incomplete.
            Recommended actions:
            1. Recompute the container pressure and kernel OOM summaries.
            2. Re-fetch the exact kernel lines that show which process was killed.
            """;
    }

    private boolean isContainerOomScenario(String prompt) {
        return prompt.contains(CONTAINER_PRESSURE_SAMPLE)
            && prompt.contains(KERNEL_OOM_SAMPLE)
            && prompt.contains(POD_OOM_SAMPLE);
    }

    private boolean isJfrGcHeapScenario(String prompt) {
        return prompt.contains(JFR_GC_HEAP_JFR_SAMPLE)
            && prompt.contains(JFR_GC_HEAP_GC_SAMPLE)
            && prompt.contains(JFR_GC_HEAP_HEAP_SAMPLE);
    }

    private boolean isJfrThreadDumpScenario(String prompt) {
        return prompt.contains(JFR_THREAD_DUMP_JFR_SAMPLE)
            && prompt.contains(JFR_THREAD_DUMP_SAMPLE);
    }

    private boolean isGcMemoryPressureScenario(String prompt) {
        return prompt.contains(GC_PRESSURE_SAMPLE)
            && prompt.contains(NMT_SAMPLE)
            && prompt.contains(PMAP_SAMPLE);
    }

    private boolean isNativeOomScenario(String prompt) {
        return prompt.contains(HS_ERR_SAMPLE)
            && prompt.contains(NMT_SAMPLE)
            && prompt.contains(PMAP_SAMPLE);
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

    private String structuredNarrative(
        String summary,
        List<String> metrics,
        List<String> likelyIssues,
        List<String> recommendedActions,
        String nextSteps
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("Summary:\n").append(summary).append('\n');
        builder.append("Key metrics:\n");
        for (String metric : metrics) {
            builder.append("- ").append(metric).append('\n');
        }
        builder.append("Likely issues:\n");
        for (String issue : likelyIssues) {
            builder.append("- ").append(issue).append('\n');
        }
        builder.append("Recommended actions:\n");
        int index = 1;
        for (String action : recommendedActions) {
            builder.append(index++).append(". ").append(action).append('\n');
        }
        return builder.toString();
    }
}
