package com.example.data;

import com.example.model.ArtifactMetadata;
import com.example.model.InputArtifact;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Transitional raw input carrier used by the current CLI flow.
 *
 * <p>New evidence-first pipeline code should prefer {@link InputArtifact}.
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public record DiagnosticData(
    DataType type,
    String content,
    String sourceFile,
    LocalDateTime timestamp,
    Map<String, Object> metadata
) {

    public DiagnosticData(DataType type, String content, String sourceFile) {
        this(type, content, sourceFile, LocalDateTime.now(), new HashMap<>());
    }

    public DiagnosticData(DataType type, String content, String sourceFile, Map<String, Object> metadata) {
        this(type, content, sourceFile, LocalDateTime.now(), metadata);
    }

    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public int getContentSize() {
        return content != null ? content.length() : 0;
    }

    public InputArtifact toInputArtifact() {
        return new InputArtifact(
            type != null ? type.toArtifactType() : null,
            new ArtifactMetadata(sourceFile, sourceFile, getContentSize(), timestamp, Map.of()),
            content
        );
    }

    public static DiagnosticData fromInputArtifact(InputArtifact artifact) {
        ArtifactMetadata metadata = artifact.metadata();
        String sourcePath = metadata != null ? metadata.sourcePath() : null;
        LocalDateTime discoveredAt = metadata != null && metadata.discoveredAt() != null
            ? metadata.discoveredAt()
            : LocalDateTime.now();
        Map<String, Object> copiedMetadata = new HashMap<>();
        if (metadata != null) {
            copiedMetadata.putAll(metadata.attributes());
        }

        return new DiagnosticData(
            DataType.fromArtifactType(artifact.type()),
            artifact.content(),
            sourcePath,
            discoveredAt,
            copiedMetadata
        );
    }

    @Override
    public String toString() {
        return "DiagnosticData{" +
                "type=" + type +
                ", sourceFile='" + sourceFile + '\'' +
                ", timestamp=" + timestamp +
                ", contentSize=" + getContentSize() +
                ", metadata=" + metadata +
                '}';
    }
}
