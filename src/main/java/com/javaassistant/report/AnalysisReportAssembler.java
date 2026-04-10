package com.javaassistant.report;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.assessment.AssessmentResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assembles a canonical analysis report from parsed artifacts and deterministic assessment output.
 */
public class AnalysisReportAssembler {

    public AnalysisReport assemble(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        AssessmentResult assessmentResult
    ) {
        return assemble(inputArtifact, parsedArtifact, assessmentResult, null);
    }

    public AnalysisReport assemble(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        AssessmentResult assessmentResult,
        String userNarrative
    ) {
        List<Finding> findings = assessmentResult.findings();
        SeverityLevel overallSeverity = findings.stream()
            .map(Finding::severity)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse(SeverityLevel.LOW);
        ConfidenceLevel confidence = findings.stream()
            .map(Finding::confidence)
            .max(Comparator.comparingInt(this::confidenceRank))
            .orElse(ConfidenceLevel.LOW);

        String incidentSummary = summarize(findings, parsedArtifact, assessmentResult);
        String analysisId = generateAnalysisId(inputArtifact);

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            analysisId,
            LocalDateTime.now(),
            incidentSummary,
            userNarrative,
            List.of(),
            null,
            overallSeverity,
            confidence,
            List.of(inputArtifact),
            List.of(parsedArtifact),
            parsedArtifact.evidence(),
            findings,
            assessmentResult.recommendedActions(),
            assessmentResult.missingData(),
            suggestedFollowUpCommands(parsedArtifact),
            List.of(),
            null
        );
    }

    public AnalysisReport assemble(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        List<AssessmentResult> evaluations,
        CorrelationResult correlationResult
    ) {
        return assemble(inputArtifacts, parsedArtifacts, evaluations, correlationResult, null);
    }

    public AnalysisReport assemble(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        List<AssessmentResult> evaluations,
        CorrelationResult correlationResult,
        String userNarrative
    ) {
        List<Finding> findings = mergeFindings(evaluations, correlationResult);
        List<RecommendedAction> actions = mergeActions(evaluations, correlationResult);
        List<String> missingData = evaluations.stream().flatMap(evaluation -> evaluation.missingData().stream()).distinct().toList();
        SeverityLevel overallSeverity;
        ConfidenceLevel confidence;
        String incidentSummary;
        if (correlationResult != null) {
            List<Finding> incidentFindings = correlationResult.findings();
            overallSeverity = incidentFindings.stream()
                .map(Finding::severity)
                .max(Comparator.comparingInt(this::severityRank))
                .orElse(SeverityLevel.LOW);
            confidence = correlationResult.confidence() != null
                ? correlationResult.confidence()
                : incidentFindings.stream()
                    .map(Finding::confidence)
                    .max(Comparator.comparingInt(this::confidenceRank))
                    .orElse(ConfidenceLevel.LOW);
            incidentSummary = summarizeCorrelation(correlationResult, parsedArtifacts.size(), missingData);
        } else {
            overallSeverity = findings.stream()
                .map(Finding::severity)
                .max(Comparator.comparingInt(this::severityRank))
                .orElse(SeverityLevel.LOW);
            confidence = findings.stream()
                .map(Finding::confidence)
                .max(Comparator.comparingInt(this::confidenceRank))
                .orElse(ConfidenceLevel.LOW);
            incidentSummary = summarize(findings, correlationResult, parsedArtifacts.size(), missingData);
        }
        String analysisId = generateAnalysisId(inputArtifacts);
        List<com.javaassistant.diagnostics.Evidence> evidence = parsedArtifacts.stream().flatMap(artifact -> artifact.evidence().stream()).toList();
        List<String> followUpCommands = parsedArtifacts.stream()
            .flatMap(artifact -> suggestedFollowUpCommands(artifact).stream())
            .distinct()
            .toList();

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            analysisId,
            LocalDateTime.now(),
            incidentSummary,
            userNarrative,
            List.of(),
            null,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            actions,
            missingData,
            followUpCommands,
            List.of(),
            correlationResult
        );
    }

    public AnalysisReport assembleComparison(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        AssessmentResult comparisonEvaluation
    ) {
        return assembleComparison(inputArtifacts, parsedArtifacts, comparisonEvaluation, null);
    }

    public AnalysisReport assembleComparison(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        AssessmentResult comparisonEvaluation,
        String userNarrative
    ) {
        List<Finding> findings = comparisonEvaluation.findings();
        List<RecommendedAction> actions = comparisonEvaluation.recommendedActions();
        List<String> missingData = comparisonEvaluation.missingData();
        SeverityLevel overallSeverity = findings.stream()
            .map(Finding::severity)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse(SeverityLevel.LOW);
        ConfidenceLevel confidence = findings.stream()
            .map(Finding::confidence)
            .max(Comparator.comparingInt(this::confidenceRank))
            .orElse(ConfidenceLevel.LOW);
        List<com.javaassistant.diagnostics.Evidence> evidence = parsedArtifacts.stream().flatMap(artifact -> artifact.evidence().stream()).toList();
        List<String> followUpCommands = parsedArtifacts.stream()
            .flatMap(artifact -> suggestedFollowUpCommands(artifact).stream())
            .distinct()
            .toList();

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            generateAnalysisId(inputArtifacts),
            LocalDateTime.now(),
            summarizeComparison(findings, inputArtifacts.size(), missingData),
            userNarrative,
            List.of(),
            null,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            actions,
            missingData,
            followUpCommands,
            List.of(),
            null
        );
    }

    public AnalysisReport assembleSequence(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        AssessmentResult sequenceEvaluation
    ) {
        return assembleSequence(inputArtifacts, parsedArtifacts, sequenceEvaluation, null);
    }

    public AnalysisReport assembleSequence(
        List<InputArtifact> inputArtifacts,
        List<ParsedArtifact> parsedArtifacts,
        AssessmentResult sequenceEvaluation,
        String userNarrative
    ) {
        List<Finding> findings = sequenceEvaluation.findings();
        List<RecommendedAction> actions = sequenceEvaluation.recommendedActions();
        List<String> missingData = sequenceEvaluation.missingData();
        SeverityLevel overallSeverity = findings.stream()
            .map(Finding::severity)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse(SeverityLevel.LOW);
        ConfidenceLevel confidence = findings.stream()
            .map(Finding::confidence)
            .max(Comparator.comparingInt(this::confidenceRank))
            .orElse(ConfidenceLevel.LOW);
        List<com.javaassistant.diagnostics.Evidence> evidence = parsedArtifacts.stream().flatMap(artifact -> artifact.evidence().stream()).toList();
        List<String> followUpCommands = parsedArtifacts.stream()
            .flatMap(artifact -> suggestedFollowUpCommands(artifact).stream())
            .distinct()
            .toList();

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            generateAnalysisId(inputArtifacts),
            LocalDateTime.now(),
            summarizeSequence(findings, inputArtifacts.size(), missingData),
            userNarrative,
            List.of(),
            null,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            actions,
            missingData,
            followUpCommands,
            List.of(),
            null
        );
    }

    private String summarize(List<Finding> findings, ParsedArtifact parsedArtifact, AssessmentResult assessmentResult) {
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

        if (!assessmentResult.missingData().isEmpty()) {
            return String.format(
                Locale.ROOT,
                "%s analysis completed with no confident findings, but additional data is needed.",
                parsedArtifact.type().name()
            );
        }

        return String.format(
            Locale.ROOT,
            "%s analysis completed with no deterministic issues detected.",
            parsedArtifact.type().name()
        );
    }

    private String summarize(List<Finding> findings, CorrelationResult correlationResult, int artifactCount, List<String> missingData) {
        if (!findings.isEmpty()) {
            Finding topFinding = findings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            return String.format(
                Locale.ROOT,
                "Multi-artifact analysis across %d file(s) found %d issue(s); highest severity is %s and the top signal is \"%s\".",
                artifactCount,
                findings.size(),
                topFinding.severity().name(),
                topFinding.title()
            );
        }

        if (!missingData.isEmpty()) {
            return String.format(
                Locale.ROOT,
                "Multi-artifact analysis across %d file(s) completed with limited confidence because additional data is needed.",
                artifactCount
            );
        }

        return correlationResult != null && correlationResult.summary() != null
            ? correlationResult.summary()
            : String.format(Locale.ROOT, "Multi-artifact analysis across %d file(s) completed with no deterministic issues detected.", artifactCount);
    }

    private String summarizeCorrelation(CorrelationResult correlationResult, int artifactCount, List<String> missingData) {
        List<Finding> incidentFindings = correlationResult != null ? correlationResult.findings() : List.of();
        if (!incidentFindings.isEmpty()) {
            Finding topFinding = incidentFindings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            return String.format(
                Locale.ROOT,
                "Multi-artifact analysis across %d file(s) found %d cross-artifact issue(s); highest severity is %s and the top signal is \"%s\".",
                artifactCount,
                incidentFindings.size(),
                topFinding.severity().name(),
                topFinding.title()
            );
        }

        if (correlationResult != null && correlationResult.summary() != null && !correlationResult.summary().isBlank()) {
            return correlationResult.summary();
        }

        if (!missingData.isEmpty()) {
            return String.format(
                Locale.ROOT,
                "Multi-artifact analysis across %d file(s) completed with limited confidence because additional data is needed.",
                artifactCount
            );
        }

        return String.format(
            Locale.ROOT,
            "Multi-artifact analysis across %d file(s) completed with no deterministic cross-artifact issues detected.",
            artifactCount
        );
    }

    private String summarizeComparison(List<Finding> findings, int artifactCount, List<String> missingData) {
        if (!findings.isEmpty()) {
            Finding topFinding = findings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            return String.format(
                Locale.ROOT,
                "Comparison analysis across %d file(s) found %d issue(s); highest severity is %s and the top signal is \"%s\".",
                artifactCount,
                findings.size(),
                topFinding.severity().name(),
                topFinding.title()
            );
        }

        if (!missingData.isEmpty()) {
            return String.format(Locale.ROOT, "Comparison analysis across %d file(s) completed with limited confidence because additional data is needed.", artifactCount);
        }

        return String.format(Locale.ROOT, "Comparison analysis across %d file(s) completed with no deterministic deltas detected.", artifactCount);
    }

    private String summarizeSequence(List<Finding> findings, int artifactCount, List<String> missingData) {
        if (!findings.isEmpty()) {
            Finding topFinding = findings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            return String.format(
                Locale.ROOT,
                "Trend analysis across %d file(s) found %d issue(s); highest severity is %s and the top signal is \"%s\".",
                artifactCount,
                findings.size(),
                topFinding.severity().name(),
                topFinding.title()
            );
        }

        if (!missingData.isEmpty()) {
            return String.format(
                Locale.ROOT,
                "Trend analysis across %d file(s) completed with limited confidence because additional data is needed.",
                artifactCount
            );
        }

        return String.format(
            Locale.ROOT,
            "Trend analysis across %d file(s) completed with no deterministic progression issues detected.",
            artifactCount
        );
    }

    private List<String> suggestedFollowUpCommands(ParsedArtifact parsedArtifact) {
        return switch (parsedArtifact.type()) {
            case GC_LOG -> List.of("jcmd <pid> GC.class_histogram", "jcmd <pid> VM.native_memory summary");
            case JFR -> List.of(
                "jfr summary <recording.jfr>",
                "jfr print --events jdk.JavaMonitorBlocked,jdk.GarbageCollection <recording.jfr>",
                "jfr print --events jdk.ThreadPark,jdk.SocketRead,jdk.SocketWrite,jdk.FileRead,jdk.FileWrite,jdk.JavaExceptionThrow,jdk.ExceptionStatistics,jdk.SafepointBegin,jdk.ExecuteVMOperation <recording.jfr>",
                "jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ObjectAllocationSample,jdk.ObjectCountAfterGC,jdk.OldObjectSample <recording.jfr>",
                "jfr print --events jdk.ExecutionSample <recording.jfr>"
            );
            case THREAD_DUMP -> List.of("jcmd <pid> Thread.print -l", "jstack -l <pid>");
            case HS_ERR_LOG -> List.of("grep -n \"Problematic frame\" <hs_err_file>", "ls -ltr hs_err_pid*.log");
            case NMT -> List.of("jcmd <pid> VM.native_memory summary", "jcmd <pid> VM.native_memory detail.diff");
            case HEAP_HISTOGRAM -> List.of("jcmd <pid> GC.class_histogram", "jmap -histo:live <pid>");
            case PMAP -> List.of("pmap -x <pid>", "jcmd <pid> VM.native_memory summary");
            case CONTAINER_MEMORY -> List.of(
                "for f in memory.current memory.max memory.high memory.events memory.stat memory.pressure; do printf '[%s]\\n' \"$f\"; cat /sys/fs/cgroup/$f; printf '\\n'; done",
                "jcmd <pid> VM.native_memory summary"
            );
            case OOM_SIGNAL -> List.of(
                "journalctl -k -g 'oom|Out of memory|Killed process'",
                "kubectl describe pod <pod-name>"
            );
            default -> List.of();
        };
    }

    private String generateAnalysisId(InputArtifact artifact) {
        String sourceName = artifact.metadata() != null && artifact.metadata().displayName() != null
            ? artifact.metadata().displayName().replaceAll("[^A-Za-z0-9._-]", "_")
            : "analysis";
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + "-" + sourceName;
    }

    private String generateAnalysisId(List<InputArtifact> artifacts) {
        Set<String> names = new LinkedHashSet<>();
        for (InputArtifact artifact : artifacts) {
            if (artifact.metadata() != null && artifact.metadata().displayName() != null) {
                names.add(artifact.metadata().displayName().replaceAll("[^A-Za-z0-9._-]", "_"));
            }
        }
        String sourceName = names.isEmpty() ? "multi-artifact" : String.join("_", names.stream().limit(3).toList());
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + "-" + sourceName;
    }

    private List<Finding> mergeFindings(List<AssessmentResult> evaluations, CorrelationResult correlationResult) {
        java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        evaluations.forEach(evaluation -> findings.addAll(evaluation.findings()));
        if (correlationResult != null) {
            findings.addAll(correlationResult.findings());
        }
        return List.copyOf(findings);
    }

    private List<RecommendedAction> mergeActions(List<AssessmentResult> evaluations, CorrelationResult correlationResult) {
        java.util.ArrayList<RecommendedAction> actions = new java.util.ArrayList<>();
        evaluations.forEach(evaluation -> actions.addAll(evaluation.recommendedActions()));
        if (correlationResult != null) {
            actions.addAll(correlationResult.recommendedActions());
        }
        return List.copyOf(actions);
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
