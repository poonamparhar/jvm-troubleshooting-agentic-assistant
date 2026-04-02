package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GcLogArtifactParser implements ArtifactParser {

    private static final Pattern G1_PAUSE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Pause\\s+(.+?)\\s+(\\d+)M->(\\d+)M\\((\\d+)M\\)\\s+([0-9.]+)ms$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ZGC_CYCLE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Garbage Collection \\((.+?)\\)\\s+(\\d+)M\\((\\d+)%\\)->(\\d+)M\\((\\d+)%\\)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ALLOCATION_STALL_PATTERN = Pattern.compile(
        "^.*Allocation Stall \\(([^\\)]+)\\)\\s+([0-9.]+)ms$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MMU_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\) MMU:\\s+2ms/([0-9.]+)%,\\s+5ms/([0-9.]+)%,\\s+10ms/([0-9.]+)%,\\s+20ms/([0-9.]+)%,\\s+50ms/([0-9.]+)%,\\s+100ms/([0-9.]+)%$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern G1_METASPACE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Metaspace:\\s*(\\d+)K\\((\\d+)K\\)->(\\d+)K\\((\\d+)K\\).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ZGC_METASPACE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Metaspace:\\s*(\\d+)M used,\\s*(\\d+)M committed,\\s*(\\d+)M reserved$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ELAPSED_SECONDS_PATTERN = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");

    private static final String EVIDENCE_LONGEST_PAUSE = "gc-longest-pause";
    private static final String EVIDENCE_PAUSE_DISTRIBUTION = "gc-pause-distribution";
    private static final String EVIDENCE_FULL_GC_SUMMARY = "gc-full-gc-summary";
    private static final String EVIDENCE_HEAP_OCCUPANCY_PEAK = "gc-heap-occupancy-peak";
    private static final String EVIDENCE_ALLOCATION_STALL_SUMMARY = "gc-allocation-stall-summary";
    private static final String EVIDENCE_METASPACE_SUMMARY = "gc-metaspace-summary";

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        GcParseState state = parseContent(artifact.content());
        GcSummary summary = summarizeGc(state);
        MetaspaceSummary metaspace = summarizeMetaspace(state.metaspaceSnapshots());

        List<Evidence> evidence = buildEvidence(artifact, summary, metaspace);
        List<String> warnings = new ArrayList<>();
        if (metricLong(summary.metrics(), "eventCount") == 0L) {
            warnings.add("Unable to parse major GC events from the GC log.");
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("collector", detectCollector(artifact.content()));
        extractedData.put("pauses", state.pauses());
        extractedData.put("gcCycles", state.gcCycles());
        extractedData.put("allocationStalls", state.allocationStalls());
        extractedData.put("mmuSamples", state.mmuSamples());
        extractedData.put("summary", summary.metrics());
        extractedData.put("metaspace", metaspace.metrics());

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "gc-log-v1", extractedData, evidence, warnings);
    }

    private List<Evidence> buildEvidence(InputArtifact artifact, GcSummary summary, MetaspaceSummary metaspace) {
        List<Evidence> evidence = new ArrayList<>();

        if (!summary.longestPause().isEmpty()) {
            Map<String, Object> longestPause = summary.longestPause();
            evidence.add(ParserUtils.evidence(
                EVIDENCE_LONGEST_PAUSE,
                artifact,
                "Longest GC pause",
                "Longest parsed GC pause event in the log.",
                String.valueOf(longestPause.get("rawLine")),
                Map.of(
                    "pauseMs", longestPause.get("pauseMs"),
                    "beforeHeapMb", longestPause.get("beforeHeapMb"),
                    "afterHeapMb", longestPause.get("afterHeapMb"),
                    "heapCapacityMb", longestPause.get("heapCapacityMb")
                )
            ));
        }

        if (!summary.percentilePause().isEmpty()) {
            Map<String, Object> distributionMetrics = new LinkedHashMap<>();
            distributionMetrics.put("pauseEventCount", summary.metrics().get("pauseEventCount"));
            distributionMetrics.put("averagePauseMs", summary.metrics().get("averagePauseMs"));
            distributionMetrics.put("medianPauseMs", summary.metrics().get("medianPauseMs"));
            distributionMetrics.put("p95PauseMs", summary.metrics().get("p95PauseMs"));
            distributionMetrics.put("totalPauseMs", summary.metrics().get("totalPauseMs"));
            distributionMetrics.put("stopTheWorldOverheadPct", summary.metrics().get("stopTheWorldOverheadPct"));
            evidence.add(ParserUtils.evidence(
                EVIDENCE_PAUSE_DISTRIBUTION,
                artifact,
                "GC pause distribution",
                "Pause distribution summary derived from parsed stop-the-world GC events.",
                String.valueOf(summary.percentilePause().get("rawLine")),
                distributionMetrics
            ));
        }

        if (!summary.longestFullGc().isEmpty()) {
            Map<String, Object> fullGcMetrics = new LinkedHashMap<>();
            fullGcMetrics.put("fullGcCount", summary.metrics().get("fullGcCount"));
            fullGcMetrics.put("metaspaceTriggeredFullGcCount", summary.metrics().get("metaspaceTriggeredFullGcCount"));
            fullGcMetrics.put("maxFullGcPauseMs", summary.metrics().get("maxFullGcPauseMs"));
            fullGcMetrics.put("fullGcCauses", summary.metrics().get("fullGcCauses"));
            evidence.add(ParserUtils.evidence(
                EVIDENCE_FULL_GC_SUMMARY,
                artifact,
                "Full GC summary",
                "Full-GC activity observed in the GC log, including the heaviest parsed full collection.",
                String.valueOf(summary.longestFullGc().get("rawLine")),
                fullGcMetrics
            ));
        }

        if (!summary.peakOccupancyEvent().isEmpty()) {
            Map<String, Object> occupancyMetrics = new LinkedHashMap<>();
            occupancyMetrics.put("peakHeapOccupancyRatio", summary.metrics().get("peakHeapOccupancyRatio"));
            if (summary.peakOccupancyEvent().containsKey("afterHeapMb")) {
                occupancyMetrics.put("afterHeapMb", summary.peakOccupancyEvent().get("afterHeapMb"));
            }
            if (summary.peakOccupancyEvent().containsKey("heapCapacityMb")) {
                occupancyMetrics.put("heapCapacityMb", summary.peakOccupancyEvent().get("heapCapacityMb"));
            }
            evidence.add(ParserUtils.evidence(
                EVIDENCE_HEAP_OCCUPANCY_PEAK,
                artifact,
                "Peak post-GC occupancy",
                "Parsed GC event showing the highest retained occupancy after collection.",
                String.valueOf(summary.peakOccupancyEvent().get("rawLine")),
                occupancyMetrics
            ));
        }

        if (!summary.longestAllocationStall().isEmpty()) {
            Map<String, Object> allocationStallMetrics = new LinkedHashMap<>();
            allocationStallMetrics.put("allocationStallCount", summary.metrics().get("allocationStallCount"));
            allocationStallMetrics.put("totalAllocationStallMs", summary.metrics().get("totalAllocationStallMs"));
            allocationStallMetrics.put("maxAllocationStallMs", summary.metrics().get("maxAllocationStallMs"));
            allocationStallMetrics.put("stopTheWorldOverheadPct", summary.metrics().get("stopTheWorldOverheadPct"));
            if (summary.metrics().containsKey("lowestMmu10msPercent")) {
                allocationStallMetrics.put("lowestMmu10msPercent", summary.metrics().get("lowestMmu10msPercent"));
            }
            evidence.add(ParserUtils.evidence(
                EVIDENCE_ALLOCATION_STALL_SUMMARY,
                artifact,
                "Allocation stall summary",
                "Repeated application-thread allocation stalls were parsed from the GC log.",
                String.valueOf(summary.longestAllocationStall().get("rawLine")),
                allocationStallMetrics
            ));
        }

        if (!metaspace.metrics().isEmpty()) {
            evidence.add(ParserUtils.evidence(
                EVIDENCE_METASPACE_SUMMARY,
                artifact,
                "GC metaspace summary",
                "Peak metaspace footprint observed in the parsed GC log.",
                String.valueOf(metaspace.peakSnapshot().get("rawLine")),
                metaspace.metrics()
            ));
        }

        return evidence;
    }

    private GcParseState parseContent(String content) {
        List<Map<String, Object>> pauses = new ArrayList<>();
        List<Map<String, Object>> gcCycles = new ArrayList<>();
        List<Map<String, Object>> allocationStalls = new ArrayList<>();
        List<Map<String, Object>> mmuSamples = new ArrayList<>();
        List<Map<String, Object>> metaspaceSnapshots = new ArrayList<>();

        for (String line : ParserUtils.lines(content)) {
            parsePauseLine(line).ifPresent(pauses::add);
            parseCycleLine(line).ifPresent(gcCycles::add);
            parseAllocationStallLine(line).ifPresent(allocationStalls::add);
            parseMmuLine(line).ifPresent(mmuSamples::add);
            parseMetaspaceLine(line).ifPresent(metaspaceSnapshots::add);
        }

        return new GcParseState(
            List.copyOf(pauses),
            List.copyOf(gcCycles),
            List.copyOf(allocationStalls),
            List.copyOf(mmuSamples),
            List.copyOf(metaspaceSnapshots)
        );
    }

    private java.util.Optional<Map<String, Object>> parsePauseLine(String line) {
        Matcher matcher = G1_PAUSE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        long beforeHeapMb = Long.parseLong(matcher.group(3));
        long afterHeapMb = Long.parseLong(matcher.group(4));
        long heapCapacityMb = Long.parseLong(matcher.group(5));
        Map<String, Object> pause = new LinkedHashMap<>();
        pause.put("gcId", Long.parseLong(matcher.group(1)));
        pause.put("event", matcher.group(2).trim());
        pause.put("beforeHeapMb", beforeHeapMb);
        pause.put("afterHeapMb", afterHeapMb);
        pause.put("heapCapacityMb", heapCapacityMb);
        pause.put("beforeOccupancyRatio", occupancyRatio(beforeHeapMb, heapCapacityMb));
        pause.put("afterOccupancyRatio", occupancyRatio(afterHeapMb, heapCapacityMb));
        pause.put("pauseMs", Double.parseDouble(matcher.group(6)));
        parseElapsedSeconds(line).ifPresent(seconds -> pause.put("elapsedSeconds", seconds));
        pause.put("rawLine", line.trim());
        return java.util.Optional.of(Map.copyOf(pause));
    }

    private java.util.Optional<Map<String, Object>> parseCycleLine(String line) {
        Matcher matcher = ZGC_CYCLE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        Map<String, Object> gcCycle = new LinkedHashMap<>();
        gcCycle.put("gcId", Long.parseLong(matcher.group(1)));
        gcCycle.put("event", "Garbage Collection (" + matcher.group(2).trim() + ")");
        gcCycle.put("beforeHeapMb", Long.parseLong(matcher.group(3)));
        gcCycle.put("afterHeapMb", Long.parseLong(matcher.group(5)));
        gcCycle.put("beforeOccupancyRatio", Integer.parseInt(matcher.group(4)) / 100.0d);
        gcCycle.put("afterOccupancyRatio", Integer.parseInt(matcher.group(6)) / 100.0d);
        parseElapsedSeconds(line).ifPresent(seconds -> gcCycle.put("elapsedSeconds", seconds));
        gcCycle.put("rawLine", line.trim());
        return java.util.Optional.of(Map.copyOf(gcCycle));
    }

    private java.util.Optional<Map<String, Object>> parseAllocationStallLine(String line) {
        Matcher matcher = ALLOCATION_STALL_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        Map<String, Object> allocationStall = new LinkedHashMap<>();
        allocationStall.put("thread", matcher.group(1).trim());
        allocationStall.put("stallMs", Double.parseDouble(matcher.group(2)));
        parseElapsedSeconds(line).ifPresent(seconds -> allocationStall.put("elapsedSeconds", seconds));
        allocationStall.put("rawLine", line.trim());
        return java.util.Optional.of(Map.copyOf(allocationStall));
    }

    private java.util.Optional<Map<String, Object>> parseMmuLine(String line) {
        Matcher matcher = MMU_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        Map<String, Object> mmuSample = new LinkedHashMap<>();
        mmuSample.put("gcId", Long.parseLong(matcher.group(1)));
        mmuSample.put("twoMsPercent", Double.parseDouble(matcher.group(2)));
        mmuSample.put("fiveMsPercent", Double.parseDouble(matcher.group(3)));
        mmuSample.put("tenMsPercent", Double.parseDouble(matcher.group(4)));
        mmuSample.put("twentyMsPercent", Double.parseDouble(matcher.group(5)));
        mmuSample.put("fiftyMsPercent", Double.parseDouble(matcher.group(6)));
        mmuSample.put("hundredMsPercent", Double.parseDouble(matcher.group(7)));
        parseElapsedSeconds(line).ifPresent(seconds -> mmuSample.put("elapsedSeconds", seconds));
        mmuSample.put("rawLine", line.trim());
        return java.util.Optional.of(Map.copyOf(mmuSample));
    }

    private java.util.Optional<Map<String, Object>> parseMetaspaceLine(String line) {
        Matcher g1Matcher = G1_METASPACE_PATTERN.matcher(line);
        if (g1Matcher.matches()) {
            long usedKb = Long.parseLong(g1Matcher.group(4));
            long committedKb = Long.parseLong(g1Matcher.group(5));
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("gcId", Long.parseLong(g1Matcher.group(1)));
            snapshot.put("usedKb", usedKb);
            snapshot.put("committedKb", committedKb);
            snapshot.put("usageRatio", occupancyRatio(usedKb, committedKb));
            parseElapsedSeconds(line).ifPresent(seconds -> snapshot.put("elapsedSeconds", seconds));
            snapshot.put("rawLine", line.trim());
            return java.util.Optional.of(Map.copyOf(snapshot));
        }

        Matcher zgcMatcher = ZGC_METASPACE_PATTERN.matcher(line);
        if (!zgcMatcher.matches()) {
            return java.util.Optional.empty();
        }

        long usedKb = Long.parseLong(zgcMatcher.group(2)) * 1_024L;
        long committedKb = Long.parseLong(zgcMatcher.group(3)) * 1_024L;
        long reservedKb = Long.parseLong(zgcMatcher.group(4)) * 1_024L;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gcId", Long.parseLong(zgcMatcher.group(1)));
        snapshot.put("usedKb", usedKb);
        snapshot.put("committedKb", committedKb);
        snapshot.put("reservedKb", reservedKb);
        snapshot.put("usageRatio", occupancyRatio(usedKb, committedKb));
        parseElapsedSeconds(line).ifPresent(seconds -> snapshot.put("elapsedSeconds", seconds));
        snapshot.put("rawLine", line.trim());
        return java.util.Optional.of(Map.copyOf(snapshot));
    }

    private java.util.Optional<Double> parseElapsedSeconds(String line) {
        Matcher matcher = ELAPSED_SECONDS_PATTERN.matcher(line);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Double.parseDouble(matcher.group(1)));
    }

    private String detectCollector(String content) {
        String lower = content.toLowerCase();
        if (lower.contains("using the z garbage collector") || lower.contains("z garbage collector") || lower.contains("using zgc")) {
            return "ZGC";
        }
        if (lower.contains("using g1") || lower.contains("g1 evacuation pause") || lower.contains("g1 compaction pause")) {
            return "G1";
        }
        if (lower.contains("shenandoah")) {
            return "Shenandoah";
        }
        if (lower.contains("parallel gc") || lower.contains("parallel scavenge") || lower.contains("using parallel")) {
            return "Parallel";
        }
        if (lower.contains("serial gc") || lower.contains("using serial")) {
            return "Serial";
        }
        return "UNKNOWN";
    }

    private GcSummary summarizeGc(GcParseState state) {
        List<Double> pauseDurations = state.pauses().stream()
            .map(event -> metricDouble(event, "pauseMs"))
            .sorted()
            .toList();
        double totalPauseMs = pauseDurations.stream().mapToDouble(Double::doubleValue).sum();
        double maxPauseMs = pauseDurations.isEmpty() ? 0.0d : pauseDurations.get(pauseDurations.size() - 1);
        double averagePauseMs = pauseDurations.isEmpty() ? 0.0d : totalPauseMs / pauseDurations.size();
        double medianPauseMs = percentile(pauseDurations, 50.0d);
        double p95PauseMs = percentile(pauseDurations, 95.0d);

        long fullGcCount = state.pauses().stream().filter(this::isFullGcEvent).count();
        long metaspaceTriggeredFullGcCount = state.pauses().stream().filter(this::isMetaspaceTriggeredFullGc).count();
        double maxFullGcPauseMs = state.pauses().stream()
            .filter(this::isFullGcEvent)
            .mapToDouble(event -> metricDouble(event, "pauseMs"))
            .max()
            .orElse(0.0d);
        Set<String> fullGcCauses = state.pauses().stream()
            .filter(this::isFullGcEvent)
            .map(event -> String.valueOf(event.get("event")))
            .collect(LinkedHashSet::new, Set::add, Set::addAll);

        double totalAllocationStallMs = state.allocationStalls().stream()
            .mapToDouble(event -> metricDouble(event, "stallMs"))
            .sum();
        double maxAllocationStallMs = state.allocationStalls().stream()
            .mapToDouble(event -> metricDouble(event, "stallMs"))
            .max()
            .orElse(0.0d);

        Map<String, Object> longestPause = state.pauses().stream()
            .max(Comparator.comparingDouble(event -> metricDouble(event, "pauseMs")))
            .orElse(Map.of());
        Map<String, Object> longestFullGc = state.pauses().stream()
            .filter(this::isFullGcEvent)
            .max(Comparator.comparingDouble(event -> metricDouble(event, "pauseMs")))
            .orElse(Map.of());
        Map<String, Object> percentilePause = percentileEvent(state.pauses(), 95.0d);
        Map<String, Object> peakOccupancyEvent = peakOccupancyEvent(state.pauses(), state.gcCycles());
        Map<String, Object> longestAllocationStall = state.allocationStalls().stream()
            .max(Comparator.comparingDouble(event -> metricDouble(event, "stallMs")))
            .orElse(Map.of());

        TimeWindow timeWindow = timeWindow(state.pauses(), state.gcCycles(), state.allocationStalls());
        double logWindowMs = timeWindow.latestSeconds() > timeWindow.earliestSeconds()
            ? (timeWindow.latestSeconds() - timeWindow.earliestSeconds()) * 1_000.0d
            : 0.0d;
        double stopTheWorldOverheadPct = logWindowMs > 0.0d
            ? Math.min(100.0d, ((totalPauseMs + totalAllocationStallMs) / logWindowMs) * 100.0d)
            : 0.0d;

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("eventCount", (long) (state.pauses().size() + state.gcCycles().size() + state.allocationStalls().size()));
        metrics.put("pauseEventCount", (long) state.pauses().size());
        metrics.put("gcCycleCount", (long) state.gcCycles().size());
        metrics.put("fullGcCount", fullGcCount);
        metrics.put("metaspaceTriggeredFullGcCount", metaspaceTriggeredFullGcCount);
        metrics.put("averagePauseMs", averagePauseMs);
        metrics.put("medianPauseMs", medianPauseMs);
        metrics.put("p95PauseMs", p95PauseMs);
        metrics.put("totalPauseMs", totalPauseMs);
        metrics.put("maxPauseMs", maxPauseMs);
        metrics.put("maxFullGcPauseMs", maxFullGcPauseMs);
        metrics.put("peakHeapOccupancyRatio", metricDouble(peakOccupancyEvent, "afterOccupancyRatio"));
        metrics.put("logWindowMs", logWindowMs);
        metrics.put("stopTheWorldOverheadPct", stopTheWorldOverheadPct);
        metrics.put("allocationStallCount", (long) state.allocationStalls().size());
        metrics.put("totalAllocationStallMs", totalAllocationStallMs);
        metrics.put("maxAllocationStallMs", maxAllocationStallMs);
        metrics.put("fullGcCauses", List.copyOf(fullGcCauses));

        if (!state.mmuSamples().isEmpty()) {
            metrics.put("lowestMmu2msPercent", state.mmuSamples().stream().mapToDouble(event -> metricDouble(event, "twoMsPercent")).min().orElse(0.0d));
            metrics.put("lowestMmu5msPercent", state.mmuSamples().stream().mapToDouble(event -> metricDouble(event, "fiveMsPercent")).min().orElse(0.0d));
            metrics.put("lowestMmu10msPercent", state.mmuSamples().stream().mapToDouble(event -> metricDouble(event, "tenMsPercent")).min().orElse(0.0d));
            metrics.put("lowestMmu20msPercent", state.mmuSamples().stream().mapToDouble(event -> metricDouble(event, "twentyMsPercent")).min().orElse(0.0d));
        }

        return new GcSummary(
            Map.copyOf(metrics),
            longestPause,
            longestFullGc,
            percentilePause,
            peakOccupancyEvent,
            longestAllocationStall
        );
    }

    private MetaspaceSummary summarizeMetaspace(List<Map<String, Object>> metaspaceSnapshots) {
        if (metaspaceSnapshots.isEmpty()) {
            return new MetaspaceSummary(Map.of(), Map.of());
        }

        Map<String, Object> firstSnapshot = metaspaceSnapshots.get(0);
        Map<String, Object> latestSnapshot = metaspaceSnapshots.get(metaspaceSnapshots.size() - 1);
        Map<String, Object> peakSnapshot = metaspaceSnapshots.stream()
            .max(Comparator.comparingDouble(snapshot -> metricDouble(snapshot, "usageRatio")))
            .orElse(Map.of());

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("snapshotCount", (long) metaspaceSnapshots.size());
        metrics.put("latestUsedKb", metricLong(latestSnapshot, "usedKb"));
        metrics.put("latestCommittedKb", metricLong(latestSnapshot, "committedKb"));
        if (latestSnapshot.containsKey("reservedKb")) {
            metrics.put("latestReservedKb", metricLong(latestSnapshot, "reservedKb"));
        }
        metrics.put("peakUsedKb", metricLong(peakSnapshot, "usedKb"));
        metrics.put("peakCommittedKb", metricLong(peakSnapshot, "committedKb"));
        if (peakSnapshot.containsKey("reservedKb")) {
            metrics.put("peakReservedKb", metricLong(peakSnapshot, "reservedKb"));
        }
        metrics.put("peakUsageRatio", metricDouble(peakSnapshot, "usageRatio"));
        metrics.put("usedGrowthKb", metricLong(latestSnapshot, "usedKb") - metricLong(firstSnapshot, "usedKb"));
        metrics.put("committedGrowthKb", metricLong(latestSnapshot, "committedKb") - metricLong(firstSnapshot, "committedKb"));

        return new MetaspaceSummary(Map.copyOf(metrics), peakSnapshot);
    }

    private Map<String, Object> percentileEvent(List<Map<String, Object>> pauses, double percentile) {
        if (pauses.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> sorted = pauses.stream()
            .sorted(Comparator.comparingDouble(event -> metricDouble(event, "pauseMs")))
            .toList();
        return sorted.get(percentileIndex(sorted.size(), percentile));
    }

    private Map<String, Object> peakOccupancyEvent(List<Map<String, Object>> pauses, List<Map<String, Object>> gcCycles) {
        Map<String, Object> peakEvent = Map.of();
        double maxRatio = 0.0d;
        for (Map<String, Object> event : pauses) {
            double ratio = metricDouble(event, "afterOccupancyRatio");
            if (ratio > maxRatio) {
                maxRatio = ratio;
                peakEvent = event;
            }
        }
        for (Map<String, Object> event : gcCycles) {
            double ratio = metricDouble(event, "afterOccupancyRatio");
            if (ratio > maxRatio) {
                maxRatio = ratio;
                peakEvent = event;
            }
        }
        return peakEvent;
    }

    private TimeWindow timeWindow(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls
    ) {
        double earliestSeconds = Double.POSITIVE_INFINITY;
        double latestSeconds = Double.NEGATIVE_INFINITY;

        for (Map<String, Object> event : pauses) {
            if (event.containsKey("elapsedSeconds")) {
                double elapsedSeconds = metricDouble(event, "elapsedSeconds");
                earliestSeconds = Math.min(earliestSeconds, elapsedSeconds);
                latestSeconds = Math.max(latestSeconds, elapsedSeconds);
            }
        }
        for (Map<String, Object> event : gcCycles) {
            if (event.containsKey("elapsedSeconds")) {
                double elapsedSeconds = metricDouble(event, "elapsedSeconds");
                earliestSeconds = Math.min(earliestSeconds, elapsedSeconds);
                latestSeconds = Math.max(latestSeconds, elapsedSeconds);
            }
        }
        for (Map<String, Object> event : allocationStalls) {
            if (event.containsKey("elapsedSeconds")) {
                double elapsedSeconds = metricDouble(event, "elapsedSeconds");
                earliestSeconds = Math.min(earliestSeconds, elapsedSeconds);
                latestSeconds = Math.max(latestSeconds, elapsedSeconds);
            }
        }

        if (!Double.isFinite(earliestSeconds) || !Double.isFinite(latestSeconds)) {
            return new TimeWindow(0.0d, 0.0d);
        }
        return new TimeWindow(earliestSeconds, latestSeconds);
    }

    private int percentileIndex(int size, double percentile) {
        if (size <= 1) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100.0d) * size) - 1;
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size - 1);
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        return values.get(percentileIndex(values.size(), percentile));
    }

    private boolean isFullGcEvent(Map<String, Object> pause) {
        return String.valueOf(pause.getOrDefault("event", "")).toLowerCase().contains("full");
    }

    private boolean isMetaspaceTriggeredFullGc(Map<String, Object> pause) {
        String event = String.valueOf(pause.getOrDefault("event", "")).toLowerCase();
        return event.contains("full") && event.contains("metadata gc");
    }

    private double occupancyRatio(long used, long committed) {
        if (committed <= 0L) {
            return 0.0d;
        }
        return (double) used / committed;
    }

    private long metricLong(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private double metricDouble(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    private record GcParseState(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> mmuSamples,
        List<Map<String, Object>> metaspaceSnapshots
    ) {
    }

    private record GcSummary(
        Map<String, Object> metrics,
        Map<String, Object> longestPause,
        Map<String, Object> longestFullGc,
        Map<String, Object> percentilePause,
        Map<String, Object> peakOccupancyEvent,
        Map<String, Object> longestAllocationStall
    ) {
    }

    private record MetaspaceSummary(
        Map<String, Object> metrics,
        Map<String, Object> peakSnapshot
    ) {
    }

    private record TimeWindow(
        double earliestSeconds,
        double latestSeconds
    ) {
    }
}
