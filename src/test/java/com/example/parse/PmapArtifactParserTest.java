package com.example.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PmapArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final PmapArtifactParser parser = new PmapArtifactParser();

    @Test
    void parsesHeaderedPmapOutput() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) parsed.extractedData().get("header");
        @SuppressWarnings("unchecked")
        Map<String, Long> categoryBreakdown = (Map<String, Long>) parsed.extractedData().get("categoryBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Long> totals = (Map<String, Long>) parsed.extractedData().get("totals");

        assertEquals(3_391_237L, header.get("pid"));
        assertTrue(String.valueOf(header.get("command")).contains("MetaspaceMemoryLeak"));
        assertTrue(categoryBreakdown.get("anon") > 0);
        assertTrue(totals.get("sizeKb") > 0);
    }
}
