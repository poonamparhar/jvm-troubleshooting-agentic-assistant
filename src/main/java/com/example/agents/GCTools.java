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

        // Support multiple GC formats (Unified Logging, ZGC, G1/Parallel, legacy, STW totals)
        Pattern[] patterns = new Pattern[] {
            // Unified logging: GC(n) Pause ... 12.3ms or 0.12s
            Pattern.compile("GC\\(\\d+\\)\\s+Pause[^\\n]*?\\b(\\d+(?:\\.\\d+)?)(ms|s)\\b"),
            // Generic Pause lines (ZGC, CMS, G1 pre-unified): Pause <phase> 1.23ms / 0.12s / 0.12 secs / seconds
            Pattern.compile("\\bPause\\b[^\\n]*?\\b(\\d+(?:\\.\\d+)?)(ms|s|secs?|seconds)\\b", Pattern.CASE_INSENSITIVE),
            // Full GC durations without the word Pause
            Pattern.compile("\\bFull GC\\b[^\\n]*?\\b(\\d+(?:\\.\\d+)?)(ms|s|secs?|seconds)\\b", Pattern.CASE_INSENSITIVE),
            // Explicit 'GC pause' phrasing (older formats)
            Pattern.compile("\\bGC pause\\b[^\\n]*?\\b(\\d+(?:\\.\\d+)?)(ms|s|secs?|seconds)\\b", Pattern.CASE_INSENSITIVE),
            // G1 'Pause Young' phrasing
            Pattern.compile("\\bPause Young\\b[^\\n]*?\\b(\\d+(?:\\.\\d+)?)(ms|s|secs?|seconds)\\b", Pattern.CASE_INSENSITIVE),
            // STW totals (treat as pauses): Total time for which application threads were stopped: X.Y seconds
            Pattern.compile("Total time for which application threads were stopped:\\s*(\\d+(?:\\.\\d+)?)\\s*seconds", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(log);
            while (matcher.find()) {
                try {
                    double value = Double.parseDouble(matcher.group(1));
                    String unit = matcher.groupCount() >= 2 ? matcher.group(2) : null;
                    double ms = (unit == null) ? (value * 1000.0) // assume seconds when unit not captured
                               : (unit.toLowerCase().startsWith("ms") ? value : value * 1000.0);
                    pauses.add(ms);
                } catch (Exception ignore) {
                    // skip malformed captures
                }
            }
        }

        return pauses;
    }

    @Tool("Calculate GC throughput as percentage (app time / total time * 100)")
    public double calculateThroughput(@P("gc log content") String log) {
        if (log == null || log.isEmpty()) return 0.0;

        double first = Double.NaN, last = Double.NaN;
        Matcher uptime = Pattern.compile("\\[(\\d+\\.\\d+)s\\]").matcher(log);
        while (uptime.find()) {
            double ts = Double.parseDouble(uptime.group(1));
            if (Double.isNaN(first)) first = ts;
            last = ts;
        }
        if (Double.isNaN(first) || Double.isNaN(last)) return 0.0;

        double total = Math.max(0, last - first);
        if (total == 0) return 0.0;

        Pattern pausePattern = Pattern.compile(
                "(?:Pause [^\\n]*?|GC\\(\\d+\\) Pause[^\\n]*?)(\\d+(?:\\.\\d+)?)(ms|s|secs?|seconds)",
                Pattern.CASE_INSENSITIVE);
        double gcTime = pausePattern.matcher(log).results()
                .mapToDouble(match -> convertToSeconds(match.group(1), match.group(2)))
                .sum();

        double appTime = Math.max(0, total - gcTime);
        return (appTime / total) * 100.0;
    }
    private double convertToSeconds(String value, String unit) {
        double v = Double.parseDouble(value);
        return unit != null && unit.toLowerCase().startsWith("ms") ? v / 1000.0 : v;
    }


    @Tool("Detect the garbage collector type used in the log")
    public String detectCollector(@P("gc log content") String log) {
        if (log == null || log.isEmpty()) return "Unknown";
        String lc = log.toLowerCase();

        // Consolidated matcher covering Unified Logging (JDK 9+), legacy/JDK 8 formats, and phase hints
        final String zgcPat =
            "(?:(?:\\[gc,init].*using\\s+.*z\\s+garbage\\s+collector)|" +
            "(?:using\\s+the\\s+z\\s+garbage\\s+collector)|" +
            "(?:\\bzgc\\b)|" +
            "(?:\\bgc\\(\\d+\\)\\s+pause\\s+(?:mark start|mark end|relocate start)))";
        final String g1Pat =
            "(?:(?:\\[gc,init].*using\\s+g1)|" +
            "(?:using\\s+g1)|" +
            "(?:\\bg1gc\\b)|" +
            "(?:g1\\s+young\\s+generation)|" +
            "(?:\\bgc\\(\\d+\\)\\s+pause\\s+young)|" +
            "(?:eden\\s+regions))"; // seen in many G1 summaries
        final String parPat =
            "(?:(?:\\[gc,init].*using\\s+parallel)|" +
            "(?:using\\s+parallel)|" +
            "(?:\\bparallelgc\\b)|" +
            "(?:\\bpsyounggen\\b)|" +
            "(?:\\bparoldgen\\b))"; // Parallel/Throughput in JDK8
        final String cmsPat =
            "(?:(?:concurrentmarksweep)|" +
            "(?:cms-(?:initial-mark|concurrent-mark|remark))|" +
            "(?:\\bparnew\\b)|" +
            "(?:\\bdefnew\\b))"; // CMS era (JDK8), ParNew/DefNew often appear
        final String serPat =
            "(?:(?:marksweepcompact)|" +
            "(?:\\btenured\\b)|" +
            "(?:\\bdefnew\\b(?!.*parnew))|" +
            "(?:using\\s+serial))"; // Serial collector heuristics

        // One master pattern with ordered alternatives; capture determines collector
        String master = "(?mi)(?:" +
                "(" + zgcPat + ")|" +     // group 1 -> ZGC
                "(" + g1Pat  + ")|" +     // group 2 -> G1
                "(" + parPat + ")|" +     // group 3 -> Parallel
                "(" + cmsPat + ")|" +     // group 4 -> CMS
                "(" + serPat + ")" +      // group 5 -> Serial
                ")";

        Matcher m = Pattern.compile(master).matcher(lc);
        if (m.find()) {
            if (m.group(1) != null) return "ZGC";
            if (m.group(2) != null) return "G1";
            if (m.group(3) != null) return "Parallel";
            if (m.group(4) != null) return "CMS";
            if (m.group(5) != null) return "Serial";
        }
        return "Unknown";
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

}
