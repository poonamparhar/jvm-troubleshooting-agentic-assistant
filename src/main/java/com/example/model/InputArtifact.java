package com.example.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw input artifact plus the canonical metadata needed by later pipeline stages.
 */
public record InputArtifact(
    ArtifactType type,
    ArtifactMetadata metadata,
    String content
) {

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", type != null ? type.name() : null);
        canonical.put("metadata", metadata != null ? metadata.toCanonicalMap() : null);
        canonical.put("content", content);
        return canonical;
    }
}
