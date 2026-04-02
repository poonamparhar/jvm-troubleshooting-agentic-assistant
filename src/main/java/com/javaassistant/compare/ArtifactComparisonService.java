package com.javaassistant.compare;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.assessment.AssessmentResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-backed structured comparison facade.
 */
public class ArtifactComparisonService {

    private final Map<ArtifactType, ArtifactComparator> comparators;

    public ArtifactComparisonService(List<ArtifactComparator> comparators) {
        Map<ArtifactType, ArtifactComparator> comparatorMap = new EnumMap<>(ArtifactType.class);
        for (ArtifactComparator comparator : comparators) {
            comparatorMap.put(comparator.supportedType(), comparator);
        }
        this.comparators = Map.copyOf(comparatorMap);
    }

    public boolean supports(ArtifactType artifactType) {
        return comparators.containsKey(artifactType);
    }

    public AssessmentResult compare(
        InputArtifact baseline,
        ParsedArtifact baselineParsed,
        InputArtifact current,
        ParsedArtifact currentParsed
    ) {
        ArtifactComparator comparator = comparators.get(baseline.type());
        if (comparator == null) {
            throw new IllegalArgumentException("No structured comparator registered for artifact type: " + baseline.type());
        }
        return comparator.compare(baseline, baselineParsed, current, currentParsed);
    }
}
