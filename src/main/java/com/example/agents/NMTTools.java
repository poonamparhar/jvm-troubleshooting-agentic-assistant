package com.example.agents;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class NMTTools {

    @Tool("Parse memory usage for a specific category from NMT output")
    public Map<String, Long> parseMemoryCategory(@P("NMT output content") String nmtContent, @P("category name") String category) {
        Map<String, Long> result = new HashMap<>();
        // Pattern for categories like "-                     Class (reserved=1069050KB, committed=26430KB)"
        Pattern p = Pattern.compile("-\\s*" + Pattern.quote(category) + "\\s*\\(reserved=(\\d+)KB, committed=(\\d+)KB\\)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(nmtContent);
        if (m.find()) {
            result.put("reserved", Long.parseLong(m.group(1)));
            result.put("committed", Long.parseLong(m.group(2)));
        }
        return result;
    }

    @Tool("Extract metaspace usage details from Class category")
    public Map<String, Long> parseMetaspaceUsage(@P("NMT output content") String nmtContent) {
        Map<String, Long> result = new HashMap<>();
        // Pattern for Metadata: reserved=24576KB, committed=24280KB, used=23890KB, free=390KB
        Pattern p = Pattern.compile("Metadata:\\s*reserved=(\\d+)KB, committed=(\\d+)KB, used=(\\d+)KB, free=(\\d+)KB", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(nmtContent);
        if (m.find()) {
            result.put("reserved", Long.parseLong(m.group(1)));
            result.put("committed", Long.parseLong(m.group(2)));
            result.put("used", Long.parseLong(m.group(3)));
            result.put("free", Long.parseLong(m.group(4)));
        }
        return result;
    }

    @Tool("Extract thread count and stack memory from Thread category")
    public Map<String, Object> parseThreadInfo(@P("NMT output content") String nmtContent) {
        Map<String, Object> result = new HashMap<>();
        // Pattern for (thread #25), (stack: reserved=23296KB, committed=23296KB)
        Pattern threadP = Pattern.compile("\\(thread #(\\d+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher tm = threadP.matcher(nmtContent);
        if (tm.find()) {
            result.put("threadCount", Integer.parseInt(tm.group(1)));
        }
        Pattern stackP = Pattern.compile("\\(stack: reserved=(\\d+)KB, committed=(\\d+)KB\\)", Pattern.CASE_INSENSITIVE);
        Matcher sm = stackP.matcher(nmtContent);
        if (sm.find()) {
            result.put("stackReserved", Long.parseLong(sm.group(1)));
            result.put("stackCommitted", Long.parseLong(sm.group(2)));
        }
        return result;
    }

    @Tool("Calculate memory utilization ratios for key categories")
    public Map<String, Double> calculateMemoryRatios(@P("NMT output content") String nmtContent) {
        Map<String, Double> ratios = new HashMap<>();
        // Get total memory
        Pattern totalP = Pattern.compile("Total: reserved=(\\d+)KB, committed=(\\d+)KB", Pattern.CASE_INSENSITIVE);
        Matcher tm = totalP.matcher(nmtContent);
        if (tm.find()) {
            long totalReserved = Long.parseLong(tm.group(1));
            long totalCommitted = Long.parseLong(tm.group(2));

            // Class ratio
            Map<String, Long> classMem = parseMemoryCategory(nmtContent, "Class");
            if (!classMem.isEmpty()) {
                ratios.put("classRatio", (double) classMem.get("committed") / totalCommitted * 100);
            }

            // Thread ratio
            Map<String, Long> threadMem = parseMemoryCategory(nmtContent, "Thread");
            if (!threadMem.isEmpty()) {
                ratios.put("threadRatio", (double) threadMem.get("committed") / totalCommitted * 100);
            }

            // Code ratio
            Map<String, Long> codeMem = parseMemoryCategory(nmtContent, "Code");
            if (!codeMem.isEmpty()) {
                ratios.put("codeRatio", (double) codeMem.get("committed") / totalCommitted * 100);
            }

            // GC ratio
            Map<String, Long> gcMem = parseMemoryCategory(nmtContent, "GC");
            if (!gcMem.isEmpty()) {
                ratios.put("gcRatio", (double) gcMem.get("committed") / totalCommitted * 100);
            }
        }
        return ratios;
    }

    @Tool("Detect potential memory pressure in key categories")
    public Map<String, String> detectMemoryPressure(@P("NMT output content") String nmtContent) {
        Map<String, String> issues = new HashMap<>();

        // Metaspace pressure
        Map<String, Long> metaspace = parseMetaspaceUsage(nmtContent);
        if (!metaspace.isEmpty()) {
            long used = metaspace.get("used");
            long committed = metaspace.get("committed");
            if (committed > 0 && (double) used / committed > 0.9) {
                issues.put("metaspace", "High metaspace utilization (>90%)");
            }
        }

        // Code cache pressure
        Map<String, Long> codeMem = parseMemoryCategory(nmtContent, "Code");
        if (!codeMem.isEmpty()) {
            long committed = codeMem.get("committed");
            long reserved = codeMem.get("reserved");
            if (reserved > 0 && (double) committed / reserved > 0.8) {
                issues.put("codeCache", "Code cache nearing capacity (>80%)");
            }
        }

        // Thread stack pressure
        Map<String, Object> threadInfo = parseThreadInfo(nmtContent);
        if (threadInfo.containsKey("threadCount")) {
            int threadCount = (Integer) threadInfo.get("threadCount");
            if (threadCount > 100) {
                issues.put("threads", "High thread count (" + threadCount + ")");
            }
        }

        return issues;
    }

    @Tool("Extract key memory metrics from NMT summary")
    public Map<String, Long> extractMemoryMetricsFromNMT(@P("NMT content") String nmtContent) {
        Map<String, Long> metrics = new HashMap<>();
        String[] lines = nmtContent.split("\n");
        for (String line : lines) {
            if (line.contains("reserved=") && line.contains("committed=")) {
                // Parse lines like "Java Heap (reserved=512MB, committed=256MB)"
                Pattern pattern = Pattern.compile("(\\w+ \\w+) \\(reserved=(\\d+)MB, committed=(\\d+)MB\\)");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String category = matcher.group(1);
                    long reserved = Long.parseLong(matcher.group(2)) * 1024 * 1024; // Convert to bytes
                    long committed = Long.parseLong(matcher.group(3)) * 1024 * 1024;
                    metrics.put(category + " reserved", reserved);
                    metrics.put(category + " committed", committed);
                }
            }
        }
        return metrics;
    }
}
