package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import java.util.ArrayList;
import java.util.List;

public final class GoogleAiGeminiChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "google";
    public static final String TRACEABILITY_PROVIDER_ID = "GOOGLE_AI_GEMINI";
    public static final GoogleAiGeminiChatModelProvider INSTANCE = new GoogleAiGeminiChatModelProvider();

    public static final String DEFAULT_MODEL_NAME = "gemini-2.5-flash";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 1048576;

    private GoogleAiGeminiChatModelProvider() {
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
        return "Google AI Gemini";
    }

    @Override
    public List<String> aliases() {
        return List.of("gemini", "google-ai-gemini");
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("GOOGLE_AI_GEMINI_API_KEY");
    }

    @Override
    public List<String> configurationNotes() {
        return List.of("Also accepts the API-key aliases `GOOGLE_API_KEY` and `GEMINI_API_KEY`.");
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
    public ProviderSetupStatus setupStatus(String modelNameOverride) {
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        checks.add(ProviderConfigSupport.requiredEnvCheck(
            "API key",
            "GOOGLE_AI_GEMINI_API_KEY",
            "GOOGLE_API_KEY",
            "GEMINI_API_KEY"
        ));
        checks.add(ProviderSetupStatus.ready("Model selection", "Using " + resolveModelName(modelNameOverride) + "."));
        return ProviderConfigSupport.buildSetupStatus(
            setupModeDescription(),
            checks,
            ProviderConfigSupport.genericNextSteps(configurationKeys(), false, checks.stream().noneMatch(check -> check.status() == ProviderSetupStatus.Status.MISSING))
        );
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        String apiKey = ProviderConfigSupport.requiredEnvValue(
            "Google AI Gemini API key is not configured. Set GOOGLE_AI_GEMINI_API_KEY, GOOGLE_API_KEY, or GEMINI_API_KEY in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "GOOGLE_AI_GEMINI_API_KEY",
            "GOOGLE_API_KEY",
            "GEMINI_API_KEY"
        );
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("GOOGLE_AI_GEMINI_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7);

            return new ConfiguredChatModel(
                builder.build(),
                traceabilityProviderId(),
                displayName(),
                modelName,
                ConfiguredChatModel.inferModelFamily(modelName),
                contextWindowTokens
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to initialize Google AI Gemini Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            ProviderConfigSupport.envFirstNonBlank(
                "GOOGLE_AI_GEMINI_MODEL_NAME",
                "GOOGLE_MODEL_NAME",
                "GEMINI_MODEL_NAME"
            ),
            DEFAULT_MODEL_NAME
        );
    }
}
