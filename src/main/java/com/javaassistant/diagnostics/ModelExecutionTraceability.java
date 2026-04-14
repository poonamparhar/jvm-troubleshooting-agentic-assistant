package com.javaassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures which provider, model, and prompt template produced an AI narrative.
 */
public record ModelExecutionTraceability(
    String providerId,
    String providerLabel,
    String modelName,
    String modelFamily,
    String templateId,
    String templateVersion
) {

    public boolean isComplete() {
        return hasText(providerId)
            && hasText(modelName)
            && hasText(modelFamily)
            && hasText(templateId)
            && hasText(templateVersion);
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("providerId", providerId);
        canonical.put("providerLabel", providerLabel);
        canonical.put("modelName", modelName);
        canonical.put("modelFamily", modelFamily);
        canonical.put("templateId", templateId);
        canonical.put("templateVersion", templateVersion);
        return canonical;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
