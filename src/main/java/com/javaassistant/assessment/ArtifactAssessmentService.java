package com.javaassistant.assessment;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-backed facade for deterministic artifact assessment.
 */
public class ArtifactAssessmentService {

    private final Map<ArtifactType, ArtifactAssessor> engines;

    public ArtifactAssessmentService(List<ArtifactAssessor> engines) {
        Map<ArtifactType, ArtifactAssessor> engineMap = new EnumMap<>(ArtifactType.class);
        for (ArtifactAssessor engine : engines) {
            engineMap.put(engine.supportedType(), engine);
        }
        this.engines = Map.copyOf(engineMap);
    }

    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        ArtifactAssessor engine = engines.get(parsedArtifact.type());
        if (engine == null) {
            throw new IllegalArgumentException("No assessor registered for artifact type: " + parsedArtifact.type());
        }
        return engine.evaluate(parsedArtifact);
    }
}
