package com.javaassistant;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import java.util.List;
import java.util.function.Predicate;

/**
 * Centralizes runtime routing decisions so supported artifacts use the structured path by default.
 */
final class RuntimeRoutingPolicy {

    enum AnalyzeCommandMode {
        SINGLE_ARTIFACT,
        COMPARE_PAIR,
        SEQUENCE_SET,
        CORRELATE_SET,
        UNSUPPORTED
    }

    record AnalyzeCommandRoute(AnalyzeCommandMode mode, String message) {

        boolean supported() {
            return mode != AnalyzeCommandMode.UNSUPPORTED;
        }
    }

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
            case GC_LOG, JFR, THREAD_DUMP, HEAP_HISTOGRAM, NMT, PMAP -> true;
            default -> false;
        };
    }

    static boolean supportsStructuredCorrelation(List<InputArtifact> artifacts) {
        return artifacts != null
            && artifacts.size() >= 2
            && artifacts.stream().allMatch(RuntimeRoutingPolicy::supportsStructuredAnalysis);
    }

    static AnalyzeCommandRoute selectAnalyzeCommandRoute(
        List<InputArtifact> artifacts,
        Predicate<ArtifactType> comparisonSupport
    ) {
        if (artifacts == null || artifacts.isEmpty()) {
            return new AnalyzeCommandRoute(
                AnalyzeCommandMode.UNSUPPORTED,
                "No supported diagnostic artifacts were found in the supplied input."
            );
        }

        if (!artifacts.stream().allMatch(RuntimeRoutingPolicy::supportsStructuredAnalysis)) {
            return new AnalyzeCommandRoute(
                AnalyzeCommandMode.UNSUPPORTED,
                "One or more supplied artifacts are not supported by the AI troubleshooting pipeline."
            );
        }

        if (artifacts.size() == 1) {
            return new AnalyzeCommandRoute(AnalyzeCommandMode.SINGLE_ARTIFACT, null);
        }

        ArtifactType firstType = artifacts.getFirst().type();
        boolean sameTypeSet = artifacts.stream().allMatch(artifact -> artifact.type() == firstType);
        if (sameTypeSet) {
            boolean comparisonAvailable = firstType != null
                && supportsStructuredComparison(firstType)
                && (comparisonSupport == null || comparisonSupport.test(firstType));
            if (artifacts.size() == 2 && comparisonAvailable) {
                return new AnalyzeCommandRoute(AnalyzeCommandMode.COMPARE_PAIR, null);
            }

            if (artifacts.size() > 2 && comparisonAvailable) {
                return new AnalyzeCommandRoute(AnalyzeCommandMode.SEQUENCE_SET, null);
            }

            if (artifacts.size() == 2) {
                String typeDescription = firstType != null ? firstType.description() : "unknown";
                return new AnalyzeCommandRoute(
                    AnalyzeCommandMode.UNSUPPORTED,
                    "You supplied two "
                        + typeDescription
                        + " artifacts, but AI comparison is not available for that artifact type yet."
                );
            }

            String typeDescription = firstType != null ? firstType.description() : "unknown";
            return new AnalyzeCommandRoute(
                AnalyzeCommandMode.UNSUPPORTED,
                "You supplied "
                    + artifacts.size()
                    + " "
                    + typeDescription
                    + " artifacts, but AI sequence analysis is not available for that artifact type yet."
            );
        }

        return new AnalyzeCommandRoute(AnalyzeCommandMode.CORRELATE_SET, null);
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
