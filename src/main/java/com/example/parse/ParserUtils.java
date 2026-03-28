package com.example.parse;

import com.example.model.Evidence;
import com.example.model.InputArtifact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ParserUtils {

    private ParserUtils() {
    }

    static List<String> lines(String content) {
        return List.of(content.split("\\R", -1));
    }

    static List<Integer> findLineNumbers(List<String> lines, String snippet) {
        List<Integer> lineNumbers = new ArrayList<>();
        if (snippet == null || snippet.isBlank()) {
            return lineNumbers;
        }

        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(snippet)) {
                lineNumbers.add(index + 1);
            }
        }
        return lineNumbers;
    }

    static Evidence evidence(
        String id,
        InputArtifact artifact,
        String label,
        String detail,
        String snippet,
        Map<String, Object> metrics
    ) {
        List<String> lines = lines(artifact.content());
        return new Evidence(
            id,
            artifact.metadata() != null ? artifact.metadata().sourcePath() : null,
            label,
            detail,
            snippet,
            findLineNumbers(lines, snippet),
            metrics
        );
    }
}
