package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class JfrTools {

    @Tool("Fetch more derived JFR context from the current recording. Leave selectorQuery blank to get the next omitted slice. Use sliceId=classLoadingSummary, sliceId=codeCacheSummary, sliceId=monitorWaitSummary, sliceId=cpuLoadSummary, sliceId=executionHotspotSummary, sliceId=observedEventTypes, or sliceId=declaredEventTypes to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include incident=runtime, incident=allocation, incident=retention, incident=chronology, eventType=gc, eventType=jdk.ExecutionSample, eventType=jdk.CPULoad, eventType=jdk.JavaMonitorWait, thread=checkout-worker, hotspot=checkoutService, allocationClass=java.lang.String, oldObject=JNI Global, start=0.200s, end=0.900s, start=2026-03-31T10:00:00Z, or end=2026-03-31T10:00:05Z. Targeted eventType, thread, hotspot, allocationClass, oldObject, and time-window requests return focused neighborhoods with top methods, threads, classes, roots, representative events, and overlapping incident windows when that richer derived context is available. If you request an exact observed event type by name or label, the tool can return that event type's derived field and sample-event detail block.")
    public String fetchJfrContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active recording") String artifactRef,
        @P("JFR selector query describing the extra derived context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieveJfr("fetchJfrContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused JFR view from the current recording. Good requests include class-loading-summary, code-cache-summary, monitor-wait-summary, cpu-load-summary, execution-hotspots, runtime-hotspots, allocation-summary, old-object-summary, incident-window-summary, runtime-incident-summary, allocation-incident-summary, chronology-summary, eventType=gc, eventType=jdk.ExecutionSample, eventType=jdk.CPULoad, thread=checkout-worker, hotspot=checkoutService, allocationClass=java.lang.String, oldObject=JNI Global, or start=0.200s,end=0.900s.")
    public String computeJfrView(
        @P("artifact reference such as current or baseline; leave blank for the active recording") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.JFR, "computeJfrView", artifactRef, request);
    }
}
