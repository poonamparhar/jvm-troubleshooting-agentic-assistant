package com.example.parse;

import com.example.model.ArtifactType;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;

/**
 * Deterministic parser contract for supported artifact types.
 */
public interface ArtifactParser {

    ArtifactType supportedType();

    ParsedArtifact parse(InputArtifact artifact);
}
