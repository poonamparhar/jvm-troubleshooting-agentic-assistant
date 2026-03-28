package com.example.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured extraction output for one artifact.
 */
public record ParsedArtifact(
    ArtifactType type,
    ArtifactMetadata metadata,
    String parserVersion,
    Map<String, Object> extractedData,
    List<Evidence> evidence,
    List<String> warnings
) {

    public ParsedArtifact {
        extractedData = extractedData == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extractedData));
        evidence = evidence == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidence));
        warnings = warnings == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", type != null ? type.name() : null);
        canonical.put("metadata", metadata != null ? metadata.toCanonicalMap() : null);
        canonical.put("parserVersion", parserVersion);
        canonical.put("extractedData", extractedData);
        canonical.put("evidence", evidence.stream().map(Evidence::toCanonicalMap).toList());
        canonical.put("warnings", warnings);
        return canonical;
    }
}
