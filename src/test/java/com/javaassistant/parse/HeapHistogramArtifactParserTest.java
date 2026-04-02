package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
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
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals(20, entries.size());
        assertEquals("[B", entries.getFirst().get("className"));
        assertEquals(285_050L, totals.get("instances"));
        assertEquals(144_832_000L, totals.get("bytes"));
        assertEquals(14_684_000L, summary.get("visibleBytes"));
        assertEquals(8_600_000L, summary.get("payloadBytes"));
        assertEquals(4_840_000L, summary.get("collectionBytes"));
        assertEquals(2_720_000L, summary.get("cacheLikeBytes"));
        assertTrue(((Number) summary.get("visibleCoverageRatio")).doubleValue() > 0.10d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("histogram-cache-like-entry")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("histogram-collection-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("histogram-payload-summary")));
        assertFalse(parsed.evidence().isEmpty());
    }
}
