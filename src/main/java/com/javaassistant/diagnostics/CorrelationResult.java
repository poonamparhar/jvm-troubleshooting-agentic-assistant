package com.javaassistant.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-artifact correlation output that can be merged into the final report.
 */
public record CorrelationResult(
    String summary,
    ConfidenceLevel confidence,
    List<Finding> findings,
    List<RecommendedAction> recommendedActions,
    List<String> contributingArtifactPaths
) {

    public CorrelationResult {
        findings = findings == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(findings));
        recommendedActions = recommendedActions == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
        contributingArtifactPaths = contributingArtifactPaths == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(contributingArtifactPaths));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("summary", summary);
        canonical.put("confidence", confidence != null ? confidence.name() : null);
        canonical.put("findings", findings.stream().map(Finding::toCanonicalMap).toList());
        canonical.put("recommendedActions", recommendedActions.stream().map(RecommendedAction::toCanonicalMap).toList());
        canonical.put("contributingArtifactPaths", contributingArtifactPaths);
        return canonical;
    }
}
