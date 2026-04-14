package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import java.util.List;

public final class AnthropicChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "anthropic";
    public static final String TRACEABILITY_PROVIDER_ID = "ANTHROPIC";
    public static final AnthropicChatModelProvider INSTANCE = new AnthropicChatModelProvider();

    public static final String DEFAULT_MODEL_NAME = "claude-sonnet-4-6";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 200000;

    private AnthropicChatModelProvider() {
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
        return "Anthropic";
    }

    @Override
    public List<String> aliases() {
        return List.of("claude");
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("ANTHROPIC_API_KEY");
    }

    @Override
    public String documentedDefaultModelName() {
        return DEFAULT_MODEL_NAME;
    }

    @Override
    public String setupModeDescription() {
        return "Hosted API key";
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        String apiKey = ProviderConfigSupport.requiredEnvValue(
            "Anthropic API key is not configured. Set ANTHROPIC_API_KEY in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "ANTHROPIC_API_KEY"
        );
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("ANTHROPIC_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7);

            String baseUrl = ProviderConfigSupport.envFirstNonBlank("ANTHROPIC_BASE_URL");
            if (baseUrl != null) {
                builder.baseUrl(baseUrl);
            }

            return new ConfiguredChatModel(
                builder.build(),
                traceabilityProviderId(),
                displayName(),
                modelName,
                ConfiguredChatModel.inferModelFamily(modelName),
                contextWindowTokens
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to initialize Anthropic Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            EnvConfig.get("ANTHROPIC_MODEL_NAME"),
            DEFAULT_MODEL_NAME
        );
    }
}
