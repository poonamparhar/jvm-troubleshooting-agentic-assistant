package com.javaassistant.ingest;

import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.InputArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Supported artifacts plus the full file inventory produced during bundle discovery.
 */
public record ArtifactDiscoveryResult(
    List<InputArtifact> supportedArtifacts,
    List<ArtifactInventoryEntry> inventoryEntries
) {

    public ArtifactDiscoveryResult {
        supportedArtifacts = supportedArtifacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(supportedArtifacts));
        inventoryEntries = inventoryEntries == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(inventoryEntries));
    }

    public List<ArtifactInventoryEntry> unsupportedEntries() {
        return inventoryEntries.stream()
            .filter(entry -> entry.status() == ArtifactInventoryStatus.UNSUPPORTED)
            .toList();
    }
}
