package com.example.rules;

import com.example.model.ArtifactType;
import com.example.model.ParsedArtifact;

/**
 * Deterministic rule engine for one supported artifact type.
 */
public interface ArtifactRuleEngine {

    ArtifactType supportedType();

    RuleEvaluation evaluate(ParsedArtifact parsedArtifact);
}
