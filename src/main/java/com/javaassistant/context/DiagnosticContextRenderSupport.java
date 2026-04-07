package com.javaassistant.context;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticContextRenderSupport {

    public static final int MAX_ITEMS_PER_COLLECTION = 6;
    public static final int MAX_RENDER_DEPTH = 4;
    public static final int MAX_SCALAR_LENGTH = 220;
    private static final int FULL_RENDER_DEPTH = 20;
    private static final Pattern LINE_ANCHOR_PATTERN = Pattern.compile("^(.*) lines \\[([^\\]]+)](.*)$");
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

    public static String renderSourceAnchor(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }

        List<String> renderedParts = Arrays.stream(normalizeTextBlock(value).split("\\s+\\+\\s+"))
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .map(DiagnosticContextRenderSupport::renderSourcePart)
            .toList();
        if (renderedParts.isEmpty()) {
            return "(none)";
        }
        return String.join(", ", renderedParts);
    }

    public static String humanizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "(unknown)";
        }
        String normalized = value.replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').trim();
        return normalized.isEmpty()
            ? "(unknown)"
            : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
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

    private static String renderSourcePart(String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isBlank()) {
            return "(none)";
        }
        String rangeSuffix = "";
        int charsIndex = normalized.indexOf(" chars ");
        if (charsIndex >= 0) {
            rangeSuffix = normalized.substring(charsIndex);
            normalized = normalized.substring(0, charsIndex).trim();
        }
        if ("artifact.metadata.attributes".equals(normalized)) {
            return "Artifact metadata" + rangeSuffix;
        }
        if ("parsedArtifact.warnings".equals(normalized)) {
            return "Parse warnings" + rangeSuffix;
        }
        if (normalized.startsWith("extractedData.")) {
            return renderStructuredSource(normalized.substring("extractedData.".length())) + rangeSuffix;
        }
        if (normalized.startsWith("diagnosticHighlights.")) {
            String highlightId = normalized.substring("diagnosticHighlights.".length());
            return humanizeIdentifier(highlightId) + " highlight" + rangeSuffix;
        }

        Matcher lineAnchorMatch = LINE_ANCHOR_PATTERN.matcher(normalized);
        if (lineAnchorMatch.matches()) {
            String prefix = lineAnchorMatch.group(1).trim();
            String lines = lineAnchorMatch.group(2).trim();
            String suffix = lineAnchorMatch.group(3).trim();
            String label = lines.contains(",") ? "lines" : "line";
            StringBuilder builder = new StringBuilder();
            if (!prefix.isBlank()) {
                builder.append(prefix).append(' ');
            }
            builder.append(label).append(' ').append(lines);
            if (!suffix.isBlank()) {
                builder.append(' ').append(suffix);
            }
            return builder + rangeSuffix;
        }

        return normalized + rangeSuffix;
    }

    private static String renderStructuredSource(String value) {
        List<String> sectionNames = Arrays.stream(value.split("/"))
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .map(DiagnosticContextRenderSupport::humanizeIdentifier)
            .toList();
        if (sectionNames.isEmpty()) {
            return "Structured diagnostic context";
        }
        if (sectionNames.size() == 1) {
            return sectionNames.getFirst() + " section";
        }
        return "Structured sections: " + String.join(", ", sectionNames);
    }

    private record RenderLimits(int maxItemsPerCollection, int maxRenderDepth, int maxScalarLength) { }
}
