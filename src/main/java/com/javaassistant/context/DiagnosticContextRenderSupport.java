package com.javaassistant.context;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DiagnosticContextRenderSupport {

    public static final int MAX_ITEMS_PER_COLLECTION = 6;
    public static final int MAX_RENDER_DEPTH = 4;
    public static final int MAX_SCALAR_LENGTH = 220;
    private static final int FULL_RENDER_DEPTH = 20;
    private static final RenderLimits DEFAULT_LIMITS = new RenderLimits(
        MAX_ITEMS_PER_COLLECTION,
        MAX_RENDER_DEPTH,
        MAX_SCALAR_LENGTH
    );
    private static final RenderLimits FULL_LIMITS = new RenderLimits(
        Integer.MAX_VALUE,
        FULL_RENDER_DEPTH,
        Integer.MAX_VALUE
    );

    private DiagnosticContextRenderSupport() {
    }

    public static String renderValue(Object value) {
        return renderValue(value, 0, DEFAULT_LIMITS);
    }

    public static String renderFullValue(Object value) {
        return renderValue(value, 0, FULL_LIMITS);
    }

    private static String renderValue(Object value, int depth, RenderLimits limits) {
        if (value == null) {
            return "(none)";
        }
        if (depth >= limits.maxRenderDepth()) {
            return "[depth limit]";
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "(empty map)";
            }
            StringBuilder builder = new StringBuilder();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (limits.maxItemsPerCollection() != Integer.MAX_VALUE && count == limits.maxItemsPerCollection()) {
                    builder.append("- ... ").append(map.size() - count).append(" more entry(s) omitted.");
                    break;
                }
                String key = renderScalar(entry.getKey(), limits.maxScalarLength());
                Object child = entry.getValue();
                if (isScalar(child)) {
                    builder.append(key).append(": ").append(renderScalar(child, limits.maxScalarLength())).append('\n');
                } else {
                    builder.append(key).append(":\n");
                    builder.append(indent(renderValue(child, depth + 1, limits), "  ")).append('\n');
                }
                count++;
            }
            return builder.toString().stripTrailing();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "(empty list)";
            }
            StringBuilder builder = new StringBuilder();
            int limit = limits.maxItemsPerCollection() == Integer.MAX_VALUE
                ? list.size()
                : Math.min(limits.maxItemsPerCollection(), list.size());
            for (int index = 0; index < limit; index++) {
                Object child = list.get(index);
                if (isScalar(child)) {
                    builder.append("- ").append(renderScalar(child, limits.maxScalarLength())).append('\n');
                } else {
                    builder.append("-\n");
                    builder.append(indent(renderValue(child, depth + 1, limits), "  ")).append('\n');
                }
            }
            if (limits.maxItemsPerCollection() != Integer.MAX_VALUE && list.size() > limit) {
                builder.append("- ... ").append(list.size() - limit).append(" more item(s) omitted.\n");
            }
            return builder.toString().stripTrailing();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                items.add(Array.get(value, index));
            }
            return renderValue(items, depth, limits);
        }
        return renderScalar(value, limits.maxScalarLength());
    }

    public static boolean isScalar(Object value) {
        return value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Enum<?>;
    }

    public static String renderScalar(Object value) {
        return renderScalar(value, MAX_SCALAR_LENGTH);
    }

    private static String renderScalar(Object value, int maxScalarLength) {
        if (value == null) {
            return "(none)";
        }
        String normalized = value.toString().replace('\r', ' ').replace('\n', ' ').trim();
        if (maxScalarLength == Integer.MAX_VALUE || normalized.length() <= maxScalarLength) {
            return normalized;
        }
        return truncateSingleLine(normalized, maxScalarLength);
    }

    public static String truncateSingleLine(String value, int limit) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "... [truncated]";
    }

    public static String truncateBlock(String value, int limit) {
        if (value == null) {
            return "(none)";
        }
        String normalized = normalizeTextBlock(value);
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "\n...[truncated]";
    }

    public static String normalizeTextBlock(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    public static String indent(String value, String prefix) {
        return value.lines()
            .map(line -> prefix + line)
            .reduce((left, right) -> left + "\n" + right)
            .orElse(prefix + "(none)");
    }

    public static List<String> lines(String text) {
        String normalized = normalizeTextBlock(text);
        return normalized.isBlank() ? List.of() : normalized.lines().toList();
    }

    private record RenderLimits(int maxItemsPerCollection, int maxRenderDepth, int maxScalarLength) { }
}
