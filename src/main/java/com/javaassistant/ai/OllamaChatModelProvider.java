package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.util.ArrayList;
import java.util.List;

public final class OllamaChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "ollama";
    public static final String TRACEABILITY_PROVIDER_ID = "OLLAMA";
    public static final OllamaChatModelProvider INSTANCE = new OllamaChatModelProvider();

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL_NAME = "llama3.2";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 16384;

    private OllamaChatModelProvider() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String traceabilityProviderId() {
        return TRACEABILITY_PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Ollama";
    }

    @Override
    public List<String> configurationKeys() {
        return List.of();
    }

    @Override
    public String documentedDefaultModelName() {
        return DEFAULT_MODEL_NAME;
    }

    @Override
    public String setupModeDescription() {
        return "Local Ollama runtime";
    }

    @Override
    public List<String> setupGuidance() {
        return List.of(
            "No env file is normally required for Ollama.",
            "Make sure the Ollama service is running and the selected model is available locally.",
            "Run `jtroubleshoot status` if you want to confirm the active model and endpoint."
        );
    }

    @Override
    public ProviderSetupStatus setupStatus(String modelNameOverride) {
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        checks.add(ProviderSetupStatus.ready(
            "Ollama endpoint",
            "Using " + EnvConfig.getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL) + ". Reachability is checked when analysis runs."
        ));
        checks.add(ProviderSetupStatus.ready("Model selection", "Using " + resolveModelName(modelNameOverride) + "."));
        return ProviderConfigSupport.buildSetupStatus(
            setupModeDescription(),
            checks,
            List.of("Make sure the Ollama service is running before you start an analysis.")
        );
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        // Get configuration from environment variables or use defaults
        String baseUrl = EnvConfig.getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("OLLAMA_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            // Configure the Ollama Chat Model
            return new ConfiguredChatModel(
                OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(0.7)
                    // .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
                    .logRequests(true)
                    .build(),
                traceabilityProviderId(),
                displayName(),
                modelName,
                ConfiguredChatModel.inferModelFamily(modelName),
                contextWindowTokens
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Ollama Chat Model", e);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        if (modelNameOverride != null && !modelNameOverride.isBlank()) {
            return modelNameOverride.strip();
        }
        return EnvConfig.getOrDefault("OLLAMA_MODEL_NAME", DEFAULT_MODEL_NAME);
    }
}
