package com.javaassistant.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records one explainable supervisor decision inside a multi-agent workflow.
 */
public record SupervisorTraceStep(
    String stepId,
    SupervisorTraceStepType stepType,
    String stageId,
    String decision,
    ArtifactType artifactType,
    List<String> artifactPaths,
    List<String> evidenceIds,
    List<String> findingIds,
    String agentName,
    AgentNarrativeSource narrativeSource,
    boolean selectedForUserNarrative,
    List<AgentToolInvocation> toolInvocations,
    ModelExecutionTraceability modelExecutionTraceability
) {

    public SupervisorTraceStep {
        artifactPaths = artifactPaths == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
        evidenceIds = evidenceIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidenceIds));
        findingIds = findingIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(findingIds));
        toolInvocations = toolInvocations == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(toolInvocations));
    }

    public SupervisorTraceStep(
        String stepId,
        SupervisorTraceStepType stepType,
        String stageId,
        String decision,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        List<String> findingIds,
        String agentName,
        AgentNarrativeSource narrativeSource,
        boolean selectedForUserNarrative
    ) {
        this(
            stepId,
            stepType,
            stageId,
            decision,
            artifactType,
            artifactPaths,
            evidenceIds,
            findingIds,
            agentName,
            narrativeSource,
            selectedForUserNarrative,
            List.of(),
            null
        );
    }

    public SupervisorTraceStep(
        String stepId,
        SupervisorTraceStepType stepType,
        String stageId,
        String decision,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        List<String> findingIds,
        String agentName,
        AgentNarrativeSource narrativeSource,
        boolean selectedForUserNarrative,
        List<AgentToolInvocation> toolInvocations
    ) {
        this(
            stepId,
            stepType,
            stageId,
            decision,
            artifactType,
            artifactPaths,
            evidenceIds,
            findingIds,
            agentName,
            narrativeSource,
            selectedForUserNarrative,
            toolInvocations,
            null
        );
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("stepId", stepId);
        canonical.put("stepType", stepType != null ? stepType.name() : null);
        canonical.put("stageId", stageId);
        canonical.put("decision", decision);
        canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
        canonical.put("artifactPaths", artifactPaths);
        canonical.put("evidenceIds", evidenceIds);
        canonical.put("findingIds", findingIds);
        canonical.put("agentName", agentName);
        canonical.put("narrativeSource", narrativeSource != null ? narrativeSource.name() : null);
        canonical.put("selectedForUserNarrative", selectedForUserNarrative);
        canonical.put("toolInvocations", toolInvocations.stream().map(AgentToolInvocation::toCanonicalMap).toList());
        canonical.put("modelExecutionTraceability", modelExecutionTraceability != null ? modelExecutionTraceability.toCanonicalMap() : null);
        return canonical;
    }
}
