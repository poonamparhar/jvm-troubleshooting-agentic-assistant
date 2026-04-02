package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
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
