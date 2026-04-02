package com.javaassistant.report;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.SeverityLevel;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only summary row for one saved analysis bundle.
 */
public record ReportCatalogEntry(
    String analysisId,
    LocalDateTime createdAt,
    SeverityLevel overallSeverity,
    ConfidenceLevel confidence,
    List<ArtifactType> artifactTypes,
    String redactionProfile,
    int inputArtifactCount,
    boolean hasCorrelationResult,
    Path bundlePath
) {

    public ReportCatalogEntry {
        artifactTypes = artifactTypes == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactTypes));
    }

    public boolean matches(SeverityLevel severityFilter, ArtifactType artifactTypeFilter) {
        if (severityFilter != null && overallSeverity != severityFilter) {
            return false;
        }
        if (artifactTypeFilter != null && !artifactTypes.contains(artifactTypeFilter)) {
            return false;
        }
        return true;
    }
}
