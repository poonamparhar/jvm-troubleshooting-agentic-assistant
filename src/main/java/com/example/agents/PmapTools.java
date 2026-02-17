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

    /**
     * Parses pmap breakdown by category (anon, file-backed, stack, heap, other)
     */
    @Tool("Parse pmap breakdown by memory category")
    public java.util.Map<String, Long> parseBreakdownByCategory(@P("Pmap content") String pmapContent) {
        java.util.Map<String, Long> breakdown = new java.util.HashMap<>();
        String[] lines = pmapContent.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("total kB") || line.trim().isEmpty() || line.toLowerCase().contains("address")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 4) {
                try {
                    long rss = Long.parseLong(parts[2]); // RSS column
                    String mapping = parts.length > 5 ? parts[5] : parts[parts.length - 1];
                    String category = categorizeMapping(mapping);
                    breakdown.put(category, breakdown.getOrDefault(category, 0L) + rss);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    // Skip malformed lines
                }
            }
        }
        return breakdown;
    }

    private String categorizeMapping(String mapping) {
        if (mapping.equals("[ anon ]") || mapping.startsWith("[ anon")) {
            return "anon";
        } else if (mapping.contains(".so") || mapping.contains(".dylib") || mapping.startsWith("/lib")) {
            return "shared_libs";
        } else if (mapping.equals("[ stack ]")) {
            return "stack";
        } else if (mapping.equals("[ heap ]")) {
            return "heap";
        } else if (mapping.startsWith("/") || mapping.startsWith("[")) {
            return "file_backed";
        } else {
            return "other";
        }
    }
}
