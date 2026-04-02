package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AgentDiagnosticContextBuilderTest {

    private final AgentDiagnosticContextBuilder contextBuilder = new AgentDiagnosticContextBuilder();

    @Test
    void buildsSingleArtifactContextFromStructuredContextAndChunkedRawContent() {
        String longAttributeValue = "attr-".repeat(80);
        String longDetail = "detail-".repeat(70);
        String longContent = IntStream.rangeClosed(1, 720)
            .mapToObj(index -> "synthetic-line-" + index)
            .collect(Collectors.joining("\n"));
        InputArtifact artifact = new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/tmp/gc.log",
                "gc.log",
                longContent.length(),
                LocalDateTime.of(2026, 3, 31, 18, 0),
                Map.of("veryLongAttribute", longAttributeValue)
            ),
            longContent
        );

        Map<String, Object> extractedData = new LinkedHashMap<>();
        for (int index = 1; index <= 18; index++) {
            extractedData.put("section" + index, Map.of("value", "v".repeat(420)));
        }

        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.GC_LOG,
            artifact.metadata(),
            "test-parser",
            extractedData,
            List.of(new Evidence("ev-1", "/tmp/gc.log", "Pause sample", longDetail, "snippet", List.of(10), Map.of("pauseMs", 123.4))),
            List.of()
        );

        String packet = contextBuilder.buildSingleArtifactContext(
            new AgentDiagnosticContextBuilder.ArtifactGrounding(
                artifact,
                parsedArtifact,
                new AssessmentResult(List.of(), List.of(), List.of())
            )
        );

        assertTrue(packet.contains("STRUCTURED_FACTS"));
        assertTrue(packet.contains("STRUCTURED_CONTEXT_SLICES"));
        assertTrue(packet.contains("REPRESENTATIVE_CONTEXT_SLICES"));
        assertTrue(packet.contains("CONTEXT_COVERAGE"));
        assertTrue(packet.contains("Slice 1"));
        assertTrue(packet.contains("Omitted Structured Blocks ("));
        assertTrue(packet.contains("Omitted Context Slices ("));
        assertTrue(packet.contains("Retrieval Hint: leave the selector blank to fetch the next omitted item."));
        assertTrue(packet.contains("offset=<charOffset>, chars=<charCount>"));
        assertTrue(packet.contains(longAttributeValue));
        assertTrue(packet.contains(longDetail));
        assertFalse(packet.contains("... [truncated]"));
        assertTrue(packet.contains("... "));
        assertFalse(packet.contains("DETERMINISTIC_FINDINGS"));
        assertFalse(packet.contains("RECOMMENDED_ACTIONS"));
    }
}
