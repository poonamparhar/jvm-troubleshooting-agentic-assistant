package com.example.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.model.ArtifactType;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArtifactLoaderTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());

    @Test
    void loadsAndClassifiesSingleFile() throws Exception {
        var artifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));

        assertEquals(ArtifactType.NMT, artifact.type());
        assertEquals("java_nmt_summary_3391237.txt", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Native Memory Tracking"));
    }

    @Test
    void discoversSupportedArtifactsFromDirectory() throws Exception {
        var artifacts = loader.discover(Path.of("samples/single_process_data"));
        Set<ArtifactType> discoveredTypes = artifacts.stream().map(artifact -> artifact.type()).collect(java.util.stream.Collectors.toSet());

        assertTrue(artifacts.size() >= 4);
        assertTrue(discoveredTypes.contains(ArtifactType.GC_LOG));
        assertTrue(discoveredTypes.contains(ArtifactType.NMT));
        assertTrue(discoveredTypes.contains(ArtifactType.PMAP));
    }
}
