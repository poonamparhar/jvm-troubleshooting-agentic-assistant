package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class PmapTools {

    @Tool("Fetch more pmap context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=largestResidentMappings to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include category=anon, pattern=[heap], section=categorySummaries, lines=1-40, or section=largestResidentMappings. For compare, artifactRef can be baseline or current.")
    public String fetchPmapContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active artifact") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.PMAP, "fetchPmapContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused pmap view from the current artifact. Good requests include resident-summary, reservation-summary, virtual-resident-summary, category-summary, rss-summary, or concentration-summary.")
    public String computePmapView(
        @P("artifact reference such as current or baseline; leave blank for the active artifact") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.PMAP, "computePmapView", artifactRef, request);
    }
}
