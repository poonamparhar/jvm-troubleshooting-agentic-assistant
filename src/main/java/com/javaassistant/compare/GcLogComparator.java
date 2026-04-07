package com.javaassistant.compare;

import com.javaassistant.assessment.AssessmentResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structured baseline-vs-current comparison for GC logs.
 */
public class GcLogComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> baselineSummary = map(baselineParsed.extractedData().get("summary"));
        Map<String, Object> currentSummary = map(currentParsed.extractedData().get("summary"));
        if (!hasComparableGcData(baselineSummary) || !hasComparableGcData(currentSummary)) {
            missingData.add("GC comparison could not parse enough pause or cycle activity from one or both logs.");
            return new AssessmentResult(findings, actions, missingData);
        }

        Map<String, Object> baselinePressure = map(baselineParsed.extractedData().get("collectorPressureSummary"));
        Map<String, Object> currentPressure = map(currentParsed.extractedData().get("collectorPressureSummary"));
        Map<String, Object> baselineRecovery = map(baselineParsed.extractedData().get("recoverySummary"));
        Map<String, Object> currentRecovery = map(currentParsed.extractedData().get("recoverySummary"));
        Map<String, Object> baselineFailure = map(baselineParsed.extractedData().get("failureSummary"));
        Map<String, Object> currentFailure = map(currentParsed.extractedData().get("failureSummary"));
        Map<String, Object> baselineG1 = map(baselineParsed.extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> currentG1 = map(currentParsed.extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> baselineConcurrent = map(baselineParsed.extractedData().get("concurrentSummary"));
        Map<String, Object> currentConcurrent = map(currentParsed.extractedData().get("concurrentSummary"));
        Map<String, Object> baselinePauseBreakdown = map(baselineParsed.extractedData().get("pauseBreakdown"));
        Map<String, Object> currentPauseBreakdown = map(currentParsed.extractedData().get("pauseBreakdown"));

        String baselineCollector = collectorName(baselineParsed, baselinePressure);
        String currentCollector = collectorName(currentParsed, currentPressure);
        boolean sameCollector = hasText(baselineCollector)
            && hasText(currentCollector)
            && baselineCollector.equalsIgnoreCase(currentCollector);
        List<String> artifactPaths = List.of(path(baselineParsed), path(currentParsed));

        double baselineP95PauseMs = firstPositiveDouble(baselinePressure, "p95PauseMs", baselineSummary, "p95PauseMs");
        double currentP95PauseMs = firstPositiveDouble(currentPressure, "p95PauseMs", currentSummary, "p95PauseMs");
        double baselineMaxPauseMs = firstPositiveDouble(baselinePressure, "maxPauseMs", baselineSummary, "maxPauseMs");
        double currentMaxPauseMs = firstPositiveDouble(currentPressure, "maxPauseMs", currentSummary, "maxPauseMs");
        double baselineStopTheWorldOverheadPct = doubleValue(baselineSummary, "stopTheWorldOverheadPct");
        double currentStopTheWorldOverheadPct = doubleValue(currentSummary, "stopTheWorldOverheadPct");

        long baselineFullGcCount = firstPositiveLong(baselinePressure, "fullGcCount", baselineSummary, "fullGcCount");
        long currentFullGcCount = firstPositiveLong(currentPressure, "fullGcCount", currentSummary, "fullGcCount");
        double baselineMaxFullGcPauseMs = firstPositiveDouble(baselineSummary, "maxFullGcPauseMs", baselinePressure, "maxPauseMs");
        double currentMaxFullGcPauseMs = firstPositiveDouble(currentSummary, "maxFullGcPauseMs", currentPressure, "maxPauseMs");
        long baselineMetaspaceTriggeredFullGcCount = firstPositiveLong(
            baselinePressure,
            "metaspaceTriggeredFullGcCount",
            baselineSummary,
            "metaspaceTriggeredFullGcCount"
        );
        long currentMetaspaceTriggeredFullGcCount = firstPositiveLong(
            currentPressure,
            "metaspaceTriggeredFullGcCount",
            currentSummary,
            "metaspaceTriggeredFullGcCount"
        );

        double baselinePeakPostGcOccupancyRatio = firstPositiveDouble(
            baselinePressure,
            "peakPostGcOccupancyRatio",
            baselineRecovery,
            "peakPostGcOccupancyRatio",
            baselineSummary,
            "peakHeapOccupancyRatio"
        );
        double currentPeakPostGcOccupancyRatio = firstPositiveDouble(
            currentPressure,
            "peakPostGcOccupancyRatio",
            currentRecovery,
            "peakPostGcOccupancyRatio",
            currentSummary,
            "peakHeapOccupancyRatio"
        );
        long baselineNearCapacityAfterGcCount = firstPositiveLong(
            baselinePressure,
            "nearCapacityAfterGcCount",
            baselineRecovery,
            "nearCapacityAfterGcCount"
        );
        long currentNearCapacityAfterGcCount = firstPositiveLong(
            currentPressure,
            "nearCapacityAfterGcCount",
            currentRecovery,
            "nearCapacityAfterGcCount"
        );
        double baselineAveragePostGcOccupancyRatio = firstPositiveDouble(
            baselinePressure,
            "averagePostGcOccupancyRatio",
            baselineRecovery,
            "averagePostGcOccupancyRatio"
        );
        double currentAveragePostGcOccupancyRatio = firstPositiveDouble(
            currentPressure,
            "averagePostGcOccupancyRatio",
            currentRecovery,
            "averagePostGcOccupancyRatio"
        );

        if (hasText(baselineCollector) && hasText(currentCollector) && !sameCollector) {
            String findingId = "compare-gc-collector-change";
            SeverityLevel severity = currentP95PauseMs >= 250.0d || currentFullGcCount >= 2L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            findings.add(new Finding(
                findingId,
                "The GC collector changed between the compared logs",
                String.format(
                    Locale.ROOT,
                    "The baseline log uses %s while the current log uses %s, so collector-specific behavior changed across the comparison.",
                    baselineCollector,
                    currentCollector
                ),
                "compare.gc.collector-change",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "gc-pause-distribution", "gc-full-gc-summary"),
                "A collector change can materially alter pause profiles and recovery behavior even before workload or heap-pressure changes are considered."
            ));
            actions.add(new RecommendedAction(
                "action-compare-gc-collector-change",
                "Verify whether the collector change was intentional and still fits the workload",
                "The compared GC logs are not using the same collector.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Confirm whether JVM startup flags or deployment defaults changed between the two captures.",
                    "Treat collector-specific metric deltas cautiously and compare pause, occupancy, and full-GC behavior first.",
                    "If the collector changed unintentionally, restore the intended JVM configuration before chasing lower-signal tuning changes."
                ),
                List.of(findingId)
            ));
        }

        boolean pauseRegression = currentP95PauseMs > 0.0d && (
            (baselineP95PauseMs <= 0.0d && currentP95PauseMs >= 100.0d)
                || (baselineP95PauseMs > 0.0d
                    && currentP95PauseMs >= Math.max(100.0d, baselineP95PauseMs * 1.75d)
                    && currentP95PauseMs - baselineP95PauseMs >= 40.0d)
                || currentMaxPauseMs >= Math.max(250.0d, baselineMaxPauseMs + 100.0d)
                || currentStopTheWorldOverheadPct >= Math.max(5.0d, baselineStopTheWorldOverheadPct * 1.5d + 2.0d)
        );
        if (pauseRegression) {
            String findingId = "compare-gc-pause-regression";
            SeverityLevel severity = currentMaxPauseMs >= 1_000.0d
                || currentP95PauseMs >= 750.0d
                || currentStopTheWorldOverheadPct >= 20.0d
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            String baselineCause = firstNonBlank(
                stringValue(baselinePressure, "dominantPauseCauseByTotalPauseMs"),
                stringValue(baselinePauseBreakdown, "dominantPauseCauseByTotalPauseMs")
            );
            String currentCause = firstNonBlank(
                stringValue(currentPressure, "dominantPauseCauseByTotalPauseMs"),
                stringValue(currentPauseBreakdown, "dominantPauseCauseByTotalPauseMs")
            );
            String causeShift = hasText(currentCause)
                ? String.format(
                    Locale.ROOT,
                    " Dominant pause cause moved from %s to %s.",
                    hasText(baselineCause) ? baselineCause : "the baseline mix",
                    currentCause
                )
                : "";
            findings.add(new Finding(
                findingId,
                "GC pause severity increased between the compared logs",
                String.format(
                    Locale.ROOT,
                    "Pause behavior changed from p95 %s and max %s in baseline to p95 %s and max %s in current, with stop-the-world overhead moving from %s to %s.%s",
                    humanMs(baselineP95PauseMs),
                    humanMs(baselineMaxPauseMs),
                    humanMs(currentP95PauseMs),
                    humanMs(currentMaxPauseMs),
                    humanPercent(baselineStopTheWorldOverheadPct),
                    humanPercent(currentStopTheWorldOverheadPct),
                    causeShift
                ),
                "compare.gc.pause",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "gc-pause-distribution", "gc-longest-pause"),
                "A large rise in p95 or max pause time, especially together with more stop-the-world overhead, is direct evidence that the current GC behavior regressed."
            ));
            actions.add(new RecommendedAction(
                "action-compare-gc-pause-regression",
                "Inspect what changed between the two captures to increase pause severity",
                "The current GC log spends materially more time in expensive pauses than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Compare the dominant pause causes, full-GC behavior, and post-GC occupancy between the two logs before changing tuning flags.",
                    "Check whether workload, allocation rate, cache growth, or heap sizing changed between the baseline and current captures.",
                    "Correlate the pause regression with a heap histogram, JFR recording, or NMT snapshot from the same interval if available."
                ),
                List.of(findingId)
            ));
        }

        boolean fullGcRegression = currentFullGcCount > 0L && (
            (baselineFullGcCount == 0L && currentFullGcCount >= 2L)
                || currentFullGcCount >= baselineFullGcCount + 2L
                || (baselineMaxFullGcPauseMs > 0.0d && currentMaxFullGcPauseMs >= baselineMaxFullGcPauseMs + 100.0d)
                || (currentFullGcCount >= baselineFullGcCount + 1L && currentPeakPostGcOccupancyRatio >= 0.95d)
        );
        if (fullGcRegression) {
            String findingId = "compare-gc-full-gc-regression";
            SeverityLevel severity = currentFullGcCount >= 5L
                || currentMaxFullGcPauseMs >= 500.0d
                || currentPeakPostGcOccupancyRatio >= 0.98d
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            String metaspaceDetail = currentMetaspaceTriggeredFullGcCount > baselineMetaspaceTriggeredFullGcCount
                ? String.format(
                    Locale.ROOT,
                    " Metaspace-triggered full GCs also increased from %d to %d.",
                    baselineMetaspaceTriggeredFullGcCount,
                    currentMetaspaceTriggeredFullGcCount
                )
                : "";
            findings.add(new Finding(
                findingId,
                "Full-GC pressure increased between the compared logs",
                String.format(
                    Locale.ROOT,
                    "Full GC count changed from %d in baseline to %d in current, and the longest full-GC pause moved from %s to %s.%s",
                    baselineFullGcCount,
                    currentFullGcCount,
                    humanMs(baselineMaxFullGcPauseMs),
                    humanMs(currentMaxFullGcPauseMs),
                    metaspaceDetail
                ),
                "compare.gc.full-gc",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "gc-full-gc-summary", "gc-longest-pause"),
                "A larger number of full collections or materially longer full-GC pauses indicates that the JVM is relying more heavily on expensive stop-the-world recovery work."
            ));
            actions.add(new RecommendedAction(
                "action-compare-gc-full-gc-regression",
                "Treat the current log as a stronger heap- or metadata-pressure incident",
                "The current capture shows materially worse full-GC behavior than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Capture a heap histogram or heap dump if it is safe to do so and compare retained growth with the baseline period.",
                    "Review whether the dominant full-GC trigger is retained heap pressure, metadata growth, or a collector-specific failure mode.",
                    "Use the comparison to focus on what changed between captures rather than only increasing heap size or relaxing pause goals."
                ),
                List.of(findingId)
            ));
        }

        boolean headroomRegression = currentPeakPostGcOccupancyRatio > 0.0d && (
            (baselinePeakPostGcOccupancyRatio <= 0.0d && currentPeakPostGcOccupancyRatio >= 0.90d)
                || (currentPeakPostGcOccupancyRatio >= baselinePeakPostGcOccupancyRatio + 0.05d
                    && currentPeakPostGcOccupancyRatio >= 0.90d)
                || currentNearCapacityAfterGcCount >= baselineNearCapacityAfterGcCount + 2L
                || (baselineAveragePostGcOccupancyRatio > 0.0d
                    && currentAveragePostGcOccupancyRatio >= baselineAveragePostGcOccupancyRatio + 0.05d
                    && currentAveragePostGcOccupancyRatio >= 0.85d)
        );
        if (headroomRegression) {
            String findingId = "compare-gc-headroom-regression";
            SeverityLevel severity = currentPeakPostGcOccupancyRatio >= 0.98d || currentNearCapacityAfterGcCount >= 3L
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            findings.add(new Finding(
                findingId,
                "Post-GC heap headroom shrank between the compared logs",
                String.format(
                    Locale.ROOT,
                    "Peak post-GC occupancy moved from %s in baseline to %s in current, while near-capacity after-GC events changed from %d to %d.",
                    humanRatio(baselinePeakPostGcOccupancyRatio),
                    humanRatio(currentPeakPostGcOccupancyRatio),
                    baselineNearCapacityAfterGcCount,
                    currentNearCapacityAfterGcCount
                ),
                "compare.gc.headroom",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "gc-heap-occupancy-peak", "gc-pause-distribution"),
                "When more collections leave the heap near capacity, the current JVM has less safety margin and is more likely to fall into long pauses or full GC."
            ));
            actions.add(new RecommendedAction(
                "action-compare-gc-headroom-regression",
                "Investigate what increased the retained live set or reduced usable heap headroom",
                "The current GC log finishes collections with less breathing room than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Compare heap histograms, cache growth, and long-lived object retention between the baseline and current periods.",
                    "Review whether heap sizing, traffic shape, or allocation patterns changed enough to erase the previous recovery margin.",
                    "If the same pressure continues after GC, prioritize live-set reduction over cosmetic pause-target tuning."
                ),
                List.of(findingId)
            ));
        }

        String normalizedCurrentCollector = currentCollector == null ? "" : currentCollector.strip().toUpperCase(Locale.ROOT);
        if (sameCollector) {
            switch (normalizedCurrentCollector) {
                case "G1" -> addG1Findings(findings, actions, artifactPaths, currentParsed, baselineFailure, currentFailure, baselineG1, currentG1);
                case "CMS" -> addCmsFindings(findings, actions, artifactPaths, currentParsed, baselinePressure, currentPressure, baselineFailure, currentFailure, baselineConcurrent, currentConcurrent);
                case "SERIAL", "PARALLEL" ->
                    addStopTheWorldCollectorFindings(findings, actions, artifactPaths, currentParsed, normalizedCurrentCollector, baselinePressure, currentPressure, baselineRecovery, currentRecovery);
                case "ZGC" -> addZgcFindings(findings, actions, artifactPaths, currentParsed, baselinePressure, currentPressure, baselineSummary, currentSummary, baselineConcurrent, currentConcurrent);
                default -> {
                }
            }
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private void addG1Findings(
        List<Finding> findings,
        List<RecommendedAction> actions,
        List<String> artifactPaths,
        ParsedArtifact currentParsed,
        Map<String, Object> baselineFailure,
        Map<String, Object> currentFailure,
        Map<String, Object> baselineG1,
        Map<String, Object> currentG1
    ) {
        long evacuationFailureDelta = longValue(currentFailure, "evacuationFailurePauseCount") - longValue(baselineFailure, "evacuationFailurePauseCount");
        long toSpaceExhaustedDelta = longValue(currentFailure, "toSpaceExhaustedCount") - longValue(baselineFailure, "toSpaceExhaustedCount");
        long fullCompactionAttemptDelta = longValue(currentFailure, "fullCompactionAttemptCount") - longValue(baselineFailure, "fullCompactionAttemptCount");
        long lowReclaimHighRetentionDelta = longValue(currentG1, "lowReclaimHighRetentionFullGcCount") - longValue(baselineG1, "lowReclaimHighRetentionFullGcCount");
        if (evacuationFailureDelta <= 0L
            && toSpaceExhaustedDelta <= 0L
            && fullCompactionAttemptDelta <= 0L
            && lowReclaimHighRetentionDelta <= 0L) {
            return;
        }

        String findingId = "compare-gc-g1-distress-regression";
        SeverityLevel severity = toSpaceExhaustedDelta > 0L || fullCompactionAttemptDelta > 0L
            ? SeverityLevel.CRITICAL
            : SeverityLevel.HIGH;
        findings.add(new Finding(
            findingId,
            "G1 evacuation or compaction distress increased in the current log",
            String.format(
                Locale.ROOT,
                "Compared with baseline, evacuation-failure pauses changed by %+d, to-space exhaustion signals changed by %+d, full-compaction attempts changed by %+d, and low-reclaim high-retention full GCs changed by %+d.",
                evacuationFailureDelta,
                toSpaceExhaustedDelta,
                fullCompactionAttemptDelta,
                lowReclaimHighRetentionDelta
            ),
            "compare.gc.g1-distress",
            severity,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            artifactPaths,
            evidenceIds(currentParsed, "gc-full-gc-summary", "gc-longest-pause", "gc-heap-occupancy-peak"),
            "New evacuation failure, to-space exhaustion, or low-reclaim full compaction behavior is a strong sign that G1 is running out of workable headroom in the current capture."
        ));
        actions.add(new RecommendedAction(
            "action-compare-gc-g1-distress-regression",
            "Focus on what caused G1 to lose region headroom in the current period",
            "The current log shows stronger G1-specific distress than the baseline.",
            ActionType.INVESTIGATION,
            severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
            List.of(
                "Compare retained old-generation growth, humongous allocation pressure, and mixed-collection recovery between the two captures.",
                "Review whether the current workload introduced a burst of large objects, cache growth, or a live-set increase that G1 could not absorb.",
                "Treat heap sizing changes as secondary until you understand why G1 is now reaching evacuation failure or compaction."
            ),
            List.of(findingId)
        ));
    }

    private void addCmsFindings(
        List<Finding> findings,
        List<RecommendedAction> actions,
        List<String> artifactPaths,
        ParsedArtifact currentParsed,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineFailure,
        Map<String, Object> currentFailure,
        Map<String, Object> baselineConcurrent,
        Map<String, Object> currentConcurrent
    ) {
        long concurrentModeFailureDelta = longValue(currentFailure, "concurrentModeFailureCount") - longValue(baselineFailure, "concurrentModeFailureCount");
        long promotionFailedDelta = longValue(currentFailure, "promotionFailedCount") - longValue(baselineFailure, "promotionFailedCount");
        long concurrentPhaseCountDelta = longValue(currentConcurrent, "concurrentPhaseCount") - longValue(baselineConcurrent, "concurrentPhaseCount");
        double longestConcurrentPhaseDelta = doubleValue(currentConcurrent, "longestConcurrentPhaseMs") - doubleValue(baselineConcurrent, "longestConcurrentPhaseMs");
        if (concurrentModeFailureDelta <= 0L
            && promotionFailedDelta <= 0L
            && concurrentPhaseCountDelta <= 0L
            && longestConcurrentPhaseDelta <= 250.0d) {
            return;
        }

        String findingId = "compare-gc-cms-fallback-regression";
        SeverityLevel severity = concurrentModeFailureDelta > 0L || promotionFailedDelta > 0L
            ? SeverityLevel.CRITICAL
            : SeverityLevel.HIGH;
        findings.add(new Finding(
            findingId,
            "CMS fallback pressure increased in the current log",
            String.format(
                Locale.ROOT,
                "Compared with baseline, concurrent-mode-failure signals changed by %+d, promotion-failed signals changed by %+d, and the longest concurrent phase moved from %s to %s.",
                concurrentModeFailureDelta,
                promotionFailedDelta,
                humanMs(doubleValue(baselineConcurrent, "longestConcurrentPhaseMs")),
                humanMs(doubleValue(currentConcurrent, "longestConcurrentPhaseMs"))
            ),
            "compare.gc.cms-fallback",
            severity,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            artifactPaths,
            evidenceIds(currentParsed, "gc-full-gc-summary", "gc-longest-pause"),
            "More concurrent-mode failure or promotion-failed behavior indicates that CMS is falling behind and relying more heavily on fallback stop-the-world recovery."
        ));
        actions.add(new RecommendedAction(
            "action-compare-gc-cms-fallback-regression",
            "Treat the current CMS log as stronger old-generation or fragmentation pressure",
            "The current log shows more CMS fallback behavior than the baseline.",
            ActionType.INVESTIGATION,
            severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
            List.of(
                "Compare old-generation retention, promotion pressure, and concurrent-cycle progress between the two captures.",
                "Review whether fragmentation, allocation bursts, or a larger retained set explain why CMS is now falling back more often.",
                "If CMS settings changed, validate the trigger and occupancy thresholds before treating the issue as a pure workload shift."
            ),
            List.of(findingId)
        ));
    }

    private void addStopTheWorldCollectorFindings(
        List<Finding> findings,
        List<RecommendedAction> actions,
        List<String> artifactPaths,
        ParsedArtifact currentParsed,
        String collector,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineRecovery,
        Map<String, Object> currentRecovery
    ) {
        long maxFullGcStreakDelta = longValue(currentPressure, "maxFullGcStreak") - longValue(baselinePressure, "maxFullGcStreak");
        double averageFullPostGcOccupancyDelta = doubleValue(currentRecovery, "averageFullPostGcOccupancyRatio")
            - doubleValue(baselineRecovery, "averageFullPostGcOccupancyRatio");
        double averageReclaimedMbDelta = doubleValue(currentPressure, "averageReclaimedMb") - doubleValue(baselinePressure, "averageReclaimedMb");
        if (maxFullGcStreakDelta <= 1L && averageFullPostGcOccupancyDelta < 0.05d && averageReclaimedMbDelta <= 0.0d) {
            return;
        }

        String findingId = "compare-gc-stop-the-world-pressure-regression";
        SeverityLevel severity = maxFullGcStreakDelta > 1L || averageFullPostGcOccupancyDelta >= 0.08d
            ? SeverityLevel.HIGH
            : SeverityLevel.MEDIUM;
        findings.add(new Finding(
            findingId,
            collector + " full-GC pressure increased in the current log",
            String.format(
                Locale.ROOT,
                "Compared with baseline, the longest full-GC streak changed by %+d and average post-full-GC occupancy moved from %s to %s.",
                maxFullGcStreakDelta,
                humanRatio(doubleValue(baselineRecovery, "averageFullPostGcOccupancyRatio")),
                humanRatio(doubleValue(currentRecovery, "averageFullPostGcOccupancyRatio"))
            ),
            "compare.gc.stop-the-world",
            severity,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            artifactPaths,
            evidenceIds(currentParsed, "gc-full-gc-summary", "gc-heap-occupancy-peak"),
            "Longer full-GC streaks or fuller heaps after stop-the-world collection mean the current JVM is recovering less useful headroom than before."
        ));
        actions.add(new RecommendedAction(
            "action-compare-gc-stop-the-world-pressure-regression",
            "Investigate why the current " + collector + " run recovers less headroom",
            "The current stop-the-world collector run stays under heavier full-GC pressure than the baseline.",
            ActionType.INVESTIGATION,
            severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
            List.of(
                "Compare retained-data growth, old-generation saturation, and heap sizing between the two captures.",
                "Review whether the workload still fits the current collector choice and pause expectations.",
                "Use a heap histogram or JFR recording to understand what changed before adjusting only collector flags."
            ),
            List.of(findingId)
        ));
    }

    private void addZgcFindings(
        List<Finding> findings,
        List<RecommendedAction> actions,
        List<String> artifactPaths,
        ParsedArtifact currentParsed,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary,
        Map<String, Object> baselineConcurrent,
        Map<String, Object> currentConcurrent
    ) {
        long allocationStallCountDelta = firstPositiveLong(currentPressure, "allocationStallCount", currentSummary, "allocationStallCount")
            - firstPositiveLong(baselinePressure, "allocationStallCount", baselineSummary, "allocationStallCount");
        double maxAllocationStallMsDelta = firstPositiveDouble(currentPressure, "maxAllocationStallMs", currentSummary, "maxAllocationStallMs")
            - firstPositiveDouble(baselinePressure, "maxAllocationStallMs", baselineSummary, "maxAllocationStallMs");
        double totalAllocationStallMsDelta = firstPositiveDouble(currentPressure, "totalAllocationStallMs", currentSummary, "totalAllocationStallMs")
            - firstPositiveDouble(baselinePressure, "totalAllocationStallMs", baselineSummary, "totalAllocationStallMs");
        double longestConcurrentPhaseDelta = doubleValue(currentConcurrent, "longestConcurrentPhaseMs") - doubleValue(baselineConcurrent, "longestConcurrentPhaseMs");
        if (allocationStallCountDelta <= 0L && maxAllocationStallMsDelta < 5.0d && totalAllocationStallMsDelta < 10.0d && longestConcurrentPhaseDelta < 100.0d) {
            return;
        }

        String findingId = "compare-gc-zgc-stall-regression";
        SeverityLevel severity = allocationStallCountDelta > 0L || maxAllocationStallMsDelta >= 10.0d
            ? SeverityLevel.HIGH
            : SeverityLevel.MEDIUM;
        findings.add(new Finding(
            findingId,
            "ZGC allocation-stall pressure increased in the current log",
            String.format(
                Locale.ROOT,
                "Compared with baseline, allocation stalls changed by %+d, maximum stall time moved from %s to %s, and total stall time moved from %s to %s.",
                allocationStallCountDelta,
                humanMs(firstPositiveDouble(baselinePressure, "maxAllocationStallMs", baselineSummary, "maxAllocationStallMs")),
                humanMs(firstPositiveDouble(currentPressure, "maxAllocationStallMs", currentSummary, "maxAllocationStallMs")),
                humanMs(firstPositiveDouble(baselinePressure, "totalAllocationStallMs", baselineSummary, "totalAllocationStallMs")),
                humanMs(firstPositiveDouble(currentPressure, "totalAllocationStallMs", currentSummary, "totalAllocationStallMs"))
            ),
            "compare.gc.zgc-stalls",
            severity,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            artifactPaths,
            evidenceIds(currentParsed, "gc-allocation-stall-summary", "gc-pause-distribution"),
            "New or longer allocation stalls in ZGC are a strong sign that the current workload is running with less usable headroom than the baseline."
        ));
        actions.add(new RecommendedAction(
            "action-compare-gc-zgc-stall-regression",
            "Investigate what increased ZGC headroom pressure in the current run",
            "The current ZGC log shows more allocation-stall pressure than the baseline.",
            ActionType.INVESTIGATION,
            severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
            List.of(
                "Compare allocation rate, live-set size, and container or host memory headroom across the two captures.",
                "Review whether the current workload introduced burstier allocation or a larger retained set than the baseline.",
                "Correlate the stalls with JFR allocation and GC-cycle context before making low-level collector changes."
            ),
            List.of(findingId)
        ));
    }

    private boolean hasComparableGcData(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return false;
        }
        return longValue(summary, "pauseEventCount") > 0L
            || longValue(summary, "gcCycleCount") > 0L
            || longValue(summary, "allocationStallCount") > 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String collectorName(ParsedArtifact parsedArtifact, Map<String, Object> pressure) {
        String collector = stringValue(pressure, "collector");
        if (hasText(collector)) {
            return collector;
        }
        return parsedArtifact != null ? stringValue(parsedArtifact.extractedData().get("collector")) : "";
    }

    private long firstPositiveLong(
        Map<String, Object> firstSource,
        String firstKey,
        Map<String, Object> secondSource,
        String secondKey
    ) {
        long value = longValue(firstSource, firstKey);
        if (value > 0L) {
            return value;
        }
        return longValue(secondSource, secondKey);
    }

    private double firstPositiveDouble(
        Map<String, Object> firstSource,
        String firstKey,
        Map<String, Object> secondSource,
        String secondKey
    ) {
        double value = doubleValue(firstSource, firstKey);
        if (value > 0.0d) {
            return value;
        }
        return doubleValue(secondSource, secondKey);
    }

    private double firstPositiveDouble(
        Map<String, Object> firstSource,
        String firstKey,
        Map<String, Object> secondSource,
        String secondKey,
        Map<String, Object> thirdSource,
        String thirdKey
    ) {
        double value = firstPositiveDouble(firstSource, firstKey, secondSource, secondKey);
        if (value > 0.0d) {
            return value;
        }
        return doubleValue(thirdSource, thirdKey);
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double doubleValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return stringValue(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : "";
    }

    private String humanMs(double value) {
        if (value <= 0.0d) {
            return "0.0ms";
        }
        return String.format(Locale.ROOT, "%.1fms", value);
    }

    private String humanPercent(double value) {
        if (value <= 0.0d) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private String humanRatio(double ratio) {
        if (ratio <= 0.0d) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0d);
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

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
