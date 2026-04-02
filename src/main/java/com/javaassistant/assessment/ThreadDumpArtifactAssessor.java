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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ThreadDumpArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.THREAD_DUMP;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> extractedData = parsedArtifact.extractedData();
        long threadCount = AssessmentSupport.longValue(extractedData, "threadCount");
        long blockedThreadCount = AssessmentSupport.longValue(extractedData, "blockedThreadCount");
        Map<String, Object> stateCounts = AssessmentSupport.map(extractedData, "stateCounts");
        Map<String, Object> deadlock = AssessmentSupport.map(extractedData, "deadlock");
        List<Map<String, Object>> contentionHotspots = AssessmentSupport.mapList(extractedData, "contentionHotspots");
        List<Map<String, Object>> poolSummaries = AssessmentSupport.mapList(extractedData, "poolSummaries");

        if (threadCount == 0L) {
            missingData.add("No thread entries were extracted from the thread dump.");
            return new AssessmentResult(findings, actions, missingData);
        }

        if (stateCounts.isEmpty()) {
            missingData.add("Thread states were not extracted from the thread dump.");
        }

        if (booleanValue(deadlock.get("detected"))) {
            List<String> deadlockedThreadNames = stringList(deadlock.get("threadNames"));
            long reportedDeadlockCount = AssessmentSupport.longValue(deadlock, "reportedDeadlockCount");
            String findingId = "thread-dump-java-deadlock";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Java-level deadlock detected in the thread dump",
                String.format(
                    "The thread dump reports %d Java-level deadlock(s)%s.",
                    reportedDeadlockCount > 0 ? reportedDeadlockCount : 1L,
                    deadlockedThreadNames.isEmpty() ? "" : " involving " + summarizeNames(deadlockedThreadNames, 4)
                ),
                "threads.deadlock",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "thread-dump-deadlock"),
                "A JVM-reported Java-level deadlock is direct evidence that at least one lock cycle is preventing forward progress."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-java-deadlock",
                "Treat the incident as an active deadlock and preserve matching evidence",
                "The thread dump explicitly reports a Java-level deadlock.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Capture a second thread dump a few seconds later to confirm the same threads remain deadlocked.",
                    "Inspect the deadlocked threads' stack frames and locked monitors to identify the code path creating the lock cycle.",
                    "If the service is wedged, coordinate a controlled mitigation or restart after preserving the evidence."
                ),
                List.of(findingId)
            ));
        }

        LockHotspot strongestHotspot = strongestHotspot(contentionHotspots);
        if (strongestHotspot != null && (strongestHotspot.blockedWaiterCount() >= 2 || strongestHotspot.waiterCount() >= 3)) {
            String findingId = "thread-dump-lock-contention-hotspot";
            SeverityLevel severity = strongestHotspot.blockedWaiterCount() >= 3 ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = strongestHotspot.ownerThreadName() != null || strongestHotspot.blockedWaiterCount() >= 2
                ? ConfidenceLevel.HIGH
                : ConfidenceLevel.MEDIUM;
            FindingStatus status = strongestHotspot.blockedWaiterCount() >= 2 ? FindingStatus.CONFIRMED : FindingStatus.LIKELY;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "A shared monitor is creating a lock-contention hotspot",
                hotspotSummary(strongestHotspot),
                "threads.lock-contention",
                severity,
                confidence,
                status,
                evidenceIds(parsedArtifact, strongestHotspot.evidenceId(), "thread-dump-blocked-threads"),
                "Multiple threads waiting for the same monitor is direct evidence of lock contention and can explain request stalls or pool starvation."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-lock-contention-hotspot",
                "Inspect the owning thread and the contended monitor before changing pool sizes",
                "The thread dump shows multiple waiters piling up behind the same monitor.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the owning thread's stack trace to find the synchronized block or lock implementation holding the monitor.",
                    "Capture a second thread dump to confirm whether the same waiters and owner remain stuck on the same monitor.",
                    "Review the application code around the shared lock before increasing worker counts or request concurrency."
                ),
                List.of(findingId)
            ));
        }

        PoolCandidate stalledPool = strongestStalledPool(poolSummaries);
        if (stalledPool != null && (stalledPool.blockedCount() >= 2 || (stalledPool.threadCount() >= 3 && stalledPool.runnableCount() == 0 && stalledPool.stalledThreadCount() >= 2))) {
            String findingId = "thread-dump-stuck-thread-pool";
            SeverityLevel severity = stalledPool.blockedCount() >= 3 || stalledPool.stalledThreadCount() >= 3
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = stalledPool.blockedCount() >= 2 ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "An executor-style thread pool appears stalled",
                poolSummary(stalledPool),
                "threads.pool-stall",
                severity,
                confidence,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, stalledPool.evidenceId(), "thread-dump-blocked-threads"),
                "A pool with multiple blocked or non-idle waiting workers and no runnable capacity is unlikely to be making normal forward progress."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-stuck-thread-pool",
                "Inspect the affected executor or request pool as a stall source",
                "The pool state suggests workers are tied up or blocked rather than processing normally.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Check whether the pool's blocked workers are all waiting on the same lock, dependency, or downstream call.",
                    "Review queue backlog, pool sizing, and any external dependency latency affecting the same pool.",
                    "Capture a follow-up thread dump to see whether the same pool remains saturated or blocked."
                ),
                List.of(findingId)
            ));
        }

        if (blockedThreadCount == 0L && findings.isEmpty()) {
            missingData.add("No blocked threads or deadlock signals were present, so the dump may represent an idle snapshot rather than an active stall.");
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private String hotspotSummary(LockHotspot hotspot) {
        StringBuilder summary = new StringBuilder();
        summary.append(hotspot.waiterCount())
            .append(" thread(s) are waiting for monitor <")
            .append(hotspot.monitorId())
            .append(">");
        if (hotspot.ownerThreadName() != null) {
            summary.append(" held by ")
                .append(hotspot.ownerThreadName());
            if (hotspot.ownerState() != null) {
                summary.append(" (").append(hotspot.ownerState()).append(")");
            }
        }
        summary.append(".");
        if (!hotspot.waitingThreadNames().isEmpty()) {
            summary.append(" Waiters include ").append(summarizeNames(hotspot.waitingThreadNames(), 3)).append(".");
        }
        return summary.toString();
    }

    private String poolSummary(PoolCandidate pool) {
        StringBuilder summary = new StringBuilder();
        summary.append("Pool ")
            .append(pool.poolName())
            .append(" has ")
            .append(pool.threadCount())
            .append(" thread(s): ")
            .append(pool.blockedCount())
            .append(" BLOCKED, ")
            .append(pool.waitingCount())
            .append(" WAITING, ")
            .append(pool.timedWaitingCount())
            .append(" TIMED_WAITING, and ")
            .append(pool.runnableCount())
            .append(" RUNNABLE.");
        if (pool.idleExecutorCount() > 0) {
            summary.append(" ")
                .append(pool.idleExecutorCount())
                .append(" worker(s) appear idle waiting for work.");
        }
        summary.append(" Representative threads: ").append(summarizeNames(pool.threadNames(), 4)).append(".");
        return summary.toString();
    }

    private LockHotspot strongestHotspot(List<Map<String, Object>> contentionHotspots) {
        LockHotspot strongest = null;
        for (Map<String, Object> contentionHotspot : contentionHotspots) {
            LockHotspot candidate = new LockHotspot(
                stringValue(contentionHotspot.get("monitorId")),
                stringList(contentionHotspot.get("waitingThreadNames")),
                AssessmentSupport.longValue(contentionHotspot, "blockedWaiterCount"),
                stringValue(contentionHotspot.get("ownerThreadName")),
                stringValue(contentionHotspot.get("ownerState")),
                stringValue(contentionHotspot.get("evidenceId"))
            );
            if (candidate.monitorId() == null) {
                continue;
            }

            if (strongest == null
                || candidate.blockedWaiterCount() > strongest.blockedWaiterCount()
                || (candidate.blockedWaiterCount() == strongest.blockedWaiterCount() && candidate.waiterCount() > strongest.waiterCount())
                || (candidate.blockedWaiterCount() == strongest.blockedWaiterCount()
                    && candidate.waiterCount() == strongest.waiterCount()
                    && candidate.monitorId().compareTo(strongest.monitorId()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private PoolCandidate strongestStalledPool(List<Map<String, Object>> poolSummaries) {
        PoolCandidate strongest = null;
        for (Map<String, Object> poolSummary : poolSummaries) {
            PoolCandidate candidate = new PoolCandidate(
                stringValue(poolSummary.get("poolName")),
                stringList(poolSummary.get("threadNames")),
                AssessmentSupport.longValue(poolSummary, "runnableCount"),
                AssessmentSupport.longValue(poolSummary, "blockedCount"),
                AssessmentSupport.longValue(poolSummary, "waitingCount"),
                AssessmentSupport.longValue(poolSummary, "timedWaitingCount"),
                AssessmentSupport.longValue(poolSummary, "idleExecutorCount"),
                AssessmentSupport.longValue(poolSummary, "stalledThreadCount"),
                stringValue(poolSummary.get("evidenceId"))
            );
            if (candidate.poolName() == null) {
                continue;
            }

            if (strongest == null
                || candidate.stalledThreadCount() > strongest.stalledThreadCount()
                || (candidate.stalledThreadCount() == strongest.stalledThreadCount() && candidate.blockedCount() > strongest.blockedCount())
                || (candidate.stalledThreadCount() == strongest.stalledThreadCount()
                    && candidate.blockedCount() == strongest.blockedCount()
                    && candidate.threadCount() > strongest.threadCount())
                || (candidate.stalledThreadCount() == strongest.stalledThreadCount()
                    && candidate.blockedCount() == strongest.blockedCount()
                    && candidate.threadCount() == strongest.threadCount()
                    && candidate.poolName().compareTo(strongest.poolName()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
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

    private String summarizeNames(List<String> values, int limit) {
        if (values.isEmpty()) {
            return "(none)";
        }
        List<String> limited = values.stream().limit(limit).toList();
        if (values.size() <= limit) {
            return String.join(", ", limited);
        }
        return String.join(", ", limited) + ", +" + (values.size() - limit) + " more";
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                strings.add(String.valueOf(item));
            }
        }
        return List.copyOf(strings);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record LockHotspot(
        String monitorId,
        List<String> waitingThreadNames,
        long blockedWaiterCount,
        String ownerThreadName,
        String ownerState,
        String evidenceId
    ) {
        private int waiterCount() {
            return waitingThreadNames.size();
        }
    }

    private record PoolCandidate(
        String poolName,
        List<String> threadNames,
        long runnableCount,
        long blockedCount,
        long waitingCount,
        long timedWaitingCount,
        long idleExecutorCount,
        long stalledThreadCount,
        String evidenceId
    ) {
        private int threadCount() {
            return threadNames.size();
        }
    }
}
