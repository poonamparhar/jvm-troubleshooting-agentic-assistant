package com.javaassistant.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evidence anchors a finding to a specific artifact section or extracted metric.
 */
public record Evidence(
    String id,
    String artifactPath,
    String label,
    String detail,
    String snippet,
    List<Integer> lineNumbers,
    Map<String, Object> metrics
) {

    public Evidence {
        lineNumbers = lineNumbers == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(lineNumbers));
        metrics = metrics == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", id);
        canonical.put("artifactPath", artifactPath);
        canonical.put("label", label);
        canonical.put("detail", detail);
        canonical.put("snippet", snippet);
        canonical.put("lineNumbers", lineNumbers);
        canonical.put("metrics", metrics);
        return canonical;
    }
}
