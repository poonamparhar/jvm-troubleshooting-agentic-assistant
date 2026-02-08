package com.example.agents;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class HeapHistogramTools {

    @Tool("Parse a heap histogram into class statistics")
    public Map<String, Map<String, Long>> parseHistogram(@P("Heap histogram content") String histogramContent) {
        Map<String, Map<String, Long>> histogram = new LinkedHashMap<>();

        // Pattern for histogram lines: "   1:         125000       5000000  [B"
        Pattern linePattern = Pattern.compile("\\s*\\d+:\\s*(\\d+)\\s*(\\d+)\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = linePattern.matcher(histogramContent);

        while (matcher.find()) {
            try {
                long instances = Long.parseLong(matcher.group(1));
                long bytes = Long.parseLong(matcher.group(2));
                String className = matcher.group(3).trim();

                Map<String, Long> stats = new HashMap<>();
                stats.put("instances", instances);
                stats.put("bytes", bytes);
                histogram.put(className, stats);
            } catch (NumberFormatException e) {
                // Skip invalid lines
            }
        }

        return histogram;
    }

    @Tool("Compare two histograms and calculate growth statistics")
    public Map<String, Map<String, Double>> compareHistograms(@P("Baseline histogram map") Map<String, Map<String, Long>> baseline,
                                                              @P("Current histogram map") Map<String, Map<String, Long>> current) {
        Map<String, Map<String, Double>> comparison = new LinkedHashMap<>();

        // Get all unique class names
        Set<String> allClasses = new HashSet<>();
        allClasses.addAll(baseline.keySet());
        allClasses.addAll(current.keySet());

        for (String className : allClasses) {
            Map<String, Long> baseStats = baseline.get(className);
            Map<String, Long> currStats = current.get(className);

            long baseInstances = baseStats != null ? baseStats.get("instances") : 0;
            long baseBytes = baseStats != null ? baseStats.get("bytes") : 0;
            long currInstances = currStats != null ? currStats.get("instances") : 0;
            long currBytes = currStats != null ? currStats.get("bytes") : 0;

            Map<String, Double> growth = new HashMap<>();
            growth.put("instanceGrowth", baseInstances > 0 ? (double) (currInstances - baseInstances) / baseInstances * 100 : (currInstances > 0 ? 100.0 : 0.0));
            growth.put("byteGrowth", baseBytes > 0 ? (double) (currBytes - baseBytes) / baseBytes * 100 : (currBytes > 0 ? 100.0 : 0.0));
            growth.put("instanceDelta", (double) (currInstances - baseInstances));
            growth.put("byteDelta", (double) (currBytes - baseBytes));

            comparison.put(className, growth);
        }

        return comparison;
    }
}
