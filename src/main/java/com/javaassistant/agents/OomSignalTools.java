package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class OomSignalTools {

    @Tool("Fetch more OOM or restart-signal context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=kernelEvents to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include section=kernelEvents, section=podSignals, pattern=OOMKilled, pattern=CrashLoopBackOff, or lines=1-40.")
    public String fetchOomSignalContext(
        @P("artifact reference such as current or a source path; leave blank for the active signal set") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.OOM_SIGNAL, "fetchOomSignalContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused OOM or restart-signal view from the current artifact. Good requests include oom-signal-summary, kernel-summary, or pod-summary.")
    public String computeOomSignalView(
        @P("artifact reference such as current; leave blank for the active signal set") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.OOM_SIGNAL, "computeOomSignalView", artifactRef, request);
    }
}
