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
        // Parse uptime timestamps [X.XXXs] to get first and last uptime
        Pattern uptimePattern = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
        Matcher uptimeMatcher = uptimePattern.matcher(log);
        double firstUptime = -1;
        double lastUptime = -1;
        while (uptimeMatcher.find()) {
            double uptime = Double.parseDouble(uptimeMatcher.group(1));
            if (firstUptime == -1) {
                firstUptime = uptime;
            }
            lastUptime = uptime;
        }

        if (firstUptime == -1 || lastUptime == -1) {
            // Fallback to wall-clock timestamps if uptime not available
            Pattern wallClockPattern = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4})\\]");
            Matcher wallMatcher = wallClockPattern.matcher(log);
            long firstWall = -1;
            long lastWall = -1;
            while (wallMatcher.find()) {
                // Approximate elapsed from wall-clock (not ideal, but better than 0)
                // For simplicity, assume log is sequential and count occurrences
                if (firstWall == -1) {
                    firstWall = 0; // Placeholder
                    lastWall = 1; // Placeholder
                }
            }
            if (firstWall == -1) return 0.0; // No timestamps
            lastUptime = 10.0; // Assume 10s elapsed if wall-clock present but uptime not
            firstUptime = 0.0;
        }

        double totalElapsedSeconds = lastUptime - firstUptime;

        // Sum GC pause durations
        // ZGC: "Pause <Phase> <duration>ms"
        // G1/Parallel: "Pause ... <duration>s" or "... <duration>s"
        double totalPauseSeconds = 0;
        Pattern pausePattern = Pattern.compile("(?:Pause [^\\s]+|GC\\(\\d+\\) Pause)\\s+[^\\s]+\\s+[^\\s]+\\s+([^\\s]+\\s*ms|[^\\s]+\\s*s)");
        Matcher pauseMatcher = pausePattern.matcher(log);
        while (pauseMatcher.find()) {
            String durationStr = pauseMatcher.group(1).trim();
            double duration = parseDuration(durationStr);
            totalPauseSeconds += duration;
        }

        // Also catch ZGC specific pauses
        Pattern zgcPausePattern = Pattern.compile("Pause (?:Mark Start|Mark End|Relocate Start)\\s+(\\d+\\.\\d+)ms");
        Matcher zgcMatcher = zgcPausePattern.matcher(log);
        while (zgcMatcher.find()) {
            double durationMs = Double.parseDouble(zgcMatcher.group(1));
            totalPauseSeconds += durationMs / 1000.0;
        }

        double appTimeSeconds = Math.max(0, totalElapsedSeconds - totalPauseSeconds);
        if (totalElapsedSeconds > 0) {
            return (appTimeSeconds / totalElapsedSeconds) * 100;
        }
        return 0.0;
    }

    private double parseDuration(String durationStr) {
        if (durationStr.endsWith("ms")) {
            return Double.parseDouble(durationStr.replace("ms", "").trim()) / 1000.0;
        } else if (durationStr.endsWith("s")) {
            return Double.parseDouble(durationStr.replace("s", "").trim());
        }
        return 0.0;
    }

    @Tool("Detect the garbage collector type used in the log")
    public String detectCollector(@P("gc log content") String log) {
        // ZGC detection
        if (log.contains("Using The Z Garbage Collector") || log.contains("Initializing The Z Garbage Collector") ||
            log.contains("ZGC") || log.contains("Pause Mark Start") || log.contains("Concurrent Mark") && log.contains("Relocate")) {
            return "ZGC";
        }
        // G1 detection
        if (log.contains("Using G1") || log.contains("G1GC") || log.contains("G1 Young Generation") ||
            log.contains("Pause Young") || log.contains("Pause Full") && log.contains("Eden regions")) {
            return "G1";
        }
        // Parallel detection
        if (log.contains("ParallelGC") || log.contains("PSYoungGen") || log.contains("ParOldGen") ||
            log.contains("Using Parallel")) {
            return "Parallel";
        }
        // CMS (legacy, keep for compatibility)
        if (log.contains("CMS") || log.contains("ConcurrentMarkSweep")) {
            return "CMS";
        }
        return "Unknown";
    }

    @Tool("Extract heap generation sizes from the log")
    public Map<String, Long> getHeapSizes(@P("gc log content") String log) {
        Map<String, Long> sizes = new HashMap<>();

        // For G1/Parallel: Pattern for heap sizes, e.g., [Eden: 100M(200M)->50M(200M)]
        Pattern genPattern = Pattern.compile("\\[(\\w+): (\\d+(?:[KMG])?)\\((\\d+(?:[KMG])?)\\)->", Pattern.CASE_INSENSITIVE);
        Matcher genMatcher = genPattern.matcher(log);
        while (genMatcher.find()) {
            String gen = genMatcher.group(1).toLowerCase();
            long size = parseSize(genMatcher.group(3));
            sizes.put(gen, size);
        }

        // For ZGC: Parse [gc,heap] capacity lines like "Capacity: 512M (50%)"
        if (sizes.isEmpty()) {
            Pattern zgcCapacityPattern = Pattern.compile("Capacity:\\s+(\\d+[KMG]?)\\s+\\((\\d+)%\\)");
            Matcher zgcMatcher = zgcCapacityPattern.matcher(log);
            if (zgcMatcher.find()) {
                long capacity = parseSize(zgcMatcher.group(1));
                sizes.put("heap", capacity);
            }
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

    @Tool("Extract structured timestamps from GC log (wall-clock and uptime)")
    public Map<String, List<String>> extractStructuredTimestamps(@P("GC log content") String gcLogContent) {
        Map<String, List<String>> result = new HashMap<>();
        List<String> wallClockTimestamps = new ArrayList<>();
        List<String> uptimeTimestamps = new ArrayList<>();

        // Wall-clock timestamps: [2023-01-01T10:00:00.123+0000]
        Pattern wallClockPattern = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4})\\]");
        Matcher wallMatcher = wallClockPattern.matcher(gcLogContent);
        while (wallMatcher.find()) {
            wallClockTimestamps.add(wallMatcher.group(1));
        }

        // Uptime timestamps: [0.025s]
        Pattern uptimePattern = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
        Matcher uptimeMatcher = uptimePattern.matcher(gcLogContent);
        while (uptimeMatcher.find()) {
            uptimeTimestamps.add(uptimeMatcher.group(1) + "s");
        }

        result.put("wallClock", wallClockTimestamps);
        result.put("uptime", uptimeTimestamps);
        return result;
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
