package com.javaassistant.agents;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.orchestration.AgentToolRuntime;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class HeapHistogramTools {

    @Tool("Fetch more heap-histogram context from the current artifact. Leave selectorQuery blank to get the next omitted slice. Use sliceId=raw-chunk-002 to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>. Other selectors include class=java.util.HashMap, pattern=byte[], section=entries, lines=1-30, or section=topConsumers. For compare, artifactRef can be baseline or current.")
    public String fetchHeapHistogramContext(
        @P("artifact reference such as current, baseline, or a source path; leave blank for the active artifact") String artifactRef,
        @P("selector query describing the extra context needed") String selectorQuery
    ) {
        return AgentToolRuntime.retrieve(ArtifactType.HEAP_HISTOGRAM, "fetchHeapHistogramContext", artifactRef, selectorQuery);
    }

    @Tool("Compute a focused heap-histogram view from the current artifact. Good requests include top-consumers, growth-ranking, or retention-families.")
    public String computeHeapHistogramView(
        @P("artifact reference such as current or baseline; leave blank for the active artifact") String artifactRef,
        @P("focused computation request") String request
    ) {
        return AgentToolRuntime.compute(ArtifactType.HEAP_HISTOGRAM, "computeHeapHistogramView", artifactRef, request);
    }
}
