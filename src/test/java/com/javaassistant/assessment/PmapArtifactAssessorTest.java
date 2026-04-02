package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.PmapArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PmapArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final PmapArtifactParser parser = new PmapArtifactParser();
    private final PmapArtifactAssessor engine = new PmapArtifactAssessor();

    @Test
    void emitsAnonymousMemoryPressureAndVirtualResidentMismatchFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-anon-pressure")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-pmap-anon-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-virtual-resident-mismatch")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-pmap-virtual-resident-mismatch")));
    }
}
