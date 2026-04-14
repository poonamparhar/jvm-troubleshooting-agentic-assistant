package com.javaassistant.compare;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.assessment.AssessmentResult;

/**
 * Structured comparator for baseline/current artifacts of the same type.
 */
public interface ArtifactComparator {

    ArtifactType supportedType();

    AssessmentResult compare(
        InputArtifact baseline,
        ParsedArtifact baselineParsed,
        InputArtifact current,
        ParsedArtifact currentParsed
    );
}
