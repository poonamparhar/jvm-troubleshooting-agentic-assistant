package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;

/**
 * Canonical tool response returned to agents and logged in internal traceability.
 */
public record DiagnosticToolResult(
    ArtifactType artifactType,
    String artifactPath,
    String sliceId,
    String kind,
    String label,
    String content,
    String traceability,
    boolean truncated,
    boolean moreAvailable
) {

    public String renderForAgent() {
        StringBuilder builder = new StringBuilder();
        builder.append("Artifact: ").append(artifactPath != null ? artifactPath : "(unknown)").append('\n');
        builder.append("Artifact type: ").append(artifactType != null ? artifactType : "(unknown)").append('\n');
        builder.append("Slice id: ").append(sliceId != null ? sliceId : "(none)").append('\n');
        builder.append("Kind: ").append(kind != null ? kind : "(none)").append('\n');
        builder.append("Label: ").append(label != null ? label : "(none)").append('\n');
        builder.append("Traceability: ").append(traceability != null ? traceability : "(none)").append('\n');
        builder.append("Truncated: ").append(truncated).append('\n');
        builder.append("More available: ").append(moreAvailable).append('\n');
        builder.append("Content:\n");
        builder.append(content != null && !content.isBlank() ? content : "(none)");
        return builder.toString().stripTrailing();
    }
}
