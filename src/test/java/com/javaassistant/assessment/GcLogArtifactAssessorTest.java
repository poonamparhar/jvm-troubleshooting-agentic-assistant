package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GcLogArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser parser = new GcLogArtifactParser();
    private final GcLogArtifactAssessor engine = new GcLogArtifactAssessor();

    @Test
    void emitsFullGcAndHeapPressureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-repeated-full-gcs")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-heap-saturation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-repeated-full-gcs")));
    }

    @Test
    void emitsAllocationStallPressureFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_allocation_stall.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-allocation-stall-pressure") && finding.evidenceIds().contains("gc-allocation-stall-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-allocation-stall-pressure")));
    }

    @Test
    void emitsMetaspaceFullGcFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-metaspace-full-gcs") && finding.evidenceIds().contains("gc-metaspace-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-metaspace-full-gcs")));
    }
}
