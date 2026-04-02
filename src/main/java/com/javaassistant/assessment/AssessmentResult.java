package com.javaassistant.assessment;

import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.RecommendedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic assessment output for a parsed artifact.
 */
public record AssessmentResult(
    List<Finding> findings,
    List<RecommendedAction> recommendedActions,
    List<String> missingData
) {

    public AssessmentResult {
        findings = findings == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(findings));
        recommendedActions = recommendedActions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
        missingData = missingData == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(missingData));
    }
}
