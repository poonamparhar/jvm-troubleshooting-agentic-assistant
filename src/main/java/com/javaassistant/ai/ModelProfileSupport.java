package com.javaassistant.ai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared model-profile heuristics used to tune first-pass context and tool budgets.
 */
public final class ModelProfileSupport {

    private static final Pattern PARAMETER_SIZE_PATTERN =
        Pattern.compile("(?:^|[:/_-])(\\d+(?:\\.\\d+)?)b(?:[^a-z0-9]|$)");

    private ModelProfileSupport() {
    }

    public static boolean isCompactLocalModel(String providerId, String modelName, String modelFamily) {
        if (providerId == null || !"OLLAMA".equalsIgnoreCase(providerId.strip())) {
            return false;
        }

        Double parameterSizeBillions = approximateParameterSizeBillions(modelName);
        if (parameterSizeBillions != null) {
            return parameterSizeBillions <= 8.0d;
        }

        String normalizedModelName = normalizeModelIdentifier(modelName);
        if (normalizedModelName.startsWith("llama3.2")) {
            return true;
        }

        String normalizedModelFamily = normalizeModelIdentifier(modelFamily);
        return normalizedModelFamily.startsWith("llama3.2");
    }

    public static Double approximateParameterSizeBillions(String modelName) {
        String normalizedModelName = normalizeModelIdentifier(modelName);
        if (normalizedModelName.isBlank()) {
            return null;
        }

        Matcher matcher = PARAMETER_SIZE_PATTERN.matcher(normalizedModelName);
        if (!matcher.find()) {
            return null;
        }

        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String normalizeModelIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.strip().toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized;
    }
}
