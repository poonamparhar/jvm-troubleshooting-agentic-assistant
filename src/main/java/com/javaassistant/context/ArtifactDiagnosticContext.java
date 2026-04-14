package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared bounded working context built from a fully parsed diagnostic artifact.
 */
public record ArtifactDiagnosticContext(
    ArtifactType artifactType,
    Map<String, Object> structuredFacts,
    List<DiagnosticHighlight> diagnosticHighlights,
    List<ContextSlice> structuredSlices,
    List<ContextSlice> representativeSlices,
    ContextCoverage coverage
) {

    public ArtifactDiagnosticContext {
        structuredFacts = structuredFacts == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(structuredFacts));
        diagnosticHighlights = diagnosticHighlights == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(diagnosticHighlights));
        structuredSlices = structuredSlices == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(structuredSlices));
        representativeSlices = representativeSlices == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(representativeSlices));
    }
}
