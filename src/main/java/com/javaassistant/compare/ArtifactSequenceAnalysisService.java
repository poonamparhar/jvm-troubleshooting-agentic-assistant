package com.javaassistant.compare;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds same-type snapshot-sequence analysis on top of the existing pairwise comparison service.
 */
public class ArtifactSequenceAnalysisService {

    private final ArtifactComparisonService comparisonService;

    public ArtifactSequenceAnalysisService(ArtifactComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    public boolean supports(ArtifactType artifactType) {
        return comparisonService != null && comparisonService.supports(artifactType);
    }

    public ArtifactSequenceAnalysis analyze(
        List<InputArtifact> artifacts,
        List<ParsedArtifact> parsedArtifacts
    ) {
        validateInputs(artifacts, parsedArtifacts);

        List<PairwiseSequenceComparison> pairwiseComparisons = new ArrayList<>();
        for (int index = 0; index < artifacts.size() - 1; index++) {
            pairwiseComparisons.add(new PairwiseSequenceComparison(
                index + 1,
                index + 2,
                artifacts.get(index),
                artifacts.get(index + 1),
                comparisonService.compare(
                    artifacts.get(index),
                    parsedArtifacts.get(index),
                    artifacts.get(index + 1),
                    parsedArtifacts.get(index + 1)
                )
            ));
        }

        AssessmentResult firstToLastEvaluation = comparisonService.compare(
            artifacts.getFirst(),
            parsedArtifacts.getFirst(),
            artifacts.getLast(),
            parsedArtifacts.getLast()
        );

        return new ArtifactSequenceAnalysis(
            firstToLastEvaluation,
            List.copyOf(pairwiseComparisons),
            aggregateEvaluation(firstToLastEvaluation, pairwiseComparisons)
        );
    }

    private void validateInputs(List<InputArtifact> artifacts, List<ParsedArtifact> parsedArtifacts) {
        if (artifacts == null || parsedArtifacts == null || artifacts.size() != parsedArtifacts.size()) {
            throw new IllegalArgumentException("Sequence analysis requires matching artifact and parsed-artifact lists.");
        }
        if (artifacts.size() < 3) {
            throw new IllegalArgumentException("Sequence analysis requires at least three artifacts.");
        }

        ArtifactType artifactType = artifacts.getFirst().type();
        if (artifactType == null || !supports(artifactType)) {
            throw new IllegalArgumentException("No sequence analysis is available for artifact type: " + artifactType);
        }

        for (int index = 0; index < artifacts.size(); index++) {
            InputArtifact artifact = artifacts.get(index);
            ParsedArtifact parsedArtifact = parsedArtifacts.get(index);
            if (artifact == null || parsedArtifact == null) {
                throw new IllegalArgumentException("Sequence analysis requires non-null artifacts and parsed artifacts.");
            }
            if (artifact.type() != artifactType || parsedArtifact.type() != artifactType) {
                throw new IllegalArgumentException("Sequence analysis requires artifacts of the same comparable type.");
            }
        }
    }

    private AssessmentResult aggregateEvaluation(
        AssessmentResult firstToLastEvaluation,
        List<PairwiseSequenceComparison> pairwiseComparisons
    ) {
        Map<String, Finding> findings = new LinkedHashMap<>();
        Map<String, RecommendedAction> actions = new LinkedHashMap<>();
        LinkedHashSet<String> missingData = new LinkedHashSet<>();

        addFindings(findings, firstToLastEvaluation.findings());
        addActions(actions, firstToLastEvaluation.recommendedActions());
        missingData.addAll(firstToLastEvaluation.missingData());

        for (PairwiseSequenceComparison pairwiseComparison : pairwiseComparisons) {
            AssessmentResult evaluation = pairwiseComparison.evaluation();
            addFindings(findings, evaluation.findings());
            addActions(actions, evaluation.recommendedActions());
            missingData.addAll(evaluation.missingData());
        }

        return new AssessmentResult(
            List.copyOf(findings.values()),
            List.copyOf(actions.values()),
            List.copyOf(missingData)
        );
    }

    private void addFindings(Map<String, Finding> findings, List<Finding> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (Finding finding : candidates) {
            if (finding == null) {
                continue;
            }
            findings.putIfAbsent(findingKey(finding), finding);
        }
    }

    private void addActions(Map<String, RecommendedAction> actions, List<RecommendedAction> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (RecommendedAction action : candidates) {
            if (action == null) {
                continue;
            }
            actions.putIfAbsent(actionKey(action), action);
        }
    }

    private String findingKey(Finding finding) {
        if (finding.id() != null && !finding.id().isBlank()) {
            return finding.id();
        }
        return String.join(
            "|",
            finding.title() != null ? finding.title() : "",
            finding.summary() != null ? finding.summary() : "",
            finding.category() != null ? finding.category() : ""
        );
    }

    private String actionKey(RecommendedAction action) {
        if (action.id() != null && !action.id().isBlank()) {
            return action.id();
        }
        return String.join(
            "|",
            action.summary() != null ? action.summary() : "",
            action.rationale() != null ? action.rationale() : ""
        );
    }

    public record PairwiseSequenceComparison(
        int fromSnapshotNumber,
        int toSnapshotNumber,
        InputArtifact fromArtifact,
        InputArtifact toArtifact,
        AssessmentResult evaluation
    ) { }

    public record ArtifactSequenceAnalysis(
        AssessmentResult firstToLastEvaluation,
        List<PairwiseSequenceComparison> pairwiseComparisons,
        AssessmentResult aggregateEvaluation
    ) { }
}
