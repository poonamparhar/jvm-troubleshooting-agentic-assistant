package com.example.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable metadata describing an input artifact without embedding analysis results.
 */
public record ArtifactMetadata(
    String sourcePath,
    String displayName,
    long contentLength,
    LocalDateTime discoveredAt,
    Map<String, String> attributes
) {

    public ArtifactMetadata {
        attributes = attributes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public ArtifactMetadata(String sourcePath, String displayName, long contentLength) {
        this(sourcePath, displayName, contentLength, LocalDateTime.now(), Map.of());
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourcePath", sourcePath);
        canonical.put("displayName", displayName);
        canonical.put("contentLength", contentLength);
        canonical.put("discoveredAt", discoveredAt != null ? discoveredAt.toString() : null);
        canonical.put("attributes", attributes);
        return canonical;
    }
}
