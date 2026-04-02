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
                    "Compare Class, Thread, GC, and Internal category deltas to isolate the dominant source.",
                    "Capture another NMT snapshot or detail.diff to confirm whether the same categories continue to grow.",
                    "Correlate the growth with pmap and heap histogram snapshots from the same time window."
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

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
