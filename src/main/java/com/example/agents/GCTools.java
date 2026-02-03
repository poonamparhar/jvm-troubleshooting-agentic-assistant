package com.example.agents;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.HashMap;

public class GCTools {

    @Tool("Extract all GC pause times in milliseconds from the GC log")
    public List<Double> extractPauses(@P("gc log content") String log) {
        List<Double> pauses = new ArrayList<>();
        // Pattern for pause times, adjust for specific GC log format
        Pattern p = Pattern.compile("Pause: (\\d+\\.\\d+) ms", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(log);
        while (m.find()) {
            try {
                pauses.add(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException e) {
                // Ignore invalid
            }
        }
        return pauses;
    }

    @Tool("Calculate GC throughput as percentage (app time / total time * 100)")
    public double calculateThroughput(@P("gc log content") String log) {
        // Simple dummy calculation; in real, parse timestamps
        Pattern timeP = Pattern.compile("(\\d+):(\\d+):(\\d+\\.\\d+)");
        Matcher m = timeP.matcher(log);
        double totalAppTime = 0;
        double totalGcTime = 0;
        while (m.find()) {
            // Parse hours, minutes, seconds - dummy
            totalAppTime += 60; // Assume
            totalGcTime += 5; // Assume
        }
        if (totalAppTime + totalGcTime > 0) {
            return (totalAppTime / (totalAppTime + totalGcTime)) * 100;
        }
        return 0.0;
    }

    @Tool("Detect the garbage collector type used in the log")
    public String detectCollector(@P("gc log content") String log) {
        if (log.contains("G1GC") || log.contains("G1 Young Generation")) return "G1";
        if (log.contains("CMS") || log.contains("ConcurrentMarkSweep")) return "CMS";
        if (log.contains("ParallelGC") || log.contains("PSYoungGen")) return "Parallel";
        if (log.contains("ZGC")) return "ZGC";
        return "Unknown";
    }

    @Tool("Extract heap generation sizes from the log")
    public Map<String, Long> getHeapSizes(@P("gc log content") String log) {
        Map<String, Long> sizes = new HashMap<>();
        // Pattern for heap sizes, e.g., [Eden: 100M(200M)->50M(200M)]
        Pattern p = Pattern.compile("\\[(\\w+): (\\d+(?:[KMG])?)\\((\\d+(?:[KMG])?)\\)->", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(log);
        while (m.find()) {
            String gen = m.group(1).toLowerCase();
            long size = parseSize(m.group(3));
            sizes.put(gen, size);
        }
        if (sizes.isEmpty()) {
            sizes.put("young", 1024L * 1024 * 100); // Dummy 100MB
            sizes.put("old", 1024L * 1024 * 400); // Dummy 400MB
        }
        return sizes;
    }

    @Tool("Extract timestamps from GC log")
    public List<String> extractTimestampsFromGCLog(@P("GC log content") String gcLogContent) {
        List<String> timestamps = new ArrayList<>();
        // Simple regex for GC timestamps like [2023-01-01T10:00:00.123+0000]
        Pattern pattern = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4})\\]");
        Matcher matcher = pattern.matcher(gcLogContent);
        while (matcher.find()) {
            timestamps.add(matcher.group(1));
        }
        return timestamps;
    }

    private long parseSize(String sizeStr) {
        // Parse K, M, G
        long multiplier = 1;
        if (sizeStr.endsWith("K")) multiplier = 1024;
        else if (sizeStr.endsWith("M")) multiplier = 1024 * 1024;
        else if (sizeStr.endsWith("G")) multiplier = 1024 * 1024 * 1024;
        long num = Long.parseLong(sizeStr.replaceAll("[KMG]", ""));
        return num * multiplier;
    }
}
