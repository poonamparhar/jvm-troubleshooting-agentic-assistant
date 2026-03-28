package com.example.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeapHistogramArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HeapHistogramArtifactParser parser = new HeapHistogramArtifactParser();

    @Test
    void parsesHistogramEntriesAndTotals() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/heap_histogram_1.txt")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) parsed.extractedData().get("entries");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) parsed.extractedData().get("totals");

        assertEquals(20, entries.size());
        assertEquals("[B", entries.getFirst().get("className"));
        assertEquals(285_050L, totals.get("instances"));
        assertEquals(144_832_000L, totals.get("bytes"));
        assertFalse(parsed.evidence().isEmpty());
    }
}
