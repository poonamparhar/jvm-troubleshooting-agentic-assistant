package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class GCTools {

    @Tool("Fetch more GC log context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=raw-chunk-013 to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include gcId=45, lines=640-650, section=summary, pattern=Evacuation Failure, start=08:32, end=08:33, or cause=G1 Compaction Pause. For compare, artifactRef can be baseline or current.")
    public String fetchGcContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active artifact") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.GC_LOG, "fetchGcContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused GC view from the current artifact. Good requests include pause-percentiles, cause-distribution, occupancy-progression, allocation-stalls, or cycle-summary.")
    public String computeGcView(
        @P("artifact reference such as current or baseline; leave blank for the active artifact") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.GC_LOG, "computeGcView", artifactRef, request);
    }
}
