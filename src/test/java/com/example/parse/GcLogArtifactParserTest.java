package com.example.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcLogArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser parser = new GcLogArtifactParser();

    @Test
    void parsesG1GcLogSummary() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("eventCount")).longValue() > 0);
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() > 0.0);
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void detectsZgcCollector() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_fullgc.log")));

        assertEquals("ZGC", parsed.extractedData().get("collector"));
    }
}
