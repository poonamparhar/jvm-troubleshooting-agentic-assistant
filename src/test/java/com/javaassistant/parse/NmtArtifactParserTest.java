package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NmtArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final NmtArtifactParser parser = new NmtArtifactParser();

    @TempDir
    Path tempDir;

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

    @Test
    void parsesCompressedClassSpaceSummaryFromGeneratedBundle() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("nmt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> classSpaceSummary = (Map<String, Object>) parsed.extractedData().get("classSpaceSummary");

        assertEquals(65_536L, classSpaceSummary.get("reservedKb"));
        assertEquals(63_488L, classSpaceSummary.get("committedKb"));
        assertEquals(62_976L, classSpaceSummary.get("usedKb"));
        assertEquals(512L, classSpaceSummary.get("wasteKb"));
        assertEquals(0.81d, ((Number) classSpaceSummary.get("wastePct")).doubleValue(), 0.0001d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("nmt-class-space-summary")));
    }

    @Test
    void warnsWhenNmtOutputIsPartial() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/java_nmt_partial.txt",
            """
                Native Memory Tracking:

                Total: reserved=102400KB, committed=51200KB
                -                 Class (reserved=20480KB, committed=10240KB)
                                    (classes #1824)
                                    (  Metadata:   )
                                    (    reserved=8192KB, committed=4096KB)
                                    (    used=3584KB)
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Long> totalKb = (Map<String, Long>) parsed.extractedData().get("totalKb");

        assertEquals(102_400L, totalKb.get("reservedKb"));
        assertEquals(51_200L, totalKb.get("committedKb"));
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("Thread category")));
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("Code category")));
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("GC category")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.NMT,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
