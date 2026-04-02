package com.javaassistant.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ArtifactTypeTest {

    @Test
    void parsesCanonicalAndLegacyExternalNames() {
        assertEquals(ArtifactType.GC_LOG, ArtifactType.fromExternalName("GC_LOG"));
        assertEquals(ArtifactType.NMT, ArtifactType.fromExternalName("NMT"));
        assertEquals(ArtifactType.NMT, ArtifactType.fromExternalName("NMT_MEMORY"));
        assertEquals(ArtifactType.PMAP, ArtifactType.fromExternalName("PMAP"));
        assertEquals(ArtifactType.PMAP, ArtifactType.fromExternalName("PMAP_OUTPUT"));
        assertEquals(ArtifactType.CONTAINER_MEMORY, ArtifactType.fromExternalName("container-memory"));
        assertEquals(ArtifactType.HS_ERR_LOG, ArtifactType.fromExternalName("hs_err.log"));
    }

    @Test
    void rejectsUnsupportedExternalNames() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactType.fromExternalName("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> ArtifactType.fromExternalName("HEAP_DUMP"));
        assertThrows(IllegalArgumentException.class, () -> ArtifactType.fromExternalName(""));
    }

    @Test
    void exposesHumanReadableDescriptions() {
        assertEquals("JVM Native Memory Tracking Output", ArtifactType.NMT.description());
        assertEquals("Process Memory Map Output", ArtifactType.PMAP.description());
        assertEquals("Kernel OOM / Restart Signal Log", ArtifactType.OOM_SIGNAL.description());
    }
}
