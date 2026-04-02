package com.javaassistant.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures which agent path produced a saved narrative and how that output was quality-checked.
 */
public record AgentTraceability(
    String stageId,
    String agentName,
    AgentNarrativeSource narrativeSource,
    ArtifactType artifactType,
    List<String> artifactPaths,
    List<String> evidenceIds,
    boolean selectedForUserNarrative,
    List<AgentQualityGateResult> qualityGates,
    List<AgentToolInvocation> toolInvocations,
    ModelExecutionTraceability modelExecutionTraceability
) {

    public AgentTraceability {
        artifactPaths = artifactPaths == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
        evidenceIds = evidenceIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidenceIds));
        qualityGates = qualityGates == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(qualityGates));
        toolInvocations = toolInvocations == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(toolInvocations));
    }

    public AgentTraceability(
        String stageId,
        String agentName,
        AgentNarrativeSource narrativeSource,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        boolean selectedForUserNarrative,
        List<AgentQualityGateResult> qualityGates
    ) {
        this(
            stageId,
            agentName,
            narrativeSource,
            artifactType,
            artifactPaths,
            evidenceIds,
            selectedForUserNarrative,
            qualityGates,
            List.of(),
            null
        );
    }

    public AgentTraceability(
        String stageId,
        String agentName,
        AgentNarrativeSource narrativeSource,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        boolean selectedForUserNarrative,
        List<AgentQualityGateResult> qualityGates,
        List<AgentToolInvocation> toolInvocations
    ) {
        this(
            stageId,
            agentName,
            narrativeSource,
            artifactType,
            artifactPaths,
            evidenceIds,
            selectedForUserNarrative,
            qualityGates,
            toolInvocations,
            null
        );
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("stageId", stageId);
        canonical.put("agentName", agentName);
        canonical.put("narrativeSource", narrativeSource != null ? narrativeSource.name() : null);
        canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
        canonical.put("artifactPaths", artifactPaths);
        canonical.put("evidenceIds", evidenceIds);
        canonical.put("selectedForUserNarrative", selectedForUserNarrative);
        canonical.put("qualityGates", qualityGates.stream().map(AgentQualityGateResult::toCanonicalMap).toList());
        canonical.put("toolInvocations", toolInvocations.stream().map(AgentToolInvocation::toCanonicalMap).toList());
        canonical.put("modelExecutionTraceability", modelExecutionTraceability != null ? modelExecutionTraceability.toCanonicalMap() : null);
        return canonical;
    }
}
