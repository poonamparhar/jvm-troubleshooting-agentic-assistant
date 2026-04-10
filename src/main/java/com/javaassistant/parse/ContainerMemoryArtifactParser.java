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
        String snapshotVariant = detectSnapshotVariant(sections);
        SectionValue current = parseFirstScalarSection(sections, "memory.current", "memory.usage_in_bytes");
        LimitValue max = parseLimitValue(parseFirstScalarSection(sections, "memory.max", "memory.limit_in_bytes").rawValue());
        LimitValue high = parseLimitValue(parseFirstScalarSection(sections, "memory.high", "memory.soft_limit_in_bytes").rawValue());
        Map<String, Long> events = parseNumericEntries(sections.get("memory.events"));
        if (events.isEmpty()) {
            events = parseLegacyEvents(sections);
        }
        Map<String, Long> stat = parseNumericEntries(sections.get("memory.stat"));
        Map<String, Map<String, Object>> pressure = parsePressure(sections.get("memory.pressure"));
        CpuQuotaValue cpuQuota = parseCpuQuota(sections);
        SectionValue cpuset = parseFirstScalarSection(sections, "cpuset.cpus.effective", "cpuset.cpus");
        long effectiveCpuCount = parseCpuSetCount(cpuset.rawValue());
        Map<String, Object> cpuStat = parseCpuStat(sections);
        Map<String, Map<String, Object>> cpuPressure = parsePressure(sections.get("cpu.pressure"));
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

        LinkedHashMap<String, Object> cpuSummary = new LinkedHashMap<>();
        cpuSummary.put("quotaDefined", cpuQuota.defined());
        if (cpuQuota.quotaMicros() > 0L) {
            cpuSummary.put("quotaMicros", cpuQuota.quotaMicros());
        }
        if (cpuQuota.periodMicros() > 0L) {
            cpuSummary.put("periodMicros", cpuQuota.periodMicros());
        }
        if (cpuQuota.defined()) {
            cpuSummary.put("quotaCores", cpuQuota.quotaCores());
        }
        if (cpuset.present() && cpuset.rawValue() != null) {
            cpuSummary.put("effectiveCpuSet", cpuset.rawValue());
        }
        if (effectiveCpuCount > 0L) {
            cpuSummary.put("effectiveCpuCount", effectiveCpuCount);
        }
        double configuredCpuCeilingCores = effectiveCpuCeilingCores(cpuQuota, effectiveCpuCount);
        if (configuredCpuCeilingCores > 0.0d) {
            cpuSummary.put("configuredCpuCeilingCores", configuredCpuCeilingCores);
        }

        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("snapshotVariant", snapshotVariant);
        extractedData.put("summary", summary);
        extractedData.put("events", events);
        extractedData.put("stat", stat);
        extractedData.put("pressure", pressure);
        extractedData.put("cpuSummary", cpuSummary);
        extractedData.put("cpuStat", cpuStat);
        extractedData.put("cpuPressure", cpuPressure);
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
                firstAvailableSectionLabel(sections, "memory.current", "memory.usage_in_bytes"),
                metrics
            ));
        }

        if (!events.isEmpty()) {
            evidence.add(ParserUtils.evidence(
                "container-memory-events",
                artifact,
                "Container memory event counters",
                "Cgroup memory high, max, OOM, and kill counters.",
                firstAvailableSectionLabel(sections, "memory.events", "memory.failcnt", "memory.oom_control"),
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

        if (!cpuSummary.isEmpty()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>(cpuSummary);
            evidence.add(ParserUtils.evidence(
                "container-cpu-summary",
                artifact,
                "Container CPU budget summary",
                "Configured CPU quota and effective CPU-set budget from cgroup files.",
                firstAvailableSectionLabel(sections, "cpu.max", "cpu.cfs_quota_us", "cpuset.cpus.effective", "cpuset.cpus"),
                metrics
            ));
        }

        if (!cpuStat.isEmpty()) {
            evidence.add(ParserUtils.evidence(
                "container-cpu-stat",
                artifact,
                "Container CPU throttling counters",
                "CPU quota, throttling, and usage counters from cpu.stat or cpuacct files.",
                firstAvailableSectionLabel(sections, "cpu.stat", "cpuacct.usage", "cpuacct.stat"),
                new LinkedHashMap<>(cpuStat)
            ));
        }

        if (!cpuPressure.isEmpty()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            if (cpuPressure.containsKey("some")) {
                metrics.put("some", cpuPressure.get("some"));
            }
            if (cpuPressure.containsKey("full")) {
                metrics.put("full", cpuPressure.get("full"));
            }
            evidence.add(ParserUtils.evidence(
                "container-cpu-pressure",
                artifact,
                "Container CPU PSI pressure",
                "Recent CPU scheduler pressure from cpu.pressure.",
                "[cpu.pressure]",
                metrics
            ));
        }

        if (!hasAnySection(sections, "memory.current", "memory.usage_in_bytes")) {
            warnings.add("No memory.current or memory.usage_in_bytes section was present in the container memory snapshot.");
        }
        if (!hasAnySection(sections, "memory.max", "memory.limit_in_bytes")) {
            warnings.add("No memory.max or memory.limit_in_bytes section was present in the container memory snapshot.");
        }
        if (!hasAnySection(sections, "memory.events", "memory.failcnt", "memory.oom_control")) {
            warnings.add("No memory.events or memory.failcnt/memory.oom_control section was present in the container memory snapshot.");
        }
        if (!sections.containsKey("memory.stat")) {
            warnings.add("memory.stat section not present in the container memory snapshot.");
        }
        if (!hasAnySection(sections, "memory.pressure", "memory.pressure_level")) {
            warnings.add("No memory.pressure PSI section was present in the container memory snapshot.");
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "container-memory-v2", extractedData, evidence, warnings);
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

    private SectionValue parseFirstScalarSection(Map<String, List<String>> sections, String... sectionNames) {
        for (String sectionName : sectionNames) {
            SectionValue value = parseScalarSection(sections.get(sectionName));
            if (value.present()) {
                return value;
            }
        }
        return new SectionValue(null, null, false);
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

    private Map<String, Long> parseLegacyEvents(Map<String, List<String>> sections) {
        LinkedHashMap<String, Long> events = new LinkedHashMap<>();
        SectionValue failCount = parseScalarSection(sections.get("memory.failcnt"));
        if (failCount.numericValue() != null) {
            events.put("max", failCount.numericValue());
            events.put("failcnt", failCount.numericValue());
        }

        Map<String, Long> oomControl = parseNumericEntries(sections.get("memory.oom_control"));
        if (oomControl.containsKey("under_oom")) {
            events.put("oom", oomControl.get("under_oom"));
        }
        if (oomControl.containsKey("oom_kill_disable")) {
            events.put("oom_kill_disable", oomControl.get("oom_kill_disable"));
        }
        return events;
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

    private CpuQuotaValue parseCpuQuota(Map<String, List<String>> sections) {
        CpuQuotaValue v2Quota = parseCpuMax(sections.get("cpu.max"));
        if (v2Quota.present()) {
            return v2Quota;
        }

        SectionValue quota = parseScalarSection(sections.get("cpu.cfs_quota_us"));
        SectionValue period = parseScalarSection(sections.get("cpu.cfs_period_us"));
        if (!quota.present() && !period.present()) {
            return CpuQuotaValue.absent();
        }

        long quotaMicros = quota.numericValue() != null ? quota.numericValue() : 0L;
        long periodMicros = period.numericValue() != null ? period.numericValue() : 0L;
        boolean defined = quotaMicros > 0L && periodMicros > 0L;
        double quotaCores = defined ? (double) quotaMicros / (double) periodMicros : 0.0d;
        return new CpuQuotaValue(true, defined, quotaMicros, periodMicros, quotaCores);
    }

    private CpuQuotaValue parseCpuMax(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return CpuQuotaValue.absent();
        }

        String rawValue = lines.getFirst().trim();
        if (rawValue.isEmpty()) {
            return CpuQuotaValue.absent();
        }

        String[] parts = rawValue.split("\\s+");
        if (parts.length < 2) {
            return new CpuQuotaValue(true, false, 0L, 0L, 0.0d);
        }

        Long periodMicros = parseLong(parts[1]);
        if (periodMicros == null || periodMicros <= 0L || "max".equalsIgnoreCase(parts[0])) {
            return new CpuQuotaValue(true, false, 0L, periodMicros != null ? periodMicros : 0L, 0.0d);
        }

        Long quotaMicros = parseLong(parts[0]);
        if (quotaMicros == null || quotaMicros <= 0L) {
            return new CpuQuotaValue(true, false, 0L, periodMicros, 0.0d);
        }
        return new CpuQuotaValue(true, true, quotaMicros, periodMicros, (double) quotaMicros / (double) periodMicros);
    }

    private Map<String, Object> parseCpuStat(Map<String, List<String>> sections) {
        LinkedHashMap<String, Object> cpuStat = new LinkedHashMap<>();
        cpuStat.putAll(parseNumericEntries(sections.get("cpu.stat")));

        SectionValue usageNs = parseScalarSection(sections.get("cpuacct.usage"));
        if (usageNs.numericValue() != null) {
            cpuStat.put("usageNs", usageNs.numericValue());
            cpuStat.put("usageMillis", usageNs.numericValue() / 1_000_000L);
        }

        Map<String, Long> cpuAcctStat = parseNumericEntries(sections.get("cpuacct.stat"));
        if (cpuAcctStat.containsKey("user")) {
            cpuStat.put("userTicks", cpuAcctStat.get("user"));
        }
        if (cpuAcctStat.containsKey("system")) {
            cpuStat.put("systemTicks", cpuAcctStat.get("system"));
        }

        long nrPeriods = longValue(cpuStat.get("nr_periods"));
        long nrThrottled = longValue(cpuStat.get("nr_throttled"));
        if (nrPeriods > 0L) {
            cpuStat.put("throttledRatio", ratio(nrThrottled, nrPeriods));
        }

        long throttledUsec = longValue(cpuStat.get("throttled_usec"));
        if (throttledUsec > 0L) {
            cpuStat.put("throttledMillis", throttledUsec / 1_000L);
        }

        long throttledTimeNs = longValue(cpuStat.get("throttled_time"));
        if (!cpuStat.containsKey("throttledMillis") && throttledTimeNs > 0L) {
            cpuStat.put("throttledMillis", throttledTimeNs / 1_000_000L);
        }

        long usageUsec = longValue(cpuStat.get("usage_usec"));
        if (usageUsec > 0L) {
            cpuStat.put("usageMillis", usageUsec / 1_000L);
        }

        return cpuStat;
    }

    private long parseCpuSetCount(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0L;
        }

        long count = 0L;
        for (String token : rawValue.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('-');
            if (separator < 0) {
                count += 1L;
                continue;
            }
            Long start = parseLong(trimmed.substring(0, separator));
            Long end = parseLong(trimmed.substring(separator + 1));
            if (start == null || end == null || end < start) {
                continue;
            }
            count += (end - start) + 1L;
        }
        return count;
    }

    private double effectiveCpuCeilingCores(CpuQuotaValue cpuQuota, long effectiveCpuCount) {
        if (cpuQuota != null && cpuQuota.defined() && effectiveCpuCount > 0L) {
            return Math.min(cpuQuota.quotaCores(), (double) effectiveCpuCount);
        }
        if (cpuQuota != null && cpuQuota.defined()) {
            return cpuQuota.quotaCores();
        }
        if (effectiveCpuCount > 0L) {
            return (double) effectiveCpuCount;
        }
        return 0.0d;
    }

    private String detectSnapshotVariant(Map<String, List<String>> sections) {
        if (hasAnySection(sections, "memory.current", "memory.max", "memory.events")) {
            return "cgroup-v2";
        }
        if (hasAnySection(sections, "memory.usage_in_bytes", "memory.limit_in_bytes", "memory.failcnt")) {
            return "cgroup-v1";
        }
        return "unknown";
    }

    private boolean hasAnySection(Map<String, List<String>> sections, String... sectionNames) {
        for (String sectionName : sectionNames) {
            if (sections.containsKey(sectionName)) {
                return true;
            }
        }
        return false;
    }

    private String firstAvailableSectionLabel(Map<String, List<String>> sections, String... sectionNames) {
        for (String sectionName : sectionNames) {
            if (sections.containsKey(sectionName)) {
                return "[" + sectionName + "]";
            }
        }
        return "[" + sectionNames[0] + "]";
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Long parseLong(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record SectionValue(String rawValue, Long numericValue, boolean present) {
    }

    private record LimitValue(String rawValue, boolean defined, long bytes) {
    }

    private record CpuQuotaValue(boolean present, boolean defined, long quotaMicros, long periodMicros, double quotaCores) {
        private static CpuQuotaValue absent() {
            return new CpuQuotaValue(false, false, 0L, 0L, 0.0d);
        }
    }
}
