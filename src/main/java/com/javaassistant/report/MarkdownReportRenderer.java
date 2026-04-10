package com.javaassistant.report;

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
 * Markdown renderer for shareable analysis reports.
 */
public class MarkdownReportRenderer {

    public String render(AnalysisReport report) {
        ShareableReportRedactor redactor = new ShareableReportRedactor();
        Map<String, Evidence> evidenceIndex = ReportRenderSupport.evidenceIndex(report);
        StringBuilder builder = new StringBuilder();

        builder.append("# JVM Analysis Report\n\n");

        builder.append("## Report Metadata\n\n");
        builder.append("- Severity: `").append(report.overallSeverity()).append("`\n");
        builder.append("- Confidence: `").append(report.confidence()).append("`\n\n");

        builder.append("## Summary\n\n").append(redactor.redact(report.incidentSummary())).append("\n\n");
        appendAnalysisPath(builder, report, redactor);

        if (report.userNarrative() != null && !report.userNarrative().isBlank()) {
            builder.append("## AI Agent Analysis\n\n").append(redactor.redact(report.userNarrative())).append("\n\n");
        }

        if (!report.agentTraceability().isEmpty()) {
            builder.append("## Agent Traceability\n\n");
            for (AgentTraceability traceability : report.agentTraceability()) {
                builder.append("- `")
                    .append(traceability.selectedForUserNarrative() ? "selected" : "supporting")
                    .append("` `")
                    .append(redactor.redact(traceability.agentName()))
                    .append("` stage `")
                    .append(redactor.redact(traceability.stageId()))
                    .append("` source `")
                    .append(traceability.narrativeSource())
                    .append("` quality ")
                    .append(qualitySummary(traceability))
                    .append('\n');
                if (!traceability.artifactPaths().isEmpty()) {
                    builder.append("  - Artifacts: ")
                        .append(inlineCodeList(traceability.artifactPaths(), redactor))
                        .append('\n');
                }
                if (!traceability.evidenceIds().isEmpty()) {
                    builder.append("  - Evidence IDs: `")
                        .append(String.join("`, `", traceability.evidenceIds()))
                        .append("`\n");
                }
                if (traceability.modelExecutionTraceability() != null) {
                    builder.append("  - Model: `")
                        .append(renderModelExecution(traceability.modelExecutionTraceability()))
                        .append("`\n");
                    builder.append("  - Template: `")
                        .append(renderTemplateExecution(traceability.modelExecutionTraceability()))
                        .append("`\n");
                }
                List<String> gateNotes = traceability.qualityGates().stream()
                    .filter(result -> result.status() == AgentQualityGateStatus.WARNING || result.status() == AgentQualityGateStatus.FAILED)
                    .map(result -> "`" + result.gateId() + "` " + redactor.redact(result.detail()))
                    .toList();
                for (String gateNote : gateNotes) {
                    builder.append("  - Gate Note: ").append(gateNote).append('\n');
                }
            }
            builder.append('\n');
        }

        if (report.supervisorTrace() != null && !report.supervisorTrace().steps().isEmpty()) {
            builder.append("## Supervisor Trace\n\n");
            builder.append("- Workflow: `").append(report.supervisorTrace().workflowType()).append("`\n");
            for (SupervisorTraceStep step : report.supervisorTrace().steps()) {
                builder.append("- `")
                    .append(redactor.redact(step.stepId()))
                    .append("` `")
                    .append(step.stepType())
                    .append('`');
                if (step.agentName() != null && !step.agentName().isBlank()) {
                    builder.append(" agent `").append(redactor.redact(step.agentName())).append('`');
                }
                if (step.selectedForUserNarrative()) {
                    builder.append(" `selected`");
                }
                builder.append(": ").append(redactor.redact(step.decision())).append('\n');
                if (!step.artifactPaths().isEmpty()) {
                    builder.append("  - Artifacts: ").append(inlineCodeList(step.artifactPaths(), redactor)).append('\n');
                }
                if (!step.findingIds().isEmpty()) {
                    builder.append("  - Finding IDs: `").append(String.join("`, `", step.findingIds())).append("`\n");
                }
                if (!step.evidenceIds().isEmpty()) {
                    builder.append("  - Evidence IDs: `").append(String.join("`, `", step.evidenceIds())).append("`\n");
                }
            }
            builder.append('\n');
        }

        if (report.correlationResult() != null) {
            builder.append("## Correlation Summary\n\n");
            if (report.correlationResult().summary() != null && !report.correlationResult().summary().isBlank()) {
                builder.append("- Summary: ").append(redactor.redact(report.correlationResult().summary())).append('\n');
            }
            if (report.correlationResult().confidence() != null) {
                builder.append("- Confidence: `").append(report.correlationResult().confidence()).append("`\n");
            }
            if (!report.correlationResult().contributingArtifactPaths().isEmpty()) {
                builder.append("- Contributing Artifacts: ")
                    .append(inlineCodeList(report.correlationResult().contributingArtifactPaths(), redactor))
                    .append('\n');
            }
            builder.append('\n');
        }

        if (!report.artifactInventory().isEmpty()) {
            builder.append("## Artifact Inventory\n\n");
            for (ArtifactInventoryEntry entry : report.artifactInventory()) {
                builder.append("- `")
                    .append(entry.status())
                    .append("` ")
                    .append(redactor.redact(entry.displayName()));
                if (entry.artifactType() != null && entry.artifactType() != ArtifactType.UNKNOWN) {
                    builder.append(" (`").append(entry.artifactType()).append("`)");
                }
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    builder.append(": ").append(redactor.redact(entry.detail()));
                }
                builder.append('\n');
            }
            builder.append('\n');
        }

        if (!report.findings().isEmpty()) {
            builder.append("## Findings\n\n");
            for (Finding finding : report.findings()) {
                builder.append("### ").append(redactor.redact(finding.title())).append("\n\n");
                builder.append("- Severity: `").append(finding.severity()).append("`\n");
                builder.append("- Confidence: `").append(finding.confidence()).append("`\n");
                builder.append("- Status: `").append(finding.status()).append("`\n");
                if (finding.category() != null && !finding.category().isBlank()) {
                    builder.append("- Category: `").append(redactor.redact(finding.category())).append("`\n");
                }
                builder.append("- Summary: ").append(redactor.redact(finding.summary())).append('\n');
                if (finding.rationale() != null && !finding.rationale().isBlank()) {
                    builder.append("- Rationale: ").append(redactor.redact(finding.rationale())).append('\n');
                }
                if (!finding.artifactPaths().isEmpty()) {
                    builder.append("- Related Artifacts: ")
                        .append(inlineCodeList(finding.artifactPaths(), redactor))
                        .append('\n');
                }
                appendEvidence(builder, ReportRenderSupport.evidenceForFinding(evidenceIndex, finding), redactor);
                builder.append('\n');
            }
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("## Recommended Actions\n\n");
            for (RecommendedAction action : report.recommendedActions()) {
                builder.append("### ").append(redactor.redact(action.summary())).append("\n\n");
                builder.append("- Priority: `").append(action.priority()).append("`\n");
                builder.append("- Type: `").append(action.actionType()).append("`\n");
                if (action.rationale() != null && !action.rationale().isBlank()) {
                    builder.append("- Rationale: ").append(redactor.redact(action.rationale())).append('\n');
                }
                if (!action.relatedFindingIds().isEmpty()) {
                    builder.append("- Related Findings: `").append(String.join("`, `", action.relatedFindingIds())).append("`\n");
                }
                if (!action.steps().isEmpty()) {
                    builder.append("- Steps:\n");
                    for (String step : action.steps()) {
                        builder.append("  - ").append(redactor.redact(step)).append('\n');
                    }
                }
                builder.append('\n');
            }
        }

        if (!report.missingData().isEmpty()) {
            builder.append("## Missing Data\n\n");
            for (String item : report.missingData()) {
                builder.append("- ").append(redactor.redact(item)).append('\n');
            }
            builder.append('\n');
        }

        return builder.toString().trim();
    }

    private void appendEvidence(StringBuilder builder, List<Evidence> evidenceList, ShareableReportRedactor redactor) {
        if (evidenceList.isEmpty()) {
            return;
        }
        builder.append("- Evidence:\n");
        for (Evidence evidence : evidenceList) {
            builder.append("  - `")
                .append(evidence.id() != null && !evidence.id().isBlank() ? evidence.id() : "no-id")
                .append("` ")
                .append(redactor.redact(evidence.label()))
                .append('\n');
            if (evidence.artifactPath() != null && !evidence.artifactPath().isBlank()) {
                builder.append("    - Artifact: `").append(redactor.redact(evidence.artifactPath())).append("`\n");
            }
            if (evidence.detail() != null && !evidence.detail().isBlank()) {
                builder.append("    - Detail: ").append(redactor.redact(evidence.detail())).append('\n');
            }
            if (!evidence.lineNumbers().isEmpty()) {
                builder.append("    - Lines: `")
                    .append(ReportRenderSupport.formatLineNumbers(evidence.lineNumbers()))
                    .append("`\n");
            }
            String metrics = ReportRenderSupport.formatMetrics(evidence.metrics());
            if (!metrics.isBlank()) {
                builder.append("    - Metrics: `").append(redactor.redact(metrics)).append("`\n");
            }
            if (evidence.snippet() != null && !evidence.snippet().isBlank()) {
                builder.append("    - Snippet:\n\n");
                builder.append("      ```text\n");
                builder.append(indent(redactor.redact(evidence.snippet()), "      "));
                builder.append("\n      ```\n");
            }
        }
    }

    private void appendAnalysisPath(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        builder.append("## Analysis Path\n\n");
        builder.append("- Analysis Path: `").append(report.analysisPathLabel()).append("`\n");
        builder.append("- AI Agent Involvement: `").append(report.aiAgentInvolvementLabel()).append("`\n");
        AgentTraceability selectedNarrative = report.selectedNarrativeTraceability();
        if (selectedNarrative != null) {
            builder.append("- Final Narrative Producer: `")
                .append(redactor.redact(selectedNarrative.agentName()))
                .append("`\n");
            if (selectedNarrative.narrativeSource() != null) {
                builder.append("- Final Narrative Source: `")
                    .append(selectedNarrative.narrativeSource())
                    .append("`\n");
            }
            if (selectedNarrative.modelExecutionTraceability() != null) {
                builder.append("- Final Narrative Provider: `")
                    .append(selectedNarrative.modelExecutionTraceability().providerId())
                    .append("`\n");
                builder.append("- Final Narrative Model: `")
                    .append(renderModelName(selectedNarrative.modelExecutionTraceability()))
                    .append("`\n");
                builder.append("- Final Narrative Template: `")
                    .append(renderTemplateExecution(selectedNarrative.modelExecutionTraceability()))
                    .append("`\n");
            }
        }
        builder.append("- LLM Used For Final Narrative: `")
            .append(report.llmNarrativeSelectedForUserNarrative() ? "yes" : "no")
            .append("`\n\n");
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

    private String inlineCodeList(List<String> items, ShareableReportRedactor redactor) {
        return items.stream()
            .map(redactor::redact)
            .map(item -> "`" + item + "`")
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private String indent(String value, String prefix) {
        return value.lines()
            .map(line -> prefix + line)
            .reduce((left, right) -> left + "\n" + right)
            .orElse(prefix);
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
}
