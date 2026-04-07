package com.javaassistant.ai;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Registry of supported AI model providers.
 */
public final class ChatModelProviderRegistry {

    public static final String DEFAULT_PROVIDER_ID = OllamaChatModelProvider.ID;

    private static final Map<String, ChatModelProviderFactory> PROVIDERS_BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> CANONICAL_IDS_BY_ALIAS = new LinkedHashMap<>();

    static {
        register(OllamaChatModelProvider.INSTANCE);
        register(OpenAiChatModelProvider.INSTANCE);
        register(AnthropicChatModelProvider.INSTANCE);
        register(GoogleAiGeminiChatModelProvider.INSTANCE);
        register(MistralAiChatModelProvider.INSTANCE);
        register(AzureOpenAiChatModelProvider.INSTANCE);
        register(OpenAiCompatibleChatModelProvider.XAI);
        register(OpenAiCompatibleChatModelProvider.GROQ);
        register(OpenAiCompatibleChatModelProvider.OPENROUTER);
        register(OpenAiCompatibleChatModelProvider.TOGETHER);
        register(OpenAiCompatibleChatModelProvider.FIREWORKS);
        register(OCIChatModelProvider.INSTANCE);
        register(OpenAiCompatibleChatModelProvider.INSTANCE);
    }

    private ChatModelProviderRegistry() {
    }

    public static Collection<ChatModelProviderFactory> providers() {
        return PROVIDERS_BY_ID.values();
    }

    public static ChatModelProviderFactory defaultProvider() {
        return provider(DEFAULT_PROVIDER_ID);
    }

    public static boolean supports(String providerId) {
        return canonicalProviderId(providerId) != null;
    }

    public static String canonicalProviderId(String providerId) {
        String normalized = normalizeProviderId(providerId);
        if (normalized == null) {
            return null;
        }
        return CANONICAL_IDS_BY_ALIAS.get(normalized);
    }

    public static ChatModelProviderFactory provider(String providerId) {
        String canonicalId = canonicalProviderId(providerId);
        if (canonicalId == null) {
            throw new IllegalArgumentException(
                "Unsupported provider '" + providerId + "'. Valid options: " + supportedProviderIdList()
            );
        }
        return PROVIDERS_BY_ID.get(canonicalId);
    }

    public static String supportedProviderIdList() {
        StringJoiner joiner = new StringJoiner(", ");
        for (ChatModelProviderFactory provider : PROVIDERS_BY_ID.values()) {
            joiner.add(provider.id());
        }
        return joiner.toString();
    }

    public static String normalizeProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        return providerId.strip().toLowerCase(Locale.ROOT);
    }

    private static void register(ChatModelProviderFactory provider) {
        PROVIDERS_BY_ID.put(provider.id(), provider);
        CANONICAL_IDS_BY_ALIAS.put(provider.id(), provider.id());
        for (String alias : provider.aliases()) {
            CANONICAL_IDS_BY_ALIAS.put(normalizeProviderId(alias), provider.id());
        }
    }
}
