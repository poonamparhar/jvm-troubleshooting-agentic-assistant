package com.javaassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result for one quality gate applied to an agent-produced narrative.
 */
public record AgentQualityGateResult(
    String gateId,
    AgentQualityGateStatus status,
    String detail
) {

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("gateId", gateId);
        canonical.put("status", status != null ? status.name() : null);
        canonical.put("detail", detail);
        return canonical;
    }
}
