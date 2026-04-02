package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class JfrTools {

    @Tool("Fetch more derived JFR context from the current recording. Leave selectorQuery blank to get the next omitted slice. Use sliceId=executionHotspotSummary, sliceId=observedEventTypes, or sliceId=declaredEventTypes to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include eventType=gc, eventType=jdk.ExecutionSample, hotspot=checkoutService, allocationClass=java.lang.String, oldObject=JNI Global, start=2026-03-31T10:00:00Z, or end=2026-03-31T10:00:05Z.")
    public String fetchJfrContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active recording") String artifactRef,
        @P("JFR selector query describing the extra derived context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieveJfr("fetchJfrContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused JFR view from the current recording. Good requests include execution-hotspots, runtime-hotspots, allocation-summary, old-object-summary, or time-window-summary.")
    public String computeJfrView(
        @P("artifact reference such as current or baseline; leave blank for the active recording") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.JFR, "computeJfrView", artifactRef, request);
    }
}
