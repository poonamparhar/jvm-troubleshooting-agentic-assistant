package com.javaassistant.agents;

import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class CorrelationTools {

    @Tool("Fetch more context from one artifact during cross-artifact synthesis. artifactRef should identify the artifact, for example artifact-1, artifact-2, baseline, current, or a source path. Leave selectorQuery blank to get the next omitted slice for that artifact. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. selectorQuery can also target lines, sections, patterns, classes, GC IDs, thread names, or JFR event selectors.")
    public String fetchArtifactContext(
        @P("artifact reference identifying which artifact to expand") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(null, "fetchArtifactContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused view for one artifact during cross-artifact synthesis. artifactRef should identify the artifact. Good requests include GC pause-percentiles, JFR execution-hotspots, NMT metaspace-summary, thread deadlock-summary, heap growth-ranking, or pmap resident-summary.")
    public String computeArtifactView(
        @P("artifact reference identifying which artifact to compute from") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(null, "computeArtifactView", artifactRef, request);
    }
}
