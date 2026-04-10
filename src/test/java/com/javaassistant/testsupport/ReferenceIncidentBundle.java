package com.javaassistant.testsupport;

import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import java.nio.file.Path;
import java.util.List;

public record ReferenceIncidentBundle(
    String bundleId,
    Path manifestPath,
    OrchestrationWorkflowType workflowType,
    List<String> artifactPaths,
    boolean expectUserNarrative,
    String expectedSelectedAgent,
    AgentNarrativeSource expectedSelectedSource,
    List<String> requiredTraceabilityAgents,
    List<String> requiredTraceAgents,
    List<String> requiredStepIds,
    List<String> expectedFindingIds,
    List<SupervisorTraceStepType> requiredStepTypes,
    int minFindings,
    int minSupervisorTraceSteps,
    StubMode stubMode,
    List<String> requiredSelectedToolNames,
    int minSelectedToolInvocations,
    List<String> scenarioIds
) {

    public String primaryScenarioId() {
        return scenarioIds().isEmpty() ? bundleId() : scenarioIds().getFirst();
    }
}
