package com.example.render;

import com.example.model.AnalysisReport;
import com.example.model.Finding;
import com.example.model.RecommendedAction;

/**
 * Simple standalone HTML renderer for saved analysis reports.
 */
public class HtmlReportRenderer {

    public String render(AnalysisReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
            .append("<title>JVM Analysis Report</title>")
            .append("<style>")
            .append("body{font-family:Helvetica,Arial,sans-serif;margin:2rem;line-height:1.5;color:#1f2933;}")
            .append("h1,h2{color:#102a43;} .meta{margin-bottom:1rem;padding:1rem;background:#f0f4f8;border-radius:8px;}")
            .append("ul{padding-left:1.2rem;} .severity{font-weight:bold;}")
            .append("</style></head><body>");
        builder.append("<h1>JVM Analysis Report</h1>");
        builder.append("<div class=\"meta\">")
            .append("<div><strong>Analysis ID:</strong> ").append(escape(report.analysisId())).append("</div>")
            .append("<div><strong>Severity:</strong> ").append(escape(String.valueOf(report.overallSeverity()))).append("</div>")
            .append("<div><strong>Confidence:</strong> ").append(escape(String.valueOf(report.confidence()))).append("</div>")
            .append("</div>");
        builder.append("<h2>Summary</h2><p>").append(escape(report.incidentSummary())).append("</p>");

        if (report.operatorNarrative() != null && !report.operatorNarrative().isBlank()) {
            builder.append("<h2>Operator Narrative</h2><p>").append(escape(report.operatorNarrative())).append("</p>");
        }

        if (!report.findings().isEmpty()) {
            builder.append("<h2>Findings</h2><ul>");
            for (Finding finding : report.findings()) {
                builder.append("<li><span class=\"severity\">")
                    .append(escape(finding.title()))
                    .append("</span> (")
                    .append(escape(String.valueOf(finding.severity())))
                    .append("): ")
                    .append(escape(finding.summary()))
                    .append("</li>");
            }
            builder.append("</ul>");
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("<h2>Recommended Actions</h2><ul>");
            for (RecommendedAction action : report.recommendedActions()) {
                builder.append("<li>")
                    .append(escape(action.summary()))
                    .append(" (")
                    .append(escape(String.valueOf(action.priority())))
                    .append(")</li>");
            }
            builder.append("</ul>");
        }

        if (!report.missingData().isEmpty()) {
            builder.append("<h2>Missing Data</h2><ul>");
            for (String item : report.missingData()) {
                builder.append("<li>").append(escape(item)).append("</li>");
            }
            builder.append("</ul>");
        }

        builder.append("</body></html>");
        return builder.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
