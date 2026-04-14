package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;

public final class OpenAiChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "openai";
    public static final String TRACEABILITY_PROVIDER_ID = "OPENAI";
    public static final OpenAiChatModelProvider INSTANCE = new OpenAiChatModelProvider();

    public static final String DEFAULT_MODEL_NAME = "gpt-4o-mini";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 131072;

    private OpenAiChatModelProvider() {
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
        return "OpenAI";
    }

    @Override
    public List<String> aliases() {
        return List.of("chatgpt", "gpt");
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("OPENAI_API_KEY");
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
            "OpenAI API key is not configured. Set OPENAI_API_KEY in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "OPENAI_API_KEY"
        );
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("OPENAI_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .logRequests(true);

            String baseUrl = ProviderConfigSupport.envFirstNonBlank("OPENAI_BASE_URL");
            if (baseUrl != null) {
                builder.baseUrl(baseUrl);
            }

            String organizationId = ProviderConfigSupport.envFirstNonBlank("OPENAI_ORGANIZATION_ID");
            if (organizationId != null) {
                builder.organizationId(organizationId);
            }

            String projectId = ProviderConfigSupport.envFirstNonBlank("OPENAI_PROJECT_ID");
            if (projectId != null) {
                builder.projectId(projectId);
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
            throw new RuntimeException("Failed to initialize OpenAI Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            EnvConfig.get("OPENAI_MODEL_NAME"),
            DEFAULT_MODEL_NAME
        );
    }
}
