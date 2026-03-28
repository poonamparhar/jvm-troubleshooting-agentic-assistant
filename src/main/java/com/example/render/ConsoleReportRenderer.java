package com.example.render;

import com.example.model.AnalysisReport;
import com.example.model.Finding;
import com.example.model.RecommendedAction;
import java.util.StringJoiner;

/**
 * Renders a concise console-friendly view of the canonical report.
 */
public class ConsoleReportRenderer {

    public String render(AnalysisReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("Analysis ID: ").append(report.analysisId()).append('\n');
        builder.append("Summary: ").append(report.incidentSummary()).append('\n');
        builder.append("Severity: ").append(report.overallSeverity()).append('\n');
        builder.append("Confidence: ").append(report.confidence()).append('\n');

        if (!report.findings().isEmpty()) {
            builder.append("\nFindings:\n");
            for (Finding finding : report.findings()) {
                builder.append("- [")
                    .append(finding.severity())
                    .append("] ")
                    .append(finding.title())
                    .append(": ")
                    .append(finding.summary())
                    .append('\n');
            }
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("\nRecommended Actions:\n");
            for (RecommendedAction action : report.recommendedActions()) {
                builder.append("- [")
                    .append(action.priority())
                    .append("] ")
                    .append(action.summary())
                    .append('\n');
            }
        }

        if (!report.missingData().isEmpty()) {
            builder.append("\nMissing Data:\n");
            for (String item : report.missingData()) {
                builder.append("- ").append(item).append('\n');
            }
        }

        if (!report.followUpCommands().isEmpty()) {
            builder.append("\nSuggested Commands:\n");
            StringJoiner joiner = new StringJoiner("\n");
            report.followUpCommands().forEach(command -> joiner.add("- " + command));
            builder.append(joiner).append('\n');
        }

        if (report.operatorNarrative() != null && !report.operatorNarrative().isBlank()) {
            builder.append("\nStructured LLM Narrative:\n");
            builder.append(report.operatorNarrative()).append('\n');
        }

        return builder.toString().trim();
    }
}
