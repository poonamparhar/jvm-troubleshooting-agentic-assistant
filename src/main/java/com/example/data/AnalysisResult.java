package com.example.data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of diagnostic analysis
 */
public record AnalysisResult(
    List<Issue> issues,
    List<Recommendation> recommendations,
    double confidence
    // Map<String, Object> metadata,
    // LocalDateTime timestamp,
    // String agentName
) {

    public AnalysisResult(List<Issue> issues, List<Recommendation> recommendations, double confidence, String agentName) {
        this(issues, recommendations, confidence);
    }

    public AnalysisResult(List<Issue> issues, List<Recommendation> recommendations, double confidence, Map<String, Object> metadata, String agentName) {
        this(issues, recommendations, confidence);
    }

    public boolean hasIssues() {
        return issues != null && !issues.isEmpty();
    }

    public boolean hasRecommendations() {
        return recommendations != null && !recommendations.isEmpty();
    }

    public int issueCount() {
        return issues != null ? issues.size() : 0;
    }

    public int recommendationCount() {
        return recommendations != null ? recommendations.size() : 0;
    }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "issues=" + issueCount() +
                ", recommendations=" + recommendationCount() +
                ", confidence=" + confidence +
              '}';
    }
}
