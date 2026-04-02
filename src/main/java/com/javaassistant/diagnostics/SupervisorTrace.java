package com.javaassistant.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the supervisor-level workflow trail behind a saved report.
 */
public record SupervisorTrace(
    OrchestrationWorkflowType workflowType,
    List<String> artifactPaths,
    List<SupervisorTraceStep> steps
) {

    public SupervisorTrace {
        artifactPaths = artifactPaths == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
        steps = steps == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("workflowType", workflowType != null ? workflowType.name() : null);
        canonical.put("artifactPaths", artifactPaths);
        canonical.put("steps", steps.stream().map(SupervisorTraceStep::toCanonicalMap).toList());
        return canonical;
    }
}
