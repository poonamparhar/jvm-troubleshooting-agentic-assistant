package com.example.ingest;

import com.example.detect.ArtifactClassifier;
import com.example.model.ArtifactMetadata;
import com.example.model.ArtifactType;
import com.example.model.InputArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads files from disk and classifies them into canonical input artifacts.
 */
public class ArtifactLoader {

    private static final int MAX_TOKENS = 50000;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final ArtifactClassifier classifier;

    public ArtifactLoader(ArtifactClassifier classifier) {
        this.classifier = classifier;
    }

    public InputArtifact load(Path path) throws IOException {
        return load(path, null);
    }

    public InputArtifact load(Path path, ArtifactType overrideType) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            throw new IOException("Expected a file but found a directory: " + path);
        }

        String content = Files.readString(path);
        String truncatedContent = truncateForInteractiveAnalysis(content);
        ArtifactType artifactType = overrideType != null ? overrideType : classifier.classify(truncatedContent);

        ArtifactMetadata metadata = new ArtifactMetadata(
            path.toString(),
            path.getFileName() != null ? path.getFileName().toString() : path.toString(),
            truncatedContent.length(),
            LocalDateTime.now(),
            Map.of(
                "originalContentLength", String.valueOf(content.length()),
                "truncated", String.valueOf(!content.equals(truncatedContent))
            )
        );

        return new InputArtifact(artifactType, metadata, truncatedContent);
    }

    public List<InputArtifact> discover(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Path not found: " + path);
        }

        if (Files.isRegularFile(path)) {
            return List.of(load(path));
        }

        try (Stream<Path> stream = Files.walk(path)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString))
                .map(this::loadQuietly)
                .filter(artifact -> artifact.type() != ArtifactType.UNKNOWN)
                .toList();
        }
    }

    public String truncateForInteractiveAnalysis(String content) {
        int estimatedTokens = content.length() / CHARS_PER_TOKEN_ESTIMATE;
        if (estimatedTokens <= MAX_TOKENS) {
            return content;
        }

        int charsToKeep = MAX_TOKENS * CHARS_PER_TOKEN_ESTIMATE;
        int keepAtStart = charsToKeep / 3;
        int keepAtEnd = charsToKeep / 3;
        int truncatePoint = content.length() - keepAtEnd;

        if (truncatePoint <= keepAtStart) {
            String truncated = content.substring(0, Math.min(charsToKeep, content.length()));
            return truncated + "\n\n[CONTENT TRUNCATED - File too large for full analysis]";
        }

        return content.substring(0, keepAtStart)
            + "\n\n[... CONTENT TRUNCATED DUE TO SIZE - "
            + String.format("%,d", estimatedTokens)
            + " estimated tokens, showing first and last portions only ...]\n\n"
            + content.substring(truncatePoint);
    }

    private InputArtifact loadQuietly(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            return new InputArtifact(
                ArtifactType.UNKNOWN,
                new ArtifactMetadata(path.toString(), path.getFileName() != null ? path.getFileName().toString() : path.toString(), 0L),
                ""
            );
        }
    }
}
