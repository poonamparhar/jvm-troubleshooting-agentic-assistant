package com.javaassistant.context;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Typed internal selector for JFR-specific derived retrieval.
 */
public record JfrSelector(
    String eventType,
    String timeWindowStart,
    String timeWindowEnd,
    String threadName,
    String hotspotKey,
    String allocationClass,
    String oldObjectFocus,
    String incident,
    String sliceId,
    String pattern,
    Integer contentOffset,
    Integer contentChars
) {

    public static JfrSelector fromQuery(String query) {
        if (query == null || query.isBlank()) {
            return new JfrSelector(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        Map<String, String> values = parseKeyValuePairs(query);
        if (values.isEmpty()) {
            return new JfrSelector(query.trim(), null, null, null, query.trim(), query.trim(), query.trim(), null, null, query.trim(), null, null);
        }

        return new JfrSelector(
            firstNonBlank(values, "eventType", "event", "family"),
            firstNonBlank(values, "start", "from", "timeWindowStart"),
            firstNonBlank(values, "end", "to", "timeWindowEnd"),
            firstNonBlank(values, "thread", "threadName"),
            firstNonBlank(values, "hotspot", "hotspotKey", "method"),
            firstNonBlank(values, "allocationClass", "class"),
            firstNonBlank(values, "oldObject", "root", "focus"),
            firstNonBlank(values, "incident", "window", "focusArea"),
            firstNonBlank(values, "sliceId", "slice"),
            firstNonBlank(values, "pattern", "match", "contains"),
            parseInteger(firstNonBlank(values, "offset", "contentOffset", "charOffset")),
            parseInteger(firstNonBlank(values, "chars", "contentChars", "limit"))
        );
    }

    public static JfrSelector fromContextSelector(ContextSelector selector) {
        if (selector == null) {
            return new JfrSelector(null, null, null, null, null, null, null, null, null, null, null, null);
        }
        return new JfrSelector(
            selector.pattern(),
            selector.timestampStart(),
            selector.timestampEnd(),
            selector.threadName(),
            selector.hotspotKey(),
            selector.className(),
            selector.pattern(),
            selector.incident(),
            selector.sliceId(),
            selector.pattern(),
            selector.contentOffset(),
            selector.contentChars()
        );
    }

    public boolean hasSpecificRequest() {
        return eventType != null
            || timeWindowStart != null
            || timeWindowEnd != null
            || threadName != null
            || hotspotKey != null
            || allocationClass != null
            || oldObjectFocus != null
            || incident != null
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
