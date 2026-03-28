package com.example.rules;

import com.example.model.Finding;
import com.example.model.RecommendedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic rule output for a parsed artifact.
 */
public record RuleEvaluation(
    List<Finding> findings,
    List<RecommendedAction> recommendedActions,
    List<String> missingData
) {

    public RuleEvaluation {
        findings = findings == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(findings));
        recommendedActions = recommendedActions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
        missingData = missingData == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(missingData));
    }
}
