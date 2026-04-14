package com.javaassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records one curated retrieval or focused computation performed by an agent tool.
 */
public record AgentToolInvocation(
    String toolName,
    String toolFamily,
    ArtifactType artifactType,
    String artifactPath,
    String request,
    String sliceId,
    String label,
    String traceability,
    boolean truncated,
    boolean moreAvailable
) {

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("toolName", toolName);
        canonical.put("toolFamily", toolFamily);
        canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
        canonical.put("artifactPath", artifactPath);
        canonical.put("request", request);
        canonical.put("sliceId", sliceId);
        canonical.put("label", label);
        canonical.put("traceability", traceability);
        canonical.put("truncated", truncated);
        canonical.put("moreAvailable", moreAvailable);
        return canonical;
    }
}
