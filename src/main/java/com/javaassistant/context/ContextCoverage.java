package com.javaassistant.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes what the bounded starting context omitted or could not extract.
 */
public record ContextCoverage(
    String artifactPath,
    List<String> omittedStructuredBlocks,
    List<String> omittedRawSlices,
    List<String> parseGaps,
    List<String> truncationMarkers,
    boolean additionalContextAvailable
) {

    public ContextCoverage(
        List<String> omittedStructuredBlocks,
        List<String> omittedRawSlices,
        List<String> parseGaps,
        List<String> truncationMarkers,
        boolean additionalContextAvailable
    ) {
        this(null, omittedStructuredBlocks, omittedRawSlices, parseGaps, truncationMarkers, additionalContextAvailable);
    }

    public ContextCoverage {
        omittedStructuredBlocks = immutableList(omittedStructuredBlocks);
        omittedRawSlices = immutableList(omittedRawSlices);
        parseGaps = immutableList(parseGaps);
        truncationMarkers = immutableList(truncationMarkers);
    }

    private static List<String> immutableList(List<String> values) {
        return values == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
