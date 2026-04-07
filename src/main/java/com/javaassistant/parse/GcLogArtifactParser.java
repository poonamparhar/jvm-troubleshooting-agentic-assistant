package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GcLogArtifactParser implements ArtifactParser {

    private static final Pattern UNIFIED_HEAP_PAUSE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Pause\\s+(.+?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)\\s+([0-9.]+)ms$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UNIFIED_DURATION_ONLY_PAUSE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Pause\\s+(.+?)\\s+([0-9.]+)ms$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ZGC_CYCLE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Garbage Collection \\((.+?)\\)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\((\\d+)%\\)->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\((\\d+)%\\)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONCURRENT_CYCLE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+(Concurrent .+? Cycle)\\s+([0-9.]+)ms$",
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
    private static final Pattern UNIFIED_METASPACE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Metaspace:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ZGC_METASPACE_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Metaspace:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\s+used,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\s+committed,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\s+reserved$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_METASPACE_PATTERN = Pattern.compile(
        "^.*(?:\\[)?Metaspace:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)(?:\\])?.*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WORKER_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Using\\s+(\\d+)\\s+workers(?:\\s+of\\s+(\\d+))?(?:\\s+for\\s+(.+?))?$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CPU_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+User=([0-9.]+)s\\s+Sys=([0-9.]+)s\\s+Real=([0-9.]+)s$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_CPU_PATTERN = Pattern.compile(
        "^.*\\[Times:\\s*user=([0-9.]+)\\s+sys=([0-9.]+),\\s*real=([0-9.]+)\\s+secs?\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HUMONGOUS_REGIONS_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+Humongous regions:\\s*(\\d+)->(\\d+).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHASE_DURATION_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+(.+?)\\s+([0-9.]+)ms$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONCURRENT_ABORT_PATTERN = Pattern.compile(
        "^.*GC\\((\\d+)\\)\\s+(.+?Abort)\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ABSOLUTE_TIMESTAMP_PATTERN = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2}T[^\\]]+)]");
    private static final Pattern LEGACY_ABSOLUTE_TIMESTAMP_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ELAPSED_SECONDS_PATTERN = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
    private static final Pattern LEGACY_ELAPSED_SECONDS_PATTERN = Pattern.compile(
        "(?:^|\\s)(\\d+(?:\\.\\d+)?):\\s*\\[(?:Full\\s+)?GC",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_GENERIC_ELAPSED_SECONDS_PATTERN = Pattern.compile(
        "^(?:\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?:\\s+)?(\\d+(?:\\.\\d+)?):\\s*\\[",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_GC_CAUSE_PATTERN = Pattern.compile(
        "\\[(?:Full\\s+)?GC\\s*\\(([^\\)]+)\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_DURATION_SECONDS_PATTERN = Pattern.compile(
        ",\\s*([0-9]+(?:\\.[0-9]+)?)\\s+secs?\\]",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_EVENT_START_PATTERN = Pattern.compile(
        "^(?:\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?:\\s+)?\\d+(?:\\.\\d+)?:\\s*\\[",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_G1_PAUSE_DESCRIPTOR_PATTERN = Pattern.compile(
        "^.*\\[GC pause\\s+(.+?),\\s*([0-9]+(?:\\.[0-9]+)?)\\s+secs?\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_G1_HEAP_SUMMARY_PATTERN = Pattern.compile(
        "^.*Heap:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_HEAP_SNAPSHOT_PATTERN = Pattern.compile(
        "([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\s*\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_CMS_CONCURRENT_PHASE_PATTERN = Pattern.compile(
        "^.*\\[(CMS-concurrent-[^:\\]]+):\\s*(?:[0-9]+(?:\\.[0-9]+)?/)?([0-9]+(?:\\.[0-9]+)?)\\s+secs\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_G1_CONCURRENT_PHASE_PATTERN = Pattern.compile(
        "^.*\\[GC\\s+(concurrent-[^,\\]]+?)(?:-end)?\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s+secs?\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MEMORY_TRANSITION_PATTERN = Pattern.compile(
        "([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])->([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\(([0-9]+(?:\\.[0-9]+)?)\\s*([BKMGT])\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_G1_WORKER_PATTERN = Pattern.compile(
        "^.*\\[Parallel Time:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms,\\s*GC Workers:\\s*(\\d+)\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_HUMONGOUS_REGIONS_PATTERN = Pattern.compile(
        "^.*\\[Humongous regions:\\s*(\\d+)->(\\d+)\\].*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UNIFIED_GC_ID_PATTERN = Pattern.compile("GC\\((\\d+)\\)");
    private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile("\\(([^\\)]+)\\)");

    private static final String EVIDENCE_LONGEST_PAUSE = "gc-longest-pause";
    private static final String EVIDENCE_PAUSE_DISTRIBUTION = "gc-pause-distribution";
    private static final String EVIDENCE_FULL_GC_SUMMARY = "gc-full-gc-summary";
    private static final String EVIDENCE_HEAP_OCCUPANCY_PEAK = "gc-heap-occupancy-peak";
    private static final String EVIDENCE_ALLOCATION_STALL_SUMMARY = "gc-allocation-stall-summary";
    private static final String EVIDENCE_METASPACE_SUMMARY = "gc-metaspace-summary";
    private static final double HIGH_RETAINED_OCCUPANCY_RATIO = 0.85d;
    private static final double NEAR_CAPACITY_OCCUPANCY_RATIO = 0.95d;
    private static final double LOW_RECLAIM_FRACTION_OF_HEAP = 0.05d;

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        GcParseState state = parseContent(artifact.content());
        String collector = detectCollector(artifact.content(), state);
        GcSummary summary = summarizeGc(state);
        Map<String, Object> pauseBreakdown = summarizePauseBreakdown(state.pauses());
        Map<String, Object> recoverySummary = summarizeRecovery(state.pauses());
        Map<String, Object> g1CycleProgressionSummary = summarizeG1CycleProgression(collector, state.pauses());
        Map<String, Object> failureSummary = summarizeFailureSignals(state.pauses(), state.failureSignals());
        Map<String, Object> phaseSummary = summarizePhaseSamples(state.phaseSamples());
        Map<String, Object> concurrentSummary = summarizeConcurrentPhases(state.phaseSamples(), state.gcCycles(), state.failureSignals());
        Map<String, Object> workerSummary = summarizeWorkers(state.workerSamples());
        Map<String, Object> cpuSummary = summarizeCpuSamples(state.cpuSamples());
        Map<String, Object> humongousSummary = summarizeHumongousRegions(state.humongousRegionSamples());
        Map<String, Object> collectorPressureSummary = summarizeCollectorPressure(
            collector,
            state.pauses(),
            state.allocationStalls(),
            state.failureSignals(),
            summary.metrics(),
            pauseBreakdown,
            recoverySummary,
            g1CycleProgressionSummary,
            failureSummary,
            concurrentSummary
        );
        MetaspaceSummary metaspace = summarizeMetaspace(state.metaspaceSnapshots());

        List<Evidence> evidence = buildEvidence(artifact, summary, metaspace);
        List<String> warnings = new ArrayList<>();
        if (metricLong(summary.metrics(), "eventCount") == 0L) {
            warnings.add("Unable to parse major GC events from the GC log.");
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("collector", collector);
        extractedData.put("pauses", state.pauses());
        extractedData.put("gcCycles", state.gcCycles());
        extractedData.put("allocationStalls", state.allocationStalls());
        extractedData.put("mmuSamples", state.mmuSamples());
        extractedData.put("workerSamples", state.workerSamples());
        extractedData.put("cpuSamples", state.cpuSamples());
        extractedData.put("humongousRegionSamples", state.humongousRegionSamples());
        extractedData.put("phaseSamples", state.phaseSamples());
        extractedData.put("failureSignals", state.failureSignals());
        extractedData.put("summary", summary.metrics());
        extractedData.put("collectorPressureSummary", collectorPressureSummary);
        extractedData.put("pauseBreakdown", pauseBreakdown);
        extractedData.put("recoverySummary", recoverySummary);
        extractedData.put("g1CycleProgressionSummary", g1CycleProgressionSummary);
        extractedData.put("failureSummary", failureSummary);
        extractedData.put("phaseSummary", phaseSummary);
        extractedData.put("concurrentSummary", concurrentSummary);
        extractedData.put("workerSummary", workerSummary);
        extractedData.put("cpuSummary", cpuSummary);
        extractedData.put("humongousSummary", humongousSummary);
        extractedData.put("metaspace", metaspace.metrics());

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "gc-log-v2", extractedData, evidence, warnings);
    }

    private List<Evidence> buildEvidence(InputArtifact artifact, GcSummary summary, MetaspaceSummary metaspace) {
        List<Evidence> evidence = new ArrayList<>();

        if (!summary.longestPause().isEmpty()) {
            Map<String, Object> longestPause = summary.longestPause();
            Map<String, Object> longestPauseMetrics = new LinkedHashMap<>();
            longestPauseMetrics.put("pauseMs", longestPause.get("pauseMs"));
            if (longestPause.containsKey("beforeHeapMb")) {
                longestPauseMetrics.put("beforeHeapMb", longestPause.get("beforeHeapMb"));
            }
            if (longestPause.containsKey("afterHeapMb")) {
                longestPauseMetrics.put("afterHeapMb", longestPause.get("afterHeapMb"));
            }
            if (longestPause.containsKey("heapCapacityMb")) {
                longestPauseMetrics.put("heapCapacityMb", longestPause.get("heapCapacityMb"));
            }
            evidence.add(ParserUtils.evidence(
                EVIDENCE_LONGEST_PAUSE,
                artifact,
                "Longest GC pause",
                "Longest parsed GC pause event in the log.",
                String.valueOf(longestPause.get("rawLine")),
                longestPauseMetrics
            ));
        }

        if (!summary.percentilePause().isEmpty()) {
            Map<String, Object> distributionMetrics = new LinkedHashMap<>();
            distributionMetrics.put("pauseEventCount", summary.metrics().get("pauseEventCount"));
            distributionMetrics.put("averagePauseMs", summary.metrics().get("averagePauseMs"));
            distributionMetrics.put("medianPauseMs", summary.metrics().get("medianPauseMs"));
            distributionMetrics.put("p95PauseMs", summary.metrics().get("p95PauseMs"));
            distributionMetrics.put("p99PauseMs", summary.metrics().get("p99PauseMs"));
            distributionMetrics.put("totalPauseMs", summary.metrics().get("totalPauseMs"));
            distributionMetrics.put("stopTheWorldOverheadPct", summary.metrics().get("stopTheWorldOverheadPct"));
            distributionMetrics.put("evacuationFailurePauseCount", summary.metrics().get("evacuationFailurePauseCount"));
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
            fullGcMetrics.put("fullCompactionAttemptCount", summary.metrics().get("fullCompactionAttemptCount"));
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
        List<Map<String, Object>> workerSamples = new ArrayList<>();
        List<Map<String, Object>> cpuSamples = new ArrayList<>();
        List<Map<String, Object>> humongousRegionSamples = new ArrayList<>();
        List<Map<String, Object>> phaseSamples = new ArrayList<>();
        List<Map<String, Object>> failureSignals = new ArrayList<>();
        List<Map<String, Object>> metaspaceSnapshots = new ArrayList<>();

        List<String> lines = ParserUtils.lines(content);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            if (isLegacyG1PauseHeaderLine(line)) {
                LegacyG1PauseBlock pauseBlock = collectLegacyG1PauseBlock(lines, index);
                parseLegacyG1PauseBlock(pauseBlock.lines()).ifPresent(pauses::add);
                for (NumberedGcLine blockLine : pauseBlock.lines()) {
                    parseWorkerLine(blockLine.content(), blockLine.lineNumber()).ifPresent(workerSamples::add);
                    parseCpuLine(blockLine.content(), blockLine.lineNumber()).ifPresent(cpuSamples::add);
                    parseHumongousRegionsLine(blockLine.content(), blockLine.lineNumber()).ifPresent(humongousRegionSamples::add);
                    parsePhaseLine(blockLine.content(), blockLine.lineNumber()).ifPresent(phaseSamples::add);
                    parseFailureSignalLine(blockLine.content(), blockLine.lineNumber()).ifPresent(failureSignals::add);
                    parseMetaspaceLine(blockLine.content(), blockLine.lineNumber()).ifPresent(metaspaceSnapshots::add);
                }
                index = pauseBlock.endIndex();
                continue;
            }
            parsePauseLine(line, lineNumber).ifPresent(pauses::add);
            parseCycleLine(line, lineNumber).ifPresent(gcCycles::add);
            parseAllocationStallLine(line, lineNumber).ifPresent(allocationStalls::add);
            parseMmuLine(line, lineNumber).ifPresent(mmuSamples::add);
            parseWorkerLine(line, lineNumber).ifPresent(workerSamples::add);
            parseCpuLine(line, lineNumber).ifPresent(cpuSamples::add);
            parseHumongousRegionsLine(line, lineNumber).ifPresent(humongousRegionSamples::add);
            parsePhaseLine(line, lineNumber).ifPresent(phaseSamples::add);
            parseFailureSignalLine(line, lineNumber).ifPresent(failureSignals::add);
            parseMetaspaceLine(line, lineNumber).ifPresent(metaspaceSnapshots::add);
        }

        return new GcParseState(
            List.copyOf(pauses),
            List.copyOf(gcCycles),
            List.copyOf(allocationStalls),
            List.copyOf(mmuSamples),
            List.copyOf(workerSamples),
            List.copyOf(cpuSamples),
            List.copyOf(humongousRegionSamples),
            List.copyOf(phaseSamples),
            List.copyOf(failureSignals),
            List.copyOf(metaspaceSnapshots)
        );
    }

    private boolean isLegacyG1PauseHeaderLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("[gc pause")
            && lower.contains("g1")
            && lower.contains("secs]")
            && LEGACY_G1_PAUSE_DESCRIPTOR_PATTERN.matcher(line).matches();
    }

    private LegacyG1PauseBlock collectLegacyG1PauseBlock(List<String> lines, int startIndex) {
        List<NumberedGcLine> blockLines = new ArrayList<>();
        blockLines.add(new NumberedGcLine(startIndex + 1, lines.get(startIndex)));

        int currentIndex = startIndex + 1;
        while (currentIndex < lines.size()) {
            String candidate = lines.get(currentIndex);
            if (!isLegacyG1PauseDetailLine(candidate)) {
                break;
            }
            blockLines.add(new NumberedGcLine(currentIndex + 1, candidate));
            currentIndex++;
        }

        return new LegacyG1PauseBlock(List.copyOf(blockLines), currentIndex - 1);
    }

    private boolean isLegacyG1PauseDetailLine(String line) {
        String trimmed = line.trim();
        return !trimmed.isBlank()
            && trimmed.startsWith("[")
            && !LEGACY_EVENT_START_PATTERN.matcher(line.stripLeading()).matches();
    }

    private Optional<Map<String, Object>> parseLegacyG1PauseBlock(List<NumberedGcLine> blockLines) {
        if (blockLines.isEmpty()) {
            return Optional.empty();
        }

        NumberedGcLine headerLine = blockLines.getFirst();
        Matcher descriptorMatcher = LEGACY_G1_PAUSE_DESCRIPTOR_PATTERN.matcher(headerLine.content());
        if (!descriptorMatcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> pause = pauseEvent(null, legacyG1PauseEvent(headerLine.content()));
        legacyG1HeapTransition(blockLines).ifPresent(transition -> addHeapFootprint(
            pause,
            transition.beforeBytes(),
            transition.afterBytes(),
            transition.capacityBytes()
        ));
        pause.put("pauseMs", Double.parseDouble(descriptorMatcher.group(2)) * 1_000.0d);
        addLineContext(pause, headerLine.content(), headerLine.lineNumber());
        return Optional.of(immutableMap(pause));
    }

    private Optional<MemoryTransition> legacyG1HeapTransition(List<NumberedGcLine> blockLines) {
        for (NumberedGcLine blockLine : blockLines) {
            Matcher heapSummaryMatcher = LEGACY_G1_HEAP_SUMMARY_PATTERN.matcher(blockLine.content());
            if (heapSummaryMatcher.matches()) {
                long beforeBytes = sizeToBytes(heapSummaryMatcher.group(1), heapSummaryMatcher.group(2));
                long beforeCapacityBytes = sizeToBytes(heapSummaryMatcher.group(3), heapSummaryMatcher.group(4));
                long afterBytes = sizeToBytes(heapSummaryMatcher.group(5), heapSummaryMatcher.group(6));
                long afterCapacityBytes = sizeToBytes(heapSummaryMatcher.group(7), heapSummaryMatcher.group(8));
                return Optional.of(new MemoryTransition(
                    beforeBytes,
                    afterBytes,
                    Math.max(beforeCapacityBytes, afterCapacityBytes)
                ));
            }
        }

        for (NumberedGcLine blockLine : blockLines) {
            List<MemoryTransition> transitions = memoryTransitions(blockLine.content());
            if (!transitions.isEmpty()) {
                return Optional.of(transitions.getLast());
            }
        }

        return Optional.empty();
    }

    private Optional<Map<String, Object>> parsePauseLine(String line, int lineNumber) {
        Matcher heapPauseMatcher = UNIFIED_HEAP_PAUSE_PATTERN.matcher(line);
        if (heapPauseMatcher.matches()) {
            long beforeHeapBytes = sizeToBytes(heapPauseMatcher.group(3), heapPauseMatcher.group(4));
            long afterHeapBytes = sizeToBytes(heapPauseMatcher.group(5), heapPauseMatcher.group(6));
            long heapCapacityBytes = sizeToBytes(heapPauseMatcher.group(7), heapPauseMatcher.group(8));
            String event = normalizeWhitespace(heapPauseMatcher.group(2));
            Map<String, Object> pause = pauseEvent(Long.parseLong(heapPauseMatcher.group(1)), event);
            addHeapFootprint(
                pause,
                beforeHeapBytes,
                afterHeapBytes,
                heapCapacityBytes
            );
            pause.put("pauseMs", Double.parseDouble(heapPauseMatcher.group(9)));
            addLineContext(pause, line, lineNumber);
            return Optional.of(immutableMap(pause));
        }

        Matcher durationOnlyPauseMatcher = UNIFIED_DURATION_ONLY_PAUSE_PATTERN.matcher(line);
        if (durationOnlyPauseMatcher.matches()) {
            String event = normalizeWhitespace(durationOnlyPauseMatcher.group(2));
            Map<String, Object> pause = pauseEvent(Long.parseLong(durationOnlyPauseMatcher.group(1)), event);
            pause.put("pauseMs", Double.parseDouble(durationOnlyPauseMatcher.group(3)));
            addLineContext(pause, line, lineNumber);
            return Optional.of(immutableMap(pause));
        }

        return parseLegacyPauseLine(line, lineNumber);
    }

    private Optional<Map<String, Object>> parseCycleLine(String line, int lineNumber) {
        Matcher matcher = ZGC_CYCLE_PATTERN.matcher(line);
        if (matcher.matches()) {
            long beforeHeapBytes = sizeToBytes(matcher.group(3), matcher.group(4));
            long afterHeapBytes = sizeToBytes(matcher.group(6), matcher.group(7));
            Map<String, Object> gcCycle = new LinkedHashMap<>();
            gcCycle.put("gcId", Long.parseLong(matcher.group(1)));
            gcCycle.put("event", "Garbage Collection (" + matcher.group(2).trim() + ")");
            gcCycle.put("cause", normalizeWhitespace(matcher.group(2)));
            gcCycle.put("beforeHeapBytes", beforeHeapBytes);
            gcCycle.put("afterHeapBytes", afterHeapBytes);
            gcCycle.put("beforeHeapMb", bytesToRoundedMb(beforeHeapBytes));
            gcCycle.put("afterHeapMb", bytesToRoundedMb(afterHeapBytes));
            gcCycle.put("beforeOccupancyRatio", Integer.parseInt(matcher.group(5)) / 100.0d);
            gcCycle.put("afterOccupancyRatio", Integer.parseInt(matcher.group(8)) / 100.0d);
            addLineContext(gcCycle, line, lineNumber);
            return Optional.of(immutableMap(gcCycle));
        }

        Matcher concurrentCycleMatcher = CONCURRENT_CYCLE_PATTERN.matcher(line);
        if (!concurrentCycleMatcher.matches()) {
            return Optional.empty();
        }

        String event = normalizeWhitespace(concurrentCycleMatcher.group(2));
        Map<String, Object> gcCycle = new LinkedHashMap<>();
        gcCycle.put("gcId", Long.parseLong(concurrentCycleMatcher.group(1)));
        gcCycle.put("event", event);
        gcCycle.put("cause", event);
        gcCycle.put("durationMs", Double.parseDouble(concurrentCycleMatcher.group(3)));
        addLineContext(gcCycle, line, lineNumber);
        return Optional.of(immutableMap(gcCycle));
    }

    private Optional<Map<String, Object>> parseAllocationStallLine(String line, int lineNumber) {
        Matcher matcher = ALLOCATION_STALL_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> allocationStall = new LinkedHashMap<>();
        allocationStall.put("thread", matcher.group(1).trim());
        allocationStall.put("stallMs", Double.parseDouble(matcher.group(2)));
        addLineContext(allocationStall, line, lineNumber);
        return Optional.of(immutableMap(allocationStall));
    }

    private Optional<Map<String, Object>> parseMmuLine(String line, int lineNumber) {
        Matcher matcher = MMU_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> mmuSample = new LinkedHashMap<>();
        mmuSample.put("gcId", Long.parseLong(matcher.group(1)));
        mmuSample.put("twoMsPercent", Double.parseDouble(matcher.group(2)));
        mmuSample.put("fiveMsPercent", Double.parseDouble(matcher.group(3)));
        mmuSample.put("tenMsPercent", Double.parseDouble(matcher.group(4)));
        mmuSample.put("twentyMsPercent", Double.parseDouble(matcher.group(5)));
        mmuSample.put("fiftyMsPercent", Double.parseDouble(matcher.group(6)));
        mmuSample.put("hundredMsPercent", Double.parseDouble(matcher.group(7)));
        addLineContext(mmuSample, line, lineNumber);
        return Optional.of(immutableMap(mmuSample));
    }

    private Optional<Map<String, Object>> parseWorkerLine(String line, int lineNumber) {
        Matcher matcher = WORKER_PATTERN.matcher(line);
        if (matcher.matches()) {
            long activeWorkers = Long.parseLong(matcher.group(2));
            Long availableWorkers = matcher.group(3) != null ? Long.parseLong(matcher.group(3)) : null;
            String purpose = normalizeWhitespace(matcher.group(4));

            Map<String, Object> workerSample = new LinkedHashMap<>();
            workerSample.put("gcId", Long.parseLong(matcher.group(1)));
            workerSample.put("activeWorkers", activeWorkers);
            if (availableWorkers != null) {
                workerSample.put("availableWorkers", availableWorkers);
                workerSample.put("utilizationRatio", occupancyRatio(activeWorkers, availableWorkers));
            }
            if (!purpose.isBlank()) {
                workerSample.put("purpose", purpose);
            }
            addLineContext(workerSample, line, lineNumber);
            return Optional.of(immutableMap(workerSample));
        }

        Matcher legacyMatcher = LEGACY_G1_WORKER_PATTERN.matcher(line);
        if (!legacyMatcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> workerSample = new LinkedHashMap<>();
        workerSample.put("activeWorkers", Long.parseLong(legacyMatcher.group(2)));
        workerSample.put("parallelTimeMs", Double.parseDouble(legacyMatcher.group(1)));
        workerSample.put("purpose", "Pause workers");
        addLineContext(workerSample, line, lineNumber);
        return Optional.of(immutableMap(workerSample));
    }

    private Optional<Map<String, Object>> parseCpuLine(String line, int lineNumber) {
        Matcher matcher = CPU_PATTERN.matcher(line);
        if (matcher.matches()) {
            double userSeconds = Double.parseDouble(matcher.group(2));
            double sysSeconds = Double.parseDouble(matcher.group(3));
            double realSeconds = Double.parseDouble(matcher.group(4));
            Map<String, Object> cpuSample = cpuSample(matcher.group(1), userSeconds, sysSeconds, realSeconds);
            addLineContext(cpuSample, line, lineNumber);
            return Optional.of(immutableMap(cpuSample));
        }

        Matcher legacyMatcher = LEGACY_CPU_PATTERN.matcher(line);
        if (!legacyMatcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> cpuSample = cpuSample(null, Double.parseDouble(legacyMatcher.group(1)), Double.parseDouble(legacyMatcher.group(2)), Double.parseDouble(legacyMatcher.group(3)));
        addLineContext(cpuSample, line, lineNumber);
        return Optional.of(immutableMap(cpuSample));
    }

    private Optional<Map<String, Object>> parseHumongousRegionsLine(String line, int lineNumber) {
        Matcher matcher = HUMONGOUS_REGIONS_PATTERN.matcher(line);
        if (matcher.matches()) {
            long beforeRegions = Long.parseLong(matcher.group(2));
            long afterRegions = Long.parseLong(matcher.group(3));
            Map<String, Object> humongousSample = new LinkedHashMap<>();
            humongousSample.put("gcId", Long.parseLong(matcher.group(1)));
            humongousSample.put("beforeRegions", beforeRegions);
            humongousSample.put("afterRegions", afterRegions);
            humongousSample.put("deltaRegions", afterRegions - beforeRegions);
            addLineContext(humongousSample, line, lineNumber);
            return Optional.of(immutableMap(humongousSample));
        }

        Matcher legacyMatcher = LEGACY_HUMONGOUS_REGIONS_PATTERN.matcher(line);
        if (!legacyMatcher.matches()) {
            return Optional.empty();
        }

        long beforeRegions = Long.parseLong(legacyMatcher.group(1));
        long afterRegions = Long.parseLong(legacyMatcher.group(2));
        Map<String, Object> humongousSample = new LinkedHashMap<>();
        humongousSample.put("beforeRegions", beforeRegions);
        humongousSample.put("afterRegions", afterRegions);
        humongousSample.put("deltaRegions", afterRegions - beforeRegions);
        addLineContext(humongousSample, line, lineNumber);
        return Optional.of(immutableMap(humongousSample));
    }

    private Optional<Map<String, Object>> parsePhaseLine(String line, int lineNumber) {
        Optional<Map<String, Object>> legacyCmsPhase = parseLegacyCmsPhaseLine(line, lineNumber);
        if (legacyCmsPhase.isPresent()) {
            return legacyCmsPhase;
        }

        Optional<Map<String, Object>> legacyG1Phase = parseLegacyG1ConcurrentPhaseLine(line, lineNumber);
        if (legacyG1Phase.isPresent()) {
            return legacyG1Phase;
        }

        String lowerLine = line.toLowerCase(Locale.ROOT);
        if (!lowerLine.contains("gc(")
            || !lowerLine.contains("ms")
            || lowerLine.contains("->")
            || lowerLine.contains("user=")
            || lowerLine.contains("mmu:")
            || lowerLine.contains("metaspace:")
            || lowerLine.contains("humongous regions")
            || lowerLine.contains("allocation stall")
            || lowerLine.contains("using ")) {
            return Optional.empty();
        }
        if (CONCURRENT_CYCLE_PATTERN.matcher(line).matches()) {
            return Optional.empty();
        }
        if (UNIFIED_DURATION_ONLY_PAUSE_PATTERN.matcher(line).matches()) {
            return Optional.empty();
        }

        Matcher matcher = PHASE_DURATION_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String phase = normalizeWhitespace(matcher.group(2));
        if (!isPhaseLabel(phase)) {
            return Optional.empty();
        }

        Map<String, Object> phaseSample = new LinkedHashMap<>();
        phaseSample.put("gcId", Long.parseLong(matcher.group(1)));
        phaseSample.put("phase", phase);
        phaseSample.put("phaseKind", classifyPhaseKind(phase));
        phaseSample.put("durationMs", Double.parseDouble(matcher.group(3)));
        addLineContext(phaseSample, line, lineNumber);
        return Optional.of(immutableMap(phaseSample));
    }

    private Optional<Map<String, Object>> parseFailureSignalLine(String line, int lineNumber) {
        String lowerLine = line.toLowerCase(Locale.ROOT);
        Matcher concurrentAbortMatcher = CONCURRENT_ABORT_PATTERN.matcher(line);
        if (concurrentAbortMatcher.matches()) {
            Map<String, Object> failureSignal = new LinkedHashMap<>();
            failureSignal.put("gcId", Long.parseLong(concurrentAbortMatcher.group(1)));
            failureSignal.put("signalType", "CONCURRENT_ABORT");
            failureSignal.put("signal", normalizeWhitespace(concurrentAbortMatcher.group(2)));
            addLineContext(failureSignal, line, lineNumber);
            return Optional.of(immutableMap(failureSignal));
        }

        if (lowerLine.contains("to-space exhausted") || lowerLine.contains("to-space overflow")) {
            Map<String, Object> failureSignal = new LinkedHashMap<>();
            failureSignal.put("signalType", "TO_SPACE_EXHAUSTED");
            extractUnifiedGcId(line).ifPresent(gcId -> failureSignal.put("gcId", gcId));
            failureSignal.put("signal", lowerLine.contains("overflow") ? "To-space overflow" : "To-space exhausted");
            addLineContext(failureSignal, line, lineNumber);
            return Optional.of(immutableMap(failureSignal));
        }

        if (lowerLine.contains("attempting full compaction")) {
            Map<String, Object> failureSignal = new LinkedHashMap<>();
            failureSignal.put("signalType", "FULL_COMPACTION_ATTEMPT");
            failureSignal.put("signal", "Attempting full compaction");
            addLineContext(failureSignal, line, lineNumber);
            return Optional.of(immutableMap(failureSignal));
        }

        if (lowerLine.contains("concurrent mode failure")) {
            Map<String, Object> failureSignal = new LinkedHashMap<>();
            failureSignal.put("signalType", "CONCURRENT_MODE_FAILURE");
            failureSignal.put("signal", "Concurrent mode failure");
            addLineContext(failureSignal, line, lineNumber);
            return Optional.of(immutableMap(failureSignal));
        }

        if (lowerLine.contains("promotion failed")) {
            Map<String, Object> failureSignal = new LinkedHashMap<>();
            failureSignal.put("signalType", "PROMOTION_FAILED");
            failureSignal.put("signal", "Promotion failed");
            addLineContext(failureSignal, line, lineNumber);
            return Optional.of(immutableMap(failureSignal));
        }

        return Optional.empty();
    }

    private Optional<Map<String, Object>> parseMetaspaceLine(String line, int lineNumber) {
        Matcher unifiedMatcher = UNIFIED_METASPACE_PATTERN.matcher(line);
        if (unifiedMatcher.matches()) {
            long usedBytes = sizeToBytes(unifiedMatcher.group(6), unifiedMatcher.group(7));
            long committedBytes = sizeToBytes(unifiedMatcher.group(8), unifiedMatcher.group(9));
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("gcId", Long.parseLong(unifiedMatcher.group(1)));
            snapshot.put("usedKb", bytesToRoundedKb(usedBytes));
            snapshot.put("committedKb", bytesToRoundedKb(committedBytes));
            snapshot.put("usageRatio", occupancyRatio(usedBytes, committedBytes));
            addLineContext(snapshot, line, lineNumber);
            return Optional.of(immutableMap(snapshot));
        }

        Matcher zgcMatcher = ZGC_METASPACE_PATTERN.matcher(line);
        if (zgcMatcher.matches()) {
            long usedBytes = sizeToBytes(zgcMatcher.group(2), zgcMatcher.group(3));
            long committedBytes = sizeToBytes(zgcMatcher.group(4), zgcMatcher.group(5));
            long reservedBytes = sizeToBytes(zgcMatcher.group(6), zgcMatcher.group(7));
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("gcId", Long.parseLong(zgcMatcher.group(1)));
            snapshot.put("usedKb", bytesToRoundedKb(usedBytes));
            snapshot.put("committedKb", bytesToRoundedKb(committedBytes));
            snapshot.put("reservedKb", bytesToRoundedKb(reservedBytes));
            snapshot.put("usageRatio", occupancyRatio(usedBytes, committedBytes));
            addLineContext(snapshot, line, lineNumber);
            return Optional.of(immutableMap(snapshot));
        }

        Matcher legacyMatcher = LEGACY_METASPACE_PATTERN.matcher(line);
        if (!legacyMatcher.matches()) {
            return Optional.empty();
        }

        long usedBytes = sizeToBytes(legacyMatcher.group(3), legacyMatcher.group(4));
        long committedBytes = sizeToBytes(legacyMatcher.group(5), legacyMatcher.group(6));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("usedKb", bytesToRoundedKb(usedBytes));
        snapshot.put("committedKb", bytesToRoundedKb(committedBytes));
        snapshot.put("usageRatio", occupancyRatio(usedBytes, committedBytes));
        addLineContext(snapshot, line, lineNumber);
        return Optional.of(immutableMap(snapshot));
    }

    private Optional<Double> parseElapsedSeconds(String line) {
        Matcher matcher = ELAPSED_SECONDS_PATTERN.matcher(line);
        if (matcher.find()) {
            return Optional.of(Double.parseDouble(matcher.group(1)));
        }
        Matcher legacyMatcher = LEGACY_ELAPSED_SECONDS_PATTERN.matcher(line);
        if (legacyMatcher.find()) {
            return Optional.of(Double.parseDouble(legacyMatcher.group(1)));
        }
        Matcher genericLegacyMatcher = LEGACY_GENERIC_ELAPSED_SECONDS_PATTERN.matcher(line);
        if (genericLegacyMatcher.find()) {
            return Optional.of(Double.parseDouble(genericLegacyMatcher.group(1)));
        }
        return Optional.empty();
    }

    private Optional<String> parseAbsoluteTimestamp(String line) {
        Matcher matcher = ABSOLUTE_TIMESTAMP_PATTERN.matcher(line);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        Matcher legacyMatcher = LEGACY_ABSOLUTE_TIMESTAMP_PATTERN.matcher(line);
        if (!legacyMatcher.find()) {
            return Optional.empty();
        }
        return Optional.of(legacyMatcher.group(1));
    }

    private void addLineContext(Map<String, Object> target, String line, int lineNumber) {
        target.put("lineNumber", lineNumber);
        parseAbsoluteTimestamp(line).ifPresent(timestamp -> target.put("absoluteTimestamp", timestamp));
        parseElapsedSeconds(line).ifPresent(seconds -> target.put("elapsedSeconds", seconds));
        target.put("rawLine", line.trim());
    }

    private String detectCollector(String content, GcParseState state) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("using the z garbage collector") || lower.contains("z garbage collector") || lower.contains("using zgc")) {
            return "ZGC";
        }
        if (containsAny(lower, "using g1", "g1 evacuation pause", "g1 compaction pause", "garbage-first")) {
            return "G1";
        }
        if (containsAny(lower, "concurrent mark sweep", "[gc,cms", "cms initial mark", "cms final remark", "cms-remark", "cms-concurrent", "parnew", "[cms:", "concurrent mode failure")) {
            return "CMS";
        }
        if (containsAny(lower, "parallel gc", "parallel scavenge", "using parallel", "psyounggen", "paroldgen", "ps old gen", "parallel old")) {
            return "Parallel";
        }
        if (containsAny(lower, "serial gc", "using serial", "defnew", "serial old", "tenured generation", "tenured:", "marksweepcompact")) {
            return "Serial";
        }
        boolean parsedG1Event = state.pauses().stream()
            .map(pause -> stringValue(pause.get("event")).toLowerCase(Locale.ROOT))
            .anyMatch(event -> event.contains("g1 evacuation pause") || event.contains("g1 compaction pause"));
        if (parsedG1Event) {
            return "G1";
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
        double p99PauseMs = percentile(pauseDurations, 99.0d);

        long fullGcCount = state.pauses().stream().filter(this::isFullGcEvent).count();
        long metaspaceTriggeredFullGcCount = state.pauses().stream().filter(this::isMetaspaceTriggeredFullGc).count();
        long evacuationFailurePauseCount = state.pauses().stream().filter(this::isEvacuationFailurePause).count();
        long concurrentMarkAbortCount = state.failureSignals().stream().filter(this::isConcurrentAbortSignal).count();
        long fullCompactionAttemptCount = state.failureSignals().stream().filter(this::isFullCompactionAttemptSignal).count();
        long peakHumongousAfterRegions = state.humongousRegionSamples().stream()
            .mapToLong(sample -> metricLong(sample, "afterRegions"))
            .max()
            .orElse(0L);
        double maxCpuRealSeconds = state.cpuSamples().stream()
            .mapToDouble(sample -> metricDouble(sample, "realSeconds"))
            .max()
            .orElse(0.0d);
        double peakCpuToWallRatio = state.cpuSamples().stream()
            .mapToDouble(sample -> metricDouble(sample, "cpuToWallRatio"))
            .max()
            .orElse(0.0d);
        double longestConcurrentPhaseMs = longestPhaseDuration(state.phaseSamples(), "CONCURRENT");
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
        metrics.put("fullGcCount", fullGcCount);
        metrics.put("allocationStallCount", (long) state.allocationStalls().size());
        metrics.put("gcCycleCount", (long) state.gcCycles().size());
        metrics.put("averagePauseMs", averagePauseMs);
        metrics.put("p95PauseMs", p95PauseMs);
        metrics.put("p99PauseMs", p99PauseMs);
        metrics.put("maxPauseMs", maxPauseMs);
        metrics.put("maxFullGcPauseMs", maxFullGcPauseMs);
        metrics.put("peakHeapOccupancyRatio", metricDouble(peakOccupancyEvent, "afterOccupancyRatio"));
        metrics.put("stopTheWorldOverheadPct", stopTheWorldOverheadPct);
        metrics.put("metaspaceTriggeredFullGcCount", metaspaceTriggeredFullGcCount);
        metrics.put("evacuationFailurePauseCount", evacuationFailurePauseCount);
        metrics.put("concurrentMarkAbortCount", concurrentMarkAbortCount);
        metrics.put("fullCompactionAttemptCount", fullCompactionAttemptCount);
        metrics.put("maxCpuRealSeconds", maxCpuRealSeconds);
        metrics.put("peakCpuToWallRatio", peakCpuToWallRatio);
        metrics.put("peakHumongousAfterRegions", peakHumongousAfterRegions);
        metrics.put("longestConcurrentPhaseMs", longestConcurrentPhaseMs);
        metrics.put("medianPauseMs", medianPauseMs);
        metrics.put("totalPauseMs", totalPauseMs);
        metrics.put("logWindowMs", logWindowMs);
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
            immutableMap(metrics),
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

        return new MetaspaceSummary(immutableMap(metrics), peakSnapshot);
    }

    private Map<String, Object> summarizePauseBreakdown(List<Map<String, Object>> pauses) {
        if (pauses.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> pauseTypeCounts = countValues(pauses, "pauseType");
        Map<String, Long> causeCounts = countValues(pauses, "cause");
        Map<String, Double> causeTotalPauseMs = sumDurationsByKey(pauses, "cause", "pauseMs");
        Map<String, Double> causeMaxPauseMs = maxDurationsByKey(pauses, "cause", "pauseMs");
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("pauseEventCount", (long) pauses.size());
        summary.put("dominantPauseCauseByCount", dominantNumericKey(causeCounts));
        summary.put("dominantPauseCauseByTotalPauseMs", dominantNumericKey(causeTotalPauseMs));
        summary.put("pauseTypeCounts", immutableLongMap(pauseTypeCounts));
        summary.put("causeCounts", immutableLongMap(causeCounts));
        summary.put("causeTotalPauseMs", immutableDoubleMap(causeTotalPauseMs));
        summary.put("causeMaxPauseMs", immutableDoubleMap(causeMaxPauseMs));
        summary.put("observedPauseTypes", List.copyOf(pauseTypeCounts.keySet()));
        summary.put("observedPauseCauses", List.copyOf(causeCounts.keySet()));
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeCollectorPressure(
        String collector,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> failureSignals,
        Map<String, Object> summary,
        Map<String, Object> pauseBreakdown,
        Map<String, Object> recoverySummary,
        Map<String, Object> g1CycleProgressionSummary,
        Map<String, Object> failureSummary,
        Map<String, Object> concurrentSummary
    ) {
        LinkedHashMap<String, Object> pressure = new LinkedHashMap<>();
        String normalizedCollector = collector == null ? "" : collector.strip().toUpperCase(Locale.ROOT);
        if (collector != null && !collector.isBlank()) {
            pressure.put("collector", collector);
        }

        putIfPositiveLong(pressure, "fullGcCount", metricLong(summary, "fullGcCount"));
        putIfPositiveDouble(pressure, "p95PauseMs", metricDouble(summary, "p95PauseMs"));
        putIfPositiveDouble(pressure, "maxPauseMs", metricDouble(summary, "maxPauseMs"));
        putIfPositiveDouble(pressure, "peakPostGcOccupancyRatio", metricDouble(recoverySummary, "peakPostGcOccupancyRatio"));
        putIfPositiveLong(pressure, "nearCapacityAfterGcCount", metricLong(recoverySummary, "nearCapacityAfterGcCount"));
        putIfPositiveLong(pressure, "maxNearCapacityPauseStreak", metricLong(recoverySummary, "maxNearCapacityPauseStreak"));
        putIfPositiveLong(pressure, "metaspaceTriggeredFullGcCount", metricLong(summary, "metaspaceTriggeredFullGcCount"));
        putIfNonBlank(pressure, "dominantPauseCauseByTotalPauseMs", stringValue(pauseBreakdown.get("dominantPauseCauseByTotalPauseMs")));

        switch (normalizedCollector) {
            case "G1" -> {
                putIfPositiveLong(pressure, "evacuationFailurePauseCount", metricLong(failureSummary, "evacuationFailurePauseCount"));
                putIfPositiveLong(pressure, "toSpaceExhaustedCount", metricLong(failureSummary, "toSpaceExhaustedCount"));
                putIfPositiveLong(pressure, "fullCompactionAttemptCount", metricLong(failureSummary, "fullCompactionAttemptCount"));
                putIfPositiveLong(pressure, "maxFullGcStreak", maxConsecutiveMatches(pauses, this::isFullGcEvent));
                putIfPositiveLong(pressure, "maxEvacuationFailureStreak", maxConsecutiveMatches(pauses, this::isEvacuationFailurePause));
                putIfPositiveLong(pressure, "mixedPausesBeforeFirstFullGc", metricLong(g1CycleProgressionSummary, "mixedPausesBeforeFirstFullGc"));
                putIfPositiveLong(pressure, "fullGcCountAfterMixedPhase", metricLong(g1CycleProgressionSummary, "fullGcCountAfterMixedPhase"));
                putIfPositiveLong(
                    pressure,
                    "lowReclaimHighRetentionFullGcCount",
                    metricLong(g1CycleProgressionSummary, "lowReclaimHighRetentionFullGcCount")
                );
                putIfPositiveLong(
                    pressure,
                    "maxLowReclaimHighRetentionFullGcStreak",
                    metricLong(g1CycleProgressionSummary, "maxLowReclaimHighRetentionFullGcStreak")
                );
                putIfPositiveDouble(
                    pressure,
                    "averageFullGcReclaimedMb",
                    metricDouble(g1CycleProgressionSummary, "averageFullGcReclaimedMb")
                );
            }
            case "CMS" -> {
                putIfPositiveLong(pressure, "concurrentModeFailureCount", metricLong(failureSummary, "concurrentModeFailureCount"));
                putIfPositiveLong(pressure, "promotionFailedCount", metricLong(failureSummary, "promotionFailedCount"));
                putIfPositiveLong(
                    pressure,
                    "maxConcurrentModeFailureStreak",
                    maxConsecutiveMatches(failureSignals, this::isConcurrentModeFailureSignal)
                );
                putIfPositiveLong(pressure, "concurrentPhaseCount", metricLong(concurrentSummary, "concurrentPhaseCount"));
                putIfPositiveDouble(pressure, "longestConcurrentPhaseMs", metricDouble(concurrentSummary, "longestConcurrentPhaseMs"));
                putIfPositiveDouble(pressure, "longestConcurrentCycleMs", metricDouble(concurrentSummary, "longestConcurrentCycleMs"));
                putIfPositiveDouble(pressure, "averageFullPostGcOccupancyRatio", metricDouble(recoverySummary, "averageFullPostGcOccupancyRatio"));
            }
            case "SERIAL", "PARALLEL" -> {
                putIfPositiveLong(pressure, "maxFullGcStreak", maxConsecutiveMatches(pauses, this::isFullGcEvent));
                putIfPositiveDouble(pressure, "averageFullPostGcOccupancyRatio", metricDouble(recoverySummary, "averageFullPostGcOccupancyRatio"));
                putIfPositiveDouble(pressure, "averageReclaimedMb", metricDouble(recoverySummary, "averageReclaimedMb"));
            }
            case "ZGC" -> {
                putIfPositiveLong(pressure, "allocationStallCount", metricLong(summary, "allocationStallCount"));
                putIfPositiveLong(pressure, "maxAllocationStallStreak", maxConsecutiveMatches(allocationStalls, event -> true));
                putIfPositiveDouble(pressure, "maxAllocationStallMs", metricDouble(summary, "maxAllocationStallMs"));
                putIfPositiveDouble(pressure, "totalAllocationStallMs", metricDouble(summary, "totalAllocationStallMs"));
                putIfPositiveDouble(pressure, "stopTheWorldOverheadPct", metricDouble(summary, "stopTheWorldOverheadPct"));
                putIfPositiveLong(pressure, "gcCycleCount", metricLong(summary, "gcCycleCount"));
                putIfPositiveDouble(pressure, "longestConcurrentPhaseMs", metricDouble(concurrentSummary, "longestConcurrentPhaseMs"));
            }
            default -> {
                putIfPositiveDouble(pressure, "averagePostGcOccupancyRatio", metricDouble(recoverySummary, "averagePostGcOccupancyRatio"));
                putIfPositiveLong(pressure, "failureSignalCount", metricLong(failureSummary, "failureSignalCount"));
            }
        }

        return immutableMap(pressure);
    }

    private Map<String, Object> summarizeRecovery(List<Map<String, Object>> pauses) {
        List<Map<String, Object>> heapPauses = pauses.stream()
            .filter(this::hasHeapFootprint)
            .toList();
        if (heapPauses.isEmpty()) {
            return Map.of();
        }

        long youngPauseCount = 0L;
        long mixedPauseCount = 0L;
        long fullPauseCount = 0L;
        long highRetainedOccupancyPauseCount = 0L;
        long nearCapacityAfterGcCount = 0L;
        long maxNearCapacityPauseStreak = 0L;
        long currentNearCapacityPauseStreak = 0L;
        double totalReclaimedMb = 0.0d;
        double totalPostGcOccupancyRatio = 0.0d;
        double totalMixedPostGcOccupancyRatio = 0.0d;
        long mixedOccupancySamples = 0L;
        double totalFullPostGcOccupancyRatio = 0.0d;
        long fullOccupancySamples = 0L;

        Map<String, Object> peakRetainedPause = Map.of();
        double peakPostGcOccupancyRatio = 0.0d;

        for (Map<String, Object> pause : heapPauses) {
            String pauseType = stringValue(pause.get("pauseType"));
            if ("YOUNG".equals(pauseType)) {
                youngPauseCount++;
            } else if ("MIXED".equals(pauseType)) {
                mixedPauseCount++;
            } else if ("FULL".equals(pauseType)) {
                fullPauseCount++;
            }

            double afterOccupancyRatio = metricDouble(pause, "afterOccupancyRatio");
            totalPostGcOccupancyRatio += afterOccupancyRatio;
            totalReclaimedMb += reclaimedMb(pause);
            if (afterOccupancyRatio >= HIGH_RETAINED_OCCUPANCY_RATIO) {
                highRetainedOccupancyPauseCount++;
            }
            if (afterOccupancyRatio >= NEAR_CAPACITY_OCCUPANCY_RATIO) {
                nearCapacityAfterGcCount++;
                currentNearCapacityPauseStreak++;
                maxNearCapacityPauseStreak = Math.max(maxNearCapacityPauseStreak, currentNearCapacityPauseStreak);
            } else {
                currentNearCapacityPauseStreak = 0L;
            }

            if ("MIXED".equals(pauseType)) {
                totalMixedPostGcOccupancyRatio += afterOccupancyRatio;
                mixedOccupancySamples++;
            }
            if ("FULL".equals(pauseType)) {
                totalFullPostGcOccupancyRatio += afterOccupancyRatio;
                fullOccupancySamples++;
            }

            if (peakRetainedPause.isEmpty() || afterOccupancyRatio > peakPostGcOccupancyRatio) {
                peakRetainedPause = pause;
                peakPostGcOccupancyRatio = afterOccupancyRatio;
            }
        }

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("youngPauseCount", youngPauseCount);
        summary.put("mixedPauseCount", mixedPauseCount);
        summary.put("fullPauseCount", fullPauseCount);
        summary.put("averageReclaimedMb", totalReclaimedMb / heapPauses.size());
        summary.put("averagePostGcOccupancyRatio", totalPostGcOccupancyRatio / heapPauses.size());
        if (mixedOccupancySamples > 0L) {
            summary.put("averageMixedPostGcOccupancyRatio", totalMixedPostGcOccupancyRatio / mixedOccupancySamples);
        }
        if (fullOccupancySamples > 0L) {
            summary.put("averageFullPostGcOccupancyRatio", totalFullPostGcOccupancyRatio / fullOccupancySamples);
        }
        summary.put("highRetainedOccupancyPauseCount", highRetainedOccupancyPauseCount);
        summary.put("nearCapacityAfterGcCount", nearCapacityAfterGcCount);
        summary.put("maxNearCapacityPauseStreak", maxNearCapacityPauseStreak);
        summary.put("peakPostGcOccupancyRatio", peakPostGcOccupancyRatio);
        if (!peakRetainedPause.isEmpty()) {
            if (peakRetainedPause.get("gcId") != null) {
                summary.put("peakRetainedGcId", peakRetainedPause.get("gcId"));
            }
            String peakRetainedPauseType = stringValue(peakRetainedPause.get("pauseType"));
            if (!peakRetainedPauseType.isBlank()) {
                summary.put("peakRetainedPauseType", peakRetainedPauseType);
            }
        }
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeG1CycleProgression(String collector, List<Map<String, Object>> pauses) {
        if (!"G1".equalsIgnoreCase(collector) || pauses.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> orderedPauses = List.copyOf(pauses);
        List<Map<String, Object>> mixedPauses = orderedPauses.stream()
            .filter(this::isMixedGcPause)
            .filter(this::hasHeapFootprint)
            .toList();
        List<Map<String, Object>> fullPauses = orderedPauses.stream()
            .filter(this::isFullGcEvent)
            .filter(this::hasHeapFootprint)
            .toList();

        int firstMixedIndex = firstPauseIndex(orderedPauses, "MIXED");
        int firstFullIndex = firstPauseIndex(orderedPauses, "FULL");

        long highRetainedMixedPauseCount = mixedPauses.stream()
            .filter(pause -> metricDouble(pause, "afterOccupancyRatio") >= HIGH_RETAINED_OCCUPANCY_RATIO)
            .count();
        double averageMixedPostGcOccupancyRatio = mixedPauses.stream()
            .mapToDouble(pause -> metricDouble(pause, "afterOccupancyRatio"))
            .average()
            .orElse(0.0d);
        double maxMixedPostGcOccupancyRatio = mixedPauses.stream()
            .mapToDouble(pause -> metricDouble(pause, "afterOccupancyRatio"))
            .max()
            .orElse(0.0d);

        long lowReclaimHighRetentionFullGcCount = fullPauses.stream()
            .filter(this::isLowReclaimHighRetentionFullGc)
            .count();
        double averageFullGcReclaimedMb = fullPauses.stream()
            .mapToDouble(this::reclaimedMb)
            .average()
            .orElse(0.0d);
        double averageFullGcReclaimedPctOfHeap = fullPauses.stream()
            .mapToDouble(this::reclaimedFractionOfHeap)
            .average()
            .orElse(0.0d);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put(
            "youngPausesBeforeFirstMixedPause",
            firstMixedIndex >= 0 ? countPausesBeforeIndex(orderedPauses, "YOUNG", firstMixedIndex) : 0L
        );
        summary.put(
            "mixedPausesBeforeFirstFullGc",
            firstFullIndex >= 0 ? countPausesBeforeIndex(orderedPauses, "MIXED", firstFullIndex) : mixedPauses.size()
        );
        summary.put(
            "fullGcCountAfterMixedPhase",
            firstMixedIndex >= 0 ? countPausesAfterIndex(orderedPauses, "FULL", firstMixedIndex) : 0L
        );
        summary.put("highRetainedMixedPauseCount", highRetainedMixedPauseCount);
        if (!mixedPauses.isEmpty()) {
            summary.put("averageMixedPostGcOccupancyRatio", averageMixedPostGcOccupancyRatio);
            summary.put("maxMixedPostGcOccupancyRatio", maxMixedPostGcOccupancyRatio);
        }
        summary.put("lowReclaimHighRetentionFullGcCount", lowReclaimHighRetentionFullGcCount);
        summary.put("maxLowReclaimHighRetentionFullGcStreak", maxLowReclaimHighRetentionFullGcStreak(fullPauses));
        if (!fullPauses.isEmpty()) {
            summary.put("averageFullGcReclaimedMb", averageFullGcReclaimedMb);
            summary.put("averageFullGcReclaimedPctOfHeap", averageFullGcReclaimedPctOfHeap);
        }

        Map<String, Object> firstMixedPause = firstPauseByType(orderedPauses, "MIXED");
        if (!firstMixedPause.isEmpty() && firstMixedPause.get("gcId") != null) {
            summary.put("firstMixedGcId", firstMixedPause.get("gcId"));
        }
        Map<String, Object> firstFullAfterMixed = firstPauseAfterIndex(orderedPauses, "FULL", firstMixedIndex);
        if (!firstFullAfterMixed.isEmpty() && firstFullAfterMixed.get("gcId") != null) {
            summary.put("firstFullGcAfterMixedGcId", firstFullAfterMixed.get("gcId"));
        }
        Map<String, Object> firstLowReclaimFullGc = fullPauses.stream()
            .filter(this::isLowReclaimHighRetentionFullGc)
            .findFirst()
            .orElse(Map.of());
        if (!firstLowReclaimFullGc.isEmpty() && firstLowReclaimFullGc.get("gcId") != null) {
            summary.put("firstLowReclaimHighRetentionFullGcId", firstLowReclaimFullGc.get("gcId"));
        }

        return immutableMap(summary);
    }

    private Map<String, Object> summarizeFailureSignals(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> failureSignals
    ) {
        long evacuationFailurePauseCount = pauses.stream().filter(this::isEvacuationFailurePause).count();
        long metadataTriggeredPauseCount = pauses.stream().filter(this::isMetaspaceTriggeredPause).count();
        long metadataClearSoftReferencesFullGcCount = pauses.stream().filter(this::isMetadataClearSoftReferencesFullGc).count();
        long concurrentMarkAbortCount = failureSignals.stream().filter(this::isConcurrentAbortSignal).count();
        long toSpaceExhaustedCount = failureSignals.stream().filter(this::isToSpaceExhaustedSignal).count();
        long fullCompactionAttemptCount = failureSignals.stream().filter(this::isFullCompactionAttemptSignal).count();
        long concurrentModeFailureCount = failureSignals.stream().filter(this::isConcurrentModeFailureSignal).count();
        long promotionFailedCount = failureSignals.stream().filter(this::isPromotionFailedSignal).count();

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("evacuationFailurePauseCount", evacuationFailurePauseCount);
        summary.put("metadataTriggeredPauseCount", metadataTriggeredPauseCount);
        summary.put("metadataClearSoftReferencesFullGcCount", metadataClearSoftReferencesFullGcCount);
        summary.put("concurrentMarkAbortCount", concurrentMarkAbortCount);
        summary.put("toSpaceExhaustedCount", toSpaceExhaustedCount);
        summary.put("fullCompactionAttemptCount", fullCompactionAttemptCount);
        summary.put("concurrentModeFailureCount", concurrentModeFailureCount);
        summary.put("promotionFailedCount", promotionFailedCount);
        summary.put("failureSignalCount", (long) failureSignals.size());
        return immutableMap(summary);
    }

    private Map<String, Object> summarizePhaseSamples(List<Map<String, Object>> phaseSamples) {
        if (phaseSamples.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> longestPhase = phaseSamples.stream()
            .max(Comparator.comparingDouble(sample -> metricDouble(sample, "durationMs")))
            .orElse(Map.of());
        Map<String, Long> phaseKindCounts = countValues(phaseSamples, "phaseKind");

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("phaseSampleCount", (long) phaseSamples.size());
        summary.put("phaseKindCounts", immutableLongMap(phaseKindCounts));
        summary.put("longestPhaseName", longestPhase.get("phase"));
        summary.put("longestPhaseMs", longestPhase.get("durationMs"));
        summary.put("longestPhaseGcId", longestPhase.get("gcId"));
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeConcurrentPhases(
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> concurrentSamples = phaseSamples.stream()
            .filter(sample -> "CONCURRENT".equals(sample.get("phaseKind")))
            .toList();
        double longestConcurrentPhaseMs = concurrentSamples.stream()
            .mapToDouble(sample -> metricDouble(sample, "durationMs"))
            .max()
            .orElse(0.0d);
        Map<String, Object> longestConcurrentPhase = concurrentSamples.stream()
            .max(Comparator.comparingDouble(sample -> metricDouble(sample, "durationMs")))
            .orElse(Map.of());
        double longestConcurrentCycleMs = gcCycles.stream()
            .filter(cycle -> stringValue(cycle.get("event")).toLowerCase(Locale.ROOT).contains("concurrent"))
            .mapToDouble(cycle -> metricDouble(cycle, "durationMs"))
            .max()
            .orElse(0.0d);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("concurrentPhaseCount", (long) concurrentSamples.size());
        summary.put(
            "concurrentCycleCount",
            gcCycles.stream().filter(cycle -> stringValue(cycle.get("event")).toLowerCase(Locale.ROOT).contains("cycle")).count()
        );
        summary.put("concurrentMarkAbortCount", failureSignals.stream().filter(this::isConcurrentAbortSignal).count());
        summary.put("longestConcurrentPhaseName", longestConcurrentPhase.get("phase"));
        summary.put("longestConcurrentPhaseMs", longestConcurrentPhaseMs);
        summary.put("longestConcurrentPhaseGcId", longestConcurrentPhase.get("gcId"));
        summary.put("longestConcurrentCycleMs", longestConcurrentCycleMs);
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeWorkers(List<Map<String, Object>> workerSamples) {
        if (workerSamples.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> purposeCounts = new LinkedHashMap<>();
        long maxActiveWorkers = 0L;
        long minActiveWorkers = Long.MAX_VALUE;
        long maxAvailableWorkers = 0L;
        double peakUtilizationRatio = 0.0d;

        for (Map<String, Object> workerSample : workerSamples) {
            long activeWorkers = metricLong(workerSample, "activeWorkers");
            maxActiveWorkers = Math.max(maxActiveWorkers, activeWorkers);
            minActiveWorkers = Math.min(minActiveWorkers, activeWorkers);
            maxAvailableWorkers = Math.max(maxAvailableWorkers, metricLong(workerSample, "availableWorkers"));
            peakUtilizationRatio = Math.max(peakUtilizationRatio, metricDouble(workerSample, "utilizationRatio"));
            String purpose = stringValue(workerSample.get("purpose"));
            if (!purpose.isBlank()) {
                purposeCounts.merge(purpose, 1L, Long::sum);
            }
        }

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", (long) workerSamples.size());
        summary.put("maxActiveWorkers", maxActiveWorkers);
        summary.put("minActiveWorkers", minActiveWorkers == Long.MAX_VALUE ? 0L : minActiveWorkers);
        summary.put("maxAvailableWorkers", maxAvailableWorkers);
        summary.put("peakUtilizationRatio", peakUtilizationRatio);
        summary.put("purposeCounts", immutableLongMap(purposeCounts));
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeCpuSamples(List<Map<String, Object>> cpuSamples) {
        if (cpuSamples.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> maxRealSample = cpuSamples.stream()
            .max(Comparator.comparingDouble(sample -> metricDouble(sample, "realSeconds")))
            .orElse(Map.of());
        double totalUserSeconds = cpuSamples.stream().mapToDouble(sample -> metricDouble(sample, "userSeconds")).sum();
        double totalSysSeconds = cpuSamples.stream().mapToDouble(sample -> metricDouble(sample, "sysSeconds")).sum();
        double totalRealSeconds = cpuSamples.stream().mapToDouble(sample -> metricDouble(sample, "realSeconds")).sum();
        double peakCpuToWallRatio = cpuSamples.stream()
            .mapToDouble(sample -> metricDouble(sample, "cpuToWallRatio"))
            .max()
            .orElse(0.0d);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", (long) cpuSamples.size());
        summary.put("totalUserSeconds", totalUserSeconds);
        summary.put("totalSysSeconds", totalSysSeconds);
        summary.put("totalRealSeconds", totalRealSeconds);
        summary.put("maxRealSeconds", metricDouble(maxRealSample, "realSeconds"));
        summary.put("maxCpuSeconds", metricDouble(maxRealSample, "cpuSeconds"));
        summary.put("maxRealGcId", maxRealSample.get("gcId"));
        summary.put("peakCpuToWallRatio", peakCpuToWallRatio);
        return immutableMap(summary);
    }

    private Map<String, Object> summarizeHumongousRegions(List<Map<String, Object>> humongousRegionSamples) {
        if (humongousRegionSamples.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> peakAfterSample = humongousRegionSamples.stream()
            .max(Comparator.comparingLong(sample -> metricLong(sample, "afterRegions")))
            .orElse(Map.of());
        long growthEventCount = humongousRegionSamples.stream()
            .filter(sample -> metricLong(sample, "deltaRegions") > 0L)
            .count();
        long maxGrowthRegions = humongousRegionSamples.stream()
            .mapToLong(sample -> metricLong(sample, "deltaRegions"))
            .max()
            .orElse(0L);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", (long) humongousRegionSamples.size());
        summary.put("peakBeforeRegions", humongousRegionSamples.stream().mapToLong(sample -> metricLong(sample, "beforeRegions")).max().orElse(0L));
        summary.put("peakAfterRegions", metricLong(peakAfterSample, "afterRegions"));
        summary.put("peakAfterGcId", peakAfterSample.get("gcId"));
        summary.put("growthEventCount", growthEventCount);
        summary.put("maxGrowthRegions", maxGrowthRegions);
        return immutableMap(summary);
    }

    private void putIfPositiveLong(Map<String, Object> target, String key, long value) {
        if (value > 0L) {
            target.put(key, value);
        }
    }

    private void putIfPositiveDouble(Map<String, Object> target, String key, double value) {
        if (value > 0.0d) {
            target.put(key, value);
        }
    }

    private void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private long maxConsecutiveMatches(List<Map<String, Object>> events, Predicate<Map<String, Object>> predicate) {
        if (events == null || events.isEmpty()) {
            return 0L;
        }

        long best = 0L;
        long current = 0L;
        for (Map<String, Object> event : events) {
            if (predicate.test(event)) {
                current++;
                best = Math.max(best, current);
            } else {
                current = 0L;
            }
        }
        return best;
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
        return stringValue(pause.getOrDefault("event", "")).toLowerCase(Locale.ROOT).contains("full");
    }

    private boolean isMetaspaceTriggeredFullGc(Map<String, Object> pause) {
        String event = stringValue(pause.getOrDefault("event", "")).toLowerCase(Locale.ROOT);
        return event.contains("full") && event.contains("metadata gc");
    }

    private boolean isMetaspaceTriggeredPause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("metadata gc threshold");
    }

    private boolean isMetadataClearSoftReferencesFullGc(Map<String, Object> pause) {
        String event = stringValue(pause.get("event")).toLowerCase(Locale.ROOT);
        return event.contains("full") && event.contains("metadata gc clear soft references");
    }

    private boolean isEvacuationFailurePause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("evacuation failure");
    }

    private boolean isConcurrentAbortSignal(Map<String, Object> failureSignal) {
        return "CONCURRENT_ABORT".equals(stringValue(failureSignal.get("signalType")));
    }

    private boolean isToSpaceExhaustedSignal(Map<String, Object> failureSignal) {
        return "TO_SPACE_EXHAUSTED".equals(stringValue(failureSignal.get("signalType")));
    }

    private boolean isFullCompactionAttemptSignal(Map<String, Object> failureSignal) {
        return "FULL_COMPACTION_ATTEMPT".equals(stringValue(failureSignal.get("signalType")));
    }

    private boolean isConcurrentModeFailureSignal(Map<String, Object> failureSignal) {
        return "CONCURRENT_MODE_FAILURE".equals(stringValue(failureSignal.get("signalType")));
    }

    private boolean isPromotionFailedSignal(Map<String, Object> failureSignal) {
        return "PROMOTION_FAILED".equals(stringValue(failureSignal.get("signalType")));
    }

    private double longestPhaseDuration(List<Map<String, Object>> phaseSamples, String phaseKind) {
        return phaseSamples.stream()
            .filter(sample -> phaseKind.equals(sample.get("phaseKind")))
            .mapToDouble(sample -> metricDouble(sample, "durationMs"))
            .max()
            .orElse(0.0d);
    }

    private double occupancyRatio(long used, long committed) {
        if (committed <= 0L) {
            return 0.0d;
        }
        return (double) used / committed;
    }

    private String classifyPauseType(String event) {
        String lower = event.toLowerCase(Locale.ROOT).replace('-', ' ');
        if (lower.contains("full")) {
            return "FULL";
        }
        if (lower.contains("degenerated")) {
            return "DEGENERATED";
        }
        if (lower.contains("init mark") || lower.contains("initial mark")) {
            return "INIT_MARK";
        }
        if (lower.contains("final mark")) {
            return "FINAL_MARK";
        }
        if (lower.contains("remark")) {
            return "REMARK";
        }
        if (lower.contains("cleanup")) {
            return "CLEANUP";
        }
        if (lower.contains("mixed")) {
            return "MIXED";
        }
        if (lower.contains("young")) {
            return "YOUNG";
        }
        if (lower.contains("update refs")) {
            return "UPDATE_REFS";
        }
        return "OTHER";
    }

    private String normalizePauseCause(String event) {
        String lastParenthetical = "";
        Matcher matcher = PARENTHETICAL_PATTERN.matcher(event);
        while (matcher.find()) {
            lastParenthetical = normalizeWhitespace(matcher.group(1));
        }
        return lastParenthetical.isBlank() ? event : lastParenthetical;
    }

    private boolean isPhaseLabel(String phase) {
        String lower = phase.toLowerCase(Locale.ROOT);
        return lower.startsWith("phase ")
            || lower.startsWith("concurrent ")
            || lower.startsWith("pause ")
            || lower.startsWith("pre evacuate")
            || lower.startsWith("merge heap roots")
            || lower.startsWith("evacuate collection set")
            || lower.startsWith("post evacuate")
            || lower.equals("other");
    }

    private String classifyPhaseKind(String phase) {
        String lower = phase.toLowerCase(Locale.ROOT);
        if (lower.startsWith("concurrent")) {
            return "CONCURRENT";
        }
        if (lower.startsWith("pause")) {
            return "PAUSE_PHASE";
        }
        if (lower.startsWith("phase ")) {
            return "FULL_GC_PHASE";
        }
        if (lower.contains("evacuate") || lower.contains("heap roots") || lower.equals("other")) {
            return "PAUSE_BREAKDOWN";
        }
        return "OTHER";
    }

    private Map<String, Long> countValues(List<Map<String, Object>> samples, String key) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            String value = stringValue(sample.get(key));
            if (!value.isBlank()) {
                counts.merge(value, 1L, Long::sum);
            }
        }
        return counts;
    }

    private Map<String, Double> sumDurationsByKey(List<Map<String, Object>> samples, String key, String durationKey) {
        LinkedHashMap<String, Double> totals = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            String value = stringValue(sample.get(key));
            if (!value.isBlank()) {
                totals.merge(value, metricDouble(sample, durationKey), Double::sum);
            }
        }
        return totals;
    }

    private Map<String, Double> maxDurationsByKey(List<Map<String, Object>> samples, String key, String durationKey) {
        LinkedHashMap<String, Double> maximums = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            String value = stringValue(sample.get(key));
            if (!value.isBlank()) {
                maximums.merge(value, metricDouble(sample, durationKey), Math::max);
            }
        }
        return maximums;
    }

    private int firstPauseIndex(List<Map<String, Object>> pauses, String pauseType) {
        for (int index = 0; index < pauses.size(); index++) {
            if (pauseType.equals(stringValue(pauses.get(index).get("pauseType")))) {
                return index;
            }
        }
        return -1;
    }

    private Map<String, Object> firstPauseByType(List<Map<String, Object>> pauses, String pauseType) {
        int index = firstPauseIndex(pauses, pauseType);
        if (index < 0) {
            return Map.of();
        }
        return pauses.get(index);
    }

    private Map<String, Object> firstPauseAfterIndex(List<Map<String, Object>> pauses, String pauseType, int index) {
        if (index < 0) {
            return Map.of();
        }
        for (int current = index + 1; current < pauses.size(); current++) {
            Map<String, Object> pause = pauses.get(current);
            if (pauseType.equals(stringValue(pause.get("pauseType")))) {
                return pause;
            }
        }
        return Map.of();
    }

    private long countPausesBeforeIndex(List<Map<String, Object>> pauses, String pauseType, int indexExclusive) {
        if (indexExclusive <= 0) {
            return 0L;
        }
        long count = 0L;
        for (int index = 0; index < Math.min(indexExclusive, pauses.size()); index++) {
            if (pauseType.equals(stringValue(pauses.get(index).get("pauseType")))) {
                count++;
            }
        }
        return count;
    }

    private long countPausesAfterIndex(List<Map<String, Object>> pauses, String pauseType, int indexExclusive) {
        if (indexExclusive < 0) {
            return 0L;
        }
        long count = 0L;
        for (int index = indexExclusive + 1; index < pauses.size(); index++) {
            if (pauseType.equals(stringValue(pauses.get(index).get("pauseType")))) {
                count++;
            }
        }
        return count;
    }

    private String dominantNumericKey(Map<String, ? extends Number> values) {
        String dominantKey = "";
        double dominantValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, ? extends Number> entry : values.entrySet()) {
            double value = entry.getValue() != null ? entry.getValue().doubleValue() : 0.0d;
            if (dominantKey.isBlank() || value > dominantValue) {
                dominantKey = entry.getKey();
                dominantValue = value;
            }
        }
        return dominantKey;
    }

    private Map<String, Object> immutableMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private Map<String, Long> immutableLongMap(Map<String, Long> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private Map<String, Object> immutableDoubleMap(Map<String, Double> values) {
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        values.forEach(converted::put);
        return immutableMap(converted);
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private Optional<Map<String, Object>> parseLegacyPauseLine(String line, int lineNumber) {
        String lower = line.toLowerCase(Locale.ROOT);
        boolean structuralPause = isLegacyStructuralPauseLine(lower);
        if ((!lower.contains("[gc") && !lower.contains("[full gc")) || !lower.contains("secs") || (!lower.contains("->") && !structuralPause)) {
            return Optional.empty();
        }

        Optional<Double> durationSeconds = legacyPauseDurationSeconds(line);
        if (durationSeconds.isEmpty()) {
            return Optional.empty();
        }

        String searchLine = line;
        int metaspaceIndex = lower.indexOf("metaspace:");
        if (metaspaceIndex >= 0) {
            searchLine = line.substring(0, metaspaceIndex);
        }
        List<MemoryTransition> transitions = memoryTransitions(searchLine);
        if (!transitions.isEmpty()) {
            MemoryTransition overallTransition = transitions.get(transitions.size() - 1);
            String event = legacyPauseEvent(line);
            Map<String, Object> pause = pauseEvent(null, event);
            addHeapFootprint(
                pause,
                overallTransition.beforeBytes(),
                overallTransition.afterBytes(),
                overallTransition.capacityBytes()
            );
            pause.put("pauseMs", durationSeconds.get() * 1_000.0d);
            addLineContext(pause, line, lineNumber);
            return Optional.of(immutableMap(pause));
        }

        List<OccupancySnapshot> snapshots = occupancySnapshots(searchLine);
        String event = legacyPauseEvent(line);
        Map<String, Object> pause = pauseEvent(null, event);
        if (!snapshots.isEmpty()) {
            OccupancySnapshot overallSnapshot = snapshots.get(snapshots.size() - 1);
            addHeapFootprint(
                pause,
                overallSnapshot.usedBytes(),
                overallSnapshot.usedBytes(),
                overallSnapshot.capacityBytes()
            );
        }
        pause.put("pauseMs", durationSeconds.get() * 1_000.0d);
        addLineContext(pause, line, lineNumber);
        return Optional.of(immutableMap(pause));
    }

    private List<MemoryTransition> memoryTransitions(String line) {
        List<MemoryTransition> transitions = new ArrayList<>();
        Matcher matcher = MEMORY_TRANSITION_PATTERN.matcher(line);
        while (matcher.find()) {
            transitions.add(new MemoryTransition(
                sizeToBytes(matcher.group(1), matcher.group(2)),
                sizeToBytes(matcher.group(3), matcher.group(4)),
                sizeToBytes(matcher.group(5), matcher.group(6))
            ));
        }
        return transitions;
    }

    private List<OccupancySnapshot> occupancySnapshots(String line) {
        List<OccupancySnapshot> snapshots = new ArrayList<>();
        Matcher matcher = LEGACY_HEAP_SNAPSHOT_PATTERN.matcher(line);
        while (matcher.find()) {
            snapshots.add(new OccupancySnapshot(
                sizeToBytes(matcher.group(1), matcher.group(2)),
                sizeToBytes(matcher.group(3), matcher.group(4))
            ));
        }
        return snapshots;
    }

    private Optional<Double> legacyPauseDurationSeconds(String line) {
        Matcher matcher = LEGACY_DURATION_SECONDS_PATTERN.matcher(line);
        Double durationSeconds = null;
        while (matcher.find()) {
            durationSeconds = Double.parseDouble(matcher.group(1));
        }
        return Optional.ofNullable(durationSeconds);
    }

    private String legacyPauseEvent(String line) {
        String legacyG1Event = legacyG1PauseEvent(line);
        if (!legacyG1Event.isBlank()) {
            return legacyG1Event;
        }

        String lower = line.toLowerCase(Locale.ROOT);
        String cause = legacyPauseCause(line);
        if (lower.contains("remark")) {
            return cause.isBlank() ? "Pause Remark" : "Pause Remark (" + cause + ")";
        }
        if (lower.contains("cleanup")) {
            return cause.isBlank() ? "Pause Cleanup" : "Pause Cleanup (" + cause + ")";
        }

        String base = lower.contains("[full gc") ? "Pause Full" : "Pause Young";
        if (cause.isBlank()) {
            return base;
        }
        return base + " (" + cause + ")";
    }

    private String legacyPauseCause(String line) {
        Matcher matcher = LEGACY_GC_CAUSE_PATTERN.matcher(line);
        if (!matcher.find()) {
            return "";
        }
        return normalizeWhitespace(matcher.group(1));
    }

    private boolean isLegacyCmsSnapshotPauseLine(String lowerLine) {
        return containsAny(lowerLine, "cms initial mark", "cms final remark", "cms-remark", "cms-initial-mark");
    }

    private boolean isLegacyStructuralPauseLine(String lowerLine) {
        return isLegacyCmsSnapshotPauseLine(lowerLine)
            || lowerLine.contains("[gc remark")
            || lowerLine.contains("[gc cleanup");
    }

    private Optional<Map<String, Object>> parseLegacyCmsPhaseLine(String line, int lineNumber) {
        Matcher matcher = LEGACY_CMS_CONCURRENT_PHASE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> phaseSample = new LinkedHashMap<>();
        phaseSample.put("phase", normalizeWhitespace(matcher.group(1).replace('-', ' ')));
        phaseSample.put("phaseKind", "CONCURRENT");
        phaseSample.put("durationMs", Double.parseDouble(matcher.group(2)) * 1_000.0d);
        addLineContext(phaseSample, line, lineNumber);
        return Optional.of(immutableMap(phaseSample));
    }

    private Optional<Map<String, Object>> parseLegacyG1ConcurrentPhaseLine(String line, int lineNumber) {
        Matcher matcher = LEGACY_G1_CONCURRENT_PHASE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> phaseSample = new LinkedHashMap<>();
        phaseSample.put("phase", humanizeLegacyPhaseName(matcher.group(1)));
        phaseSample.put("phaseKind", "CONCURRENT");
        phaseSample.put("durationMs", Double.parseDouble(matcher.group(2)) * 1_000.0d);
        addLineContext(phaseSample, line, lineNumber);
        return Optional.of(immutableMap(phaseSample));
    }

    private String legacyG1PauseEvent(String line) {
        Matcher matcher = LEGACY_G1_PAUSE_DESCRIPTOR_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return "";
        }

        List<String> segments = new ArrayList<>();
        Matcher parentheticalMatcher = PARENTHETICAL_PATTERN.matcher(matcher.group(1));
        while (parentheticalMatcher.find()) {
            String segment = normalizeWhitespace(parentheticalMatcher.group(1));
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }

        if (segments.isEmpty()) {
            return "Pause Young";
        }

        boolean full = segments.stream().anyMatch(segment -> legacySegmentKey(segment).contains("full"));
        boolean mixed = segments.stream().anyMatch(segment -> "mixed".equals(legacySegmentKey(segment)));
        boolean young = mixed || segments.stream().anyMatch(segment -> "young".equals(legacySegmentKey(segment)));
        boolean initialMark = segments.stream().anyMatch(this::isInitialMarkSegment);
        boolean finalMark = segments.stream().anyMatch(this::isFinalMarkSegment);
        boolean concurrentStart = segments.stream().anyMatch(this::isConcurrentStartSegment);

        String cause = "";
        List<String> trailingSignals = new ArrayList<>();
        for (String segment : segments) {
            if (isLegacyG1ModeSegment(segment)) {
                continue;
            }
            String humanized = humanizeLegacyGcToken(segment);
            if (cause.isBlank() && isLegacyG1CauseSegment(segment)) {
                cause = humanized;
                continue;
            }
            trailingSignals.add(humanized);
        }

        if (cause.isBlank() && !trailingSignals.isEmpty()) {
            cause = trailingSignals.removeFirst();
        }

        StringBuilder event = new StringBuilder(full ? "Pause Full" : (young ? "Pause Young" : "Pause"));
        if (mixed) {
            event.append(" (Mixed)");
        }
        if (initialMark) {
            event.append(" (Initial Mark)");
        }
        if (finalMark) {
            event.append(" (Final Mark)");
        }
        if (concurrentStart) {
            event.append(" (Concurrent Start)");
        }
        if (!cause.isBlank()) {
            event.append(" (").append(cause).append(")");
        }
        for (String trailingSignal : trailingSignals) {
            event.append(" (").append(trailingSignal).append(")");
        }
        return event.toString();
    }

    private boolean isLegacyG1ModeSegment(String segment) {
        String key = legacySegmentKey(segment);
        return "young".equals(key)
            || "mixed".equals(key)
            || "full".equals(key)
            || "normal".equals(key)
            || isInitialMarkSegment(segment)
            || isFinalMarkSegment(segment)
            || isConcurrentStartSegment(segment);
    }

    private boolean isLegacyG1CauseSegment(String segment) {
        String key = legacySegmentKey(segment);
        return key.startsWith("g1 ")
            || key.contains("g1 evacuation pause")
            || key.contains("g1 compaction pause")
            || key.contains("humongous allocation")
            || key.contains("preventive collection");
    }

    private boolean isInitialMarkSegment(String segment) {
        String key = legacySegmentKey(segment);
        return "initial mark".equals(key) || "init mark".equals(key);
    }

    private boolean isFinalMarkSegment(String segment) {
        return "final mark".equals(legacySegmentKey(segment));
    }

    private boolean isConcurrentStartSegment(String segment) {
        return "concurrent start".equals(legacySegmentKey(segment));
    }

    private String legacySegmentKey(String segment) {
        return normalizeWhitespace(segment).toLowerCase(Locale.ROOT).replace('-', ' ');
    }

    private String humanizeLegacyGcToken(String segment) {
        String key = legacySegmentKey(segment);
        return switch (key) {
            case "young" -> "Young";
            case "mixed" -> "Mixed";
            case "full" -> "Full";
            case "normal" -> "Normal";
            case "initial mark", "init mark" -> "Initial Mark";
            case "final mark" -> "Final Mark";
            case "concurrent start" -> "Concurrent Start";
            case "to space exhausted" -> "To-space exhausted";
            case "to space overflow" -> "To-space overflow";
            default -> normalizeWhitespace(segment);
        };
    }

    private String humanizeLegacyPhaseName(String phase) {
        String[] tokens = normalizeWhitespace(phase.replace('-', ' ')).split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if ("g1".equals(lower) || "gc".equals(lower) || "cms".equals(lower)) {
                builder.append(lower.toUpperCase(Locale.ROOT));
            } else {
                builder.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    builder.append(lower.substring(1));
                }
            }
        }
        return builder.toString();
    }

    private Optional<Long> extractUnifiedGcId(String line) {
        Matcher matcher = UNIFIED_GC_ID_PATTERN.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(matcher.group(1)));
    }

    private Map<String, Object> pauseEvent(Long gcId, String event) {
        Map<String, Object> pause = new LinkedHashMap<>();
        if (gcId != null) {
            pause.put("gcId", gcId);
        }
        pause.put("event", event);
        pause.put("pauseType", classifyPauseType(event));
        pause.put("cause", normalizePauseCause(event));
        return pause;
    }

    private void addHeapFootprint(
        Map<String, Object> pause,
        long beforeHeapBytes,
        long afterHeapBytes,
        long heapCapacityBytes
    ) {
        pause.put("beforeHeapBytes", beforeHeapBytes);
        pause.put("afterHeapBytes", afterHeapBytes);
        pause.put("heapCapacityBytes", heapCapacityBytes);
        pause.put("beforeHeapMb", bytesToRoundedMb(beforeHeapBytes));
        pause.put("afterHeapMb", bytesToRoundedMb(afterHeapBytes));
        pause.put("heapCapacityMb", bytesToRoundedMb(heapCapacityBytes));
        pause.put("beforeOccupancyRatio", occupancyRatio(beforeHeapBytes, heapCapacityBytes));
        pause.put("afterOccupancyRatio", occupancyRatio(afterHeapBytes, heapCapacityBytes));
    }

    private boolean hasHeapFootprint(Map<String, Object> pause) {
        return pause.get("beforeHeapBytes") instanceof Number
            && pause.get("afterHeapBytes") instanceof Number
            && pause.get("heapCapacityBytes") instanceof Number
            && metricLong(pause, "heapCapacityBytes") > 0L;
    }

    private boolean isMixedGcPause(Map<String, Object> pause) {
        return "MIXED".equals(stringValue(pause.get("pauseType")));
    }

    private boolean isLowReclaimHighRetentionFullGc(Map<String, Object> pause) {
        return isFullGcEvent(pause)
            && hasHeapFootprint(pause)
            && reclaimedFractionOfHeap(pause) <= LOW_RECLAIM_FRACTION_OF_HEAP
            && metricDouble(pause, "afterOccupancyRatio") >= HIGH_RETAINED_OCCUPANCY_RATIO;
    }

    private double reclaimedMb(Map<String, Object> pause) {
        long beforeBytes = metricLong(pause, "beforeHeapBytes");
        long afterBytes = metricLong(pause, "afterHeapBytes");
        return (beforeBytes - afterBytes) / (1024.0d * 1024.0d);
    }

    private double reclaimedFractionOfHeap(Map<String, Object> pause) {
        long heapCapacityBytes = metricLong(pause, "heapCapacityBytes");
        if (heapCapacityBytes <= 0L) {
            return 0.0d;
        }
        long reclaimedBytes = Math.max(0L, metricLong(pause, "beforeHeapBytes") - metricLong(pause, "afterHeapBytes"));
        return (double) reclaimedBytes / heapCapacityBytes;
    }

    private long maxLowReclaimHighRetentionFullGcStreak(List<Map<String, Object>> fullPauses) {
        long maxStreak = 0L;
        long currentStreak = 0L;
        for (Map<String, Object> fullPause : fullPauses) {
            if (isLowReclaimHighRetentionFullGc(fullPause)) {
                currentStreak++;
                maxStreak = Math.max(maxStreak, currentStreak);
            } else {
                currentStreak = 0L;
            }
        }
        return maxStreak;
    }

    private Map<String, Object> cpuSample(String gcIdText, double userSeconds, double sysSeconds, double realSeconds) {
        Map<String, Object> cpuSample = new LinkedHashMap<>();
        if (gcIdText != null && !gcIdText.isBlank()) {
            cpuSample.put("gcId", Long.parseLong(gcIdText));
        }
        cpuSample.put("userSeconds", userSeconds);
        cpuSample.put("sysSeconds", sysSeconds);
        cpuSample.put("realSeconds", realSeconds);
        cpuSample.put("cpuSeconds", userSeconds + sysSeconds);
        if (realSeconds > 0.0d) {
            cpuSample.put("cpuToWallRatio", (userSeconds + sysSeconds) / realSeconds);
        }
        return cpuSample;
    }

    private long sizeToBytes(String value, String unit) {
        if (value == null || unit == null || value.isBlank() || unit.isBlank()) {
            return 0L;
        }
        double numericValue = Double.parseDouble(value);
        return switch (Character.toUpperCase(unit.charAt(0))) {
            case 'T' -> Math.round(numericValue * 1_024d * 1_024d * 1_024d * 1_024d);
            case 'G' -> Math.round(numericValue * 1_024d * 1_024d * 1_024d);
            case 'M' -> Math.round(numericValue * 1_024d * 1_024d);
            case 'K' -> Math.round(numericValue * 1_024d);
            case 'B' -> Math.round(numericValue);
            default -> 0L;
        };
    }

    private long bytesToRoundedMb(long bytes) {
        return Math.round(bytes / (1024.0d * 1024.0d));
    }

    private long bytesToRoundedKb(long bytes) {
        return Math.round(bytes / 1024.0d);
    }

    private boolean containsAny(String content, String... needles) {
        for (String needle : needles) {
            if (content.contains(needle)) {
                return true;
            }
        }
        return false;
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record GcParseState(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> mmuSamples,
        List<Map<String, Object>> workerSamples,
        List<Map<String, Object>> cpuSamples,
        List<Map<String, Object>> humongousRegionSamples,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals,
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

    private record NumberedGcLine(
        int lineNumber,
        String content
    ) {
    }

    private record LegacyG1PauseBlock(
        List<NumberedGcLine> lines,
        int endIndex
    ) {
    }

    private record MemoryTransition(
        long beforeBytes,
        long afterBytes,
        long capacityBytes
    ) {
    }

    private record OccupancySnapshot(
        long usedBytes,
        long capacityBytes
    ) {
    }
}
