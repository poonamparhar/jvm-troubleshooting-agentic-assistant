package com.example.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HsErrArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final HsErrArtifactParser parser = new HsErrArtifactParser();

    @Test
    void parsesCrashHeaderAndProblematicFrame() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));

        @SuppressWarnings("unchecked")
        Map<String, String> problematicFrame = (Map<String, String>) parsed.extractedData().get("problematicFrame");

        assertEquals("SIGBUS", parsed.extractedData().get("signal"));
        assertTrue(String.valueOf(parsed.extractedData().get("jreVersion")).contains("11.0.4+10"));
        assertTrue(problematicFrame.get("symbol").contains("G1FullGCMarker::follow_object"));
        assertFalse(parsed.evidence().isEmpty());
    }
}
