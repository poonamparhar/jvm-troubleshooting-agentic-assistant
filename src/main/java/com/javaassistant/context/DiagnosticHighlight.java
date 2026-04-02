package com.javaassistant.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact extracted fact that gives an agent a high-signal diagnostic clue plus traceability.
 */
public record DiagnosticHighlight(
    String highlightId,
    String label,
    String detail,
    Map<String, Object> metrics,
    String traceability
) {

    public DiagnosticHighlight {
        metrics = metrics == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}
