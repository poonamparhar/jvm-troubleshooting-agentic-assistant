package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PmapArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final PmapArtifactParser parser = new PmapArtifactParser();

    @Test
    void parsesHeaderedPmapOutput() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) parsed.extractedData().get("header");
        @SuppressWarnings("unchecked")
        Map<String, Long> totals = (Map<String, Long>) parsed.extractedData().get("totals");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Long> categoryBreakdown = (Map<String, Long>) parsed.extractedData().get("categoryBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Long> rssCategoryBreakdown = (Map<String, Long>) parsed.extractedData().get("rssCategoryBreakdown");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> largestResidentMappings = (List<Map<String, Object>>) parsed.extractedData().get("largestResidentMappings");

        assertEquals(3_391_237L, header.get("pid"));
        assertTrue(String.valueOf(header.get("command")).contains("MetaspaceMemoryLeak"));
        assertEquals(5_495_680L, totals.get("sizeKb"));
        assertEquals(111_536L, totals.get("rssKb"));
        assertEquals(82_916L, totals.get("dirtyKb"));
        assertEquals(Boolean.TRUE, summary.get("rssAvailable"));
        assertEquals(5_317_372L, summary.get("anonSizeKb"));
        assertEquals(78_084L, summary.get("anonRssKb"));
        assertEquals(5_384_144L, summary.get("reservedGapKb"));
        assertEquals(5_317_372L, categoryBreakdown.get("anon"));
        assertEquals(78_084L, rssCategoryBreakdown.get("anon"));
        assertEquals(25_344L, largestResidentMappings.getFirst().get("sizeKb"));
        assertEquals(20_568L, largestResidentMappings.getFirst().get("rssKb"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("pmap-largest-mapping")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("pmap-largest-resident-mapping")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("pmap-resident-gap")));
    }

    @Test
    void parsesHeaderlessPmapOutputWithoutResidentMetrics() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/pmap.1")));

        @SuppressWarnings("unchecked")
        Map<String, Long> totals = (Map<String, Long>) parsed.extractedData().get("totals");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Long> categoryBreakdown = (Map<String, Long>) parsed.extractedData().get("categoryBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Long> rssCategoryBreakdown = (Map<String, Long>) parsed.extractedData().get("rssCategoryBreakdown");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> largestResidentMappings = (List<Map<String, Object>>) parsed.extractedData().get("largestResidentMappings");

        assertEquals(6_085_224L, totals.get("sizeKb"));
        assertEquals(6_030_316L, categoryBreakdown.get("anon"));
        assertEquals(Boolean.FALSE, summary.get("rssAvailable"));
        assertTrue(rssCategoryBreakdown.isEmpty());
        assertTrue(largestResidentMappings.isEmpty());
    }

    @Test
    void warnsWhenPmapOutputContainsClippedMappingLines() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/pmap_partial.txt",
            """
                4242: /usr/bin/java -jar service.jar
                Address           Kbytes     RSS   Dirty Mode  Mapping
                0000000100000000   65536   32768   16384 rw---   [ anon ]
                0000000101000000   32768   4096
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals(1L, ((Number) summary.get("mappingCount")).longValue());
        assertEquals(65_536L, ((Number) summary.get("totalSizeKb")).longValue());
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("incomplete or clipped")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("pmap-largest-mapping")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.PMAP,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
