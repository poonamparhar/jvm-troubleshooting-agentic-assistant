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

public class JfrComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.JFR;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> baselineSummary = map(baselineParsed.extractedData().get("summary"));
        Map<String, Object> currentSummary = map(currentParsed.extractedData().get("summary"));
        long baselineEventCount = longValue(baselineSummary, "eventCount");
        long currentEventCount = longValue(currentSummary, "eventCount");
        if (baselineEventCount == 0L || currentEventCount == 0L) {
            missingData.add("JFR comparison could not parse events from one or both recordings.");
            return new AssessmentResult(findings, actions, missingData);
        }

        long baselineDurationMs = safeDurationMs(longValue(baselineSummary, "durationMs"));
        long currentDurationMs = safeDurationMs(longValue(currentSummary, "durationMs"));

        Map<String, Object> baselineLock = map(baselineParsed.extractedData().get("lockSummary"));
        Map<String, Object> currentLock = map(currentParsed.extractedData().get("lockSummary"));
        Map<String, Object> baselineGc = map(baselineParsed.extractedData().get("gcSummary"));
        Map<String, Object> currentGc = map(currentParsed.extractedData().get("gcSummary"));
        Map<String, Object> baselineExecutionHotspots = map(baselineParsed.extractedData().get("executionHotspotSummary"));
        Map<String, Object> currentExecutionHotspots = map(currentParsed.extractedData().get("executionHotspotSummary"));
        Map<String, Object> baselineAllocation = map(baselineParsed.extractedData().get("allocationFieldSummary"));
        Map<String, Object> currentAllocation = map(currentParsed.extractedData().get("allocationFieldSummary"));
        Map<String, Object> baselineOldObject = map(baselineParsed.extractedData().get("oldObjectFieldSummary"));
        Map<String, Object> currentOldObject = map(currentParsed.extractedData().get("oldObjectFieldSummary"));
        List<String> artifactPaths = List.of(path(baselineParsed), path(currentParsed));

        long baselineLockCount = longValue(baselineLock, "eventCount");
        long currentLockCount = longValue(currentLock, "eventCount");
        long baselineLockTotalMs = longValue(baselineLock, "totalDurationMs");
        long currentLockTotalMs = longValue(currentLock, "totalDurationMs");
        long baselineLockMaxMs = longValue(baselineLock, "maxDurationMs");
        long currentLockMaxMs = longValue(currentLock, "maxDurationMs");
        double baselineLockRate = eventRatePerMinute(baselineLockCount, baselineDurationMs);
        double currentLockRate = eventRatePerMinute(currentLockCount, currentDurationMs);
        double baselineLockShare = durationShare(baselineLockTotalMs, baselineDurationMs);
        double currentLockShare = durationShare(currentLockTotalMs, currentDurationMs);
        boolean lockRegressed = currentLockCount >= 2L && (
            (baselineLockCount == 0L && (currentLockTotalMs >= 250L || currentLockMaxMs >= 150L))
                || (baselineLockCount > 0L && currentLockRate >= baselineLockRate * 1.5d && currentLockRate - baselineLockRate >= 1.0d)
                || (baselineLockTotalMs > 0L && currentLockShare >= Math.max(0.03d, baselineLockShare * 1.5d) && currentLockShare - baselineLockShare >= 0.02d)
                || (baselineLockTotalMs > 0L
                    && currentLockTotalMs >= baselineLockTotalMs + 150L
                    && currentLockTotalMs >= baselineLockTotalMs * 2L)
                || currentLockMaxMs >= Math.max(100L, baselineLockMaxMs + 50L)
        );
        if (lockRegressed) {
            String findingId = "compare-jfr-lock-contention-regression";
            SeverityLevel severity = currentLockMaxMs >= 500L || currentLockTotalMs >= 1_000L || currentLockShare >= 0.10d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(new Finding(
                findingId,
                "Lock contention increased between the compared JFR recordings",
                String.format(
                    Locale.ROOT,
                    "Monitor-blocked activity increased from %d event(s), %s total blocked time, and %s max blocked time in baseline to %d event(s), %s total blocked time, and %s max blocked time in current.",
                    baselineLockCount,
                    humanDuration(baselineLockTotalMs),
                    humanDuration(baselineLockMaxMs),
                    currentLockCount,
                    humanDuration(currentLockTotalMs),
                    humanDuration(currentLockMaxMs)
                ),
                "compare.jfr.lock-contention",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-lock-summary", "jfr-recording-summary"),
                "Growing monitor-blocked rates and blocked-time share across recordings are stronger evidence of worsening lock contention than one isolated blocked event."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-lock-contention-regression",
                "Inspect what changed between recordings to increase monitor-blocked time",
                "The current JFR recording shows materially more lock contention than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.JavaMonitorBlocked <current.jfr>` to inspect the dominant contended monitors and stacks in the newer recording.",
                    "Compare the newer blocked stacks with a thread dump or another recording from the same interval to identify the owning code path.",
                    "Review synchronized sections, lock striping, or concurrency changes made between the two captures before raising worker counts."
                ),
                List.of(findingId)
            ));
        }

        long baselineGcCount = longValue(baselineGc, "eventCount");
        long currentGcCount = longValue(currentGc, "eventCount");
        long baselineGcTotalMs = longValue(baselineGc, "totalDurationMs");
        long currentGcTotalMs = longValue(currentGc, "totalDurationMs");
        long baselineGcMaxMs = longValue(baselineGc, "maxDurationMs");
        long currentGcMaxMs = longValue(currentGc, "maxDurationMs");
        double baselineGcShare = durationShare(baselineGcTotalMs, baselineDurationMs);
        double currentGcShare = durationShare(currentGcTotalMs, currentDurationMs);
        boolean gcRegressed = currentGcCount > 0L && (
            (baselineGcCount == 0L && (currentGcTotalMs >= 300L || currentGcMaxMs >= 200L))
                || (baselineGcTotalMs > 0L && currentGcShare >= Math.max(0.04d, baselineGcShare * 1.5d) && currentGcShare - baselineGcShare >= 0.02d)
                || (baselineGcTotalMs > 0L && currentGcTotalMs >= baselineGcTotalMs + 250L && currentGcTotalMs >= baselineGcTotalMs * 3L / 2L)
                || currentGcMaxMs >= Math.max(250L, baselineGcMaxMs + 100L)
        );
        if (gcRegressed) {
            String findingId = "compare-jfr-gc-pause-regression";
            SeverityLevel severity = currentGcMaxMs >= 1_000L || currentGcTotalMs >= 1_500L || currentGcShare >= 0.12d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(new Finding(
                findingId,
                "GC pause activity increased between the compared JFR recordings",
                String.format(
                    Locale.ROOT,
                    "GC pause activity changed from %d event(s), %s total pause time, and %s max pause in baseline to %d event(s), %s total pause time, and %s max pause in current.",
                    baselineGcCount,
                    humanDuration(baselineGcTotalMs),
                    humanDuration(baselineGcMaxMs),
                    currentGcCount,
                    humanDuration(currentGcTotalMs),
                    humanDuration(currentGcMaxMs)
                ),
                "compare.jfr.gc-pause",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-gc-summary", "jfr-recording-summary"),
                "A larger pause-time share or longer max GC pause in the newer recording is direct evidence that GC behavior regressed between captures."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-gc-pause-regression",
                "Inspect what changed between recordings to increase GC pause time",
                "The newer JFR recording spends materially more time in GC pauses than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.GarbageCollection,jdk.GCPhasePause <current.jfr>` to inspect the longer pauses in the newer recording.",
                    "Compare heap pressure, allocation churn, and old-object signals between the two recordings to isolate why GC worsened.",
                    "Check whether collector settings, traffic shape, or allocation-heavy code changed between the baseline and current captures."
                ),
                List.of(findingId)
            ));
        }

        String baselineExecutionMethod = stringValue(baselineExecutionHotspots, "topMethod");
        String currentExecutionMethod = stringValue(currentExecutionHotspots, "topMethod");
        long currentExecutionMethodCount = longValue(currentExecutionHotspots, "topMethodCount");
        long currentExecutionStackCount = longValue(currentExecutionHotspots, "stackEventCount");
        double baselineExecutionShare = doubleValue(baselineExecutionHotspots, "topMethodShare");
        double currentExecutionShare = doubleValue(currentExecutionHotspots, "topMethodShare");
        if (currentExecutionMethod != null
            && currentExecutionMethodCount >= 3L
            && currentExecutionShare >= 0.60d
            && (baselineExecutionMethod == null
                || !currentExecutionMethod.equals(baselineExecutionMethod)
                || currentExecutionShare >= baselineExecutionShare + 0.20d)) {
            String findingId = "compare-jfr-execution-hot-path-shift";
            boolean shifted = baselineExecutionMethod != null && !currentExecutionMethod.equals(baselineExecutionMethod);
            SeverityLevel severity = currentExecutionShare >= 0.80d || currentExecutionStackCount >= 8L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String summary = shifted
                ? String.format(
                    Locale.ROOT,
                    "Execution samples moved from %s (%.0f%% share) in baseline to %s (%.0f%% share) in current.",
                    baselineExecutionMethod,
                    baselineExecutionShare * 100.0d,
                    currentExecutionMethod,
                    currentExecutionShare * 100.0d
                )
                : String.format(
                    Locale.ROOT,
                    "Execution samples concentrate more heavily in %s, rising from %.0f%% share in baseline to %.0f%% in current.",
                    currentExecutionMethod,
                    baselineExecutionShare * 100.0d,
                    currentExecutionShare * 100.0d
                );
            findings.add(new Finding(
                findingId,
                shifted
                    ? "The dominant JFR execution hot path shifted between recordings"
                    : "The current JFR recording concentrates more heavily in one execution hot path",
                summary,
                "compare.jfr.hot-path.execution",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-execution-hotspots", "jfr-recording-summary"),
                "A hot-path change or stronger concentration in the newer recording is a strong hint that the performance bottleneck moved or intensified between captures."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-execution-hot-path-shift",
                "Inspect what changed in the dominant sampled execution path",
                "The newer recording points at a different or more concentrated execution hot path than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.ExecutionSample <current.jfr>` to inspect the dominant sampled stacks in the newer recording.",
                    "Compare the baseline and current hot methods with recent code changes, request mix changes, or dependency behavior.",
                    "Treat the shifted hot path as a lead for CPU or throughput regression before tuning unrelated JVM settings."
                ),
                List.of(findingId)
            ));
        }

        long baselineAllocationCount = longValue(baselineAllocation, "eventCount");
        long currentAllocationCount = longValue(currentAllocation, "eventCount");
        long baselineAllocatedBytes = longValue(baselineAllocation, "totalAllocatedBytes");
        long currentAllocatedBytes = longValue(currentAllocation, "totalAllocatedBytes");
        String baselineAllocationClass = stringValue(baselineAllocation, "topClass");
        String currentAllocationClass = stringValue(currentAllocation, "topClass");
        double baselineAllocationClassShare = topShare(baselineAllocation, "topClassAllocatedByteShare", "topClassEventShare");
        double currentAllocationClassShare = topShare(currentAllocation, "topClassAllocatedByteShare", "topClassEventShare");
        double baselineAllocationRate = valueRatePerSecond(baselineAllocatedBytes, baselineDurationMs);
        double currentAllocationRate = valueRatePerSecond(currentAllocatedBytes, currentDurationMs);
        boolean allocationRegressed = currentAllocationCount >= 4L && (
            (baselineAllocationCount == 0L && currentAllocatedBytes >= 1_000_000L)
                || (baselineAllocatedBytes > 0L
                    && currentAllocatedBytes >= baselineAllocatedBytes + 500_000L
                    && currentAllocationRate >= baselineAllocationRate * 1.5d)
                || (currentAllocationClass != null
                    && !currentAllocationClass.equals(baselineAllocationClass)
                    && currentAllocationClassShare >= 0.60d)
        );
        if (allocationRegressed) {
            String findingId = "compare-jfr-allocation-regression";
            SeverityLevel severity = currentAllocatedBytes >= 4_000_000L || currentAllocationClassShare >= 0.80d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String classShift = "";
            if (currentAllocationClass != null) {
                if (baselineAllocationClass != null && !currentAllocationClass.equals(baselineAllocationClass)) {
                    classShift = String.format(
                        Locale.ROOT,
                        " The dominant allocating class shifted from %s to %s.",
                        baselineAllocationClass,
                        currentAllocationClass
                    );
                } else {
                    classShift = String.format(Locale.ROOT, " %s leads the current allocation activity.", currentAllocationClass);
                }
            }
            findings.add(new Finding(
                findingId,
                "Allocation pressure increased between the compared JFR recordings",
                String.format(
                    Locale.ROOT,
                    "Attributed allocation volume changed from about %s across %d event(s) in baseline to about %s across %d event(s) in current.%s",
                    humanBytes(baselineAllocatedBytes),
                    baselineAllocationCount,
                    humanBytes(currentAllocatedBytes),
                    currentAllocationCount,
                    classShift
                ),
                "compare.jfr.allocation",
                severity,
                currentAllocatedBytes > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-allocation-field-summary", "jfr-allocation-hotspots", "jfr-recording-summary"),
                "A higher allocation rate or a new dominant allocating class in the newer recording is direct evidence that allocation churn increased between captures."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-allocation-regression",
                "Inspect what changed between recordings to increase allocation churn",
                "The current JFR recording attributes more allocation pressure than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ObjectAllocationSample <current.jfr>` to inspect the heavier allocation paths in the newer recording.",
                    "Compare the dominant allocating class and stack path with heap or class histograms from the same incident interval.",
                    "Treat the changed allocation class or path as a stronger regression lead than broad GC tuning."
                ),
                List.of(findingId)
            ));
        }

        long baselineOldObjectCount = longValue(baselineOldObject, "eventCount");
        long currentOldObjectCount = longValue(currentOldObject, "eventCount");
        long baselineOldObjectBytes = longValue(baselineOldObject, "totalSampledObjectBytes");
        long currentOldObjectBytes = longValue(currentOldObject, "totalSampledObjectBytes");
        long baselineOldObjectMaxAgeMs = longValue(baselineOldObject, "maxObjectAgeMs");
        long currentOldObjectMaxAgeMs = longValue(currentOldObject, "maxObjectAgeMs");
        String baselineOldObjectClass = stringValue(baselineOldObject, "topClass");
        String currentOldObjectClass = stringValue(currentOldObject, "topClass");
        double baselineOldObjectClassShare = topShare(baselineOldObject, "topClassSampledObjectByteShare", "topClassEventShare");
        double currentOldObjectClassShare = topShare(currentOldObject, "topClassSampledObjectByteShare", "topClassEventShare");
        boolean oldObjectRegressed = currentOldObjectCount >= 2L && (
            (baselineOldObjectCount == 0L && (currentOldObjectBytes >= 512_000L || currentOldObjectMaxAgeMs >= 60_000L))
                || (baselineOldObjectBytes > 0L
                    && currentOldObjectBytes >= baselineOldObjectBytes + 256_000L
                    && currentOldObjectBytes >= baselineOldObjectBytes * 3L / 2L)
                || currentOldObjectMaxAgeMs >= Math.max(60_000L, baselineOldObjectMaxAgeMs + 60_000L)
                || (currentOldObjectClass != null
                    && !currentOldObjectClass.equals(baselineOldObjectClass)
                    && currentOldObjectClassShare >= 0.60d)
        );
        if (oldObjectRegressed) {
            String findingId = "compare-jfr-old-object-growth";
            SeverityLevel severity = currentOldObjectBytes >= 2_000_000L || currentOldObjectMaxAgeMs >= 300_000L || currentOldObjectCount >= 4L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String classShift = "";
            if (currentOldObjectClass != null) {
                if (baselineOldObjectClass != null && !currentOldObjectClass.equals(baselineOldObjectClass)) {
                    classShift = String.format(
                        Locale.ROOT,
                        " The dominant old-object class shifted from %s to %s.",
                        baselineOldObjectClass,
                        currentOldObjectClass
                    );
                } else {
                    classShift = String.format(Locale.ROOT, " %s dominates the newer old-object samples.", currentOldObjectClass);
                }
            }
            findings.add(new Finding(
                findingId,
                "Long-lived old-object candidates increased between the compared JFR recordings",
                String.format(
                    Locale.ROOT,
                    "Old-object sampling changed from %d sample(s), about %s of sampled old-object size, and max age %s in baseline to %d sample(s), about %s, and max age %s in current.%s",
                    baselineOldObjectCount,
                    humanBytes(baselineOldObjectBytes),
                    humanDuration(baselineOldObjectMaxAgeMs),
                    currentOldObjectCount,
                    humanBytes(currentOldObjectBytes),
                    humanDuration(currentOldObjectMaxAgeMs),
                    classShift
                ),
                "compare.jfr.old-object",
                severity,
                currentOldObjectBytes > 0L && currentOldObjectMaxAgeMs > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-old-object-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "A larger population of sampled old objects or substantially older samples in the newer recording is strong evidence that long-lived retention candidates increased."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-old-object-growth",
                "Inspect what changed between recordings to create more long-lived objects",
                "The newer recording contains stronger old-object signals than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.OldObjectSample <current.jfr>` to inspect the dominant classes, ages, and root hints in the newer recording.",
                    "Compare the newer long-lived classes with heap histograms or class histograms from the same interval.",
                    "If the same class keeps dominating, capture a heap dump and inspect dominators or retained paths for that class."
                ),
                List.of(findingId)
            ));
        }

        long baselineDepthEvents = longValue(baselineOldObject, "depthEventCount");
        long currentDepthEvents = longValue(currentOldObject, "depthEventCount");
        long baselineMaxDepth = longValue(baselineOldObject, "maxReferenceDepth");
        long currentMaxDepth = longValue(currentOldObject, "maxReferenceDepth");
        double baselineAverageDepth = doubleValue(baselineOldObject, "averageReferenceDepth");
        double currentAverageDepth = doubleValue(currentOldObject, "averageReferenceDepth");
        String currentRootType = stringValue(currentOldObject, "topRootType");
        String currentRootSystem = stringValue(currentOldObject, "topRootSystem");
        boolean depthRegressed = currentDepthEvents > 0L && (
            (baselineDepthEvents == 0L && currentMaxDepth >= 4L)
                || (currentMaxDepth >= Math.max(4L, baselineMaxDepth + 2L))
                || (currentAverageDepth >= 2.5d && currentAverageDepth >= baselineAverageDepth + 1.0d)
        );
        if (depthRegressed) {
            String findingId = "compare-jfr-old-object-depth-regression";
            SeverityLevel severity = currentMaxDepth >= 8L || currentAverageDepth >= 4.0d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String rootHint = "";
            if (currentRootType != null || currentRootSystem != null) {
                rootHint = String.format(
                    Locale.ROOT,
                    " The current root hints point to %s%s.",
                    currentRootSystem != null ? currentRootSystem : "",
                    currentRootType != null ? (currentRootSystem != null ? "/" : "") + currentRootType : ""
                );
            }
            findings.add(new Finding(
                findingId,
                "Old-object reference depth increased between the compared JFR recordings",
                String.format(
                    Locale.ROOT,
                    "Old-object reference depth changed from max %d and average %.1f in baseline to max %d and average %.1f in current.%s",
                    baselineMaxDepth,
                    baselineAverageDepth,
                    currentMaxDepth,
                    currentAverageDepth,
                    rootHint
                ),
                "compare.jfr.old-object.depth",
                severity,
                currentDepthEvents > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                artifactPaths,
                evidenceIds(currentParsed, "jfr-old-object-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "Deeper reference chains in the newer recording suggest retention is happening through a more involved object graph or a GC-root path that was not present in the baseline."
            ));
            actions.add(new RecommendedAction(
                "action-compare-jfr-old-object-depth-regression",
                "Inspect the newer GC-root paths behind the deeper old-object samples",
                "The newer recording shows deeper retained-object chains than the baseline.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.OldObjectSample <current.jfr>` to inspect the deeper referrer chains and GC roots in the newer recording.",
                    "If the path is still unclear, capture a heap dump and inspect dominator trees for the dominant old-object class.",
                    "Enable path-to-gc-roots on future recordings if you need fuller retained-path detail than the current recording provides."
                ),
                List.of(findingId)
            ));
        }

        if (baselineOldObjectCount == 0L || currentOldObjectCount == 0L) {
            missingData.add("JFR comparison could not assess old-object deltas because one or both recordings lack old-object samples.");
        }
        if (baselineAllocationCount == 0L || currentAllocationCount == 0L) {
            missingData.add("JFR comparison could not assess allocation deltas because one or both recordings lack allocation events.");
        }
        if (baselineExecutionMethod == null || currentExecutionMethod == null) {
            missingData.add("JFR comparison could not assess execution hot-path shifts because one or both recordings lack execution-sample hotspots.");
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private List<String> evidenceIds(ParsedArtifact artifact, String... candidateIds) {
        Set<String> availableEvidenceIds = artifact.evidence().stream()
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
        return value != null ? String.valueOf(value) : null;
    }

    private long safeDurationMs(long durationMs) {
        return Math.max(durationMs, 1L);
    }

    private double eventRatePerMinute(long value, long durationMs) {
        return durationMs > 0L ? value * 60_000.0d / (double) durationMs : (double) value;
    }

    private double valueRatePerSecond(long value, long durationMs) {
        return durationMs > 0L ? value * 1_000.0d / (double) durationMs : (double) value;
    }

    private double durationShare(long totalDurationMs, long recordingDurationMs) {
        return recordingDurationMs > 0L ? (double) totalDurationMs / (double) recordingDurationMs : 0.0d;
    }

    private double topShare(Map<String, Object> source, String byteShareKey, String eventShareKey) {
        double byteShare = doubleValue(source, byteShareKey);
        return byteShare > 0.0d ? byteShare : doubleValue(source, eventShareKey);
    }

    private String humanDuration(long durationMs) {
        if (durationMs >= 1_000L) {
            return String.format(Locale.ROOT, "%.2fs", durationMs / 1_000.0d);
        }
        return durationMs + "ms";
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0d);
        }
        if (bytes >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / 1_048_576.0d);
        }
        if (bytes >= 1_024L) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / 1_024.0d);
        }
        return bytes + " B";
    }

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
