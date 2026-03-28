package com.example.report;

import com.example.model.ActionPriority;
import com.example.model.AnalysisReport;
import com.example.model.ConfidenceLevel;
import com.example.model.Finding;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;
import com.example.model.SeverityLevel;
import com.example.rules.RuleEvaluation;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Assembles a canonical analysis report from parsed artifacts and deterministic rule output.
 */
public class AnalysisReportAssembler {

    public AnalysisReport assemble(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        RuleEvaluation ruleEvaluation
    ) {
        return assemble(inputArtifact, parsedArtifact, ruleEvaluation, null);
    }

    public AnalysisReport assemble(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        RuleEvaluation ruleEvaluation,
        String operatorNarrative
    ) {
        List<Finding> findings = ruleEvaluation.findings();
        SeverityLevel overallSeverity = findings.stream()
            .map(Finding::severity)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse(SeverityLevel.LOW);
        ConfidenceLevel confidence = findings.stream()
            .map(Finding::confidence)
            .max(Comparator.comparingInt(this::confidenceRank))
            .orElse(ConfidenceLevel.LOW);

        String incidentSummary = summarize(findings, parsedArtifact, ruleEvaluation);
        String analysisId = generateAnalysisId(inputArtifact);

        return new AnalysisReport(
            analysisId,
            LocalDateTime.now(),
            incidentSummary,
            operatorNarrative,
            overallSeverity,
            confidence,
            List.of(inputArtifact),
            List.of(parsedArtifact),
            parsedArtifact.evidence(),
            findings,
            ruleEvaluation.recommendedActions(),
            ruleEvaluation.missingData(),
            suggestedFollowUpCommands(parsedArtifact),
            null
        );
    }

    private String summarize(List<Finding> findings, ParsedArtifact parsedArtifact, RuleEvaluation ruleEvaluation) {
        if (!findings.isEmpty()) {
            Finding topFinding = findings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            return String.format(
                Locale.ROOT,
                "%s analysis found %d issue(s); highest severity is %s and the top signal is \"%s\".",
                parsedArtifact.type().name(),
                findings.size(),
                topFinding.severity().name(),
                topFinding.title()
            );
        }

        if (!ruleEvaluation.missingData().isEmpty()) {
            return String.format(
                Locale.ROOT,
                "%s analysis completed with no confident findings, but additional data is needed.",
                parsedArtifact.type().name()
            );
        }

        return String.format(
            Locale.ROOT,
            "%s analysis completed with no rule-based issues detected.",
            parsedArtifact.type().name()
        );
    }

    private List<String> suggestedFollowUpCommands(ParsedArtifact parsedArtifact) {
        return switch (parsedArtifact.type()) {
            case GC_LOG -> List.of("jcmd <pid> GC.class_histogram", "jcmd <pid> VM.native_memory summary");
            case HS_ERR_LOG -> List.of("grep -n \"Problematic frame\" <hs_err_file>", "ls -ltr hs_err_pid*.log");
            case NMT -> List.of("jcmd <pid> VM.native_memory summary", "jcmd <pid> VM.native_memory detail.diff");
            case HEAP_HISTOGRAM -> List.of("jcmd <pid> GC.class_histogram", "jmap -histo:live <pid>");
            case PMAP -> List.of("pmap -x <pid>", "jcmd <pid> VM.native_memory summary");
            default -> List.of();
        };
    }

    private String generateAnalysisId(InputArtifact artifact) {
        String sourceName = artifact.metadata() != null && artifact.metadata().displayName() != null
            ? artifact.metadata().displayName().replaceAll("[^A-Za-z0-9._-]", "_")
            : "analysis";
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + "-" + sourceName;
    }

    private int severityRank(SeverityLevel severityLevel) {
        return switch (severityLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    private int confidenceRank(ConfidenceLevel confidenceLevel) {
        return switch (confidenceLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }
}
