package com.javaassistant;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import java.util.List;

/**
 * Centralizes runtime routing decisions so supported artifacts use the structured path by default.
 */
final class RuntimeRoutingPolicy {

    private RuntimeRoutingPolicy() {
    }

    static boolean supportsStructuredAnalysis(InputArtifact artifact) {
        if (artifact == null || artifact.type() == null) {
            return false;
        }
        return supportsStructuredAnalysis(artifact.type());
    }

    static boolean supportsStructuredAnalysis(ArtifactType artifactType) {
        if (artifactType == null) {
            return false;
        }
        return switch (artifactType) {
            case GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL -> true;
            default -> false;
        };
    }

    static boolean supportsStructuredComparison(ArtifactType artifactType) {
        if (artifactType == null) {
            return false;
        }
        return switch (artifactType) {
            case JFR, THREAD_DUMP, HEAP_HISTOGRAM, NMT, PMAP -> true;
            default -> false;
        };
    }

    static boolean supportsStructuredCorrelation(List<InputArtifact> artifacts) {
        return artifacts != null
            && artifacts.size() >= 2
            && artifacts.stream().allMatch(RuntimeRoutingPolicy::supportsStructuredAnalysis);
    }

    static boolean reportMatchesLoadedData(AnalysisReport report, InputArtifact loadedArtifact) {
        if (report == null) {
            return false;
        }
        if (loadedArtifact == null) {
            return true;
        }
        String loadedSourcePath = loadedArtifact.metadata() != null ? loadedArtifact.metadata().sourcePath() : null;
        return report.inputArtifacts().stream()
            .map(artifact -> artifact.metadata() != null ? artifact.metadata().sourcePath() : null)
            .anyMatch(sourcePath -> sourcePath != null && sourcePath.equals(loadedSourcePath));
    }
}
