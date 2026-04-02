package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class ContainerMemoryTools {

    @Tool("Fetch more container-memory context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=memory.pressure to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include section=memory.events, section=memory.stat, section=memory.pressure, pattern=oom, or lines=1-40.")
    public String fetchContainerMemoryContext(
        @P("artifact reference such as current or a source path; leave blank for the active snapshot") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.CONTAINER_MEMORY, "fetchContainerMemoryContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused container-memory view from the current artifact. Good requests include budget-summary, pressure-summary, or event-summary.")
    public String computeContainerMemoryView(
        @P("artifact reference such as current; leave blank for the active snapshot") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.CONTAINER_MEMORY, "computeContainerMemoryView", artifactRef, request);
    }
}
