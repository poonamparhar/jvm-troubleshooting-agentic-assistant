package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "openai-compatible";
    public static final String TRACEABILITY_PROVIDER_ID = "OPENAI_COMPATIBLE";
    public static final OpenAiCompatibleChatModelProvider INSTANCE = genericProvider();
    public static final OpenAiCompatibleChatModelProvider GROQ = hostedProvider(
        "groq",
        "GROQ",
        "Groq",
        List.of(),
        List.of("GROQ_API_KEY"),
        List.of("GROQ_MODEL_NAME"),
        List.of("GROQ_BASE_URL"),
        List.of("GROQ_CONTEXT_WINDOW_TOKENS"),
        List.of("GROQ_API_KEY"),
        "https://api.groq.com/openai/v1",
        "llama-3.1-8b-instant",
        131072,
        false,
        Map.of(),
        List.of()
    );
    public static final OpenAiCompatibleChatModelProvider XAI = hostedProvider(
        "xai",
        "XAI",
        "xAI",
        List.of("grok"),
        List.of("XAI_API_KEY"),
        List.of("XAI_MODEL_NAME"),
        List.of("XAI_BASE_URL"),
        List.of("XAI_CONTEXT_WINDOW_TOKENS"),
        List.of("XAI_API_KEY"),
        "https://api.x.ai/v1",
        null,
        131072,
        false,
        Map.of(),
        List.of("Set the model with `jtroubleshoot config set model <name>` or `--model <name>`.")
    );
    public static final OpenAiCompatibleChatModelProvider OPENROUTER = hostedProvider(
        "openrouter",
        "OPENROUTER",
        "OpenRouter",
        List.of(),
        List.of("OPENROUTER_API_KEY"),
        List.of("OPENROUTER_MODEL_NAME"),
        List.of("OPENROUTER_BASE_URL"),
        List.of("OPENROUTER_CONTEXT_WINDOW_TOKENS"),
        List.of("OPENROUTER_API_KEY"),
        "https://openrouter.ai/api/v1",
        null,
        131072,
        false,
        orderedHeaderEnvKeys(
            "HTTP-Referer", List.of("OPENROUTER_HTTP_REFERER"),
            "X-Title", List.of("OPENROUTER_X_TITLE")
        ),
        List.of(
            "Set the routed model with `jtroubleshoot config set model <provider/model>` or `--model <provider/model>`.",
            "`OPENROUTER_HTTP_REFERER` and `OPENROUTER_X_TITLE` are optional but useful for OpenRouter app attribution."
        )
    );
    public static final OpenAiCompatibleChatModelProvider TOGETHER = hostedProvider(
        "together",
        "TOGETHER_AI",
        "Together AI",
        List.of("together-ai"),
        List.of("TOGETHER_API_KEY"),
        List.of("TOGETHER_MODEL_NAME"),
        List.of("TOGETHER_BASE_URL"),
        List.of("TOGETHER_CONTEXT_WINDOW_TOKENS"),
        List.of("TOGETHER_API_KEY"),
        "https://api.together.xyz/v1",
        null,
        131072,
        false,
        Map.of(),
        List.of("Set the model with `jtroubleshoot config set model <name>` or `--model <name>`.")
    );
    public static final OpenAiCompatibleChatModelProvider FIREWORKS = hostedProvider(
        "fireworks",
        "FIREWORKS_AI",
        "Fireworks AI",
        List.of("fireworks-ai"),
        List.of("FIREWORKS_API_KEY"),
        List.of("FIREWORKS_MODEL_NAME"),
        List.of("FIREWORKS_BASE_URL"),
        List.of("FIREWORKS_CONTEXT_WINDOW_TOKENS"),
        List.of("FIREWORKS_API_KEY"),
        "https://api.fireworks.ai/inference/v1",
        null,
        131072,
        false,
        Map.of(),
        List.of("Set the model with `jtroubleshoot config set model <name>` or `--model <name>`.")
    );

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;

    private final String id;
    private final String traceabilityProviderId;
    private final String displayName;
    private final List<String> aliases;
    private final List<String> configurationKeys;
    private final List<String> configurationNotes;
    private final List<String> apiKeyEnvKeys;
    private final List<String> modelNameEnvKeys;
    private final List<String> baseUrlEnvKeys;
    private final List<String> contextWindowEnvKeys;
    private final String defaultBaseUrl;
    private final String defaultModelName;
    private final int defaultContextWindowTokens;
    private final boolean apiKeyOptional;
    private final Map<String, List<String>> optionalHeaderEnvKeys;

    private OpenAiCompatibleChatModelProvider(
        String id,
        String traceabilityProviderId,
        String displayName,
        List<String> aliases,
        List<String> configurationKeys,
        List<String> configurationNotes,
        List<String> apiKeyEnvKeys,
        List<String> modelNameEnvKeys,
        List<String> baseUrlEnvKeys,
        List<String> contextWindowEnvKeys,
        String defaultBaseUrl,
        String defaultModelName,
        int defaultContextWindowTokens,
        boolean apiKeyOptional,
        Map<String, List<String>> optionalHeaderEnvKeys
    ) {
        this.id = id;
        this.traceabilityProviderId = traceabilityProviderId;
        this.displayName = displayName;
        this.aliases = List.copyOf(aliases);
        this.configurationKeys = List.copyOf(configurationKeys);
        this.configurationNotes = List.copyOf(configurationNotes);
        this.apiKeyEnvKeys = List.copyOf(apiKeyEnvKeys);
        this.modelNameEnvKeys = List.copyOf(modelNameEnvKeys);
        this.baseUrlEnvKeys = List.copyOf(baseUrlEnvKeys);
        this.contextWindowEnvKeys = List.copyOf(contextWindowEnvKeys);
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModelName = defaultModelName;
        this.defaultContextWindowTokens = defaultContextWindowTokens;
        this.apiKeyOptional = apiKeyOptional;

        LinkedHashMap<String, List<String>> normalizedOptionalHeaderEnvKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : optionalHeaderEnvKeys.entrySet()) {
            normalizedOptionalHeaderEnvKeys.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.optionalHeaderEnvKeys = Collections.unmodifiableMap(normalizedOptionalHeaderEnvKeys);
    }

    private static OpenAiCompatibleChatModelProvider genericProvider() {
        return new OpenAiCompatibleChatModelProvider(
            ID,
            TRACEABILITY_PROVIDER_ID,
            "OpenAI-Compatible",
            List.of("compatible"),
            List.of("OPENAI_COMPATIBLE_BASE_URL"),
            List.of(
                "Use this for any OpenAI-style endpoint that does not already have a named provider entry.",
                "Set the model with `jtroubleshoot config set model <name>` or `--model <name>`.",
                "`OPENAI_COMPATIBLE_API_KEY` is optional for gateways that do not require authentication."
            ),
            List.of("OPENAI_COMPATIBLE_API_KEY"),
            List.of("OPENAI_COMPATIBLE_MODEL_NAME"),
            List.of("OPENAI_COMPATIBLE_BASE_URL"),
            List.of("OPENAI_COMPATIBLE_CONTEXT_WINDOW_TOKENS"),
            null,
            null,
            DEFAULT_CONTEXT_WINDOW_TOKENS,
            true,
            Map.of()
        );
    }

    private static OpenAiCompatibleChatModelProvider hostedProvider(
        String id,
        String traceabilityProviderId,
        String displayName,
        List<String> aliases,
        List<String> apiKeyEnvKeys,
        List<String> modelNameEnvKeys,
        List<String> baseUrlEnvKeys,
        List<String> contextWindowEnvKeys,
        List<String> configurationKeys,
        String defaultBaseUrl,
        String defaultModelName,
        int defaultContextWindowTokens,
        boolean apiKeyOptional,
        Map<String, List<String>> optionalHeaderEnvKeys,
        List<String> configurationNotes
    ) {
        return new OpenAiCompatibleChatModelProvider(
            id,
            traceabilityProviderId,
            displayName,
            aliases,
            buildConfigurationKeys(configurationKeys),
            configurationNotes,
            apiKeyEnvKeys,
            modelNameEnvKeys,
            baseUrlEnvKeys,
            contextWindowEnvKeys,
            defaultBaseUrl,
            defaultModelName,
            defaultContextWindowTokens,
            apiKeyOptional,
            optionalHeaderEnvKeys
        );
    }

    private static List<String> buildConfigurationKeys(List<String> configurationKeys) {
        LinkedHashMap<String, Boolean> orderedKeys = new LinkedHashMap<>();
        addConfigurationKeys(orderedKeys, configurationKeys);
        return List.copyOf(orderedKeys.keySet());
    }

    private static void addConfigurationKeys(LinkedHashMap<String, Boolean> orderedKeys, List<String> envKeys) {
        if (envKeys == null) {
            return;
        }
        for (String envKey : envKeys) {
            if (envKey != null && !envKey.isBlank()) {
                orderedKeys.put(envKey, Boolean.TRUE);
            }
        }
    }

    private static Map<String, List<String>> orderedHeaderEnvKeys(
        String firstHeaderName,
        List<String> firstHeaderEnvKeys,
        String secondHeaderName,
        List<String> secondHeaderEnvKeys
    ) {
        LinkedHashMap<String, List<String>> orderedHeaders = new LinkedHashMap<>();
        orderedHeaders.put(firstHeaderName, firstHeaderEnvKeys);
        orderedHeaders.put(secondHeaderName, secondHeaderEnvKeys);
        return orderedHeaders;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String traceabilityProviderId() {
        return traceabilityProviderId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public List<String> aliases() {
        return aliases;
    }

    @Override
    public List<String> configurationKeys() {
        return configurationKeys;
    }

    @Override
    public List<String> configurationNotes() {
        return configurationNotes;
    }

    @Override
    public String documentedDefaultModelName() {
        return defaultModelName;
    }

    @Override
    public String setupModeDescription() {
        if (ID.equals(id)) {
            return "OpenAI-compatible endpoint URL";
        }
        return "Hosted API key";
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        String baseUrl = ProviderConfigSupport.firstNonBlank(
            ProviderConfigSupport.envFirstNonBlank(baseUrlEnvKeys.toArray(String[]::new)),
            defaultBaseUrl
        );
        if (baseUrl == null) {
            throw new IllegalStateException(
                displayName + " base URL is not configured. Set " + primaryEnvironmentKey(baseUrlEnvKeys)
                    + " in your environment or " + EnvConfig.supportedEnvFileDescription() + "."
            );
        }
        String modelName = resolveModelName(modelNameOverride);
        if (modelName == null) {
            throw new IllegalStateException(
                displayName + " model name is not configured. Use `jtroubleshoot config set model <name>` or pass `--model <name>`."
            );
        }
        String apiKey = ProviderConfigSupport.envFirstNonBlank(apiKeyEnvKeys.toArray(String[]::new));
        if ((apiKey == null || apiKey.isBlank()) && !apiKeyOptional) {
            throw new IllegalStateException(
                displayName + " API key is not configured. Set " + primaryEnvironmentKey(apiKeyEnvKeys)
                    + " in your environment or " + EnvConfig.supportedEnvFileDescription() + "."
            );
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "none";
        }
        int contextWindowTokens = resolvedContextWindowTokens();

        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .logRequests(true);

            Map<String, String> customHeaders = resolveCustomHeaders();
            if (!customHeaders.isEmpty()) {
                builder.customHeaders(customHeaders);
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
            throw new RuntimeException("Failed to initialize " + displayName + " Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            ProviderConfigSupport.envFirstNonBlank(modelNameEnvKeys.toArray(String[]::new)),
            defaultModelName
        );
    }

    private int resolvedContextWindowTokens() {
        for (String envKey : contextWindowEnvKeys) {
            Integer configuredValue = EnvConfig.getInt(envKey);
            if (configuredValue != null) {
                return configuredValue;
            }
        }
        return defaultContextWindowTokens;
    }

    private Map<String, String> resolveCustomHeaders() {
        if (optionalHeaderEnvKeys.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, String> resolvedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : optionalHeaderEnvKeys.entrySet()) {
            String value = ProviderConfigSupport.envFirstNonBlank(entry.getValue().toArray(String[]::new));
            if (value != null) {
                resolvedHeaders.put(entry.getKey(), value);
            }
        }
        return resolvedHeaders;
    }

    private static String primaryEnvironmentKey(List<String> envKeys) {
        if (envKeys == null || envKeys.isEmpty()) {
            return "(environment variable)";
        }
        return envKeys.getFirst();
    }
}
