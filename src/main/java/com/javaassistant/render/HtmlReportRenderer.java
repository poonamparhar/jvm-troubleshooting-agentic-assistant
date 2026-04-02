package com.javaassistant.render;

import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import java.util.List;
import java.util.Map;

/**
 * Standalone HTML renderer for shareable analysis reports.
 */
public class HtmlReportRenderer {

    public String render(AnalysisReport report) {
        ShareableReportRedactor redactor = new ShareableReportRedactor();
        Map<String, Evidence> evidenceIndex = ReportRenderSupport.evidenceIndex(report);
        StringBuilder builder = new StringBuilder();

        builder.append("""
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <title>JVM Analysis Report</title>
              <style>
                :root {
                  color-scheme: light;
                  --page: #f7f3ea;
                  --panel: #fffdf8;
                  --panel-border: #decfb8;
                  --ink: #1f1a14;
                  --muted: #6b6256;
                  --accent: #a54b1a;
                  --accent-soft: #f3dfd0;
                  --critical: #8b1e1e;
                  --high: #b45309;
                  --medium: #7c5e10;
                  --low: #355c3b;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: "Avenir Next", "Segoe UI", sans-serif;
                  background:
                    radial-gradient(circle at top right, rgba(165, 75, 26, 0.12), transparent 32rem),
                    linear-gradient(180deg, #fbf7ef 0%, var(--page) 55%, #f2ece0 100%);
                  color: var(--ink);
                }
                main {
                  max-width: 1100px;
                  margin: 0 auto;
                  padding: 2.5rem 1.5rem 3rem;
                }
                .hero, section {
                  background: rgba(255, 253, 248, 0.92);
                  border: 1px solid var(--panel-border);
                  border-radius: 22px;
                  box-shadow: 0 18px 40px rgba(55, 38, 17, 0.07);
                }
                .hero {
                  padding: 2rem;
                  margin-bottom: 1.25rem;
                }
                .hero h1 {
                  margin: 0 0 0.6rem;
                  font-size: clamp(2rem, 4vw, 3rem);
                  line-height: 1.05;
                  letter-spacing: -0.03em;
                }
                .notice {
                  margin: 0 0 1.2rem;
                  padding: 0.9rem 1rem;
                  border-radius: 14px;
                  background: var(--accent-soft);
                  color: #6a2d10;
                }
                .meta-grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                  gap: 0.85rem;
                }
                .meta-card {
                  padding: 0.9rem 1rem;
                  border-radius: 16px;
                  background: rgba(255, 255, 255, 0.9);
                  border: 1px solid rgba(165, 75, 26, 0.18);
                }
                .meta-card .label {
                  display: block;
                  font-size: 0.78rem;
                  text-transform: uppercase;
                  letter-spacing: 0.08em;
                  color: var(--muted);
                  margin-bottom: 0.35rem;
                }
                .meta-card .value {
                  font-size: 1rem;
                  font-weight: 700;
                }
                section {
                  padding: 1.4rem;
                  margin-top: 1rem;
                }
                h2 {
                  margin: 0 0 1rem;
                  font-size: 1.2rem;
                  letter-spacing: 0.01em;
                }
                p, li { line-height: 1.6; }
                ul {
                  margin: 0;
                  padding-left: 1.2rem;
                }
                .stack {
                  display: grid;
                  gap: 0.9rem;
                }
                .finding, .action, .evidence {
                  background: rgba(255, 255, 255, 0.96);
                  border: 1px solid rgba(53, 92, 59, 0.12);
                  border-radius: 18px;
                  padding: 1rem;
                }
                .finding h3, .action h3, .evidence h4 {
                  margin: 0 0 0.5rem;
                }
                .chips {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 0.45rem;
                  margin-bottom: 0.8rem;
                }
                .chip {
                  padding: 0.22rem 0.62rem;
                  border-radius: 999px;
                  background: #efe6d4;
                  border: 1px solid rgba(31, 26, 20, 0.08);
                  font-size: 0.83rem;
                }
                .chip.critical { background: #f7dede; color: var(--critical); }
                .chip.high { background: #f9e5cf; color: var(--high); }
                .chip.medium { background: #f4ebc4; color: var(--medium); }
                .chip.low { background: #deecdf; color: var(--low); }
                code {
                  font-family: "SFMono-Regular", "Menlo", monospace;
                  background: #f5efe4;
                  border-radius: 8px;
                  padding: 0.15rem 0.38rem;
                }
                pre {
                  margin: 0.55rem 0 0;
                  padding: 0.9rem;
                  overflow-x: auto;
                  border-radius: 14px;
                  background: #1d1a16;
                  color: #f7f1e3;
                }
                .muted {
                  color: var(--muted);
                }
                .summary {
                  font-size: 1.03rem;
                  margin: 0;
                }
                .grid-two {
                  display: grid;
                  gap: 1rem;
                }
                @media (min-width: 860px) {
                  .grid-two {
                    grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
                  }
                }
              </style>
            </head>
            <body>
              <main>
            """);

        builder.append("<header class=\"hero\">");
        builder.append("<h1>JVM Analysis Report</h1>");
        builder.append("<p class=\"notice\">").append(escape(redactor.shareabilityNotice())).append("</p>");
        builder.append("<div class=\"meta-grid\">");
        appendMetaCard(builder, "Analysis ID", redactor.redact(report.analysisId()));
        appendMetaCard(builder, "Created At", report.createdAt() != null ? String.valueOf(report.createdAt()) : "n/a");
        appendMetaCard(builder, "Severity", String.valueOf(report.overallSeverity()));
        appendMetaCard(builder, "Confidence", String.valueOf(report.confidence()));
        appendMetaCard(builder, "Artifacts", String.valueOf(report.inputArtifacts().size()));
        appendMetaCard(builder, "Findings", String.valueOf(report.findings().size()));
        appendMetaCard(builder, "Evidence", String.valueOf(report.evidence().size()));
        appendMetaCard(builder, "Redaction Profile", redactor.profileName());
        builder.append("</div></header>");

        builder.append("<section><h2>Summary</h2><p class=\"summary\">")
            .append(escape(redactor.redact(report.incidentSummary())))
            .append("</p></section>");
        appendAnalysisPath(builder, report, redactor);

        if (report.userNarrative() != null && !report.userNarrative().isBlank()) {
            builder.append("<section><h2>AI Agent Analysis</h2><p>")
                .append(escape(redactor.redact(report.userNarrative())))
                .append("</p></section>");
        }

        if (!report.agentTraceability().isEmpty()) {
            builder.append("<section><h2>Agent Traceability</h2><div class=\"stack\">");
            for (AgentTraceability traceability : report.agentTraceability()) {
                builder.append("<article class=\"evidence\">");
                builder.append("<h4>")
                    .append(escape(redactor.redact(traceability.agentName())))
                    .append("</h4>");
                builder.append("<div class=\"chips\">");
                builder.append("<span class=\"chip\">")
                    .append(traceability.selectedForUserNarrative() ? "selected" : "supporting")
                    .append("</span>");
                builder.append("<span class=\"chip\">")
                    .append(escape(redactor.redact(traceability.stageId())))
                    .append("</span>");
                builder.append("<span class=\"chip\">")
                    .append(escape(String.valueOf(traceability.narrativeSource())))
                    .append("</span>");
                builder.append("</div>");
                builder.append("<p><strong>Quality:</strong> ").append(escape(qualitySummary(traceability))).append("</p>");
                if (!traceability.artifactPaths().isEmpty()) {
                    builder.append("<p><strong>Artifacts:</strong> ")
                        .append(escape(ReportRenderSupport.joinRedacted(traceability.artifactPaths(), redactor)))
                        .append("</p>");
                }
                if (!traceability.evidenceIds().isEmpty()) {
                    builder.append("<p><strong>Evidence IDs:</strong> ")
                        .append(escape(String.join(", ", traceability.evidenceIds())))
                        .append("</p>");
                }
                if (traceability.modelExecutionTraceability() != null) {
                    builder.append("<p><strong>Model:</strong> ")
                        .append(escape(renderModelExecution(traceability.modelExecutionTraceability())))
                        .append("</p>");
                    builder.append("<p><strong>Template:</strong> ")
                        .append(escape(renderTemplateExecution(traceability.modelExecutionTraceability())))
                        .append("</p>");
                }
                List<String> gateNotes = traceability.qualityGates().stream()
                    .filter(result -> result.status() == AgentQualityGateStatus.WARNING || result.status() == AgentQualityGateStatus.FAILED)
                    .map(result -> result.gateId() + ": " + redactor.redact(result.detail()))
                    .toList();
                if (!gateNotes.isEmpty()) {
                    builder.append("<ul>");
                    for (String gateNote : gateNotes) {
                        builder.append("<li>").append(escape(gateNote)).append("</li>");
                    }
                    builder.append("</ul>");
                }
                builder.append("</article>");
            }
            builder.append("</div></section>");
        }

        if (report.supervisorTrace() != null && !report.supervisorTrace().steps().isEmpty()) {
            builder.append("<section><h2>Supervisor Trace</h2><div class=\"stack\">");
            builder.append("<article class=\"evidence\"><p><strong>Workflow:</strong> ")
                .append(escape(String.valueOf(report.supervisorTrace().workflowType())))
                .append("</p></article>");
            for (SupervisorTraceStep step : report.supervisorTrace().steps()) {
                builder.append("<article class=\"evidence\">");
                builder.append("<h4>").append(escape(redactor.redact(step.stepId()))).append("</h4>");
                builder.append("<div class=\"chips\">");
                builder.append("<span class=\"chip\">").append(escape(String.valueOf(step.stepType()))).append("</span>");
                if (step.agentName() != null && !step.agentName().isBlank()) {
                    builder.append("<span class=\"chip\">").append(escape(redactor.redact(step.agentName()))).append("</span>");
                }
                if (step.selectedForUserNarrative()) {
                    builder.append("<span class=\"chip\">selected</span>");
                }
                builder.append("</div>");
                builder.append("<p>").append(escape(redactor.redact(step.decision()))).append("</p>");
                if (!step.artifactPaths().isEmpty()) {
                    builder.append("<p><strong>Artifacts:</strong> ")
                        .append(escape(ReportRenderSupport.joinRedacted(step.artifactPaths(), redactor)))
                        .append("</p>");
                }
                if (!step.findingIds().isEmpty()) {
                    builder.append("<p><strong>Finding IDs:</strong> ")
                        .append(escape(String.join(", ", step.findingIds())))
                        .append("</p>");
                }
                if (!step.evidenceIds().isEmpty()) {
                    builder.append("<p><strong>Evidence IDs:</strong> ")
                        .append(escape(String.join(", ", step.evidenceIds())))
                        .append("</p>");
                }
                builder.append("</article>");
            }
            builder.append("</div></section>");
        }

        if (report.correlationResult() != null) {
            builder.append("<section><h2>Correlation Summary</h2><ul>");
            if (report.correlationResult().summary() != null && !report.correlationResult().summary().isBlank()) {
                builder.append("<li>").append(escape(redactor.redact(report.correlationResult().summary()))).append("</li>");
            }
            if (report.correlationResult().confidence() != null) {
                builder.append("<li><strong>Confidence:</strong> ")
                    .append(escape(String.valueOf(report.correlationResult().confidence())))
                    .append("</li>");
            }
            if (!report.correlationResult().contributingArtifactPaths().isEmpty()) {
                builder.append("<li><strong>Contributing Artifacts:</strong> ")
                    .append(escape(ReportRenderSupport.joinRedacted(report.correlationResult().contributingArtifactPaths(), redactor)))
                    .append("</li>");
            }
            builder.append("</ul></section>");
        }

        if (!report.artifactInventory().isEmpty()) {
            builder.append("<section><h2>Artifact Inventory</h2><ul>");
            for (ArtifactInventoryEntry entry : report.artifactInventory()) {
                builder.append("<li><strong>")
                    .append(escape(String.valueOf(entry.status())))
                    .append("</strong> ")
                    .append(escape(redactor.redact(entry.displayName())));
                if (entry.artifactType() != null && entry.artifactType() != ArtifactType.UNKNOWN) {
                    builder.append(" <span class=\"muted\">(")
                        .append(escape(String.valueOf(entry.artifactType())))
                        .append(")</span>");
                }
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    builder.append(": ").append(escape(redactor.redact(entry.detail())));
                }
                builder.append("</li>");
            }
            builder.append("</ul></section>");
        }

        if (!report.findings().isEmpty()) {
            builder.append("<section><h2>Findings</h2><div class=\"stack\">");
            for (Finding finding : report.findings()) {
                appendFinding(builder, finding, ReportRenderSupport.evidenceForFinding(evidenceIndex, finding), redactor);
            }
            builder.append("</div></section>");
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("<section><h2>Recommended Actions</h2><div class=\"stack\">");
            for (RecommendedAction action : report.recommendedActions()) {
                appendAction(builder, action, redactor);
            }
            builder.append("</div></section>");
        }

        builder.append("<div class=\"grid-two\">");

        if (!report.missingData().isEmpty()) {
            builder.append("<section><h2>Missing Data</h2><ul>");
            for (String item : report.missingData()) {
                builder.append("<li>").append(escape(redactor.redact(item))).append("</li>");
            }
            builder.append("</ul></section>");
        }

        if (!report.followUpCommands().isEmpty()) {
            builder.append("<section><h2>Suggested Commands</h2><ul>");
            for (String command : report.followUpCommands()) {
                builder.append("<li><code>").append(escape(redactor.redact(command))).append("</code></li>");
            }
            builder.append("</ul></section>");
        }

        builder.append("</div>");
        builder.append("</main></body></html>");
        return builder.toString();
    }

    private void appendAnalysisPath(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        builder.append("<section><h2>Analysis Path</h2><div class=\"stack\"><article class=\"evidence\">");
        builder.append("<p><strong>Analysis Path:</strong> ")
            .append(escape(report.analysisPathLabel()))
            .append("</p>");
        builder.append("<p><strong>AI Agent Involvement:</strong> ")
            .append(escape(report.aiAgentInvolvementLabel()))
            .append("</p>");
        AgentTraceability selectedNarrative = report.selectedNarrativeTraceability();
        if (selectedNarrative != null) {
            builder.append("<p><strong>Final Narrative Producer:</strong> ")
                .append(escape(redactor.redact(selectedNarrative.agentName())))
                .append("</p>");
            if (selectedNarrative.narrativeSource() != null) {
                builder.append("<p><strong>Final Narrative Source:</strong> ")
                    .append(escape(String.valueOf(selectedNarrative.narrativeSource())))
                    .append("</p>");
            }
            if (selectedNarrative.modelExecutionTraceability() != null) {
                builder.append("<p><strong>Final Narrative Provider:</strong> ")
                    .append(escape(selectedNarrative.modelExecutionTraceability().providerId()))
                    .append("</p>");
                builder.append("<p><strong>Final Narrative Model:</strong> ")
                    .append(escape(renderModelName(selectedNarrative.modelExecutionTraceability())))
                    .append("</p>");
                builder.append("<p><strong>Final Narrative Template:</strong> ")
                    .append(escape(renderTemplateExecution(selectedNarrative.modelExecutionTraceability())))
                    .append("</p>");
            }
        }
        builder.append("<p><strong>LLM Used For Final Narrative:</strong> ")
            .append(report.llmNarrativeSelectedForUserNarrative() ? "yes" : "no")
            .append("</p>");
        builder.append("</article></div></section>");
    }

    private String renderModelExecution(ModelExecutionTraceability modelExecutionTraceability) {
        return modelExecutionTraceability.providerId() + " / " + renderModelName(modelExecutionTraceability);
    }

    private String renderModelName(ModelExecutionTraceability modelExecutionTraceability) {
        if (modelExecutionTraceability.modelFamily() == null || modelExecutionTraceability.modelFamily().isBlank()) {
            return modelExecutionTraceability.modelName();
        }
        return modelExecutionTraceability.modelName() + " (family " + modelExecutionTraceability.modelFamily() + ")";
    }

    private String renderTemplateExecution(ModelExecutionTraceability modelExecutionTraceability) {
        return modelExecutionTraceability.templateId() + "@" + modelExecutionTraceability.templateVersion();
    }

    private void appendMetaCard(StringBuilder builder, String label, String value) {
        builder.append("<div class=\"meta-card\"><span class=\"label\">")
            .append(escape(label))
            .append("</span><span class=\"value\">")
            .append(escape(value))
            .append("</span></div>");
    }

    private String qualitySummary(AgentTraceability traceability) {
        long passed = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.PASSED).count();
        long warnings = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.WARNING).count();
        long failed = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.FAILED).count();
        long notApplicable = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.NOT_APPLICABLE).count();

        List<String> parts = new java.util.ArrayList<>();
        if (passed > 0) {
            parts.add(passed + " passed");
        }
        if (warnings > 0) {
            parts.add(warnings + " warning");
        }
        if (failed > 0) {
            parts.add(failed + " failed");
        }
        if (notApplicable > 0) {
            parts.add(notApplicable + " n/a");
        }
        return parts.isEmpty() ? "no checks" : String.join(", ", parts);
    }

    private void appendFinding(
        StringBuilder builder,
        Finding finding,
        List<Evidence> evidenceList,
        ShareableReportRedactor redactor
    ) {
        builder.append("<article class=\"finding\">");
        builder.append("<h3>").append(escape(redactor.redact(finding.title()))).append("</h3>");
        builder.append("<div class=\"chips\">");
        builder.append("<span class=\"chip ").append(severityClass(finding.severity() != null ? finding.severity().name() : "LOW")).append("\">")
            .append(escape(String.valueOf(finding.severity())))
            .append("</span>");
        builder.append("<span class=\"chip\">").append(escape(String.valueOf(finding.confidence()))).append("</span>");
        builder.append("<span class=\"chip\">").append(escape(String.valueOf(finding.status()))).append("</span>");
        if (finding.category() != null && !finding.category().isBlank()) {
            builder.append("<span class=\"chip\">").append(escape(redactor.redact(finding.category()))).append("</span>");
        }
        builder.append("</div>");
        builder.append("<p>").append(escape(redactor.redact(finding.summary()))).append("</p>");
        if (finding.rationale() != null && !finding.rationale().isBlank()) {
            builder.append("<p><strong>Rationale:</strong> ")
                .append(escape(redactor.redact(finding.rationale())))
                .append("</p>");
        }
        if (!finding.artifactPaths().isEmpty()) {
            builder.append("<p><strong>Related Artifacts:</strong> ")
                .append(escape(ReportRenderSupport.joinRedacted(finding.artifactPaths(), redactor)))
                .append("</p>");
        }
        if (!evidenceList.isEmpty()) {
            builder.append("<div class=\"stack\">");
            for (Evidence evidence : evidenceList) {
                appendEvidence(builder, evidence, redactor);
            }
            builder.append("</div>");
        }
        builder.append("</article>");
    }

    private void appendEvidence(StringBuilder builder, Evidence evidence, ShareableReportRedactor redactor) {
        builder.append("<div class=\"evidence\">");
        builder.append("<h4>")
            .append(escape(evidence.id() != null && !evidence.id().isBlank() ? evidence.id() : "no-id"))
            .append(" - ")
            .append(escape(redactor.redact(evidence.label())))
            .append("</h4>");
        if (evidence.artifactPath() != null && !evidence.artifactPath().isBlank()) {
            builder.append("<p><strong>Artifact:</strong> <code>")
                .append(escape(redactor.redact(evidence.artifactPath())))
                .append("</code></p>");
        }
        if (evidence.detail() != null && !evidence.detail().isBlank()) {
            builder.append("<p><strong>Detail:</strong> ")
                .append(escape(redactor.redact(evidence.detail())))
                .append("</p>");
        }
        if (!evidence.lineNumbers().isEmpty()) {
            builder.append("<p><strong>Lines:</strong> <code>")
                .append(escape(ReportRenderSupport.formatLineNumbers(evidence.lineNumbers())))
                .append("</code></p>");
        }
        String metrics = ReportRenderSupport.formatMetrics(evidence.metrics());
        if (!metrics.isBlank()) {
            builder.append("<p><strong>Metrics:</strong> <code>")
                .append(escape(redactor.redact(metrics)))
                .append("</code></p>");
        }
        if (evidence.snippet() != null && !evidence.snippet().isBlank()) {
            builder.append("<pre>").append(escape(redactor.redact(evidence.snippet()))).append("</pre>");
        }
        builder.append("</div>");
    }

    private void appendAction(StringBuilder builder, RecommendedAction action, ShareableReportRedactor redactor) {
        builder.append("<article class=\"action\">");
        builder.append("<h3>").append(escape(redactor.redact(action.summary()))).append("</h3>");
        builder.append("<div class=\"chips\">");
        builder.append("<span class=\"chip\">").append(escape(String.valueOf(action.priority()))).append("</span>");
        builder.append("<span class=\"chip\">").append(escape(String.valueOf(action.actionType()))).append("</span>");
        builder.append("</div>");
        if (action.rationale() != null && !action.rationale().isBlank()) {
            builder.append("<p><strong>Rationale:</strong> ")
                .append(escape(redactor.redact(action.rationale())))
                .append("</p>");
        }
        if (!action.relatedFindingIds().isEmpty()) {
            builder.append("<p><strong>Related Findings:</strong> <code>")
                .append(escape(String.join(", ", action.relatedFindingIds())))
                .append("</code></p>");
        }
        if (!action.steps().isEmpty()) {
            builder.append("<ul>");
            for (String step : action.steps()) {
                builder.append("<li>").append(escape(redactor.redact(step))).append("</li>");
            }
            builder.append("</ul>");
        }
        builder.append("</article>");
    }

    private String severityClass(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "critical";
            case "HIGH" -> "high";
            case "MEDIUM" -> "medium";
            default -> "low";
        };
    }

    private String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
