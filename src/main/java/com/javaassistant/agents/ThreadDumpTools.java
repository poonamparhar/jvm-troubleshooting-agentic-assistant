package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class ThreadDumpTools {

    @Tool("Fetch more thread-dump context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=raw-chunk-013 to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include thread=pool-1-thread-3, section=thread:pool-1-thread-3, lines=120-155, pattern=parking to wait for, or hotspot=java.util.concurrent.")
    public String fetchThreadDumpContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active dump") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.THREAD_DUMP, "fetchThreadDumpContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused thread-dump view from the current artifact. Good requests include deadlock-summary, blocked-clusters, thread-state-summary, or pool-summary.")
    public String computeThreadDumpView(
        @P("artifact reference such as current or baseline; leave blank for the active dump") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.THREAD_DUMP, "computeThreadDumpView", artifactRef, request);
    }
}
