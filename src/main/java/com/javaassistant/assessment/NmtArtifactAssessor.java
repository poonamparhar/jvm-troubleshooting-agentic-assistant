package com.javaassistant.assessment;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class NmtArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.NMT;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> metaspaceSummary = AssessmentSupport.map(extractedData, "metaspaceSummary");
        Map<String, Object> metaspaceSummaryDeltas = AssessmentSupport.map(extractedData, "metaspaceSummaryDeltas");
        Map<String, Object> classSpaceSummary = AssessmentSupport.map(extractedData, "classSpaceSummary");
        Map<String, Object> classSpaceSummaryDeltas = AssessmentSupport.map(extractedData, "classSpaceSummaryDeltas");
        Map<String, Object> totalSummary = AssessmentSupport.map(extractedData, "totalKb");
        Map<String, Object> totalDelta = AssessmentSupport.map(extractedData, "totalDeltaKb");
        Map<String, Object> classSummary = AssessmentSupport.map(extractedData, "classSummary");
        Map<String, Object> classSummaryDeltas = AssessmentSupport.map(extractedData, "classSummaryDeltas");
        Map<String, Object> threadSummary = AssessmentSupport.map(extractedData, "threadSummary");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categoryDeltas = (Map<String, Map<String, Long>>) extractedData.getOrDefault("categoryDeltas", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categories = (Map<String, Map<String, Long>>) extractedData.getOrDefault("categories", Map.of());
        String snapshotKind = String.valueOf(extractedData.getOrDefault("snapshotKind", "summary"));

        long committedDeltaKb = AssessmentSupport.longValue(totalDelta, "committedKb");
        long totalCommittedKb = AssessmentSupport.longValue(totalSummary, "committedKb");
        DominantCategoryDelta dominantCategoryDelta = strongestCommittedCategoryDelta(categoryDeltas);
        if (committedDeltaKb >= 16_384L) {
            String findingId = "nmt-native-allocation-growth";
            SeverityLevel severity = committedDeltaKb >= 65_536L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            String dominantCategoryName = dominantCategoryDelta != null ? dominantCategoryDelta.categoryName() : null;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "NMT diff shows native memory growth",
                String.format(
                    "Committed native memory increased by %dKB in the diff snapshot.%s",
                    committedDeltaKb,
                    dominantCategoryName != null
                        ? " Largest positive committed category delta was " + dominantCategoryName + " " + formatSigned(dominantCategoryDelta.committedKb()) + "KB."
                        : ""
                ),
                "memory.native.growth",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "nmt-total-delta", dominantCategoryName != null ? categoryEvidenceId("nmt-category-delta", dominantCategoryName) : null),
                "A positive committed-memory delta in NMT diff output is direct evidence of native footprint growth over the measured interval."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-native-allocation-growth",
                "Inspect the categories driving native memory growth",
                "The NMT diff shows meaningful positive native memory growth.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Review the Class, Thread, GC, Internal, Unknown, and Arena Chunk category deltas to isolate the dominant source.",
                    "Capture another NMT diff to confirm whether the same categories continue to grow.",
                    "Correlate the same time window with pmap or heap evidence if the process is still available."
                ),
                List.of(findingId)
            ));
        }

        InternalLikeNativeSummary internalLikeNativeSummary = summarizeInternalLikeNative(categories, categoryDeltas);
        double internalLikeShare = totalCommittedKb > 0L
            ? (double) internalLikeNativeSummary.committedKb() / totalCommittedKb
            : 0.0d;
        double internalLikeGrowthShare = committedDeltaKb > 0L
            ? (double) internalLikeNativeSummary.committedDeltaKb() / committedDeltaKb
            : 0.0d;
        if (shouldFlagInternalLikeNativeGrowth(snapshotKind, internalLikeNativeSummary, internalLikeShare, internalLikeGrowthShare)) {
            String findingId = "nmt-internal-arena-growth";
            String deltaBreakdown = describeCategoryCommittedBreakdown(categoryDeltas, internalLikeNativeSummary.categoriesPresent(), true);
            String currentBreakdown = describeCategoryCommittedBreakdown(categories, internalLikeNativeSummary.categoriesPresent(), false);
            boolean diffDriven = "diff".equals(snapshotKind) && internalLikeNativeSummary.committedDeltaKb() > 0L;
            String summaryText = diffDriven
                ? String.format(
                    "Internal, Unknown, and Arena Chunk categories account for %sKB of committed native growth%s.",
                    formatSigned(internalLikeNativeSummary.committedDeltaKb()),
                    deltaBreakdown.isBlank() ? "" : " (" + deltaBreakdown + ")"
                )
                : String.format(
                    Locale.ROOT,
                    "Internal, Unknown, and Arena Chunk categories account for %dKB committed native memory (%.1f%% of total committed)%s.",
                    internalLikeNativeSummary.committedKb(),
                    internalLikeShare * 100.0d,
                    currentBreakdown.isBlank() ? "" : " (" + currentBreakdown + ")"
                );
            SeverityLevel severity = internalLikeNativeSummary.committedDeltaKb() >= 32_768L
                || internalLikeNativeSummary.committedKb() >= 49_152L
                || internalLikeShare >= 0.35d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Internal or arena-backed native memory growth is concentrated",
                summaryText,
                "memory.native.internal",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                internalLikeEvidenceIds(parsedArtifact, internalLikeNativeSummary.categoriesPresent()),
                "Growth concentrated in Internal, Unknown, or Arena Chunk is stronger evidence of native allocator or off-heap growth than of Java heap pressure."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-internal-arena-growth",
                "Investigate internal or arena-backed native growth",
                "NMT shows native-memory growth concentrated in Internal, Unknown, or Arena Chunk categories.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Capture another NMT diff or detail.diff to confirm whether Internal, Unknown, or Arena Chunk continue to grow.",
                    "Correlate the same interval with pmap anonymous mappings and any off-heap buffer, JNI, native-library, or allocator-heavy subsystems.",
                    "Treat this as native-memory investigation first rather than heap tuning unless other diagnostics also show heap pressure."
                ),
                List.of(findingId)
            ));
        }

        NonHeapReservationSummary nonHeapReservationSummary = summarizeNonHeapReservation(categories);
        double nonHeapCommitRatio = nonHeapReservationSummary.reservedKb() > 0L
            ? (double) nonHeapReservationSummary.committedKb() / nonHeapReservationSummary.reservedKb()
            : 0.0d;
        if (shouldFlagReservedCommittedMismatch(nonHeapReservationSummary, nonHeapCommitRatio)) {
            String findingId = "nmt-reserved-committed-mismatch";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Large native reservations are not matched by committed native use",
                String.format(
                    Locale.ROOT,
                    "Non-heap native categories reserve %dKB but only %dKB are committed (%.1f%% committed). Largest reservation gaps are %s.",
                    nonHeapReservationSummary.reservedKb(),
                    nonHeapReservationSummary.committedKb(),
                    nonHeapCommitRatio * 100.0d,
                    nonHeapReservationSummary.topGapSummary()
                ),
                "memory.native.reservation",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                reservationEvidenceIds(parsedArtifact, nonHeapReservationSummary.topGapCategories()),
                "A large reserved-versus-committed gap means the address-space footprint overstates active native consumption, so reserved size should be interpreted separately from committed or resident memory."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-reserved-committed-mismatch",
                "Separate address-space reservation from committed native use",
                "NMT shows that much of the native footprint is reserved rather than committed.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare NMT committed memory with pmap RSS before treating the reserved footprint as active RAM pressure.",
                    "Focus on categories whose committed usage is actually growing, not only categories with large reserved ranges.",
                    "If reservations themselves are expanding over time, capture another NMT snapshot or diff to see whether committed memory eventually follows."
                ),
                List.of(findingId)
            ));
        }

        long metaspaceUsedKb = AssessmentSupport.longValue(metaspaceSummary, "usedKb");
        long metaspaceCommittedKb = AssessmentSupport.longValue(metaspaceSummary, "committedKb");
        long metaspaceUsedDeltaKb = AssessmentSupport.longValue(metaspaceSummaryDeltas, "usedKb");
        Map<String, Long> classCategoryDelta = categoryDeltas.getOrDefault("Class", Map.of());
        long classCommittedDeltaKb = classCategoryDelta.getOrDefault("committedKb", 0L);
        long classCountDelta = AssessmentSupport.longValue(classSummaryDeltas, "classCount");
        if (metaspaceCommittedKb > 0) {
            double utilization = (double) metaspaceUsedKb / metaspaceCommittedKb;
            if (utilization >= 0.85d) {
                String findingId = "nmt-metaspace-pressure";
                findings.add(AssessmentSupport.finding(
                    parsedArtifact,
                    findingId,
                    "Metaspace pressure is high",
                    String.format("Metaspace is using %.1f%% of committed memory (%dKB of %dKB).", utilization * 100.0, metaspaceUsedKb, metaspaceCommittedKb),
                    "memory.metaspace",
                    SeverityLevel.HIGH,
                    ConfidenceLevel.HIGH,
                    FindingStatus.CONFIRMED,
                    evidenceIds(parsedArtifact, "nmt-metaspace-summary", "nmt-category-class", "nmt-class-summary"),
                    "High metaspace utilization increases the risk of class metadata allocation failures."
                ));
                actions.add(AssessmentSupport.action(
                    "action-nmt-metaspace-pressure",
                    "Inspect class loading growth and metaspace limits",
                    "The class metadata footprint is close to the currently committed metaspace.",
                    ActionType.INVESTIGATION,
                    ActionPriority.HIGH,
                    List.of(
                        "Review recent class loading growth and dynamic proxy generation.",
                        "Capture another NMT snapshot or diff to confirm whether Class or Metaspace continues to grow.",
                        "Review MaxMetaspaceSize and class unloading behavior."
                    ),
                    List.of(findingId)
                ));
            }
        } else {
            missingData.add("Metaspace committed and used values were not parsed from the NMT artifact.");
        }

        long classSpaceReservedKb = AssessmentSupport.longValue(classSpaceSummary, "reservedKb");
        long classSpaceCommittedKb = AssessmentSupport.longValue(classSpaceSummary, "committedKb");
        long classSpaceUsedKb = AssessmentSupport.longValue(classSpaceSummary, "usedKb");
        long classSpaceUsedDeltaKb = AssessmentSupport.longValue(classSpaceSummaryDeltas, "usedKb");
        if ((classSpaceCommittedKb > 0L && (double) classSpaceUsedKb / classSpaceCommittedKb >= 0.90d)
            || (classSpaceReservedKb > 0L && (double) classSpaceUsedKb / classSpaceReservedKb >= 0.85d)
            || classSpaceUsedDeltaKb >= 4_096L) {
            double committedUtilization = classSpaceCommittedKb > 0L ? (double) classSpaceUsedKb / classSpaceCommittedKb : 0.0d;
            double reservedUtilization = classSpaceReservedKb > 0L ? (double) classSpaceUsedKb / classSpaceReservedKb : 0.0d;
            SeverityLevel severity = committedUtilization >= 0.97d || reservedUtilization >= 0.95d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String findingId = "nmt-compressed-class-space-pressure";
            String summaryText = classSpaceCommittedKb > 0L
                ? String.format(
                    Locale.ROOT,
                    "Compressed class space is using %.1f%% of committed memory (%dKB of %dKB)%s.",
                    committedUtilization * 100.0d,
                    classSpaceUsedKb,
                    classSpaceCommittedKb,
                    classSpaceReservedKb > 0L
                        ? String.format(Locale.ROOT, " and %.1f%% of reserved space", reservedUtilization * 100.0d)
                        : ""
                )
                : String.format(
                    Locale.ROOT,
                    "Compressed class space usage increased by %dKB between NMT snapshots.",
                    classSpaceUsedDeltaKb
                );
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Compressed class space pressure is elevated",
                summaryText,
                "memory.native.compressed-class-space",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "nmt-class-space-summary", "nmt-class-space-summary-delta", "nmt-class-summary"),
                "High compressed-class-space utilization or growth increases the risk of class-metadata allocation failure even when general heap pressure is not dominant."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-compressed-class-space-pressure",
                "Inspect class-loader churn and compressed class space headroom",
                "The NMT Class section shows compressed class space running close to its available headroom.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect dynamic class generation, proxy creation, redeploy or reload behavior, and class-loader churn in the same interval.",
                    "Capture a follow-up NMT snapshot or JFR class-loading view to see whether compressed class space keeps filling.",
                    "Review `CompressedClassSpaceSize` only as temporary mitigation until the class-growth source is understood."
                ),
                List.of(findingId)
            ));
        }

        if ("diff".equals(snapshotKind) && (classCountDelta >= 5_000L || classCommittedDeltaKb >= 8_192L || metaspaceUsedDeltaKb >= 8_192L)) {
            String findingId = "nmt-class-metadata-growth";
            SeverityLevel severity = (classCountDelta >= 20_000L || classCommittedDeltaKb >= 16_384L || metaspaceUsedDeltaKb >= 16_384L)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Class metadata is growing between NMT snapshots",
                "The NMT diff shows "
                    + describeGrowthSignals(classCountDelta, classCommittedDeltaKb, metaspaceUsedDeltaKb)
                    + ".",
                "memory.native.class-metadata",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "nmt-class-summary-delta", "nmt-category-delta-class", "nmt-metaspace-summary-delta", "nmt-class-space-summary-delta"),
                "Concurrent class-count and class-metadata growth is a strong indicator of expanding class metadata footprint."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-class-metadata-growth",
                "Investigate class loading and metaspace growth",
                "The NMT diff indicates that class metadata is expanding materially between snapshots.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Review dynamic class generation, proxy creation, and redeployment behavior in the same time window.",
                    "Capture class-loading statistics or another NMT diff to confirm whether class metadata keeps growing.",
                    "Inspect metaspace limits and class unloading behavior before increasing JVM limits."
                ),
                List.of(findingId)
            ));
        }

        Map<String, Long> codeCategory = categories.getOrDefault("Code", Map.of());
        Map<String, Long> codeCategoryDelta = categoryDeltas.getOrDefault("Code", Map.of());
        long codeCommittedKb = codeCategory.getOrDefault("committedKb", 0L);
        long codeCommittedDeltaKb = codeCategoryDelta.getOrDefault("committedKb", 0L);
        if (codeCommittedKb >= 16_384L || codeCommittedDeltaKb >= 8_192L) {
            String findingId = "nmt-code-cache-pressure";
            SeverityLevel severity = (codeCommittedKb >= 32_768L || codeCommittedDeltaKb >= 16_384L)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String summary = codeCommittedDeltaKb >= 8_192L
                ? String.format(
                    "The Code category grew by %sKB and now has %dKB committed native memory.",
                    formatSigned(codeCommittedDeltaKb),
                    codeCommittedKb
                )
                : String.format("The Code category has %dKB committed native memory.", codeCommittedKb);
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Code cache or compiled-code native footprint is elevated",
                summary,
                "memory.native.code-cache",
                severity,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "nmt-category-code", "nmt-category-delta-code"),
                "Large or growing Code-category allocations can indicate code cache pressure or an unusually heavy compiled-code footprint."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-code-cache-pressure",
                "Inspect code cache usage and compiler activity",
                "The NMT Code category is large enough to justify targeted code cache inspection.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Run jcmd <pid> Compiler.codecache to inspect code cache occupancy and sweeper activity.",
                    "Review ReservedCodeCacheSize, tiered compilation behavior, and any recent burst of generated or compiled code.",
                    "Capture another NMT snapshot if code-category memory is still growing."
                ),
                List.of(findingId)
            ));
        }

        Map<String, Long> gcCategory = categories.get("GC");
        if (gcCategory != null && gcCategory.getOrDefault("committedKb", 0L) >= 32_768L) {
            String findingId = "nmt-gc-native-pressure";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "GC native memory usage is elevated",
                String.format("The GC native memory category has %dKB committed.", gcCategory.get("committedKb")),
                "memory.native.gc",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "nmt-category-gc", "nmt-total"),
                "Elevated GC native memory can accompany heap pressure or aggressive collector activity."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-gc-native-pressure",
                "Correlate NMT GC usage with heap pressure and GC logs",
                "GC native memory is large enough to justify cross-artifact correlation.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare this NMT snapshot with GC pause behavior and heap occupancy.",
                    "Collect an NMT diff if only a single snapshot is available."
                ),
                List.of(findingId)
            ));
        }

        long threadCount = AssessmentSupport.longValue(threadSummary, "threadCount");
        long stackReservedKb = AssessmentSupport.longValue(threadSummary, "stackReservedKb");
        if (threadCount > 100 || stackReservedKb >= 32_768L) {
            String findingId = "nmt-thread-stack-pressure";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Thread stack native memory is elevated",
                String.format("Thread count is %d with %dKB reserved for stacks.", threadCount, stackReservedKb),
                "memory.native.threads",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "nmt-thread-summary", "nmt-category-thread"),
                "Large thread counts or stack reservations can consume significant native memory."
            ));
            actions.add(AssessmentSupport.action(
                "action-nmt-thread-stack-pressure",
                "Inspect thread growth and stack sizing",
                "Thread count or reserved stack memory is high enough to contribute meaningfully to native memory pressure.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Review recent thread creation spikes and pool sizing.",
                    "Check whether stack size settings are amplifying native memory use.",
                    "Capture a thread dump if the process is still live to identify the sources of thread growth."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private String describeGrowthSignals(long classCountDelta, long classCommittedDeltaKb, long metaspaceUsedDeltaKb) {
        List<String> parts = new ArrayList<>();
        if (classCountDelta != 0L) {
            parts.add("loaded class count " + formatSigned(classCountDelta));
        }
        if (classCommittedDeltaKb != 0L) {
            parts.add("Class committed memory " + formatSigned(classCommittedDeltaKb) + "KB");
        }
        if (metaspaceUsedDeltaKb != 0L) {
            parts.add("metaspace used " + formatSigned(metaspaceUsedDeltaKb) + "KB");
        }
        return parts.isEmpty() ? "class metadata growth" : String.join(", ", parts);
    }

    private List<String> evidenceIds(ParsedArtifact parsedArtifact, String... candidateIds) {
        Set<String> availableEvidenceIds = parsedArtifact.evidence().stream()
            .map(evidence -> evidence.id())
            .collect(Collectors.toSet());
        List<String> selected = new ArrayList<>();
        for (String candidateId : candidateIds) {
            if (candidateId != null && availableEvidenceIds.contains(candidateId)) {
                selected.add(candidateId);
            }
        }
        return List.copyOf(selected);
    }

    private DominantCategoryDelta strongestCommittedCategoryDelta(Map<String, Map<String, Long>> categoryDeltas) {
        String strongestCategory = null;
        long strongestDelta = 0L;

        for (Map.Entry<String, Map<String, Long>> entry : categoryDeltas.entrySet()) {
            long committedKb = entry.getValue().getOrDefault("committedKb", 0L);
            if (committedKb > strongestDelta) {
                strongestCategory = entry.getKey();
                strongestDelta = committedKb;
            }
        }

        if (strongestCategory == null) {
            return null;
        }

        return new DominantCategoryDelta(strongestCategory, strongestDelta);
    }

    private boolean shouldFlagInternalLikeNativeGrowth(
        String snapshotKind,
        InternalLikeNativeSummary internalLikeNativeSummary,
        double internalLikeShare,
        double internalLikeGrowthShare
    ) {
        if (internalLikeNativeSummary.committedDeltaKb() >= 16_384L
            && (internalLikeGrowthShare >= 0.35d || internalLikeNativeSummary.committedDeltaKb() >= 24_576L)) {
            return true;
        }
        return !"diff".equals(snapshotKind)
            && internalLikeNativeSummary.committedKb() >= 32_768L
            && internalLikeShare >= 0.20d;
    }

    private boolean shouldFlagReservedCommittedMismatch(NonHeapReservationSummary nonHeapReservationSummary, double nonHeapCommitRatio) {
        return nonHeapReservationSummary.reservedKb() >= 262_144L
            && nonHeapReservationSummary.gapKb() >= 196_608L
            && nonHeapReservationSummary.committedKb() > 0L
            && nonHeapCommitRatio <= 0.25d;
    }

    private InternalLikeNativeSummary summarizeInternalLikeNative(
        Map<String, Map<String, Long>> categories,
        Map<String, Map<String, Long>> categoryDeltas
    ) {
        long committedKb = 0L;
        long committedDeltaKb = 0L;
        List<String> categoriesPresent = new ArrayList<>();

        for (String categoryName : List.of("Internal", "Unknown", "Arena Chunk")) {
            long currentCommittedKb = categories.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L);
            long deltaCommittedKb = categoryDeltas.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L);
            if (currentCommittedKb > 0L || deltaCommittedKb != 0L) {
                categoriesPresent.add(categoryName);
            }
            committedKb += currentCommittedKb;
            committedDeltaKb += deltaCommittedKb;
        }

        return new InternalLikeNativeSummary(
            committedKb,
            committedDeltaKb,
            List.copyOf(categoriesPresent)
        );
    }

    private String describeCategoryCommittedBreakdown(
        Map<String, Map<String, Long>> categoryValues,
        List<String> categoryNames,
        boolean signed
    ) {
        List<String> parts = new ArrayList<>();
        for (String categoryName : categoryNames) {
            long committedKb = categoryValues.getOrDefault(categoryName, Map.of()).getOrDefault("committedKb", 0L);
            if (committedKb == 0L) {
                continue;
            }
            parts.add(categoryName + " " + (signed ? formatSigned(committedKb) : Long.toString(committedKb)) + "KB");
        }
        return String.join(", ", parts);
    }

    private List<String> internalLikeEvidenceIds(ParsedArtifact parsedArtifact, List<String> categoryNames) {
        List<String> candidateIds = new ArrayList<>(List.of("nmt-total", "nmt-total-delta"));
        for (String categoryName : categoryNames) {
            candidateIds.add(categoryEvidenceId("nmt-category", categoryName));
            candidateIds.add(categoryEvidenceId("nmt-category-delta", categoryName));
        }
        return evidenceIds(parsedArtifact, candidateIds.toArray(new String[0]));
    }

    private NonHeapReservationSummary summarizeNonHeapReservation(Map<String, Map<String, Long>> categories) {
        long reservedKb = 0L;
        long committedKb = 0L;
        List<CategoryGap> topGaps = categories.entrySet().stream()
            .filter(entry -> countsTowardReservationMismatch(entry.getKey()))
            .map(entry -> new CategoryGap(
                entry.getKey(),
                Math.max(0L, entry.getValue().getOrDefault("reservedKb", 0L) - entry.getValue().getOrDefault("committedKb", 0L))
            ))
            .filter(categoryGap -> categoryGap.gapKb() > 0L)
            .sorted((left, right) -> Long.compare(right.gapKb(), left.gapKb()))
            .limit(3)
            .toList();

        for (Map.Entry<String, Map<String, Long>> entry : categories.entrySet()) {
            if (!countsTowardReservationMismatch(entry.getKey())) {
                continue;
            }
            reservedKb += entry.getValue().getOrDefault("reservedKb", 0L);
            committedKb += entry.getValue().getOrDefault("committedKb", 0L);
        }

        String topGapSummary = topGaps.isEmpty()
            ? "no dominant reserved categories were parsed"
            : topGaps.stream()
                .map(categoryGap -> categoryGap.categoryName() + " " + categoryGap.gapKb() + "KB")
                .collect(Collectors.joining(", "));

        return new NonHeapReservationSummary(
            reservedKb,
            committedKb,
            Math.max(0L, reservedKb - committedKb),
            topGapSummary,
            topGaps.stream().map(CategoryGap::categoryName).toList()
        );
    }

    private List<String> reservationEvidenceIds(ParsedArtifact parsedArtifact, List<String> categoryNames) {
        List<String> candidateIds = new ArrayList<>(List.of("nmt-total"));
        for (String categoryName : categoryNames) {
            candidateIds.add(categoryEvidenceId("nmt-category", categoryName));
        }
        return evidenceIds(parsedArtifact, candidateIds.toArray(new String[0]));
    }

    private String categoryEvidenceId(String prefix, String categoryName) {
        return prefix + "-" + slugify(categoryName);
    }

    private boolean countsTowardReservationMismatch(String categoryName) {
        return !"Java Heap".equals(categoryName) && !"Metaspace".equals(categoryName);
    }

    private String slugify(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
    }

    private String formatSigned(long value) {
        return (value >= 0 ? "+" : "") + value;
    }

    private record InternalLikeNativeSummary(
        long committedKb,
        long committedDeltaKb,
        List<String> categoriesPresent
    ) { }

    private record NonHeapReservationSummary(
        long reservedKb,
        long committedKb,
        long gapKb,
        String topGapSummary,
        List<String> topGapCategories
    ) { }

    private record CategoryGap(String categoryName, long gapKb) { }

    private record DominantCategoryDelta(String categoryName, long committedKb) { }
}
