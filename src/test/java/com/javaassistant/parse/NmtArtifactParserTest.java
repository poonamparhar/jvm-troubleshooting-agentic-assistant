package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
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
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-category-class")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-category-gc")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-class-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-metaspace-summary")));
        assertFalse(parsed.evidence().isEmpty());
    }

    @Test
    void parsesNmtDiffIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_diff_3391237.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Long> totalKb = (Map<String, Long>) parsed.extractedData().get("totalKb");
        @SuppressWarnings("unchecked")
        Map<String, Long> totalDeltaKb = (Map<String, Long>) parsed.extractedData().get("totalDeltaKb");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categories = (Map<String, Map<String, Long>>) parsed.extractedData().get("categories");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categoryDeltas = (Map<String, Map<String, Long>>) parsed.extractedData().get("categoryDeltas");
        @SuppressWarnings("unchecked")
        Map<String, Long> metaspaceSummaryDeltas = (Map<String, Long>) parsed.extractedData().get("metaspaceSummaryDeltas");

        assertEquals(4_361_084L, totalKb.get("reservedKb"));
        assertEquals(9_123L, totalDeltaKb.get("reservedKb"));
        assertEquals(-169_093L, totalDeltaKb.get("committedKb"));
        assertEquals(27_762L, categories.get("Class").get("committedKb"));
        assertEquals(19_602L, categoryDeltas.get("Class").get("committedKb"));
        assertEquals(12_176L, metaspaceSummaryDeltas.get("usedKb"));
        assertEquals("diff", parsed.extractedData().get("snapshotKind"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-total-delta")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-category-delta-class")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-class-summary-delta")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-metaspace-summary-delta")));
    }
}
