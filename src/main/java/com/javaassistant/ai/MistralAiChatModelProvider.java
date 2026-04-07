package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import java.util.List;

public final class MistralAiChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "mistral";
    public static final String TRACEABILITY_PROVIDER_ID = "MISTRAL_AI";
    public static final MistralAiChatModelProvider INSTANCE = new MistralAiChatModelProvider();

    public static final String DEFAULT_MODEL_NAME = "mistral-small-latest";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;

    private MistralAiChatModelProvider() {
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
        return "Mistral AI";
    }

    @Override
    public List<String> aliases() {
        return List.of("mistral-ai");
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("MISTRAL_AI_API_KEY");
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
            "Mistral AI API key is not configured. Set MISTRAL_AI_API_KEY in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "MISTRAL_AI_API_KEY"
        );
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("MISTRAL_AI_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            MistralAiChatModel.MistralAiChatModelBuilder builder = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7);

            String baseUrl = ProviderConfigSupport.envFirstNonBlank("MISTRAL_AI_BASE_URL");
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
            throw new RuntimeException("Failed to initialize Mistral AI Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            EnvConfig.get("MISTRAL_AI_MODEL_NAME"),
            DEFAULT_MODEL_NAME
        );
    }
}
