package com.javaassistant.report;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.SeverityLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-fidelity user-facing renderer for local CLI use.
 */
public class UserConsoleReportRenderer {

    private static final int MAX_KEY_METRIC_LINES = 5;
    private static final int MAX_METRICS_PER_LINE = 4;
    private static final String SECTION_SUMMARY = "Summary";
    private static final String SECTION_KEY_METRICS = "Key metrics";
    private static final String SECTION_LIKELY_ISSUES = "Likely issues";
    private static final String SECTION_RECOMMENDED_ACTIONS = "Recommended actions";
    private static final String LEGACY_SECTION_NEXT_STEPS = "Next steps";
    private static final Pattern MARKDOWN_HEADING_PREFIX = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern LIST_PREFIX = Pattern.compile("^[-*+]\\s+");
    private static final Pattern EMPHASIZED_HEADING = Pattern.compile("^(\\*\\*|__|\\*|_)(.+?:)\\1\\s*(.*)$");

    public String render(AnalysisReport report) {
        if (report == null || !report.hasAiAgentBackedUserNarrative()) {
            return "AI agent-backed troubleshooting analysis is unavailable for this report.";
        }

        Map<String, Evidence> evidenceIndex = ReportRenderSupport.evidenceIndex(report);
        Map<String, String> narrativeSections = parseNarrativeSections(report.userNarrative());
        StringBuilder builder = new StringBuilder();

        appendAssessment(builder, report, narrativeSections);
        appendKeyMetrics(builder, report, evidenceIndex, narrativeSections);
        appendIssues(builder, narrativeSections);
        appendRecommendedActions(builder, narrativeSections);
        appendMissingData(builder, report);

        return builder.toString().trim();
    }

    private void appendAssessment(StringBuilder builder, AnalysisReport report, Map<String, String> narrativeSections) {
        builder.append("\nTroubleshooting Assessment:\n");
        String summary = narrativeSections.get(SECTION_SUMMARY);
        String assessment = hasMeaningfulContent(summary) ? summary.strip() : report.userNarrative().strip();
        builder.append(assessment).append('\n');

        String correlationSummary = correlationAssessmentSummary(report, assessment);
        if (hasMeaningfulContent(correlationSummary)) {
            builder.append(correlationSummary).append('\n');
        }
    }

    private void appendKeyMetrics(
        StringBuilder builder,
        AnalysisReport report,
        Map<String, Evidence> evidenceIndex,
        Map<String, String> narrativeSections
    ) {
        String aiMetrics = narrativeSections.get(SECTION_KEY_METRICS);
        if (hasMeaningfulContent(aiMetrics)) {
            builder.append("\nKey Metrics:\n");
            appendNarrativeSection(builder, aiMetrics);
            return;
        }

        List<String> metricLines = keyMetricLines(report, evidenceIndex);
        if (!metricLines.isEmpty()) {
            builder.append("\nKey Metrics:\n");
            for (String metricLine : metricLines) {
                builder.append("- ").append(metricLine).append('\n');
            }
        }
    }

    private void appendIssues(StringBuilder builder, Map<String, String> narrativeSections) {
        String likelyIssues = narrativeSections.get(SECTION_LIKELY_ISSUES);
        if (!hasMeaningfulContent(likelyIssues)) {
            return;
        }

        builder.append("\nIssues Identified:\n");
        appendNarrativeSection(builder, likelyIssues);
    }

    private void appendRecommendedActions(StringBuilder builder, Map<String, String> narrativeSections) {
        String actions = narrativeSections.get(SECTION_RECOMMENDED_ACTIONS);
        if (!hasMeaningfulContent(actions)) {
            return;
        }

        builder.append("\nRecommended Actions:\n");
        appendNarrativeSection(builder, actions);
    }

    private void appendMissingData(StringBuilder builder, AnalysisReport report) {
        if (report.missingData().isEmpty()) {
            return;
        }

        builder.append("\nAdditional Data That Would Help:\n");
        for (String item : report.missingData()) {
            builder.append("- ").append(item).append('\n');
        }
    }

    private Map<String, String> parseNarrativeSections(String narrative) {
        LinkedHashMap<String, StringBuilder> buffers = new LinkedHashMap<>();
        String currentSection = null;

        for (String rawLine : narrative.lines().toList()) {
            String line = rawLine.stripTrailing();
            SectionMatch sectionMatch = matchSection(line);
            if (sectionMatch != null) {
                currentSection = sectionMatch.section();
                buffers.computeIfAbsent(currentSection, ignored -> new StringBuilder());
                if (!sectionMatch.remainder().isBlank()) {
                    buffers.get(currentSection).append(sectionMatch.remainder()).append('\n');
                }
                continue;
            }

            if (isIgnoredLegacySection(line)) {
                currentSection = null;
                continue;
            }

            if (currentSection == null) {
                continue;
            }
            buffers.get(currentSection).append(line).append('\n');
        }

        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : buffers.entrySet()) {
            sections.put(entry.getKey(), entry.getValue().toString().strip());
        }
        return sections;
    }

    private SectionMatch matchSection(String line) {
        String normalized = normalizeSectionLine(line);
        for (String section : List.of(
            SECTION_SUMMARY,
            SECTION_KEY_METRICS,
            SECTION_LIKELY_ISSUES,
            SECTION_RECOMMENDED_ACTIONS
        )) {
            String heading = section + ":";
            if (normalized.regionMatches(true, 0, heading, 0, heading.length())) {
                return new SectionMatch(section, normalized.substring(heading.length()).trim());
            }
        }
        return null;
    }

    private boolean isIgnoredLegacySection(String line) {
        String normalized = normalizeSectionLine(line);
        String heading = LEGACY_SECTION_NEXT_STEPS + ":";
        return normalized.regionMatches(true, 0, heading, 0, heading.length());
    }

    private String normalizeSectionLine(String line) {
        if (line == null) {
            return "";
        }

        String normalized = line.strip();
        normalized = MARKDOWN_HEADING_PREFIX.matcher(normalized).replaceFirst("");
        normalized = LIST_PREFIX.matcher(normalized).replaceFirst("");

        Matcher emphasizedHeadingMatcher = EMPHASIZED_HEADING.matcher(normalized);
        if (emphasizedHeadingMatcher.matches()) {
            String heading = emphasizedHeadingMatcher.group(2).trim();
            String remainder = emphasizedHeadingMatcher.group(3).trim();
            return remainder.isEmpty() ? heading : heading + " " + remainder;
        }

        return normalized;
    }

    private void appendNarrativeSection(StringBuilder builder, String content) {
        for (String line : content.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (startsWithListMarker(trimmed)) {
                builder.append(trimmed).append('\n');
            } else {
                builder.append("- ").append(trimmed).append('\n');
            }
        }
    }

    private String correlationAssessmentSummary(AnalysisReport report, String assessment) {
        if (report == null
            || report.correlationResult() == null
            || report.correlationResult().summary() == null
            || report.correlationResult().summary().isBlank()) {
            return null;
        }

        String summary = report.correlationResult().summary().strip();
        if (assessment != null && !assessment.isBlank()) {
            String normalizedAssessment = assessment.toLowerCase(Locale.ROOT);
            if (normalizedAssessment.contains(summary.toLowerCase(Locale.ROOT))) {
                return null;
            }
            for (Finding finding : report.correlationResult().findings()) {
                if (finding != null && finding.title() != null && !finding.title().isBlank()
                    && normalizedAssessment.contains(finding.title().toLowerCase(Locale.ROOT))) {
                    return null;
                }
            }
        }
        return summary;
    }

    private boolean startsWithListMarker(String value) {
        if (value.startsWith("- ") || value.startsWith("* ")) {
            return true;
        }
        int dotIndex = value.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= 3) {
            return false;
        }
        for (int index = 0; index < dotIndex; index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return value.length() > dotIndex + 1 && Character.isWhitespace(value.charAt(dotIndex + 1));
    }

    private boolean hasMeaningfulContent(String value) {
        return value != null && !value.isBlank();
    }

    private record SectionMatch(String section, String remainder) { }

    private List<String> keyMetricLines(AnalysisReport report, Map<String, Evidence> evidenceIndex) {
        List<String> lines = new ArrayList<>();
        Set<String> seenEvidenceIds = new LinkedHashSet<>();

        for (Finding finding : orderedFindings(report)) {
            for (Evidence evidence : ReportRenderSupport.evidenceForFinding(evidenceIndex, finding)) {
                if (evidence.id() == null || !seenEvidenceIds.add(evidence.id()) || evidence.metrics().isEmpty()) {
                    continue;
                }

                String formattedMetrics = formatKeyMetrics(evidence.metrics());
                if (formattedMetrics.isBlank()) {
                    continue;
                }

                String label = evidence.label() != null && !evidence.label().isBlank()
                    ? evidence.label()
                    : humanizeIdentifier(evidence.id());
                lines.add(label + ": " + formattedMetrics);
                if (lines.size() >= MAX_KEY_METRIC_LINES) {
                    return lines;
                }
            }
        }

        return lines;
    }

    private String formatKeyMetrics(Map<String, Object> metrics) {
        StringJoiner joiner = new StringJoiner("; ");
        int count = 0;
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            if (entry.getValue() == null || entry.getValue() instanceof Map<?, ?>) {
                continue;
            }
            joiner.add(humanizeMetricKey(entry.getKey()) + " " + formatMetricValue(entry.getKey(), entry.getValue()));
            count++;
            if (count >= MAX_METRICS_PER_LINE) {
                break;
            }
        }
        return joiner.toString();
    }

    private String formatMetricValue(String key, Object value) {
        if (value instanceof List<?> list) {
            return abbreviateList(list.stream().map(String::valueOf).toList(), 3);
        }
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.endsWith("ratio")) {
                return formatPercentage(numericValue);
            }
            if (lowerKey.endsWith("percent") || lowerKey.endsWith("percentage")) {
                return formatNumber(numericValue) + "%";
            }
            if (lowerKey.endsWith("ms")) {
                return formatNumber(numericValue) + " ms";
            }
            if (lowerKey.endsWith("mb")) {
                return formatNumber(numericValue) + " MB";
            }
            if (lowerKey.endsWith("kb")) {
                return formatNumber(numericValue) + " KB";
            }
            if (lowerKey.endsWith("gb")) {
                return formatNumber(numericValue) + " GB";
            }
            return formatNumber(numericValue);
        }
        return String.valueOf(value);
    }

    private String humanizeMetricKey(String key) {
        String normalized = stripMetricUnitSuffix(key)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .trim();
        if (normalized.isEmpty()) {
            return "Metric";
        }
        return fixAbbreviations(capitalize(normalized));
    }

    private String stripMetricUnitSuffix(String key) {
        for (String suffix : List.of("Ratio", "Percent", "Percentage", "Ms", "Mb", "Kb", "Gb")) {
            if (key.endsWith(suffix) && key.length() > suffix.length()) {
                return key.substring(0, key.length() - suffix.length());
            }
        }
        return key;
    }

    private String humanizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "Signal";
        }
        return capitalize(value.replace('-', ' ').replace('_', ' ').trim());
    }

    private String abbreviateList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.size() <= limit) {
            return String.join(", ", values);
        }
        List<String> head = values.subList(0, limit);
        return String.join(", ", head) + ", +" + (values.size() - limit) + " more";
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String fixAbbreviations(String value) {
        return value
            .replace("Gc", "GC")
            .replace("Jvm", "JVM")
            .replace("Jfr", "JFR")
            .replace("Nmt", "NMT")
            .replace("Pmap", "pmap")
            .replace("Rss", "RSS");
    }

    private String formatPercentage(double value) {
        double percentage = value >= 0.0 && value <= 1.0 ? value * 100.0 : value;
        return formatNumber(percentage) + "%";
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        return String.format(Locale.ROOT, "%,.1f", value);
    }

    private List<Finding> orderedFindings(AnalysisReport report) {
        return report.findings().stream()
            .sorted(
                Comparator.comparingInt((Finding finding) -> severityRank(finding.severity()))
                    .thenComparingInt(finding -> confidenceRank(finding.confidence()))
                    .reversed()
                    .thenComparing(Finding::title, Comparator.nullsLast(String::compareToIgnoreCase))
            )
            .toList();
    }

    private int severityRank(SeverityLevel severityLevel) {
        return severityLevel != null ? severityLevel.ordinal() : SeverityLevel.LOW.ordinal();
    }

    private int confidenceRank(ConfidenceLevel confidenceLevel) {
        return confidenceLevel != null ? confidenceLevel.ordinal() : ConfidenceLevel.LOW.ordinal();
    }
}
