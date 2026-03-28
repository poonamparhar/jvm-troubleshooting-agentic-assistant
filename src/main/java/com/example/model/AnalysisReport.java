package com.example.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical report model that later renderers can turn into console, JSON, Markdown, or HTML.
 */
public record AnalysisReport(
    String analysisId,
    LocalDateTime createdAt,
    String incidentSummary,
    String operatorNarrative,
    SeverityLevel overallSeverity,
    ConfidenceLevel confidence,
    List<InputArtifact> inputArtifacts,
    List<ParsedArtifact> parsedArtifacts,
    List<Evidence> evidence,
    List<Finding> findings,
    List<RecommendedAction> recommendedActions,
    List<String> missingData,
    List<String> followUpCommands,
    CorrelationResult correlationResult
) {

    public AnalysisReport {
        inputArtifacts = inputArtifacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(inputArtifacts));
        parsedArtifacts = parsedArtifacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(parsedArtifacts));
        evidence = evidence == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidence));
        findings = findings == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(findings));
        recommendedActions = recommendedActions == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
        missingData = missingData == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(missingData));
        followUpCommands = followUpCommands == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(followUpCommands));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("analysisId", analysisId);
        canonical.put("createdAt", createdAt != null ? createdAt.toString() : null);
        canonical.put("incidentSummary", incidentSummary);
        canonical.put("operatorNarrative", operatorNarrative);
        canonical.put("overallSeverity", overallSeverity != null ? overallSeverity.name() : null);
        canonical.put("confidence", confidence != null ? confidence.name() : null);
        canonical.put("inputArtifacts", inputArtifacts.stream().map(InputArtifact::toCanonicalMap).toList());
        canonical.put("parsedArtifacts", parsedArtifacts.stream().map(ParsedArtifact::toCanonicalMap).toList());
        canonical.put("evidence", evidence.stream().map(Evidence::toCanonicalMap).toList());
        canonical.put("findings", findings.stream().map(Finding::toCanonicalMap).toList());
        canonical.put("recommendedActions", recommendedActions.stream().map(RecommendedAction::toCanonicalMap).toList());
        canonical.put("missingData", missingData);
        canonical.put("followUpCommands", followUpCommands);
        canonical.put("correlationResult", correlationResult != null ? correlationResult.toCanonicalMap() : null);
        return canonical;
    }
}
