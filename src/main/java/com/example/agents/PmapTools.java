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
            if (line.trim().startsWith("total kB") || line.trim().isEmpty() || line.contains("Address")) continue;
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
        } else if (mapping.startsWith("/") || mapping.startsWith("[") && !mapping.equals("[ anon ]")) {
            return "file_backed";
        } else {
            return "other";
        }
    }

    /**
     * Parses top N largest mappings by RSS from pmap output
     */
    @Tool("Parse top N largest mappings by RSS")
    public java.util.List<String> parseTopMappings(@P("Pmap content") String pmapContent, @P("Number of top mappings to return") int topN) {
        java.util.List<java.util.Map.Entry<String, Long>> mappings = new java.util.ArrayList<>();
        String[] lines = pmapContent.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("total kB") || line.trim().isEmpty() || line.contains("Address")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 4) {
                try {
                    long rss = Long.parseLong(parts[2]);
                    String mapping = parts.length > 5 ? parts[5] : parts[parts.length - 1];
                    mappings.add(new java.util.AbstractMap.SimpleEntry<>(mapping, rss));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    // Skip malformed lines
                }
            }
        }
        // Sort by RSS descending
        mappings.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(topN, mappings.size()); i++) {
            var entry = mappings.get(i);
            result.add(entry.getValue() + " KB: " + entry.getKey());
        }
        return result;
    }
}
