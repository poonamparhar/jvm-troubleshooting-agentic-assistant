package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PmapArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final PmapArtifactParser parser = new PmapArtifactParser();
    private final PmapArtifactAssessor engine = new PmapArtifactAssessor();

    @TempDir
    Path tempDir;

    @Test
    void emitsAnonymousMemoryPressureAndVirtualResidentMismatchFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/pmap_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-anon-pressure")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-pmap-anon-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-virtual-resident-mismatch")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-pmap-virtual-resident-mismatch")));
    }

    @Test
    void emitsAnonymousResidentPressureWithoutReservationMismatchForGeneratedBundle() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createActiveNativeGrowthBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("pmap")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-anon-pressure")));
        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("pmap-virtual-resident-mismatch")));
    }
}
