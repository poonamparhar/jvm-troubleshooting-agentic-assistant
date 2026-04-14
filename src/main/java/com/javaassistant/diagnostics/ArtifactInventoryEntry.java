package com.javaassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inventory entry for one file considered during bundle discovery.
 */
public record ArtifactInventoryEntry(
    String sourcePath,
    String displayName,
    ArtifactType artifactType,
    ArtifactInventoryStatus status,
    String detail
) {

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourcePath", sourcePath);
        canonical.put("displayName", displayName);
        canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
        canonical.put("status", status != null ? status.name() : null);
        canonical.put("detail", detail);
        return canonical;
    }
}
