package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContainerMemoryArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ContainerMemoryArtifactParser parser = new ContainerMemoryArtifactParser();
    private final ContainerMemoryArtifactAssessor engine = new ContainerMemoryArtifactAssessor();

    @Test
    void emitsContainerLimitPressureOomAndPsiFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("container-memory-limit-pressure") && finding.evidenceIds().contains("container-memory-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-high-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-oom-events")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-reclaim-stalls")));
        assertTrue(evaluation.missingData().isEmpty());
        assertFalse(evaluation.recommendedActions().isEmpty());
    }
}
