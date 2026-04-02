package com.javaassistant.modelproviders;

import com.javaassistant.diagnostics.ModelExecutionTraceability;
import dev.langchain4j.model.chat.ChatModel;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundles the configured chat model with stable provider and model identity metadata.
 */
public record ConfiguredChatModel(
    ChatModel chatModel,
    String providerId,
    String providerLabel,
    String modelName,
    String modelFamily,
    Integer approximateContextWindowTokens
) {

    private static final Pattern FAMILY_PATTERN = Pattern.compile("^([a-z]+\\d*)(?:[._-]\\d.*)?$");

    public ModelExecutionTraceability executionTraceability(String templateId, String templateVersion) {
        return new ModelExecutionTraceability(
            providerId,
            providerLabel,
            modelName,
            modelFamily,
            templateId,
            templateVersion
        );
    }

    public static ConfiguredChatModel synthetic(ChatModel chatModel) {
        String modelName = chatModel != null ? chatModel.getClass().getSimpleName() : "UnknownChatModel";
        return new ConfiguredChatModel(
            chatModel,
            "TEST",
            "Test Chat Model",
            modelName,
            modelName,
            null
        );
    }

    public static String inferModelFamily(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }

        String normalized = modelName.strip();
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        int colon = normalized.indexOf(':');
        if (colon > 0) {
            normalized = normalized.substring(0, colon);
        }

        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        Matcher matcher = FAMILY_PATTERN.matcher(lowerCase);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return lowerCase;
    }
}
