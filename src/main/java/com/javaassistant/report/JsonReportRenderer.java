package com.javaassistant.report;

import com.javaassistant.diagnostics.AnalysisReport;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON renderer for canonical report maps.
 */
public class JsonReportRenderer {

    public String render(AnalysisReport report) {
        return renderValue(report.toCanonicalMap(), 0);
    }

    private String renderValue(Object value, int indent) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return renderObject(map, indent);
        }
        if (value instanceof List<?> list) {
            return renderArray(list, indent);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String renderObject(Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{\n");
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            builder.append("  ".repeat(indent + 1))
                .append("\"")
                .append(escape(String.valueOf(entry.getKey())))
                .append("\": ")
                .append(renderValue(entry.getValue(), indent + 1));
            if (iterator.hasNext()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ".repeat(indent)).append('}');
        return builder.toString();
    }

    private String renderArray(List<?> list, int indent) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[\n");
        for (int index = 0; index < list.size(); index++) {
            builder.append("  ".repeat(indent + 1))
                .append(renderValue(list.get(index), indent + 1));
            if (index < list.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ".repeat(indent)).append(']');
        return builder.toString();
    }

    private String escape(String text) {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
