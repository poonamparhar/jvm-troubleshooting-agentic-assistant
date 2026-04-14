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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ThreadDumpComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.THREAD_DUMP;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        long baselineThreadCount = longValue(baselineParsed.extractedData(), "threadCount");
        long currentThreadCount = longValue(currentParsed.extractedData(), "threadCount");
        if (baselineThreadCount == 0L || currentThreadCount == 0L) {
            missingData.add("Thread-dump comparison could not parse thread entries from one or both files.");
            return new AssessmentResult(findings, actions, missingData);
        }

        Map<String, Object> baselineDeadlock = mapValue(baselineParsed.extractedData().get("deadlock"));
        Map<String, Object> currentDeadlock = mapValue(currentParsed.extractedData().get("deadlock"));
        boolean baselineDeadlockDetected = boolValue(baselineDeadlock, "detected");
        boolean currentDeadlockDetected = boolValue(currentDeadlock, "detected");

        HotspotChange hotspotChange = strongestHotspotChange(
            mapList(baselineParsed.extractedData().get("contentionHotspots")),
            mapList(currentParsed.extractedData().get("contentionHotspots"))
        );
        PoolChange poolChange = strongestPoolChange(
            mapList(baselineParsed.extractedData().get("poolSummaries")),
            mapList(currentParsed.extractedData().get("poolSummaries"))
        );

        if (!baselineDeadlockDetected && currentDeadlockDetected) {
            String findingId = "compare-thread-dump-deadlock-appeared";
            findings.add(new Finding(
                findingId,
                "A Java-level deadlock appeared between thread dumps",
                String.format(
                    "The baseline dump reported no JVM deadlock, but the current dump reports %d Java-level deadlock(s)%s.",
                    longValue(currentDeadlock, "reportedDeadlockCount") > 0L ? longValue(currentDeadlock, "reportedDeadlockCount") : 1L,
                    threadNamesSuffix(stringList(currentDeadlock.get("threadNames")))
                ),
                "compare.thread-dump-deadlock",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "thread-dump-deadlock", "thread-dump-summary"),
                "A JVM-reported deadlock in the newer snapshot is direct evidence that a lock cycle emerged between the compared captures."
            ));
            actions.add(new RecommendedAction(
                "action-compare-thread-dump-deadlock-appeared",
                "Treat the current snapshot as an active deadlock incident",
                "The newer thread dump reports a JVM-detected deadlock that was not present in the baseline snapshot.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Capture another thread dump a few seconds later to confirm the same threads remain deadlocked.",
                    "Inspect the deadlocked threads and locked monitors to identify the code path creating the lock cycle.",
                    "If the service is wedged, coordinate mitigation only after preserving the matching thread-dump evidence."
                ),
                List.of(findingId)
            ));
        }

        if (baselineDeadlockDetected && !currentDeadlockDetected) {
            String findingId = "compare-thread-dump-deadlock-resolved";
            findings.add(new Finding(
                findingId,
                "A previously reported Java-level deadlock is absent in the current thread dump",
                String.format(
                    "The baseline dump reported %d Java-level deadlock(s)%s, but the current dump no longer contains a JVM deadlock section.",
                    longValue(baselineDeadlock, "reportedDeadlockCount") > 0L ? longValue(baselineDeadlock, "reportedDeadlockCount") : 1L,
                    threadNamesSuffix(stringList(baselineDeadlock.get("threadNames")))
                ),
                "compare.thread-dump-deadlock",
                SeverityLevel.LOW,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "thread-dump-summary"),
                "The missing deadlock marker in the newer snapshot is strong evidence that the specific JVM-reported lock cycle cleared between captures."
            ));
            actions.add(new RecommendedAction(
                "action-compare-thread-dump-deadlock-resolved",
                "Confirm the cleared deadlock stays resolved under live traffic",
                "The current thread dump no longer shows the deadlock that was present in the baseline snapshot.",
                ActionType.DATA_COLLECTION,
                ActionPriority.MEDIUM,
                List.of(
                    "Capture another thread dump under similar load to confirm the same deadlock does not reappear.",
                    "Verify request throughput and latency recovered instead of shifting to another stall pattern.",
                    "Review whether any mitigation or rollback between the two captures explains the improvement."
                ),
                List.of(findingId)
            ));
        }

        if (hotspotChange != null) {
            String findingId = "compare-thread-dump-contention-hotspot";
            boolean emergent = hotspotChange.baselineWaiterCount() == 0L;
            findings.add(new Finding(
                findingId,
                emergent
                    ? "A new lock-contention hotspot appeared between thread dumps"
                    : "A shared-lock contention hotspot intensified between thread dumps",
                String.format(
                    "Monitor <%s> moved from %d waiter(s) with %d BLOCKED in the baseline dump to %d waiter(s) with %d BLOCKED in the current dump%s",
                    hotspotChange.monitorId(),
                    hotspotChange.baselineWaiterCount(),
                    hotspotChange.baselineBlockedWaiterCount(),
                    hotspotChange.currentWaiterCount(),
                    hotspotChange.currentBlockedWaiterCount(),
                    ownerSuffix(hotspotChange.ownerThreadName(), hotspotChange.ownerState())
                ),
                "compare.thread-dump-lock-contention",
                hotspotChange.currentBlockedWaiterCount() >= 3L || hotspotChange.currentWaiterCount() >= 4L
                    ? SeverityLevel.HIGH
                    : SeverityLevel.MEDIUM,
                hotspotChange.ownerThreadName() != null || hotspotChange.currentBlockedWaiterCount() >= 2L
                    ? ConfidenceLevel.HIGH
                    : ConfidenceLevel.MEDIUM,
                hotspotChange.currentBlockedWaiterCount() >= 2L ? FindingStatus.CONFIRMED : FindingStatus.LIKELY,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, hotspotChange.evidenceId(), "thread-dump-blocked-threads"),
                "Repeated or growing waiter piles behind the same monitor across snapshots are stronger lock-contention evidence than an isolated blocked thread in one dump."
            ));
            actions.add(new RecommendedAction(
                "action-compare-thread-dump-contention-hotspot",
                "Inspect the contended monitor and owning thread before changing concurrency",
                "The newer thread dump shows a stronger pile-up behind the same shared monitor.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the owning thread stack to find the synchronized block or lock implementation holding the monitor.",
                    "Compare another later thread dump to see whether the same waiter set keeps growing on the same monitor.",
                    "Review the application path behind the contended lock before increasing worker counts or request concurrency."
                ),
                List.of(findingId)
            ));
        }

        if (poolChange != null) {
            String findingId = "compare-thread-dump-pool-stall";
            boolean emergent = !poolChange.baselineLooksStalled();
            findings.add(new Finding(
                findingId,
                emergent
                    ? "An executor-style pool newly appears stalled in the current thread dump"
                    : "An executor-style pool looks more stalled than in the baseline dump",
                String.format(
                    "Pool %s changed from %d RUNNABLE, %d BLOCKED, and %d stalled worker(s) in baseline to %d RUNNABLE, %d BLOCKED, and %d stalled worker(s) in current.%s",
                    poolChange.poolName(),
                    poolChange.baselineRunnableCount(),
                    poolChange.baselineBlockedCount(),
                    poolChange.baselineStalledThreadCount(),
                    poolChange.currentRunnableCount(),
                    poolChange.currentBlockedCount(),
                    poolChange.currentStalledThreadCount(),
                    threadNamesSentence(poolChange.threadNames())
                ),
                "compare.thread-dump-pool-stall",
                poolChange.currentBlockedCount() >= 3L || poolChange.currentStalledThreadCount() >= 4L
                    ? SeverityLevel.HIGH
                    : SeverityLevel.MEDIUM,
                poolChange.currentBlockedCount() >= 2L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, poolChange.evidenceId(), "thread-dump-blocked-threads"),
                "A pool that loses runnable capacity and accumulates blocked or non-idle waiting workers across snapshots is a strong sign of worsening executor or request stalls."
            ));
            actions.add(new RecommendedAction(
                "action-compare-thread-dump-pool-stall",
                "Inspect the affected executor or request pool before raising pool sizes",
                "The comparison shows a pool moving toward blocked or non-runnable workers instead of normal forward progress.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Check whether the pool's blocked workers are piling up behind the same lock, dependency, or downstream call.",
                    "Review queue backlog, pool sizing, and recent latency changes affecting the same executor or request pool.",
                    "Capture a follow-up thread dump to confirm whether the same pool remains saturated or blocked."
                ),
                List.of(findingId)
            ));
        }

        long baselineBlockedThreadCount = longValue(baselineParsed.extractedData(), "blockedThreadCount");
        long currentBlockedThreadCount = longValue(currentParsed.extractedData(), "blockedThreadCount");
        long blockedThreadDelta = currentBlockedThreadCount - baselineBlockedThreadCount;
        boolean materiallyMoreBlockedThreads = blockedThreadDelta >= 3L
            && (currentBlockedThreadCount >= 8L || (currentBlockedThreadCount >= 4L && currentBlockedThreadCount * 5L >= currentThreadCount));
        if (materiallyMoreBlockedThreads) {
            String findingId = "compare-thread-dump-blocked-growth";
            List<String> evidenceIds = new ArrayList<>(evidenceIds(currentParsed, "thread-dump-blocked-threads", "thread-dump-summary"));
            if (hotspotChange != null) {
                evidenceIds.addAll(evidenceIds(currentParsed, hotspotChange.evidenceId()));
            }
            if (poolChange != null) {
                evidenceIds.addAll(evidenceIds(currentParsed, poolChange.evidenceId()));
            }
            findings.add(new Finding(
                findingId,
                "Blocked threads increased materially between thread dumps",
                String.format(
                    "Blocked threads increased from %d in baseline to %d in current, which is %d of %d current threads.",
                    baselineBlockedThreadCount,
                    currentBlockedThreadCount,
                    currentBlockedThreadCount,
                    currentThreadCount
                ),
                "compare.thread-dump-blocked-growth",
                currentBlockedThreadCount >= 8L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM,
                currentDeadlockDetected || hotspotChange != null || poolChange != null ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                List.copyOf(evidenceIds.stream().distinct().toList()),
                "A sustained increase in blocked threads between snapshots is a stronger stall signal than a single blocked thread in one dump."
            ));
            actions.add(new RecommendedAction(
                "action-compare-thread-dump-blocked-growth",
                "Inspect what changed between captures to create more blocked threads",
                "The newer snapshot contains materially more blocked threads than the baseline dump.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare the blocked thread names and top frames between the two dumps to isolate the new stall source.",
                    "Inspect whether a new lock hotspot, deadlock, or saturated executor explains the blocked-thread increase.",
                    "Capture a third dump to confirm the same blocked-thread pattern persists."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private HotspotChange strongestHotspotChange(List<Map<String, Object>> baselineHotspots, List<Map<String, Object>> currentHotspots) {
        Map<String, Map<String, Object>> baselineByMonitor = indexByStringKey(baselineHotspots, "monitorId");
        HotspotChange strongest = null;
        for (Map<String, Object> currentHotspot : currentHotspots) {
            String monitorId = stringValue(currentHotspot.get("monitorId"));
            if (monitorId == null) {
                continue;
            }

            long currentWaiterCount = longValue(currentHotspot, "waiterCount");
            long currentBlockedWaiterCount = longValue(currentHotspot, "blockedWaiterCount");
            if (currentBlockedWaiterCount < 2L && currentWaiterCount < 3L) {
                continue;
            }

            Map<String, Object> baselineHotspot = baselineByMonitor.getOrDefault(monitorId, Map.of());
            long baselineWaiterCount = longValue(baselineHotspot, "waiterCount");
            long baselineBlockedWaiterCount = longValue(baselineHotspot, "blockedWaiterCount");
            boolean materiallyChanged = baselineHotspot.isEmpty()
                || currentBlockedWaiterCount >= baselineBlockedWaiterCount + 1L
                || currentWaiterCount >= baselineWaiterCount + 2L;
            if (!materiallyChanged) {
                continue;
            }

            HotspotChange candidate = new HotspotChange(
                monitorId,
                baselineWaiterCount,
                currentWaiterCount,
                baselineBlockedWaiterCount,
                currentBlockedWaiterCount,
                stringValue(currentHotspot.get("ownerThreadName")),
                stringValue(currentHotspot.get("ownerState")),
                stringValue(currentHotspot.get("evidenceId"))
            );
            if (strongest == null
                || candidate.blockedWaiterDelta() > strongest.blockedWaiterDelta()
                || (candidate.blockedWaiterDelta() == strongest.blockedWaiterDelta()
                    && candidate.currentBlockedWaiterCount() > strongest.currentBlockedWaiterCount())
                || (candidate.blockedWaiterDelta() == strongest.blockedWaiterDelta()
                    && candidate.currentBlockedWaiterCount() == strongest.currentBlockedWaiterCount()
                    && candidate.waiterDelta() > strongest.waiterDelta())
                || (candidate.blockedWaiterDelta() == strongest.blockedWaiterDelta()
                    && candidate.currentBlockedWaiterCount() == strongest.currentBlockedWaiterCount()
                    && candidate.waiterDelta() == strongest.waiterDelta()
                    && candidate.monitorId().compareTo(strongest.monitorId()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private PoolChange strongestPoolChange(List<Map<String, Object>> baselinePools, List<Map<String, Object>> currentPools) {
        Map<String, Map<String, Object>> baselineByPool = indexByStringKey(baselinePools, "poolName");
        PoolChange strongest = null;
        for (Map<String, Object> currentPool : currentPools) {
            String poolName = stringValue(currentPool.get("poolName"));
            if (poolName == null) {
                continue;
            }

            Map<String, Object> baselinePool = baselineByPool.getOrDefault(poolName, Map.of());
            PoolChange candidate = new PoolChange(
                poolName,
                stringList(currentPool.get("threadNames")),
                longValue(baselinePool, "threadCount"),
                longValue(currentPool, "threadCount"),
                longValue(baselinePool, "runnableCount"),
                longValue(currentPool, "runnableCount"),
                longValue(baselinePool, "blockedCount"),
                longValue(currentPool, "blockedCount"),
                longValue(baselinePool, "stalledThreadCount"),
                longValue(currentPool, "stalledThreadCount"),
                stringValue(currentPool.get("evidenceId"))
            );
            if (!candidate.currentLooksStalled() || !candidate.materiallyChanged()) {
                continue;
            }

            if (strongest == null
                || candidate.stalledThreadDelta() > strongest.stalledThreadDelta()
                || (candidate.stalledThreadDelta() == strongest.stalledThreadDelta()
                    && candidate.currentBlockedCount() > strongest.currentBlockedCount())
                || (candidate.stalledThreadDelta() == strongest.stalledThreadDelta()
                    && candidate.currentBlockedCount() == strongest.currentBlockedCount()
                    && candidate.poolName().compareTo(strongest.poolName()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private Map<String, Map<String, Object>> indexByStringKey(List<Map<String, Object>> values, String key) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String stringKey = stringValue(value.get(key));
            if (stringKey != null) {
                indexed.put(stringKey, value);
            }
        }
        return indexed;
    }

    private long longValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean boolValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return value instanceof List<?> ? ((List<Object>) value).stream().map(String::valueOf).toList() : List.of();
    }

    private String ownerSuffix(String ownerThreadName, String ownerState) {
        if (ownerThreadName == null) {
            return ".";
        }
        if (ownerState == null) {
            return " while " + ownerThreadName + " owns the monitor.";
        }
        return " while " + ownerThreadName + " owns the monitor in " + ownerState + ".";
    }

    private String threadNamesSentence(List<String> threadNames) {
        if (threadNames.isEmpty()) {
            return "";
        }
        return " Representative threads include " + summarizeNames(threadNames, 3) + ".";
    }

    private String threadNamesSuffix(List<String> threadNames) {
        if (threadNames.isEmpty()) {
            return "";
        }
        return " involving " + summarizeNames(threadNames, 4);
    }

    private String summarizeNames(List<String> threadNames, int limit) {
        if (threadNames.isEmpty()) {
            return "(none)";
        }
        return threadNames.stream().limit(limit).collect(Collectors.joining(", "))
            + (threadNames.size() > limit ? ", ..." : "");
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

    private record HotspotChange(
        String monitorId,
        long baselineWaiterCount,
        long currentWaiterCount,
        long baselineBlockedWaiterCount,
        long currentBlockedWaiterCount,
        String ownerThreadName,
        String ownerState,
        String evidenceId
    ) {
        private long waiterDelta() {
            return currentWaiterCount - baselineWaiterCount;
        }

        private long blockedWaiterDelta() {
            return currentBlockedWaiterCount - baselineBlockedWaiterCount;
        }
    }

    private record PoolChange(
        String poolName,
        List<String> threadNames,
        long baselineThreadCount,
        long currentThreadCount,
        long baselineRunnableCount,
        long currentRunnableCount,
        long baselineBlockedCount,
        long currentBlockedCount,
        long baselineStalledThreadCount,
        long currentStalledThreadCount,
        String evidenceId
    ) {
        private boolean currentLooksStalled() {
            return currentBlockedCount >= 2L || (currentThreadCount >= 3L && currentRunnableCount == 0L && currentStalledThreadCount >= 2L);
        }

        private boolean baselineLooksStalled() {
            return baselineBlockedCount >= 2L || (baselineThreadCount >= 3L && baselineRunnableCount == 0L && baselineStalledThreadCount >= 2L);
        }

        private boolean materiallyChanged() {
            if (!baselineLooksStalled()) {
                return true;
            }
            return currentStalledThreadCount >= baselineStalledThreadCount + 2L
                || currentBlockedCount >= baselineBlockedCount + 2L
                || (baselineRunnableCount > 0L && currentRunnableCount == 0L && currentStalledThreadCount > baselineStalledThreadCount);
        }

        private long stalledThreadDelta() {
            return currentStalledThreadCount - baselineStalledThreadCount;
        }
    }
}
