package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class GCTools {

    @Tool("Fetch more GC log context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=raw-chunk-013 to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include gcId=45, lines=640-650, section=summary, section=failureSummary, section=phaseSummary, pattern=Evacuation Failure, start=08:32, end=08:33, start=6.6s,end=7.35s, gcId=45,windowSeconds=2, cause=G1 Compaction Pause, cause=Metadata GC Threshold, cause=Allocation Failure, pauseType=FULL, phase=Concurrent Mark From Roots, phaseKind=CONCURRENT, signalType=FULL_COMPACTION_ATTEMPT, streak=full-gc, streak=distress, incident=dominant-pressure, incident=failure-cluster, incident=peak-occupancy, or incident=tail. For compare, artifactRef can be baseline or current, and you should prefer targeted baseline/current incident retrieval over broad searches.")
    public String fetchGcContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active artifact") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.GC_LOG, "fetchGcContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused GC view from the current artifact. Good requests include pause-percentiles, pause-breakdown, cause-distribution, collector-pressure-summary, g1-pressure-summary, cms-pressure-summary, serial-pressure-summary, parallel-pressure-summary, zgc-pressure-summary, dominant-window-summary, recovery-summary, g1-cycle-progression, occupancy-progression, allocation-stalls, cycle-summary, failure-summary, phase-summary, worker-summary, cpu-summary, humongous-summary, start=6.6s,end=7.35s, gcId=45,windowSeconds=2, streak=full-gc, streak=distress, incident=dominant-pressure, incident=failure-cluster, incident=peak-occupancy, gcId=45, cause=G1 Compaction Pause, cause=Metadata GC Threshold, cause=Allocation Failure, pauseType=FULL, phaseKind=CONCURRENT, phase=Concurrent Mark From Roots, or signalType=CONCURRENT_ABORT. For compare, use artifactRef=current first for the regressed side, then request the matching artifactRef=baseline view.")
    public String computeGcView(
        @P("artifact reference such as current or baseline; leave blank for the active artifact") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.GC_LOG, "computeGcView", artifactRef, request);
    }
}
