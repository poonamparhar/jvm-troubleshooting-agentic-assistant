package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class HSErrTools {

    @Tool("Fetch more hs_err crash-log context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=raw-chunk-003 to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include section=problematicFrame, section=currentThread, section=commandLine, pattern=Native memory allocation, or lines=40-90.")
    public String fetchHsErrContext(
        @P("artifact reference such as current or a source path; leave blank for the active crash log") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.HS_ERR_LOG, "fetchHsErrContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused hs_err view from the current artifact. Good requests include crash-summary, thread-context, vm-context, or native-memory-context.")
    public String computeHsErrView(
        @P("artifact reference such as current; leave blank for the active crash log") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.HS_ERR_LOG, "computeHsErrView", artifactRef, request);
    }
}
