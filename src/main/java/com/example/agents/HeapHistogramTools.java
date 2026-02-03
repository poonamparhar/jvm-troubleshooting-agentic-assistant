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

    @Tool("Identify potential memory leak suspects from histogram comparison")
    public List<String> identifyLeakSuspects(@P("Histogram comparison results") Map<String, Map<String, Double>> comparison) {
        List<String> suspects = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : comparison.entrySet()) {
            String className = entry.getKey();
            Map<String, Double> growth = entry.getValue();

            double instanceGrowth = growth.get("instanceGrowth");
            double byteGrowth = growth.get("byteGrowth");
            double instanceDelta = growth.get("instanceDelta");

            // Suspect criteria:
            // - Instance growth > 50% AND absolute delta > 1000
            // - Byte growth > 100% AND absolute delta > 1MB
            // - Known collection/cache classes with high growth
            boolean isSuspect = false;

            if (instanceGrowth > 50 && Math.abs(instanceDelta) > 1000) {
                isSuspect = true;
            } else if (byteGrowth > 100 && Math.abs(growth.get("byteDelta")) > 1024 * 1024) {
                isSuspect = true;
            } else if ((className.contains("HashMap$Entry") || className.contains("ArrayList") ||
                       className.contains("LinkedList") || className.contains("Cache") ||
                       className.contains("ConcurrentHashMap")) &&
                      (instanceGrowth > 25 || byteGrowth > 50)) {
                isSuspect = true;
            }

            if (isSuspect) {
                suspects.add(String.format("%s: %.1f%% instance growth (%d delta), %.1f%% byte growth",
                                         className, instanceGrowth, (long) instanceDelta, byteGrowth));
            }
        }

        return suspects;
    }

    @Tool("Calculate total heap growth between histograms")
    public Map<String, Long> calculateTotalHeapGrowth(@P("Baseline histogram map") Map<String, Map<String, Long>> baseline,
                                                       @P("Current histogram map") Map<String, Map<String, Long>> current) {
        Map<String, Long> totals = new HashMap<>();

        long baselineTotalBytes = baseline.values().stream()
                .mapToLong(stats -> stats.get("bytes"))
                .sum();

        long currentTotalBytes = current.values().stream()
                .mapToLong(stats -> stats.get("bytes"))
                .sum();

        long baselineTotalInstances = baseline.values().stream()
                .mapToLong(stats -> stats.get("instances"))
                .sum();

        long currentTotalInstances = current.values().stream()
                .mapToLong(stats -> stats.get("instances"))
                .sum();

        totals.put("baselineBytes", baselineTotalBytes);
        totals.put("currentBytes", currentTotalBytes);
        totals.put("baselineInstances", baselineTotalInstances);
        totals.put("currentInstances", currentTotalInstances);
        totals.put("byteGrowth", currentTotalBytes - baselineTotalBytes);
        totals.put("instanceGrowth", currentTotalInstances - baselineTotalInstances);

        return totals;
    }

    @Tool("Get top memory consumers from histogram")
    public List<String> getTopMemoryConsumers(@P("Histogram map") Map<String, Map<String, Long>> histogram,
                                              @P("Number of top consumers") int topN) {
        List<String> consumers = new ArrayList<>();

        histogram.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().get("bytes"), e1.getValue().get("bytes")))
                .limit(topN)
                .forEach(entry -> {
                    String className = entry.getKey();
                    long bytes = entry.getValue().get("bytes");
                    long instances = entry.getValue().get("instances");
                    consumers.add(String.format("%s: %d bytes (%d instances)", className, bytes, instances));
                });

        return consumers;
    }
}
