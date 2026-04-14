package com.javaassistant.assessment;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;

/**
 * Deterministic assessor for one supported artifact type.
 */
public interface ArtifactAssessor {

    ArtifactType supportedType();

    AssessmentResult evaluate(ParsedArtifact parsedArtifact);
}
