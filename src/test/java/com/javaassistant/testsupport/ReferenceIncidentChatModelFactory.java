package com.javaassistant.testsupport;

import dev.langchain4j.model.chat.ChatModel;

public final class ReferenceIncidentChatModelFactory {

    private ReferenceIncidentChatModelFactory() {
    }

    public static ChatModel create(ReferenceIncidentBundle bundle) {
        return switch (bundle.stubMode()) {
            case CORRELATION_TOOL_RETRIEVAL -> new CorrelationToolCallingStubChatModel(correlationProfile(bundle));
            case GC_COMPARE_TOOL_RETRIEVAL -> new GcComparisonToolCallingStubChatModel();
            case GC_TOOL_RETRIEVAL -> new GcToolCallingStubChatModel();
            case LEGACY_GC_TOOL_RETRIEVAL -> new LegacyGcToolCallingStubChatModel();
            case GC_WINDOW_STREAK_TOOL_RETRIEVAL -> new GcWindowStreakToolCallingStubChatModel();
            case LEGACY_GC_WINDOW_STREAK_TOOL_RETRIEVAL -> new LegacyGcWindowStreakToolCallingStubChatModel();
            case JFR_TOOL_RETRIEVAL -> new JfrToolCallingStubChatModel();
            case SCENARIO_LAB_ROUTING -> new ScenarioLabChatModel(bundle.primaryScenarioId());
            case NON_TOOLING -> new RoutingStubChatModel();
        };
    }

    private static CorrelationToolCallingStubChatModel.ScenarioProfile correlationProfile(ReferenceIncidentBundle bundle) {
        return switch (bundle.primaryScenarioId()) {
            case "correlate-container-pressure-and-oom-signal" -> CorrelationToolCallingStubChatModel.ScenarioProfile.CONTAINER_OOM;
            case "container-limit-below-jvm-budget" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_CONTAINER_BUDGET;
            case "oome-java-heap-space-or-gc-overhead-limit-exceeded" -> heapExhaustionProfile(bundle);
            case "correlate-gc-nmt-pmap-memory-pressure" -> CorrelationToolCallingStubChatModel.ScenarioProfile.MEMORY_PRESSURE;
            case "correlate-metaspace-gc-nmt-pmap", "metaspace-classloader-leak" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_METASPACE;
            case "correlate-thread-leak-with-nmt-thread-stacks" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_THREAD_GROWTH;
            case "native-thread-exhaustion" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_NATIVE_THREAD_EXHAUSTION;
            case "compressed-class-space-oom" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_COMPRESSED_CLASS_SPACE_OOM;
            case "correlate-classloading-storm-with-metaspace-pressure" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_CLASSLOADING_METASPACE;
            case "code-cache-full" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_CODE_CACHE_FULL;
            case "direct-buffer-native-leak" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_DIRECT_BUFFER_NATIVE_LEAK;
            case "active-native-growth-or-off-heap-pressure" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_ACTIVE_NATIVE_GROWTH;
            case "reserved-vs-committed-native-mismatch" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_RESERVED_COMMITTED_MISMATCH;
            case "gc-g1-humongous-allocation-pressure" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_G1_HUMONGOUS_PRESSURE;
            case "correlate-jfr-gc-heap-retained-pressure" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.JFR_GC_HEAP;
            case "correlate-jfr-thread-dump-contention" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.JFR_THREAD_DUMP;
            case "correlate-direct-buffer-leak-and-native-oom" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_DIRECT_BUFFER_NATIVE_OOM;
            case "correlate-hs-err-nmt-pmap-native-pressure" ->
                CorrelationToolCallingStubChatModel.ScenarioProfile.NATIVE_OOM;
            default -> null;
        };
    }

    private static CorrelationToolCallingStubChatModel.ScenarioProfile heapExhaustionProfile(ReferenceIncidentBundle bundle) {
        return bundle.artifactPaths().stream().anyMatch(path -> path.contains("oome-java-heap-space-terminal"))
            ? CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_JAVA_HEAP_SPACE_EXHAUSTION
            : CorrelationToolCallingStubChatModel.ScenarioProfile.GENERATED_HEAP_EXHAUSTION;
    }
}
