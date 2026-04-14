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
        builder.append("Artifact: ").append(renderArtifact()).append('\n');
        builder.append("Slice: ").append(renderSlice()).append('\n');
        builder.append("Source: ").append(DiagnosticContextRenderSupport.renderSourceAnchor(traceability)).append('\n');
        builder.append("Truncated: ").append(truncated).append('\n');
        builder.append("More available: ").append(moreAvailable).append('\n');
        builder.append("Content:\n");
        builder.append(content != null && !content.isBlank() ? content : "(none)");
        return builder.toString().stripTrailing();
    }

    private String renderArtifact() {
        String path = artifactPath != null ? artifactPath : "(unknown)";
        return artifactType != null ? path + " (" + artifactType + ")" : path;
    }

    private String renderSlice() {
        String renderedLabel = label != null && !label.isBlank() ? label : "(none)";
        if (sliceId == null || sliceId.isBlank()) {
            return renderedLabel;
        }
        return renderedLabel + " [" + sliceId + "]";
    }
}
