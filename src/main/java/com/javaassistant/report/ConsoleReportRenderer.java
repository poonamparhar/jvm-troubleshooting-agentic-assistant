package com.javaassistant.report;

import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders a console-friendly shareable view of the canonical report.
 */
public class ConsoleReportRenderer {

    public String render(AnalysisReport report) {
        ShareableReportRedactor redactor = new ShareableReportRedactor();
        Map<String, Evidence> evidenceIndex = ReportRenderSupport.evidenceIndex(report);
        StringBuilder builder = new StringBuilder();

        builder.append("Analysis ID: ").append(redactor.redact(report.analysisId())).append('\n');
        if (report.createdAt() != null) {
            builder.append("Created At: ").append(report.createdAt()).append('\n');
        }
        builder.append("Summary: ").append(redactor.redact(report.incidentSummary())).append('\n');
        builder.append("Severity: ").append(report.overallSeverity()).append('\n');
        builder.append("Confidence: ").append(report.confidence()).append('\n');
        builder.append("Artifacts: ").append(report.inputArtifacts().size()).append('\n');
        builder.append("Findings: ").append(report.findings().size()).append('\n');
        builder.append("Evidence Anchors: ").append(report.evidence().size()).append('\n');
        builder.append("Sharing: ").append(redactor.shareabilityNotice()).append('\n');
        appendAnalysisPath(builder, report, redactor);

        if (report.userNarrative() != null && !report.userNarrative().isBlank()) {
            builder.append("\nAI Agent Analysis:\n");
            builder.append(redactor.redact(report.userNarrative())).append('\n');
        }

        appendAgentTraceability(builder, report, redactor);
        appendSupervisorTrace(builder, report, redactor);

        if (!report.artifactInventory().isEmpty()) {
            builder.append("\nArtifact Inventory:\n");
            for (ArtifactInventoryEntry entry : report.artifactInventory()) {
                builder.append("- [")
                    .append(entry.status())
                    .append("] ")
                    .append(redactor.redact(entry.displayName()));
                if (entry.artifactType() != null && entry.artifactType() != ArtifactType.UNKNOWN) {
                    builder.append(" (").append(entry.artifactType()).append(")");
                }
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    builder.append(": ").append(redactor.redact(entry.detail()));
                }
                builder.append('\n');
            }
        }

        if (report.correlationResult() != null) {
            builder.append("\nCorrelation Summary:\n");
            if (report.correlationResult().summary() != null && !report.correlationResult().summary().isBlank()) {
                builder.append("- ").append(redactor.redact(report.correlationResult().summary())).append('\n');
            }
            if (report.correlationResult().confidence() != null) {
                builder.append("- Confidence: ").append(report.correlationResult().confidence()).append('\n');
            }
            if (!report.correlationResult().contributingArtifactPaths().isEmpty()) {
                builder.append("- Contributing Artifacts: ")
                    .append(ReportRenderSupport.joinRedacted(report.correlationResult().contributingArtifactPaths(), redactor))
                    .append('\n');
            }
        }

        if (!report.findings().isEmpty()) {
            builder.append("\nFindings:\n");
            for (Finding finding : report.findings()) {
                builder.append("- [")
                    .append(finding.severity())
                    .append("/")
                    .append(finding.confidence())
                    .append("/")
                    .append(finding.status())
                    .append("] ")
                    .append(redactor.redact(finding.title()))
                    .append('\n');
                if (finding.category() != null && !finding.category().isBlank()) {
                    builder.append("  Category: ").append(redactor.redact(finding.category())).append('\n');
                }
                builder.append("  Summary: ").append(redactor.redact(finding.summary())).append('\n');
                if (finding.rationale() != null && !finding.rationale().isBlank()) {
                    builder.append("  Rationale: ").append(redactor.redact(finding.rationale())).append('\n');
                }
                if (!finding.artifactPaths().isEmpty()) {
                    builder.append("  Artifacts: ")
                        .append(ReportRenderSupport.joinRedacted(finding.artifactPaths(), redactor))
                        .append('\n');
                }
                appendEvidence(builder, ReportRenderSupport.evidenceForFinding(evidenceIndex, finding), redactor);
            }
        }

        if (!report.recommendedActions().isEmpty()) {
            builder.append("\nRecommended Actions:\n");
            for (RecommendedAction action : report.recommendedActions()) {
                builder.append("- [")
                    .append(action.priority())
                    .append("/")
                    .append(action.actionType())
                    .append("] ")
                    .append(redactor.redact(action.summary()))
                    .append('\n');
                if (action.rationale() != null && !action.rationale().isBlank()) {
                    builder.append("  Rationale: ").append(redactor.redact(action.rationale())).append('\n');
                }
                if (!action.steps().isEmpty()) {
                    builder.append("  Steps:\n");
                    for (String step : action.steps()) {
                        builder.append("    - ").append(redactor.redact(step)).append('\n');
                    }
                }
                if (!action.relatedFindingIds().isEmpty()) {
                    builder.append("  Related Findings: ").append(String.join(", ", action.relatedFindingIds())).append('\n');
                }
            }
        }

        if (!report.missingData().isEmpty()) {
            builder.append("\nMissing Data:\n");
            for (String item : report.missingData()) {
                builder.append("- ").append(redactor.redact(item)).append('\n');
            }
        }

        if (!report.followUpCommands().isEmpty()) {
            builder.append("\nSuggested Commands:\n");
            StringJoiner joiner = new StringJoiner("\n");
            report.followUpCommands().forEach(command -> joiner.add("- " + redactor.redact(command)));
            builder.append(joiner).append('\n');
        }

        appendStructuredDeltas(builder, report, redactor);

        return builder.toString().trim();
    }

    private void appendAgentTraceability(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        if (report.agentTraceability().isEmpty()) {
            return;
        }

        builder.append("\nAgent Traceability:\n");
        for (AgentTraceability traceability : report.agentTraceability()) {
            builder.append("- [")
                .append(traceability.selectedForUserNarrative() ? "selected" : "supporting")
                .append("] ")
                .append(redactor.redact(traceability.agentName()))
                .append(" | stage=")
                .append(redactor.redact(traceability.stageId()))
                .append(" | source=")
                .append(traceability.narrativeSource())
                .append(" | quality=")
                .append(qualitySummary(traceability))
                .append('\n');
            if (!traceability.artifactPaths().isEmpty()) {
                builder.append("  Artifacts: ")
                    .append(ReportRenderSupport.joinRedacted(traceability.artifactPaths(), redactor))
                    .append('\n');
            }
            if (!traceability.evidenceIds().isEmpty()) {
                builder.append("  Evidence IDs: ").append(String.join(", ", traceability.evidenceIds())).append('\n');
            }
            if (traceability.modelExecutionTraceability() != null) {
                builder.append("  Model: ")
                    .append(redactor.redact(renderModelExecution(traceability.modelExecutionTraceability())))
                    .append('\n');
                builder.append("  Template: ")
                    .append(redactor.redact(renderTemplateExecution(traceability.modelExecutionTraceability())))
                    .append('\n');
            }
            List<String> gateNotes = traceability.qualityGates().stream()
                .filter(result -> result.status() == AgentQualityGateStatus.WARNING || result.status() == AgentQualityGateStatus.FAILED)
                .map(result -> result.gateId() + "=" + redactor.redact(result.detail()))
                .toList();
            if (!gateNotes.isEmpty()) {
                builder.append("  Gate Notes: ").append(String.join(" | ", gateNotes)).append('\n');
            }
        }
    }

    private void appendSupervisorTrace(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        if (report.supervisorTrace() == null || report.supervisorTrace().steps().isEmpty()) {
            return;
        }

        builder.append("\nSupervisor Trace:\n");
        builder.append("- Workflow: ").append(report.supervisorTrace().workflowType()).append('\n');
        for (SupervisorTraceStep step : report.supervisorTrace().steps()) {
            builder.append("- ")
                .append(redactor.redact(step.stepId()))
                .append(" [")
                .append(step.stepType())
                .append("]");
            if (step.agentName() != null && !step.agentName().isBlank()) {
                builder.append(" agent=").append(redactor.redact(step.agentName()));
            }
            if (step.selectedForUserNarrative()) {
                builder.append(" | selected");
            }
            builder.append(": ").append(redactor.redact(step.decision())).append('\n');
            if (!step.artifactPaths().isEmpty()) {
                builder.append("  Artifacts: ")
                    .append(ReportRenderSupport.joinRedacted(step.artifactPaths(), redactor))
                    .append('\n');
            }
            if (!step.findingIds().isEmpty()) {
                builder.append("  Finding IDs: ").append(String.join(", ", step.findingIds())).append('\n');
            }
            if (!step.evidenceIds().isEmpty()) {
                builder.append("  Evidence IDs: ").append(String.join(", ", step.evidenceIds())).append('\n');
            }
        }
    }

    private void appendAnalysisPath(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        builder.append("Analysis Path: ").append(report.analysisPathLabel()).append('\n');
        builder.append("AI Agent Involvement: ").append(report.aiAgentInvolvementLabel()).append('\n');
        AgentTraceability selectedNarrative = report.selectedNarrativeTraceability();
        if (selectedNarrative != null) {
            builder.append("Final Narrative Producer: ").append(redactor.redact(selectedNarrative.agentName())).append('\n');
            if (selectedNarrative.narrativeSource() != null) {
                builder.append("Final Narrative Source: ").append(selectedNarrative.narrativeSource()).append('\n');
            }
            if (selectedNarrative.modelExecutionTraceability() != null) {
                builder.append("Final Narrative Provider: ")
                    .append(redactor.redact(selectedNarrative.modelExecutionTraceability().providerId()))
                    .append('\n');
                builder.append("Final Narrative Model: ")
                    .append(redactor.redact(renderModelName(selectedNarrative.modelExecutionTraceability())))
                    .append('\n');
                builder.append("Final Narrative Template: ")
                    .append(redactor.redact(renderTemplateExecution(selectedNarrative.modelExecutionTraceability())))
                    .append('\n');
            }
        }
        builder.append("LLM Used For Final Narrative: ")
            .append(report.llmNarrativeSelectedForUserNarrative() ? "yes" : "no")
            .append('\n');
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

    private void appendEvidence(StringBuilder builder, List<Evidence> evidenceList, ShareableReportRedactor redactor) {
        if (evidenceList.isEmpty()) {
            return;
        }
        builder.append("  Evidence:\n");
        for (Evidence evidence : evidenceList) {
            builder.append("    - ")
                .append(evidence.id() != null && !evidence.id().isBlank() ? evidence.id() : "(no-id)")
                .append(": ")
                .append(redactor.redact(evidence.label()))
                .append('\n');
            if (evidence.artifactPath() != null && !evidence.artifactPath().isBlank()) {
                builder.append("      Artifact: ").append(redactor.redact(evidence.artifactPath())).append('\n');
            }
            if (evidence.detail() != null && !evidence.detail().isBlank()) {
                builder.append("      Detail: ").append(redactor.redact(evidence.detail())).append('\n');
            }
            if (!evidence.lineNumbers().isEmpty()) {
                builder.append("      Lines: ").append(ReportRenderSupport.formatLineNumbers(evidence.lineNumbers())).append('\n');
            }
            String metrics = ReportRenderSupport.formatMetrics(evidence.metrics());
            if (!metrics.isBlank()) {
                builder.append("      Metrics: ").append(redactor.redact(metrics)).append('\n');
            }
            if (evidence.snippet() != null && !evidence.snippet().isBlank()) {
                builder.append("      Snippet: ").append(redactor.redact(evidence.snippet())).append('\n');
            }
        }
    }

    private void appendStructuredDeltas(StringBuilder builder, AnalysisReport report, ShareableReportRedactor redactor) {
        List<String> deltaSections = report.parsedArtifacts().stream()
            .map(artifact -> renderDeltaSection(artifact, redactor))
            .filter(section -> !section.isBlank())
            .toList();

        if (deltaSections.isEmpty()) {
            return;
        }

        builder.append("\nStructured Deltas:\n");
        for (String section : deltaSections) {
            builder.append(section);
        }
    }

    private String renderDeltaSection(ParsedArtifact artifact, ShareableReportRedactor redactor) {
        if (artifact.type() != ArtifactType.NMT || !"diff".equals(artifact.extractedData().get("snapshotKind"))) {
            return "";
        }

        String sourceName = artifact.metadata() != null && artifact.metadata().displayName() != null
            ? redactor.redact(artifact.metadata().displayName())
            : "nmt-diff";

        StringBuilder builder = new StringBuilder();
        builder.append("- ").append(sourceName).append(" [NMT diff]").append('\n');

        @SuppressWarnings("unchecked")
        Map<String, Long> totalDelta = (Map<String, Long>) artifact.extractedData().getOrDefault("totalDeltaKb", Map.of());
        if (!totalDelta.isEmpty()) {
            builder.append("  total reserved ").append(formatDelta(totalDelta.get("reservedKb")))
                .append(", committed ").append(formatDelta(totalDelta.get("committedKb")))
                .append('\n');
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categoryDeltas = (Map<String, Map<String, Long>>) artifact.extractedData().getOrDefault("categoryDeltas", Map.of());
        if (!categoryDeltas.isEmpty()) {
            List<String> topCommittedDeltas = new ArrayList<>();
            categoryDeltas.entrySet().stream()
                .filter(entry -> entry.getValue().containsKey("committedKb"))
                .sorted(Comparator.comparingLong(entry -> -Math.abs(entry.getValue().get("committedKb"))))
                .limit(3)
                .forEach(entry -> topCommittedDeltas.add(entry.getKey() + " " + formatDelta(entry.getValue().get("committedKb"))));
            if (!topCommittedDeltas.isEmpty()) {
                builder.append("  top category committed deltas: ")
                    .append(String.join(", ", topCommittedDeltas))
                    .append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Long> metaspaceDeltas = (Map<String, Long>) artifact.extractedData().getOrDefault("metaspaceSummaryDeltas", Map.of());
        if (!metaspaceDeltas.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (metaspaceDeltas.containsKey("usedKb")) {
                parts.add("used " + formatDelta(metaspaceDeltas.get("usedKb")));
            }
            if (metaspaceDeltas.containsKey("committedKb")) {
                parts.add("committed " + formatDelta(metaspaceDeltas.get("committedKb")));
            }
            if (!parts.isEmpty()) {
                builder.append("  metaspace delta: ").append(String.join(", ", parts)).append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Long> threadDeltas = (Map<String, Long>) artifact.extractedData().getOrDefault("threadSummaryDeltas", Map.of());
        if (!threadDeltas.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (threadDeltas.containsKey("threadCount")) {
                parts.add("thread count " + formatSigned(threadDeltas.get("threadCount")));
            }
            if (threadDeltas.containsKey("stackReservedKb")) {
                parts.add("stack reserved " + formatDelta(threadDeltas.get("stackReservedKb")));
            }
            if (!parts.isEmpty()) {
                builder.append("  thread delta: ").append(String.join(", ", parts)).append('\n');
            }
        }

        return builder.toString();
    }

    private String qualitySummary(AgentTraceability traceability) {
        long passed = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.PASSED).count();
        long warnings = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.WARNING).count();
        long failed = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.FAILED).count();
        long notApplicable = traceability.qualityGates().stream().filter(result -> result.status() == AgentQualityGateStatus.NOT_APPLICABLE).count();

        List<String> parts = new ArrayList<>();
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

    private String formatDelta(Long value) {
        return formatSigned(value) + "KB";
    }

    private String formatSigned(Long value) {
        if (value == null) {
            return "n/a";
        }
        return (value >= 0 ? "+" : "") + value;
    }
}
