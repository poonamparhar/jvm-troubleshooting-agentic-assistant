package com.example.agents;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

/**
 * Tools for parsing pmap output
 */
public class PmapTools {

    /**
     * Parses total memory from pmap output
     */
    @Tool("Parse total memory from pmap")
    public long parseTotalMemoryFromPmap(@P("Pmap content") String pmapContent) {
        String[] lines = pmapContent.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("total kB")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        return Long.parseLong(parts[2]);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }
}
