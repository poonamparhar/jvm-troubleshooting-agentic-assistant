package com.javaassistant.testsupport;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic correlation chat stub that exercises the bounded correlation tool surface.
 */
public class CorrelationToolCallingStubChatModel implements ChatModel {

    public enum ScenarioProfile {
        JFR_GC_HEAP,
        JFR_THREAD_DUMP,
        CONTAINER_OOM,
        GENERATED_CONTAINER_BUDGET,
        GENERATED_HEAP_EXHAUSTION,
        GENERATED_JAVA_HEAP_SPACE_EXHAUSTION,
        MEMORY_PRESSURE,
        GENERATED_METASPACE,
        GENERATED_THREAD_GROWTH,
        GENERATED_NATIVE_THREAD_EXHAUSTION,
        GENERATED_COMPRESSED_CLASS_SPACE_OOM,
        GENERATED_CLASSLOADING_METASPACE,
        GENERATED_CODE_CACHE_FULL,
        GENERATED_DIRECT_BUFFER_NATIVE_LEAK,
        GENERATED_DIRECT_BUFFER_NATIVE_OOM,
        GENERATED_ACTIVE_NATIVE_GROWTH,
        GENERATED_RESERVED_COMMITTED_MISMATCH,
        GENERATED_G1_HUMONGOUS_PRESSURE,
        NATIVE_OOM
    }

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
    private static final String GENERATED_CONTAINER_BUDGET_SNAPSHOT_SAMPLE = "generated-container-budget-pressure-snapshot.txt";
    private static final String GENERATED_CONTAINER_BUDGET_GC_SAMPLE = "generated-container-budget-pressure-gc.log";
    private static final String GENERATED_CONTAINER_BUDGET_NMT_SAMPLE = "generated-container-budget-pressure-nmt.txt";
    private static final String GENERATED_HEAP_EXHAUSTION_JFR_SAMPLE = "generated-heap-exhaustion-recording.jfr";
    private static final String GENERATED_HEAP_EXHAUSTION_GC_SAMPLE = "generated-heap-exhaustion-gc.log";
    private static final String GENERATED_HEAP_EXHAUSTION_HEAP_SAMPLE = "generated-heap-exhaustion-heap.txt";
    private static final String GENERATED_JAVA_HEAP_SPACE_JFR_SAMPLE = "generated-java-heap-space-recording.jfr";
    private static final String GENERATED_JAVA_HEAP_SPACE_GC_SAMPLE = "generated-java-heap-space-gc.log";
    private static final String GENERATED_JAVA_HEAP_SPACE_HEAP_SAMPLE = "generated-java-heap-space-heap.txt";
    private static final String GENERATED_METASPACE_GC_SAMPLE = "generated-metaspace-pressure-gc.log";
    private static final String GENERATED_METASPACE_NMT_SAMPLE = "generated-metaspace-pressure-nmt.txt";
    private static final String GENERATED_METASPACE_PMAP_SAMPLE = "generated-metaspace-pressure-pmap.txt";
    private static final String GENERATED_THREAD_GROWTH_THREAD_DUMP_SAMPLE = "generated-thread-growth-thread-dump.txt";
    private static final String GENERATED_THREAD_GROWTH_NMT_SAMPLE = "generated-thread-growth-nmt.txt";
    private static final String GENERATED_NATIVE_THREAD_EXHAUSTION_THREAD_DUMP_SAMPLE = "generated-native-thread-exhaustion-thread-dump.txt";
    private static final String GENERATED_NATIVE_THREAD_EXHAUSTION_NMT_SAMPLE = "generated-native-thread-exhaustion-nmt.txt";
    private static final String GENERATED_NATIVE_THREAD_EXHAUSTION_HS_ERR_SAMPLE = "generated-native-thread-exhaustion-hs-err.log";
    private static final String GENERATED_COMPRESSED_CLASS_SPACE_NMT_SAMPLE = "generated-compressed-class-space-pressure-nmt.txt";
    private static final String GENERATED_COMPRESSED_CLASS_SPACE_HS_ERR_SAMPLE = "generated-compressed-class-space-pressure-hs-err.log";
    private static final String GENERATED_CLASSLOADING_JFR_SAMPLE = "generated-classloading-pressure-recording.jfr";
    private static final String GENERATED_CLASSLOADING_GC_SAMPLE = "generated-classloading-pressure-gc.log";
    private static final String GENERATED_CLASSLOADING_NMT_SAMPLE = "generated-classloading-pressure-nmt.txt";
    private static final String GENERATED_CODE_CACHE_JFR_SAMPLE = "generated-code-cache-pressure-recording.jfr";
    private static final String GENERATED_CODE_CACHE_NMT_SAMPLE = "generated-code-cache-pressure-nmt.txt";
    private static final String GENERATED_CODE_CACHE_HS_ERR_SAMPLE = "generated-code-cache-pressure-hs-err.log";
    private static final String GENERATED_DIRECT_BUFFER_JFR_SAMPLE = "generated-direct-buffer-pressure-recording.jfr";
    private static final String GENERATED_DIRECT_BUFFER_NMT_SAMPLE = "generated-direct-buffer-pressure-nmt.txt";
    private static final String GENERATED_DIRECT_BUFFER_PMAP_SAMPLE = "generated-direct-buffer-pressure-pmap.txt";
    private static final String GENERATED_DIRECT_BUFFER_HS_ERR_SAMPLE = "generated-direct-buffer-pressure-hs-err.log";
    private static final String GENERATED_ACTIVE_NATIVE_GROWTH_NMT_SAMPLE = "generated-active-native-growth-diff-nmt.txt";
    private static final String GENERATED_ACTIVE_NATIVE_GROWTH_PMAP_SAMPLE = "generated-active-native-growth-current-pmap.txt";
    private static final String GENERATED_RESERVED_MISMATCH_NMT_SAMPLE = "generated-reserved-committed-mismatch-current-nmt.txt";
    private static final String GENERATED_RESERVED_MISMATCH_PMAP_SAMPLE = "generated-reserved-committed-mismatch-current-pmap.txt";
    private static final String GENERATED_G1_HUMONGOUS_JFR_SAMPLE = "generated-g1-humongous-pressure-recording.jfr";
    private static final String GENERATED_G1_HUMONGOUS_GC_SAMPLE = "generated-g1-humongous-pressure-gc.log";
    private static final String GENERATED_G1_HUMONGOUS_HEAP_SAMPLE = "generated-g1-humongous-pressure-heap.txt";

    private final List<String> prompts = new ArrayList<>();
    private final RoutingStubChatModel fallback = new RoutingStubChatModel();
    private final ScenarioProfile scenarioProfile;

    public CorrelationToolCallingStubChatModel() {
        this(null);
    }

    public CorrelationToolCallingStubChatModel(ScenarioProfile scenarioProfile) {
        this.scenarioProfile = scenarioProfile;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = StubChatModelSupport.renderPrompt(chatRequest);
        prompts.add(prompt);

        List<ToolExecutionResultMessage> toolResults = StubChatModelSupport.toolResults(chatRequest);

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
                if (isGeneratedContainerBudgetScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-container-budget")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "budget-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-container-gc-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-container-nmt-threads")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "thread-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-container-nmt-heap")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-3", "pattern=Java Heap"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedHeapExhaustionScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-heap-runtime-incident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "runtime-incident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-heap-gc-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-heap-retention")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "retention-families"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-heap-oome")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=OutOfMemoryError"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedJavaHeapSpaceScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-java-heap-runtime-incident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "runtime-incident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-java-heap-gc-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-java-heap-retention")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "retention-families"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-java-heap-oome")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=OutOfMemoryError"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedG1HumongousScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-humongous-runtime-incident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "runtime-incident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-humongous-gc-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "humongous-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-humongous-retention")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "retention-families"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-humongous-gc-lines")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Humongous regions"))
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
                if (isGeneratedMetaspacePressureScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-gc-dominant-window")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "dominant-window-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-nmt-metaspace")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "metaspace-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-pmap-anon")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-3", "pattern=[ anon ]"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedActiveNativeGrowthScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-active-native-delta")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "delta-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-active-native-resident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "resident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-active-native-internal")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-1", "pattern=Internal"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedReservationMismatchScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-nmt-reservation")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "reservation-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-pmap-reservation")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "reservation-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-pmap-anon")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=[ anon ]"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedThreadGrowthScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-thread-pool")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "pool-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-nmt-thread-summary")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "thread-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-thread-pool")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-1", "pattern=http-worker"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedNativeThreadExhaustionScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-native-thread-pool")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "pool-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-native-thread-summary")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "thread-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-native-thread-crash-summary")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "crash-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-native-thread-hs-err")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-3", "pattern=unable to create new native thread"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedCompressedClassSpaceScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-class-space-nmt")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "class-space-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-class-space-hs-err")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "class-space-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-class-space-hs-err")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Compressed class space"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedClassLoadingMetaspaceScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-class-loading")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "class-loading-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-nmt-metaspace")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "metaspace-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-metadata-gc")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Metadata GC Threshold"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedCodeCacheFullScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-code-cache-jfr")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "code-cache-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-code-cache-nmt")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "code-cache-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-code-cache-hs-err")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "code-cache-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-code-cache-hs-err")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-3", "pattern=CodeCache"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedDirectBufferNativeLeakScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-allocation")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "allocation-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-native-delta")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "delta-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-resident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "resident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-direct-buffer-internal")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-2", "pattern=Internal"))
                                .build()
                        )))
                        .build();
                }
                if (isGeneratedDirectBufferNativeOomScenario(prompt)) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-allocation")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-1", "allocation-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-native-delta")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-2", "delta-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-resident")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-3", "resident-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-compute-generated-direct-buffer-crash")
                                .name(COMPUTE_TOOL_NAME)
                                .arguments(argumentsJson(computeSpec, "artifact-4", "crash-summary"))
                                .build(),
                            ToolExecutionRequest.builder()
                                .id("correlation-fetch-generated-direct-buffer-crash")
                                .name(FETCH_TOOL_NAME)
                                .arguments(argumentsJson(fetchSpec, "artifact-4", "pattern=DirectByteBuffer::reserveMemory"))
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

        return StubChatModelSupport.textResponse(finalNarrative(prompt, toolResults));
    }

    public List<String> prompts() {
        return prompts;
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

        if (isGeneratedContainerBudgetScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return """
                Summary:
                The generated container snapshot, GC log, and NMT output point to the same container-budget incident, with the JVM already consuming almost all available cgroup headroom before any kernel OOM kill occurs.
                Key metrics:
                - containerUsagePctOfLimit: 95
                - containerHighEvents: 96
                - fullGcCount: 3
                - peakPostGcHeapPct: 97
                - threadCount: 128
                - stackReservedMb: 128
                Likely issues:
                - The container memory limit is too close to the JVM's real operating footprint, so the process has very little room for heap recovery, native stacks, code cache, metaspace, and page-cache activity together.
                - Repeated G1 full compactions show the heap is already running with poor recovery, while NMT confirms a non-trivial native footprint from thread stacks and other JVM subsystems.
                Recommended actions:
                1. Treat the container limit and JVM sizing as one budget problem, and compare memory.max or memory.high with heap commitment, thread stacks, metaspace, and other native consumers before increasing only Xmx.
                2. Capture another container snapshot, NMT sample, and GC window from the same workload phase to confirm whether the headroom shortfall is steady-state sizing pressure or a workload spike.
                """;
        }

        if (isGeneratedHeapExhaustionScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR recording, GC log, and heap histogram all describe the same heap-exhaustion incident: retained heap stays critically high, full GCs recover too little space, and the log ends in `OutOfMemoryError: GC overhead limit exceeded`.",
                List.of(
                    "dominantRuntimeWindow: present",
                    "fullGcCount: 4",
                    "peakPostGcOccupancy: critical",
                    "dominantRetentionFamilies: java.util.LinkedHashMap, byte[]",
                    "terminalOome: GC overhead limit exceeded"
                ),
                List.of(
                    "This is retained-heap pressure rather than only a short-lived allocation spike, because the histogram and JFR retention signals both stay concentrated in the same families while full GCs reclaim very little space.",
                    "The generated bundle ends in GC overhead limit exceeded, and the same pressure pattern could also surface as Java heap space when live retained data leaves almost no recoverable headroom."
                ),
                List.of(
                    "Inspect the owners of the retained `LinkedHashMap` and `byte[]` families first, especially caches, request aggregation, session state, or buffering paths that can grow without a firm bound.",
                    "Capture a heap dump or repeated histograms from the same pressure window so you can confirm whether the same retained families dominate immediately before the terminal OutOfMemoryError.",
                    "Treat a larger heap as temporary breathing room only after you understand the retention path, because the current full-GC pattern suggests the application is carrying too much live data between collections."
                ),
                ""
            );
        }

        if (isGeneratedJavaHeapSpaceScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR recording, GC log, and heap histogram all describe the same heap-exhaustion incident: retained heap stays critically high, full GCs recover too little space, and the log ends in `OutOfMemoryError: Java heap space`.",
                List.of(
                    "dominantRuntimeWindow: present",
                    "fullGcCount: 4",
                    "peakPostGcOccupancy: critical",
                    "dominantRetentionFamilies: java.util.LinkedHashMap, byte[]",
                    "terminalOome: Java heap space"
                ),
                List.of(
                    "This is retained-heap pressure rather than only a short-lived allocation spike, because the histogram and JFR retention signals both stay concentrated in the same families while full GCs reclaim very little space.",
                    "This terminal variant shows the JVM running out of recoverable Java-heap headroom directly, which is consistent with live retained data consuming almost the whole heap."
                ),
                List.of(
                    "Inspect the owners of the retained `LinkedHashMap` and `byte[]` families first, especially caches, request aggregation, session state, or buffering paths that can grow without a firm bound.",
                    "Capture a heap dump or repeated histograms from the same pressure window so you can confirm whether the same retained families dominate immediately before the terminal OutOfMemoryError.",
                    "Treat a larger heap as temporary breathing room only after you understand the retention path, because the current full-GC pattern suggests the application is carrying too much live data between collections."
                ),
                ""
            );
        }

        if (isGeneratedG1HumongousScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR recording, GC log, and heap histogram all point to the same G1 humongous-pressure incident: large byte-array allocations and retained payloads are consuming humongous regions while post-GC occupancy stays near capacity.",
                List.of(
                    "dominantRuntimeWindow: present",
                    "peakHumongousAfterRegions: 226",
                    "humongousGrowthEvents: 4",
                    "peakPostGcOccupancy: near-capacity",
                    "dominantRetentionFamilies: byte[], java.lang.String"
                ),
                List.of(
                    "This is not just ordinary young-generation churn, because the GC log shows humongous-region growth while the JFR recording and heap histogram both keep pointing back to large retained payload objects.",
                    "The shared byte-array emphasis across JFR and the heap histogram strengthens the case that object shape and retention are reducing G1 free-region headroom."
                ),
                List.of(
                    "Inspect the owners of the dominant `byte[]` and payload-retention families first, especially caches, response assembly buffers, image or report batches, and any code path that batches large payloads in memory.",
                    "Capture a heap dump or repeated histograms around the same window so you can confirm whether the same large object family keeps occupying humongous regions.",
                    "Treat G1 region-size or heap-size tuning as secondary until you understand which large object path is driving the humongous-region growth."
                ),
                ""
            );
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

        if (isGeneratedMetaspacePressureScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The GC log, NMT snapshot, and pmap output all support the same metaspace-driven native-memory incident rather than a heap-only problem.
                Key metrics:
                - metaspaceTriggeredFullGcCount: 3
                - metaspaceUsedPctOfCommitted: 96
                - anonymousVirtualShare: dominant
                - correlationConfidence: high
                Likely issues:
                - Class metadata growth is forcing repeated metadata-triggered full GCs while committed metaspace is already nearly full.
                - Native address space is dominated by anonymous mappings, which supports the view that this is broader process memory pressure around the class-metadata footprint rather than only Java-heap saturation.
                Recommended actions:
                1. Investigate class-loader churn, dynamic class generation, and recent deployment or reload behavior before treating a larger metaspace limit as the main fix.
                2. Capture follow-up NMT or class-loader evidence from the same service window so you can confirm whether class metadata and related native mappings continue growing together.
                """;
        }

        if (isGeneratedActiveNativeGrowthScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The NMT diff and pmap snapshot both point to active native-memory growth, with committed native memory increasing materially and anonymous resident mappings already consuming real RAM.
                Key metrics:
                - committedNativeDeltaKb: +65536
                - dominantNativeDelta: Internal +61440
                - anonymousResidentKb: 491520
                - totalResidentKb: 495616
                - correlationConfidence: high
                Likely issues:
                - This is active off-heap or native-memory pressure rather than a reservation-only footprint, because committed memory and resident anonymous usage are both elevated.
                - The native growth is concentrated enough to justify investigating off-heap buffers, JNI/native-library allocations, or allocator-heavy internal subsystems before tuning heap settings.
                Recommended actions:
                1. Compare the dominant NMT deltas with the largest resident anonymous mappings to identify which off-heap subsystem is turning into resident memory.
                2. Capture another NMT diff and pmap snapshot from the same workload window to confirm whether the same committed categories and resident mappings keep growing.
                """;
        }

        if (isGeneratedThreadGrowthScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The thread dump and NMT output point to the same thread-pool expansion incident, with blocked request workers and a materially larger native thread-stack footprint.
                Key metrics:
                - affectedPool: http-worker
                - blockedPoolThreads: 3
                - liveThreadCount: 192
                - threadCountDelta: +96
                - stackReservedMb: 192
                Likely issues:
                - Request or worker threads are piling up behind a shared lock or downstream dependency instead of returning to an idle pool state.
                - Native memory use is rising with the thread footprint, so raising heap settings alone will not address the incident.
                Recommended actions:
                1. Inspect the `http-worker` pool backlog, thread-creation path, and the shared lock or dependency that is keeping those workers blocked.
                2. Review pool sizing, request concurrency, and stack sizing together, then capture a follow-up thread dump and NMT diff from the same window to confirm whether the same pool keeps growing.
                """;
        }

        if (isGeneratedReservationMismatchScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The NMT snapshot and pmap output both show a reservation-heavy native footprint, where large virtual or reserved ranges are not matched by comparable committed memory or resident RSS.
                Key metrics:
                - nonHeapReservedKb: 1146880
                - nonHeapCommittedKb: 91520
                - nonHeapCommitPct: 8.0
                - reservedGapKb: 4407296
                - totalRssKb: 81920
                Likely issues:
                - The apparent native footprint is being overstated by address-space reservations, so this is not strong evidence of active native RAM pressure or a leak by itself.
                - The more important follow-up question is whether committed native memory or RSS is also growing over time in the same categories and mappings.
                Recommended actions:
                1. Compare later NMT committed usage and pmap RSS before treating the total reserved footprint as active memory pressure.
                2. Focus on the categories and mappings whose committed or resident usage is actually rising, rather than only the largest reserved ranges.
                """;
        }

        if (isGeneratedNativeThreadExhaustionScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return """
                Summary:
                The thread dump, NMT diff, and hs_err log all describe the same native-thread exhaustion incident, with blocked request workers, materially higher thread-stack reservation, and the JVM failed to create another native thread.
                Key metrics:
                - affectedPool: http-worker
                - blockedPoolThreads: 3
                - liveThreadCount: 192
                - threadCountDelta: +96
                - stackReservedMb: 192
                Likely issues:
                - Request or worker threads are piling up behind a shared lock or slow dependency instead of draining, and the process is exhausting thread-creation headroom.
                - The immediate limit may be native stack headroom, an operating-system or container pid limit, or both, so heap tuning alone will not resolve the incident.
                Recommended actions:
                1. Inspect the `http-worker` pool backlog and the blocking dependency or lock path that is causing threads to accumulate.
                2. Check thread or pid limits together with `-Xss`, then compare current thread count with the intended pool sizing and concurrency controls.
                """;
        }

        if (isGeneratedCompressedClassSpaceScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The hs_err log and NMT output both point to compressed class space exhaustion, so this incident is class-metadata headroom failure rather than ordinary Java-heap pressure.
                Key metrics:
                - classSpaceUsedKb: 62976
                - classSpaceCommittedKb: 63488
                - classSpaceReservedKb: 65536
                - classSpaceUsagePctOfCommitted: 99
                - classCount: 68120
                Likely issues:
                - The JVM ran out of compressed class space while defining or loading more classes, which usually points to class-loader churn, dynamic class generation, or an undersized compressed class space for the workload.
                - Metadata usage is not the dominant signal here; the failure is concentrated in the compressed class space portion of class metadata.
                Recommended actions:
                1. Inspect class-loader churn, generated classes, proxy creation, and reload or redeploy behavior before changing heap settings.
                2. Compare the NMT Class section with JFR class-loading evidence or `jcmd <pid> VM.classloader_stats`, and treat a larger `CompressedClassSpaceSize` only as temporary mitigation.
                """;
        }

        if (isGeneratedClassLoadingMetaspaceScenario(prompt) && computeCount >= 2L && fetchCount >= 1L) {
            return """
                Summary:
                The JFR recording, GC log, and NMT output all point to the same class-loading and metaspace-pressure incident rather than a heap-only problem.
                Key metrics:
                - classLoadingEvents: 12
                - definedClasses: 12
                - dominantLoader: DynamicProxyLoader
                - metaspaceTriggeredFullGcCount: 3
                - classCountDelta: +16240
                Likely issues:
                - Class definitions are arriving fast enough through one dominant loader to build meaningful metaspace pressure during the same window that the GC log starts hitting Metadata GC Threshold full collections.
                - NMT shows the class-metadata footprint still growing, which supports a loader churn or dynamic-generation problem instead of ordinary heap saturation.
                Recommended actions:
                1. Inspect the dominant class loader, generated package family, and any proxy or bytecode-generation path before treating a larger metaspace limit as the main fix.
                2. Compare the JFR class-loading view with `jcmd <pid> VM.classloader_stats` and later NMT diffs to confirm whether the same loader or package family keeps expanding.
                """;
        }

        if (isGeneratedCodeCacheFullScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return """
                Summary:
                The JFR recording, NMT output, and hs_err log all point to code-cache exhaustion, with compiler activity running into a nearly full code cache until the compiler was disabled.
                Key metrics:
                - compilationEvents: 7
                - codeCacheFullEvents: 1
                - peakCodeCacheUsagePct: 99
                - minCodeCacheFreeKb: 640
                - codeCommittedKb: 61440
                Likely issues:
                - Compiled-code pressure or frequent recompilation grew until the JVM ran out of usable code-cache headroom.
                - This incident is centered on compiler and native code-cache pressure, not ordinary Java-heap saturation.
                Recommended actions:
                1. Inspect code-cache occupancy and compiler activity together using `jcmd <pid> Compiler.codecache` or a similar snapshot from the same workload window.
                2. Review rapid recompilation, generated-code bursts, or other compiled-code churn before treating a larger `ReservedCodeCacheSize` as more than temporary mitigation.
                """;
        }

        if (isGeneratedDirectBufferNativeOomScenario(prompt) && computeCount >= 4L && fetchCount >= 1L) {
            return structuredNarrative(
                "The generated JFR recording, NMT diff, pmap snapshot, and hs_err log all describe the same off-heap failure sequence: direct-buffer-heavy allocation churn grew native pressure until the JVM hit a fatal native allocation failure in `DirectByteBuffer::reserveMemory`.",
                List.of(
                    "topAllocationClass: java.nio.ByteBuffer",
                    "totalAllocatedBytes: 18,760,000",
                    "committedNativeDeltaKb: +49,152",
                    "anonymousResidentKb: 237,568",
                    "terminalNativeFailure: DirectByteBuffer::reserveMemory"
                ),
                List.of(
                    "This is not an isolated crash path. The direct-buffer allocation signal, native commitment growth, and resident anonymous footprint all build into the same fatal off-heap allocation failure.",
                    "The hs_err evidence sharpens the diagnosis from general native pressure to a direct-buffer or off-heap exhaustion path that ran out of available native headroom."
                ),
                List.of(
                    "Inspect direct-buffer allocation and release paths first, including NIO, networking clients, pooled buffers, and any layer that can retain or delay freeing off-heap memory.",
                    "Compare `MaxDirectMemorySize`, container or host headroom, and other native consumers so you can see whether direct-buffer growth alone explains the crash or whether multiple native consumers are competing for the same budget.",
                    "Capture another JFR, NMT diff, pmap snapshot, and hs_err or near-crash context from the same workload phase to confirm whether direct-buffer churn and native RSS keep rising together before failure."
                ),
                ""
            );
        }

        if (isGeneratedDirectBufferNativeLeakScenario(prompt) && computeCount >= 3L && fetchCount >= 1L) {
            return structuredNarrative(
                "The JFR recording, NMT diff, and pmap output all point to the same native-memory incident, with buffer-heavy allocation churn lining up with rising native commitment and a large resident anonymous footprint.",
                List.of(
                    "topAllocationClass: java.nio.ByteBuffer",
                    "totalAllocatedBytes: 18,760,000",
                    "committedNativeDeltaKb: +49,152",
                    "internalCommittedDeltaKb: +32,768",
                    "anonymousResidentKb: 237,568"
                ),
                List.of(
                    "The process is showing both heavy buffer-oriented allocation churn in the JFR recording and meaningful native-memory growth outside a simple heap-only pattern.",
                    "The NMT diff and pmap output both support anonymous or internal native growth, which fits off-heap buffer pressure or a native buffer pool that is not releasing memory quickly enough."
                ),
                List.of(
                    "Inspect direct or off-heap buffer allocation and release paths first, including NIO, client libraries, and any native or pooled buffer layer on the hot allocation path.",
                    "Capture another NMT diff and pmap snapshot from the same workload interval to confirm whether Internal growth and anonymous RSS continue to rise together.",
                    "Compare the buffer-heavy JFR allocation path with connection counts, request size, and pooling configuration before changing heap settings alone."
                ),
                ""
            );
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
        return scenarioProfile == ScenarioProfile.CONTAINER_OOM
            || (scenarioProfile == null
            && prompt.contains(CONTAINER_PRESSURE_SAMPLE)
            && prompt.contains(KERNEL_OOM_SAMPLE)
            && prompt.contains(POD_OOM_SAMPLE));
    }

    private boolean isJfrGcHeapScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.JFR_GC_HEAP
            || (scenarioProfile == null
            && prompt.contains(JFR_GC_HEAP_JFR_SAMPLE)
            && prompt.contains(JFR_GC_HEAP_GC_SAMPLE)
            && prompt.contains(JFR_GC_HEAP_HEAP_SAMPLE));
    }

    private boolean isJfrThreadDumpScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.JFR_THREAD_DUMP
            || (scenarioProfile == null
            && prompt.contains(JFR_THREAD_DUMP_JFR_SAMPLE)
            && prompt.contains(JFR_THREAD_DUMP_SAMPLE));
    }

    private boolean isGeneratedContainerBudgetScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_CONTAINER_BUDGET
            || (scenarioProfile == null
            && prompt.contains(GENERATED_CONTAINER_BUDGET_SNAPSHOT_SAMPLE)
            && prompt.contains(GENERATED_CONTAINER_BUDGET_GC_SAMPLE)
            && prompt.contains(GENERATED_CONTAINER_BUDGET_NMT_SAMPLE));
    }

    private boolean isGeneratedHeapExhaustionScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_HEAP_EXHAUSTION
            || (scenarioProfile == null
            && prompt.contains(GENERATED_HEAP_EXHAUSTION_JFR_SAMPLE)
            && prompt.contains(GENERATED_HEAP_EXHAUSTION_GC_SAMPLE)
            && prompt.contains(GENERATED_HEAP_EXHAUSTION_HEAP_SAMPLE));
    }

    private boolean isGeneratedJavaHeapSpaceScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_JAVA_HEAP_SPACE_EXHAUSTION
            || (scenarioProfile == null
            && prompt.contains(GENERATED_JAVA_HEAP_SPACE_JFR_SAMPLE)
            && prompt.contains(GENERATED_JAVA_HEAP_SPACE_GC_SAMPLE)
            && prompt.contains(GENERATED_JAVA_HEAP_SPACE_HEAP_SAMPLE));
    }

    private boolean isGeneratedG1HumongousScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_G1_HUMONGOUS_PRESSURE
            || (scenarioProfile == null
            && prompt.contains(GENERATED_G1_HUMONGOUS_JFR_SAMPLE)
            && prompt.contains(GENERATED_G1_HUMONGOUS_GC_SAMPLE)
            && prompt.contains(GENERATED_G1_HUMONGOUS_HEAP_SAMPLE));
    }

    private boolean isGcMemoryPressureScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.MEMORY_PRESSURE
            || (scenarioProfile == null
            && prompt.contains(GC_PRESSURE_SAMPLE)
            && prompt.contains(NMT_SAMPLE)
            && prompt.contains(PMAP_SAMPLE));
    }

    private boolean isGeneratedMetaspacePressureScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_METASPACE
            || (scenarioProfile == null
            && prompt.contains(GENERATED_METASPACE_GC_SAMPLE)
            && prompt.contains(GENERATED_METASPACE_NMT_SAMPLE)
            && prompt.contains(GENERATED_METASPACE_PMAP_SAMPLE));
    }

    private boolean isGeneratedThreadGrowthScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_THREAD_GROWTH
            || (scenarioProfile == null
            && prompt.contains(GENERATED_THREAD_GROWTH_THREAD_DUMP_SAMPLE)
            && prompt.contains(GENERATED_THREAD_GROWTH_NMT_SAMPLE));
    }

    private boolean isGeneratedNativeThreadExhaustionScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_NATIVE_THREAD_EXHAUSTION
            || (scenarioProfile == null
            && prompt.contains(GENERATED_NATIVE_THREAD_EXHAUSTION_THREAD_DUMP_SAMPLE)
            && prompt.contains(GENERATED_NATIVE_THREAD_EXHAUSTION_NMT_SAMPLE)
            && prompt.contains(GENERATED_NATIVE_THREAD_EXHAUSTION_HS_ERR_SAMPLE));
    }

    private boolean isGeneratedCompressedClassSpaceScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_COMPRESSED_CLASS_SPACE_OOM
            || (scenarioProfile == null
            && prompt.contains(GENERATED_COMPRESSED_CLASS_SPACE_NMT_SAMPLE)
            && prompt.contains(GENERATED_COMPRESSED_CLASS_SPACE_HS_ERR_SAMPLE));
    }

    private boolean isGeneratedClassLoadingMetaspaceScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_CLASSLOADING_METASPACE
            || (scenarioProfile == null
            && prompt.contains(GENERATED_CLASSLOADING_JFR_SAMPLE)
            && prompt.contains(GENERATED_CLASSLOADING_GC_SAMPLE)
            && prompt.contains(GENERATED_CLASSLOADING_NMT_SAMPLE));
    }

    private boolean isGeneratedCodeCacheFullScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_CODE_CACHE_FULL
            || (scenarioProfile == null
            && prompt.contains(GENERATED_CODE_CACHE_JFR_SAMPLE)
            && prompt.contains(GENERATED_CODE_CACHE_NMT_SAMPLE)
            && prompt.contains(GENERATED_CODE_CACHE_HS_ERR_SAMPLE));
    }

    private boolean isGeneratedDirectBufferNativeLeakScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_DIRECT_BUFFER_NATIVE_LEAK
            || (scenarioProfile == null
            && prompt.contains(GENERATED_DIRECT_BUFFER_JFR_SAMPLE)
            && prompt.contains(GENERATED_DIRECT_BUFFER_NMT_SAMPLE)
            && prompt.contains(GENERATED_DIRECT_BUFFER_PMAP_SAMPLE)
            && !prompt.contains(GENERATED_DIRECT_BUFFER_HS_ERR_SAMPLE));
    }

    private boolean isGeneratedDirectBufferNativeOomScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_DIRECT_BUFFER_NATIVE_OOM
            || (scenarioProfile == null
            && prompt.contains(GENERATED_DIRECT_BUFFER_JFR_SAMPLE)
            && prompt.contains(GENERATED_DIRECT_BUFFER_NMT_SAMPLE)
            && prompt.contains(GENERATED_DIRECT_BUFFER_PMAP_SAMPLE)
            && prompt.contains(GENERATED_DIRECT_BUFFER_HS_ERR_SAMPLE));
    }

    private boolean isGeneratedActiveNativeGrowthScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_ACTIVE_NATIVE_GROWTH
            || (scenarioProfile == null
            && prompt.contains(GENERATED_ACTIVE_NATIVE_GROWTH_NMT_SAMPLE)
            && prompt.contains(GENERATED_ACTIVE_NATIVE_GROWTH_PMAP_SAMPLE));
    }

    private boolean isGeneratedReservationMismatchScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.GENERATED_RESERVED_COMMITTED_MISMATCH
            || (scenarioProfile == null
            && prompt.contains(GENERATED_RESERVED_MISMATCH_NMT_SAMPLE)
            && prompt.contains(GENERATED_RESERVED_MISMATCH_PMAP_SAMPLE));
    }

    private boolean isNativeOomScenario(String prompt) {
        return scenarioProfile == ScenarioProfile.NATIVE_OOM
            || (scenarioProfile == null
            && prompt.contains(HS_ERR_SAMPLE)
            && prompt.contains(NMT_SAMPLE)
            && prompt.contains(PMAP_SAMPLE));
    }

    private ToolSpecification toolSpecification(ChatRequest chatRequest, String toolName) {
        return StubChatModelSupport.toolSpecification(chatRequest, toolName);
    }

    private String argumentsJson(ToolSpecification specification, String firstValue, String secondValue) {
        return StubChatModelSupport.argumentsJson(specification, firstValue, secondValue);
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
