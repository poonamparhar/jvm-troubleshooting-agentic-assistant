package com.example.render;

import com.example.model.AnalysisReport;
import com.example.model.Finding;
import com.example.model.RecommendedAction;

/**
 * Simple Markdown renderer for saved analysis reports.
 */
public class MarkdownReportRenderer {

    public String render(AnalysisReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# JVM Analysis Report\n\n");
        builder.append("- Analysis ID: `").append(report.analysisId()).append("`\n");
        builder.append("- Severity: `").append(report.overallSeverity()).append("`\n");
        builder.append("- Confidence: `").append(report.confidence()).append("`\n\n");
        builder.append("## Summary\n\n").append(report.incidentSummary()).append("\n\n");

        if (report.operatorNarrative() != null && !report.operatorNarrative().isBlank()) {
            builder.append("## Operator Narrative\n\n").append(report.operatorNarrative()).append("\n\n");
        }

        if (!report.findings().isEmpty()) {
            builder.append("## Findings\n\n");
            for (Finding finding : report.findings()) {
                builder.append("- **")
                    .append(finding.title())
                    .append("** (`")
                    .append(finding.severity())
                    .append("`): ")
                    .append(finding.summary())
                    .append('\n');
            }
            builder.append('\n');
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("## Recommended Actions\n\n");
            for (RecommendedAction action : report.recommendedActions()) {
                builder.append("- **")
                    .append(action.summary())
                    .append("** (`")
                    .append(action.priority())
                    .append("`)\n");
            }
            builder.append('\n');
        }

        if (!report.missingData().isEmpty()) {
            builder.append("## Missing Data\n\n");
            for (String item : report.missingData()) {
                builder.append("- ").append(item).append('\n');
            }
            builder.append('\n');
        }

        return builder.toString().trim();
    }
}
