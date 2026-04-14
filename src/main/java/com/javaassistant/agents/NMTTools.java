package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class NMTTools {

    @Tool("Fetch more Native Memory Tracking context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=categoryDeltas to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include section=categories, category=Class, category=Thread, category=Internal, category=Unknown, category=Arena Chunk, lines=10-50, pattern=Metadata, pattern=Class space, section=metaspaceSummary, or section=classSpaceSummary. For compare, artifactRef can be baseline or current.")
    public String fetchNmtContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active artifact") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.NMT, "fetchNmtContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused NMT view from the current artifact. Good requests include metaspace-summary, class-space-summary, reservation-summary, internal-summary, thread-summary, category-summary, or delta-summary.")
    public String computeNmtView(
        @P("artifact reference such as current or baseline; leave blank for the active artifact") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.NMT, "computeNmtView", artifactRef, request);
    }
}
