package com.javaassistant.agents;

import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class CorrelationTools {

    @Tool("Retrieve more detail from one artifact while correlating multiple diagnostics. Prefer this when exact neighboring context matters, for example a deadlocked thread block, the lines around a kernel OOM kill, a problematic hs_err section, a GC incident window, or a JFR hotspot slice. artifactRef should match ARTIFACT_OVERVIEW, for example artifact-1, artifact-2, baseline, current, primary, or a source path. Leave selectorQuery blank to fetch the next omitted slice. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. selectorQuery can target lines, sections, patterns, thread names, classes, hotspot keys, GC IDs, incidents, or JFR selectors.")
    public String fetchRelevantArtifactContext(
        @P("artifact reference identifying which artifact to expand") String artifactRef,
        @P("selector query describing the exact extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(null, "fetchRelevantArtifactContext", artifactRef, selectorQuery);
    }

    @Tool("Compute one focused view from one artifact while correlating multiple diagnostics. Prefer this before raw retrieval when a compact artifact-specific summary is enough to confirm or challenge a cross-artifact hypothesis. Good requests include GC dominant-window-summary or recovery-summary, JFR execution-hotspots or time-window summaries, thread deadlock-summary or blocked-clusters, hs_err crash-summary, NMT metaspace-summary, heap retention-families, pmap resident-summary, container pressure-summary, or OOM kernel-summary.")
    public String computeRelevantArtifactView(
        @P("artifact reference identifying which artifact to compute from") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(null, "computeRelevantArtifactView", artifactRef, request);
    }
}
