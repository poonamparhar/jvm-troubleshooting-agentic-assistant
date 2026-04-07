package com.javaassistant.report;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Shared lookup and formatting helpers for human-readable report renderers.
 */
final class ReportRenderSupport {

    private ReportRenderSupport() {
    }

    static Map<String, Evidence> evidenceIndex(AnalysisReport report) {
        Map<String, Evidence> evidenceIndex = new LinkedHashMap<>();
        for (Evidence evidence : report.evidence()) {
            if (evidence.id() != null && !evidence.id().isBlank()) {
                evidenceIndex.put(evidence.id(), evidence);
            }
        }
        return evidenceIndex;
    }

    static List<Evidence> evidenceForFinding(Map<String, Evidence> evidenceIndex, Finding finding) {
        if (finding == null || finding.evidenceIds().isEmpty()) {
            return List.of();
        }
        return finding.evidenceIds().stream()
            .map(evidenceIndex::get)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    static String formatLineNumbers(List<Integer> lineNumbers) {
        if (lineNumbers == null || lineNumbers.isEmpty()) {
            return "";
        }
        return lineNumbers.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(", "));
    }

    static String formatMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "";
        }
        return metrics.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
            .collect(Collectors.joining(", "));
    }

    static String joinRedacted(List<String> values, ShareableReportRedactor redactor) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String value : values) {
            joiner.add(redactor.redact(value));
        }
        return joiner.toString();
    }
}
