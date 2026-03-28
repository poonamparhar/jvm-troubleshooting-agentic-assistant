package com.example.parse;

import com.example.model.ArtifactType;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-backed parsing facade for deterministic artifact extraction.
 */
public class ArtifactParsingService {

    private final Map<ArtifactType, ArtifactParser> parsers;

    public ArtifactParsingService(List<ArtifactParser> parsers) {
        Map<ArtifactType, ArtifactParser> parserMap = new EnumMap<>(ArtifactType.class);
        for (ArtifactParser parser : parsers) {
            parserMap.put(parser.supportedType(), parser);
        }
        this.parsers = Map.copyOf(parserMap);
    }

    public ParsedArtifact parse(InputArtifact artifact) {
        ArtifactParser parser = parsers.get(artifact.type());
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for artifact type: " + artifact.type());
        }
        return parser.parse(artifact);
    }

    public boolean supports(ArtifactType artifactType) {
        return parsers.containsKey(artifactType);
    }
}
