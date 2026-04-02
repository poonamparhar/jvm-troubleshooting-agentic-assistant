package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContainerMemoryArtifactParser implements ArtifactParser {

    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("^\\[(.+)]\\s*$");
    private static final Pattern NUMERIC_ENTRY_PATTERN = Pattern.compile("^([A-Za-z0-9_.-]+)\\s+(-?\\d+)\\s*$");
    private static final Pattern PRESSURE_PATTERN = Pattern.compile(
        "^(some|full)\\s+avg10=([0-9]+(?:\\.[0-9]+)?)\\s+avg60=([0-9]+(?:\\.[0-9]+)?)\\s+avg300=([0-9]+(?:\\.[0-9]+)?)\\s+total=(\\d+)\\s*$"
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.CONTAINER_MEMORY;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, List<String>> sections = parseSections(artifact.content());
        SectionValue current = parseScalarSection(sections.get("memory.current"));
        LimitValue max = parseLimitValue(parseScalarSection(sections.get("memory.max")).rawValue());
        LimitValue high = parseLimitValue(parseScalarSection(sections.get("memory.high")).rawValue());
        Map<String, Long> events = parseNumericEntries(sections.get("memory.events"));
        Map<String, Long> stat = parseNumericEntries(sections.get("memory.stat"));
        Map<String, Map<String, Object>> pressure = parsePressure(sections.get("memory.pressure"));
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long currentBytes = current.numericValue() != null ? current.numericValue() : 0L;
        long anonBytes = stat.getOrDefault("anon", 0L);
        long fileBytes = stat.getOrDefault("file", 0L);
        long slabBytes = stat.containsKey("slab")
            ? stat.get("slab")
            : stat.getOrDefault("slab_reclaimable", 0L) + stat.getOrDefault("slab_unreclaimable", 0L);
        long kernelBytes = stat.containsKey("kernel")
            ? stat.get("kernel")
            : stat.getOrDefault("kernel_stack", 0L)
                + stat.getOrDefault("pagetables", 0L)
                + stat.getOrDefault("sock", 0L)
                + stat.getOrDefault("percpu", 0L);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        if (current.numericValue() != null) {
            summary.put("currentBytes", currentBytes);
        }
        summary.put("maxDefined", max.defined());
        if (max.defined()) {
            summary.put("maxBytes", max.bytes());
            summary.put("headroomToMaxBytes", Math.max(0L, max.bytes() - currentBytes));
            if (currentBytes > 0L && max.bytes() > 0L) {
                summary.put("usageOfMaxRatio", ratio(currentBytes, max.bytes()));
            }
        }
        summary.put("highDefined", high.defined());
        if (high.defined()) {
            summary.put("highBytes", high.bytes());
            summary.put("headroomToHighBytes", Math.max(0L, high.bytes() - currentBytes));
            if (currentBytes > 0L && high.bytes() > 0L) {
                summary.put("usageOfHighRatio", ratio(currentBytes, high.bytes()));
            }
        }
        if (!stat.isEmpty()) {
            summary.put("anonBytes", anonBytes);
            summary.put("fileBytes", fileBytes);
            summary.put("slabBytes", slabBytes);
            summary.put("kernelBytes", kernelBytes);
            if (currentBytes > 0L) {
                summary.put("anonShareOfCurrent", ratio(anonBytes, currentBytes));
                summary.put("fileShareOfCurrent", ratio(fileBytes, currentBytes));
                summary.put("slabShareOfCurrent", ratio(slabBytes, currentBytes));
                summary.put("kernelShareOfCurrent", ratio(kernelBytes, currentBytes));
            }
        }

        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", summary);
        extractedData.put("events", events);
        extractedData.put("stat", stat);
        extractedData.put("pressure", pressure);
        extractedData.put("sectionsPresent", List.copyOf(sections.keySet()));

        if (current.present() || max.defined() || high.defined()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            if (current.numericValue() != null) {
                metrics.put("currentBytes", currentBytes);
            }
            if (max.defined()) {
                metrics.put("maxBytes", max.bytes());
                metrics.put("headroomToMaxBytes", summary.get("headroomToMaxBytes"));
                if (summary.containsKey("usageOfMaxRatio")) {
                    metrics.put("usageOfMaxRatio", summary.get("usageOfMaxRatio"));
                }
            }
            if (high.defined()) {
                metrics.put("highBytes", high.bytes());
                metrics.put("headroomToHighBytes", summary.get("headroomToHighBytes"));
                if (summary.containsKey("usageOfHighRatio")) {
                    metrics.put("usageOfHighRatio", summary.get("usageOfHighRatio"));
                }
            }
            evidence.add(ParserUtils.evidence(
                "container-memory-summary",
                artifact,
                "Container memory budget summary",
                "Current cgroup memory usage and configured memory ceilings.",
                "[memory.current]",
                metrics
            ));
        }

        if (!events.isEmpty()) {
            evidence.add(ParserUtils.evidence(
                "container-memory-events",
                artifact,
                "Container memory event counters",
                "Cgroup memory high, max, OOM, and kill counters.",
                "[memory.events]",
                new LinkedHashMap<>(events)
            ));
        }

        if (!stat.isEmpty()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("anonBytes", anonBytes);
            metrics.put("fileBytes", fileBytes);
            metrics.put("slabBytes", slabBytes);
            metrics.put("kernelBytes", kernelBytes);
            evidence.add(ParserUtils.evidence(
                "container-memory-breakdown",
                artifact,
                "Container memory usage breakdown",
                "Selected anon, file, slab, and kernel totals from memory.stat.",
                "[memory.stat]",
                metrics
            ));
        }

        if (!pressure.isEmpty()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            if (pressure.containsKey("some")) {
                metrics.put("some", pressure.get("some"));
            }
            if (pressure.containsKey("full")) {
                metrics.put("full", pressure.get("full"));
            }
            evidence.add(ParserUtils.evidence(
                "container-memory-pressure",
                artifact,
                "Container memory PSI pressure",
                "Recent memory reclaim and full-stall pressure from memory.pressure.",
                "[memory.pressure]",
                metrics
            ));
        }

        if (!sections.containsKey("memory.current")) {
            warnings.add("memory.current section not present in the container memory snapshot.");
        }
        if (!sections.containsKey("memory.max")) {
            warnings.add("memory.max section not present in the container memory snapshot.");
        }
        if (!sections.containsKey("memory.events")) {
            warnings.add("memory.events section not present in the container memory snapshot.");
        }
        if (!sections.containsKey("memory.stat")) {
            warnings.add("memory.stat section not present in the container memory snapshot.");
        }
        if (!sections.containsKey("memory.pressure")) {
            warnings.add("memory.pressure section not present in the container memory snapshot.");
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "container-memory-v1", extractedData, evidence, warnings);
    }

    private Map<String, List<String>> parseSections(String content) {
        LinkedHashMap<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        for (String rawLine : ParserUtils.lines(content)) {
            String trimmed = rawLine.trim();
            Matcher headerMatcher = SECTION_HEADER_PATTERN.matcher(trimmed);
            if (headerMatcher.matches()) {
                currentSection = headerMatcher.group(1).trim();
                sections.putIfAbsent(currentSection, new ArrayList<>());
                continue;
            }

            if (currentSection == null || trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            sections.get(currentSection).add(trimmed);
        }

        LinkedHashMap<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return frozen;
    }

    private SectionValue parseScalarSection(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new SectionValue(null, null, false);
        }

        String value = lines.getFirst().trim();
        try {
            return new SectionValue(value, Long.parseLong(value), true);
        } catch (NumberFormatException ignored) {
            return new SectionValue(value, null, true);
        }
    }

    private LimitValue parseLimitValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || "max".equalsIgnoreCase(rawValue)) {
            return new LimitValue(rawValue, false, 0L);
        }

        try {
            return new LimitValue(rawValue, true, Long.parseLong(rawValue));
        } catch (NumberFormatException ignored) {
            return new LimitValue(rawValue, false, 0L);
        }
    }

    private Map<String, Long> parseNumericEntries(List<String> lines) {
        LinkedHashMap<String, Long> values = new LinkedHashMap<>();
        if (lines == null) {
            return values;
        }

        for (String line : lines) {
            Matcher matcher = NUMERIC_ENTRY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            values.put(matcher.group(1), Long.parseLong(matcher.group(2)));
        }
        return values;
    }

    private Map<String, Map<String, Object>> parsePressure(List<String> lines) {
        LinkedHashMap<String, Map<String, Object>> pressure = new LinkedHashMap<>();
        if (lines == null) {
            return pressure;
        }

        for (String line : lines) {
            Matcher matcher = PRESSURE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("avg10", Double.parseDouble(matcher.group(2)));
            metrics.put("avg60", Double.parseDouble(matcher.group(3)));
            metrics.put("avg300", Double.parseDouble(matcher.group(4)));
            metrics.put("total", Long.parseLong(matcher.group(5)));
            pressure.put(matcher.group(1), metrics);
        }
        return pressure;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private record SectionValue(String rawValue, Long numericValue, boolean present) {
    }

    private record LimitValue(String rawValue, boolean defined, long bytes) {
    }
}
