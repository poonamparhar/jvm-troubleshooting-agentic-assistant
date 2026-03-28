package com.example.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NmtArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();

    @Test
    void parsesNmtSummaryIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Long> totalKb = (Map<String, Long>) parsed.extractedData().get("totalKb");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categories = (Map<String, Map<String, Long>>) parsed.extractedData().get("categories");
        @SuppressWarnings("unchecked")
        Map<String, Object> classSummary = (Map<String, Object>) parsed.extractedData().get("classSummary");

        assertEquals(4_361_177L, totalKb.get("reservedKb"));
        assertEquals(160_397L, totalKb.get("committedKb"));
        assertTrue(categories.containsKey("Class"));
        assertTrue(categories.containsKey("Thread"));
        assertEquals(41_417L, classSummary.get("classCount"));
        assertFalse(parsed.evidence().isEmpty());
    }
}
