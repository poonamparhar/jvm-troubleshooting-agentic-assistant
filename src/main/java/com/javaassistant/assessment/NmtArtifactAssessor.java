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
                    "Review the Class, Thread, GC, and Internal category deltas to isolate the dominant source.",
                    "Capture another NMT diff to confirm whether the same categories continue to grow.",
                    "Correlate the same time window with pmap or heap evidence if the process is still available."
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
                evidenceIds(parsedArtifact, "nmt-class-summary-delta", "nmt-category-delta-class", "nmt-metaspace-summary-delta"),
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

    private String categoryEvidenceId(String prefix, String categoryName) {
        return prefix + "-" + slugify(categoryName);
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

    private record DominantCategoryDelta(String categoryName, long committedKb) { }
}
