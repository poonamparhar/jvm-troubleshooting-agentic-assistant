package com.example.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical problem statement emitted by artifact rules or correlators.
 */
public record Finding(
    String id,
    String title,
    String summary,
    String category,
    SeverityLevel severity,
    ConfidenceLevel confidence,
    FindingStatus status,
    List<String> artifactPaths,
    List<String> evidenceIds,
    String rationale
) {

    public Finding {
        artifactPaths = artifactPaths == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
        evidenceIds = evidenceIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidenceIds));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", id);
        canonical.put("title", title);
        canonical.put("summary", summary);
        canonical.put("category", category);
        canonical.put("severity", severity != null ? severity.name() : null);
        canonical.put("confidence", confidence != null ? confidence.name() : null);
        canonical.put("status", status != null ? status.name() : null);
        canonical.put("artifactPaths", artifactPaths);
        canonical.put("evidenceIds", evidenceIds);
        canonical.put("rationale", rationale);
        return canonical;
    }
}
