package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerMemoryArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ContainerMemoryArtifactParser parser = new ContainerMemoryArtifactParser();

    @Test
    void parsesContainerMemorySnapshotIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) parsed.extractedData().get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> stat = (Map<String, Object>) parsed.extractedData().get("stat");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> pressure = (Map<String, Map<String, Object>>) parsed.extractedData().get("pressure");
        @SuppressWarnings("unchecked")
        List<String> sectionsPresent = (List<String>) parsed.extractedData().get("sectionsPresent");

        assertEquals(1_040_187_392L, ((Number) summary.get("currentBytes")).longValue());
        assertEquals(Boolean.TRUE, summary.get("maxDefined"));
        assertEquals(1_073_741_824L, ((Number) summary.get("maxBytes")).longValue());
        assertEquals(Boolean.TRUE, summary.get("highDefined"));
        assertEquals(943_718_400L, ((Number) summary.get("highBytes")).longValue());
        assertEquals(0.96875d, ((Number) summary.get("usageOfMaxRatio")).doubleValue(), 0.0001d);
        assertEquals(128L, ((Number) events.get("high")).longValue());
        assertEquals(1L, ((Number) events.get("oom_kill")).longValue());
        assertEquals(775_946_240L, ((Number) stat.get("anon")).longValue());
        assertEquals(188_743_680L, ((Number) stat.get("file")).longValue());
        assertEquals(6.50d, ((Number) pressure.get("some").get("avg10")).doubleValue(), 0.0001d);
        assertTrue(sectionsPresent.contains("memory.pressure"));

        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-events")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-breakdown")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-pressure")));
    }
}
