package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;

/**
 * Deterministic parser contract for supported artifact types.
 */
public interface ArtifactParser {

    ArtifactType supportedType();

    ParsedArtifact parse(InputArtifact artifact);
}
