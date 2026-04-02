package com.javaassistant.context;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Typed internal selector for curated text or derived-context retrieval.
 */
public record ContextSelector(
    Integer lineStart,
    Integer lineEnd,
    String sectionId,
    String timestampStart,
    String timestampEnd,
    String gcId,
    String threadName,
    String className,
    String hotspotKey,
    String mappingCategory,
    String sliceId,
    String pattern,
    Integer contentOffset,
    Integer contentChars
) {

    public static ContextSelector fromQuery(String query) {
        if (query == null || query.isBlank()) {
            return new ContextSelector(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        Map<String, String> values = parseKeyValuePairs(query);
        Integer lineStart = null;
        Integer lineEnd = null;
        String lines = firstNonBlank(values, "lines", "lineRange");
        if (lines != null) {
            String[] parts = lines.split("-", 2);
            lineStart = parseInteger(parts[0]);
            lineEnd = parts.length > 1 ? parseInteger(parts[1]) : lineStart;
        }

        return new ContextSelector(
            lineStart,
            lineEnd,
            firstNonBlank(values, "section", "sectionId"),
            firstNonBlank(values, "start", "timestampStart", "from"),
            firstNonBlank(values, "end", "timestampEnd", "to"),
            firstNonBlank(values, "gcId", "gcid"),
            firstNonBlank(values, "thread", "threadName"),
            firstNonBlank(values, "class", "className"),
            firstNonBlank(values, "hotspot", "hotspotKey", "method"),
            firstNonBlank(values, "category", "mappingCategory"),
            firstNonBlank(values, "sliceId", "slice"),
            pattern(values, query),
            parseInteger(firstNonBlank(values, "offset", "contentOffset", "charOffset")),
            parseInteger(firstNonBlank(values, "chars", "contentChars", "limit"))
        );
    }

    public boolean hasSpecificRequest() {
        return lineStart != null
            || lineEnd != null
            || sectionId != null
            || timestampStart != null
            || timestampEnd != null
            || gcId != null
            || threadName != null
            || className != null
            || hotspotKey != null
            || mappingCategory != null
            || sliceId != null
            || pattern != null
            || contentOffset != null
            || contentChars != null;
    }

    private static Map<String, String> parseKeyValuePairs(String query) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String[] parts = query.split("[,;\\n]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(separator + 1).trim();
            if (!key.isBlank() && !value.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String pattern(Map<String, String> values, String query) {
        String pattern = firstNonBlank(values, "pattern", "match", "contains", "cause");
        if (pattern != null) {
            return pattern;
        }
        return values.isEmpty() ? query.trim() : null;
    }

    private static String firstNonBlank(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key.toLowerCase(Locale.ROOT));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseInteger(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(candidate.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
