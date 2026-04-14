package com.javaassistant.context;

import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full internal index used for bounded starting context plus later retrieval and computation.
 */
public record IndexedArtifactDiagnosticContext(
    InputArtifact inputArtifact,
    ParsedArtifact parsedArtifact,
    ArtifactDiagnosticContext diagnosticContext,
    Map<String, String> structuredBlocks,
    List<String> rawLines,
    Map<String, LineRange> rawSections,
    List<ContextSlice> allRawOrDerivedSlices
) {

    public IndexedArtifactDiagnosticContext {
        structuredBlocks = structuredBlocks == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(structuredBlocks));
        rawLines = rawLines == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(rawLines));
        rawSections = rawSections == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(rawSections));
        allRawOrDerivedSlices = allRawOrDerivedSlices == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(allRawOrDerivedSlices));
    }

    public record LineRange(int startLineInclusive, int endLineInclusive) {
    }
}
