package com.example.rules;

import com.example.model.ActionPriority;
import com.example.model.ActionType;
import com.example.model.ConfidenceLevel;
import com.example.model.Finding;
import com.example.model.FindingStatus;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;
import com.example.model.RecommendedAction;
import com.example.model.SeverityLevel;
import java.util.List;
import java.util.Map;

final class RuleSupport {

    private RuleSupport() {
    }

    static String artifactPath(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }

    static Finding finding(
        ParsedArtifact artifact,
        String id,
        String title,
        String summary,
        String category,
        SeverityLevel severity,
        ConfidenceLevel confidence,
        FindingStatus status,
        List<String> evidenceIds,
        String rationale
    ) {
        return new Finding(
            id,
            title,
            summary,
            category,
            severity,
            confidence,
            status,
            List.of(artifactPath(artifact)),
            evidenceIds,
            rationale
        );
    }

    static RecommendedAction action(
        String id,
        String summary,
        String rationale,
        ActionType actionType,
        ActionPriority priority,
        List<String> steps,
        List<String> relatedFindingIds
    ) {
        return new RecommendedAction(id, summary, rationale, actionType, priority, steps, relatedFindingIds);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    static long longValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static double doubleValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }
}
