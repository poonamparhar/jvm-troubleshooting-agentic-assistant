package com.example.agents;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

/**
 * Tools for correlating JVM diagnostic data across multiple sources
 */
public class CorrelationTools {

    /**
     * Correlates GC events with memory usage changes
     */
    @Tool("Correlate GC events with memory")
    public String correlateGCWithMemory(@P("GC log content") String gcLogContent, @P("Memory content") String memoryContent) {
        // Simple correlation: count GC events and check memory totals
        long gcCount = gcLogContent.split("\n").length; // Rough estimate
        long totalMemory = new PmapTools().parseTotalMemoryFromPmap(memoryContent);
        return "GC Events: " + gcCount + ", Total Memory: " + totalMemory + " kB";
    }
}
