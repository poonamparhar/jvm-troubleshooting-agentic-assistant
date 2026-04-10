package com.javaassistant.compare;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.assessment.AssessmentResult;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NmtComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.NMT;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Long> baselineTotal = longMap(baselineParsed.extractedData().get("totalKb"));
        Map<String, Long> currentTotal = longMap(currentParsed.extractedData().get("totalKb"));
        Map<String, Long> baselineMetaspace = longMap(baselineParsed.extractedData().get("metaspaceSummary"));
        Map<String, Long> currentMetaspace = longMap(currentParsed.extractedData().get("metaspaceSummary"));
        Map<String, Long> baselineThread = longMap(baselineParsed.extractedData().get("threadSummary"));
        Map<String, Long> currentThread = longMap(currentParsed.extractedData().get("threadSummary"));
        Map<String, Map<String, Long>> baselineCategories = categoryMap(baselineParsed.extractedData().get("categories"));
        Map<String, Map<String, Long>> currentCategories = categoryMap(currentParsed.extractedData().get("categories"));

        long totalCommittedDeltaKb = currentTotal.getOrDefault("committedKb", 0L) - baselineTotal.getOrDefault("committedKb", 0L);
        long classCommittedDeltaKb = categoryDelta(baselineCategories, currentCategories, "Class", "committedKb");
        long metaspaceUsedDeltaKb = currentMetaspace.getOrDefault("usedKb", 0L) - baselineMetaspace.getOrDefault("usedKb", 0L);
        long threadCountDelta = currentThread.getOrDefault("threadCount", 0L) - baselineThread.getOrDefault("threadCount", 0L);
        long stackReservedDeltaKb = currentThread.getOrDefault("stackReservedKb", 0L) - baselineThread.getOrDefault("stackReservedKb", 0L);
        Map<String, Long> internalLikeCategoryDeltas = internalLikeCategoryDeltas(baselineCategories, currentCategories);
        long internalLikeCommittedDeltaKb = internalLikeCategoryDeltas.values().stream().mapToLong(Long::longValue).sum();
        long currentInternalLikeCommittedKb = internalLikeCommittedKb(currentCategories);
        double internalLikeGrowthShare = totalCommittedDeltaKb > 0L
            ? (double) internalLikeCommittedDeltaKb / totalCommittedDeltaKb
            : 0.0d;
        ReservationSummary baselineReservationSummary = reservationSummary(baselineCategories);
        ReservationSummary currentReservationSummary = reservationSummary(currentCategories);
        long nonHeapReservedDeltaKb = currentReservationSummary.reservedKb() - baselineReservationSummary.reservedKb();
        long nonHeapCommittedDeltaKb = currentReservationSummary.committedKb() - baselineReservationSummary.committedKb();
        long nonHeapGapDeltaKb = currentReservationSummary.gapKb() - baselineReservationSummary.gapKb();

        if (baselineTotal.isEmpty() || currentTotal.isEmpty()) {
            missingData.add("NMT comparison could not parse total reserved/committed memory from one or both files.");
            return new AssessmentResult(findings, actions, missingData);
        }

        if (totalCommittedDeltaKb >= 16_384L) {
            String findingId = "compare-nmt-native-growth";
            findings.add(new Finding(
                findingId,
                "Native committed memory grew materially between NMT snapshots",
                String.format("Committed native memory increased by %dKB between baseline and current snapshots.", totalCommittedDeltaKb),
                "compare.nmt-native-growth",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                List.of("nmt-total"),
                "A large positive delta in total committed native memory is a strong sign of real native footprint growth."
            ));
            actions.add(new RecommendedAction(
                "action-compare-nmt-native-growth",
                "Investigate the fastest-growing native memory categories",
                "Committed native memory increased materially between the two NMT snapshots.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare Class, Thread, GC, Internal, Unknown, and Arena Chunk deltas to isolate the dominant source.",
                    "Capture another NMT snapshot or detail.diff to confirm whether the same categories continue to grow.",
                    "Correlate the growth with pmap and heap histogram snapshots from the same time window."
                ),
                List.of(findingId)
            ));
        }

        if (internalLikeCommittedDeltaKb >= 16_384L && (internalLikeGrowthShare >= 0.35d || internalLikeCommittedDeltaKb >= 24_576L)) {
            String findingId = "compare-nmt-internal-arena-growth";
            SeverityLevel severity = internalLikeCommittedDeltaKb >= 32_768L || currentInternalLikeCommittedKb >= 49_152L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(new Finding(
                findingId,
                "Internal or arena-backed native memory grew between NMT snapshots",
                String.format(
                    "Internal, Unknown, and Arena Chunk categories changed by %sKB committed between baseline and current snapshots%s.",
                    signed(internalLikeCommittedDeltaKb),
                    categoryDeltaBreakdown(internalLikeCategoryDeltas).isBlank() ? "" : " (" + categoryDeltaBreakdown(internalLikeCategoryDeltas) + ")"
                ),
                "compare.nmt-internal-arena-growth",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds("nmt-total", "nmt-category-internal", "nmt-category-delta-internal", "nmt-category-unknown", "nmt-category-delta-unknown", "nmt-category-arena-chunk", "nmt-category-delta-arena-chunk"),
                "When Internal, Unknown, or Arena Chunk dominate the native-memory delta, the change is more consistent with native allocator or off-heap growth than with Java heap expansion."
            ));
            actions.add(new RecommendedAction(
                "action-compare-nmt-internal-arena-growth",
                "Investigate internal or arena-backed native growth between snapshots",
                "The compared NMT snapshots show native-memory growth concentrated in Internal, Unknown, or Arena Chunk categories.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Capture another NMT summary pair or detail.diff to confirm whether Internal, Unknown, or Arena Chunk continue to climb.",
                    "Correlate the same interval with pmap anonymous mappings and any off-heap buffer, JNI, native-library, or allocator-heavy subsystems.",
                    "Treat this as native-memory investigation first instead of heap tuning unless other diagnostics also show heap pressure."
                ),
                List.of(findingId)
            ));
        }

        if (nonHeapReservedDeltaKb >= 131_072L && nonHeapGapDeltaKb >= 131_072L && Math.abs(nonHeapCommittedDeltaKb) <= 16_384L) {
            String findingId = "compare-nmt-reserved-expansion";
            SeverityLevel severity = nonHeapReservedDeltaKb >= 262_144L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            findings.add(new Finding(
                findingId,
                "Native reserved space grew without matching committed growth between NMT snapshots",
                String.format(
                    "Non-heap reserved space changed by %sKB, non-heap committed changed by %sKB, and the reserved-versus-committed gap changed by %sKB. Largest reservation-gap changes were %s.",
                    signed(nonHeapReservedDeltaKb),
                    signed(nonHeapCommittedDeltaKb),
                    signed(nonHeapGapDeltaKb),
                    reservationGapDeltaSummary(baselineCategories, currentCategories)
                ),
                "compare.nmt-reserved-expansion",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds("nmt-total", "nmt-category-code", "nmt-category-class", "nmt-category-gc", "nmt-category-internal"),
                "When reserved space grows but committed space stays mostly flat, the change is more likely to be additional address-space reservation than active native RAM growth."
            ));
            actions.add(new RecommendedAction(
                "action-compare-nmt-reserved-expansion",
                "Interpret reservation growth separately from committed native growth",
                "The compared NMT snapshots show reservation expansion without matching committed growth.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare the same interval with pmap RSS so reserved expansion is not mistaken for active resident-memory pressure.",
                    "Inspect which categories added the largest reserved gaps and whether their committed memory is still mostly flat.",
                    "Capture another later snapshot to see whether committed memory eventually follows the new reservations."
                ),
                List.of(findingId)
            ));
        }

        if (metaspaceUsedDeltaKb >= 8_192L || classCommittedDeltaKb >= 8_192L) {
            String findingId = "compare-nmt-metaspace-growth";
            findings.add(new Finding(
                findingId,
                "Class metadata usage grew sharply between NMT snapshots",
                String.format(
                    "Metaspace used changed by %dKB and Class committed memory changed by %dKB.",
                    metaspaceUsedDeltaKb,
                    classCommittedDeltaKb
                ),
                "compare.nmt-metaspace-growth",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                List.of("nmt-total"),
                "Concurrent growth in metaspace used or Class committed memory is a strong indicator of expanding class metadata footprint."
            ));
            actions.add(new RecommendedAction(
                "action-compare-nmt-metaspace-growth",
                "Inspect class loading and metaspace growth between snapshots",
                "Class metadata usage increased materially between baseline and current NMT output.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Review dynamic class generation, proxy creation, and redeployment behavior.",
                    "Capture class loading statistics or a heap histogram to see whether class-related structures also increased.",
                    "Review metaspace limits and unloading behavior before increasing JVM limits."
                ),
                List.of(findingId)
            ));
        }

        if (threadCountDelta >= 20L || stackReservedDeltaKb >= 8_192L) {
            String findingId = "compare-nmt-thread-growth";
            findings.add(new Finding(
                findingId,
                "Thread-related native memory grew between NMT snapshots",
                String.format("Thread count changed by %d and reserved thread stack memory changed by %dKB.", threadCountDelta, stackReservedDeltaKb),
                "compare.nmt-thread-growth",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of(path(baselineParsed), path(currentParsed)),
                List.of("nmt-total"),
                "Large increases in thread count or reserved stack memory can explain meaningful native growth outside the heap."
            ));
            actions.add(new RecommendedAction(
                "action-compare-nmt-thread-growth",
                "Review thread creation and stack sizing",
                "Thread count or reserved stack memory increased materially between the compared NMT snapshots.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Inspect recent thread creation spikes and pool sizing changes.",
                    "Check whether stack size settings are amplifying native growth.",
                    "Capture thread dumps if the process is still live to identify the source of thread growth."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> longMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Long>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> categoryMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Map<String, Long>>) value : Map.of();
    }

    private long categoryDelta(
        Map<String, Map<String, Long>> baselineCategories,
        Map<String, Map<String, Long>> currentCategories,
        String category,
        String key
    ) {
        long baselineValue = baselineCategories.getOrDefault(category, Map.of()).getOrDefault(key, 0L);
        long currentValue = currentCategories.getOrDefault(category, Map.of()).getOrDefault(key, 0L);
        return currentValue - baselineValue;
    }

    private Map<String, Long> internalLikeCategoryDeltas(
        Map<String, Map<String, Long>> baselineCategories,
        Map<String, Map<String, Long>> currentCategories
    ) {
        Map<String, Long> deltas = new java.util.LinkedHashMap<>();
        for (String categoryName : List.of("Internal", "Unknown", "Arena Chunk")) {
            long delta = categoryDelta(baselineCategories, currentCategories, categoryName, "committedKb");
            if (delta != 0L) {
                deltas.put(categoryName, delta);
            }
        }
        return Map.copyOf(deltas);
    }

    private long internalLikeCommittedKb(Map<String, Map<String, Long>> categories) {
        long total = 0L;
        for (String categoryName : List.of("Internal", "Unknown", "Arena Chunk")) {
            total += categories.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L);
        }
        return total;
    }

    private ReservationSummary reservationSummary(Map<String, Map<String, Long>> categories) {
        long reservedKb = 0L;
        long committedKb = 0L;
        for (Map.Entry<String, Map<String, Long>> entry : categories.entrySet()) {
            if (!countsTowardReservationMismatch(entry.getKey())) {
                continue;
            }
            reservedKb += entry.getValue().getOrDefault("reservedKb", 0L);
            committedKb += entry.getValue().getOrDefault("committedKb", 0L);
        }
        return new ReservationSummary(reservedKb, committedKb, Math.max(0L, reservedKb - committedKb));
    }

    private String reservationGapDeltaSummary(
        Map<String, Map<String, Long>> baselineCategories,
        Map<String, Map<String, Long>> currentCategories
    ) {
        return currentCategories.keySet().stream()
            .filter(this::countsTowardReservationMismatch)
            .map(categoryName -> {
                long baselineGap = Math.max(
                    0L,
                    baselineCategories.getOrDefault(categoryName, Map.of()).getOrDefault("reservedKb", 0L)
                        - baselineCategories.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L)
                );
                long currentGap = Math.max(
                    0L,
                    currentCategories.getOrDefault(categoryName, Map.of()).getOrDefault("reservedKb", 0L)
                        - currentCategories.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L)
                );
                return new CategoryGapDelta(categoryName, currentGap - baselineGap);
            })
            .filter(categoryGapDelta -> categoryGapDelta.gapDeltaKb() > 0L)
            .sorted((left, right) -> Long.compare(right.gapDeltaKb(), left.gapDeltaKb()))
            .limit(3)
            .map(categoryGapDelta -> categoryGapDelta.categoryName() + " " + signed(categoryGapDelta.gapDeltaKb()) + "KB")
            .reduce((left, right) -> left + ", " + right)
            .orElse("no dominant reservation-gap category change was parsed");
    }

    private boolean countsTowardReservationMismatch(String categoryName) {
        return !"Java Heap".equals(categoryName) && !"Metaspace".equals(categoryName);
    }

    private String categoryDeltaBreakdown(Map<String, Long> deltas) {
        return deltas.entrySet().stream()
            .map(entry -> entry.getKey() + " " + signed(entry.getValue()) + "KB")
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private List<String> evidenceIds(String... ids) {
        return Arrays.stream(ids).toList();
    }

    private String signed(long value) {
        return (value >= 0 ? "+" : "") + value;
    }

    private record ReservationSummary(long reservedKb, long committedKb, long gapKb) { }

    private record CategoryGapDelta(String categoryName, long gapDeltaKb) { }

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
