package com.example.rules;

import com.example.model.ArtifactType;
import com.example.model.ParsedArtifact;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-backed facade for deterministic artifact rule evaluation.
 */
public class ArtifactRuleEngineService {

    private final Map<ArtifactType, ArtifactRuleEngine> engines;

    public ArtifactRuleEngineService(List<ArtifactRuleEngine> engines) {
        Map<ArtifactType, ArtifactRuleEngine> engineMap = new EnumMap<>(ArtifactType.class);
        for (ArtifactRuleEngine engine : engines) {
            engineMap.put(engine.supportedType(), engine);
        }
        this.engines = Map.copyOf(engineMap);
    }

    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        ArtifactRuleEngine engine = engines.get(parsedArtifact.type());
        if (engine == null) {
            throw new IllegalArgumentException("No rule engine registered for artifact type: " + parsedArtifact.type());
        }
        return engine.evaluate(parsedArtifact);
    }
}
