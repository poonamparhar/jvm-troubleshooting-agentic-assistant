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
import java.util.LinkedHashMap;
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
        List<Map<String, Object>> threadsData = AssessmentSupport.mapList(extractedData, "threads");
        List<Map<String, Object>> contentionHotspots = AssessmentSupport.mapList(extractedData, "contentionHotspots");
        List<Map<String, Object>> poolSummaries = AssessmentSupport.mapList(extractedData, "poolSummaries");
        List<ThreadSnapshot> threads = threadsData.stream().map(this::threadSnapshot).toList();

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

        DownstreamIoPileup ioPileup = strongestDownstreamIoPileup(threads, poolSummaries);
        if (ioPileup != null && ioPileup.ioThreadCount() >= 2 && ioPileup.runnableNonIoThreadCount() == 0) {
            String findingId = "thread-dump-downstream-io-pileup";
            SeverityLevel severity = ioPileup.ioThreadCount() >= 3 ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Worker threads are piling up behind downstream I/O",
                String.format(
                    "Pool %s has %d thread(s) stalled in downstream I/O frames%s. Representative threads: %s.",
                    ioPileup.poolName(),
                    ioPileup.ioThreadCount(),
                    ioPileup.representativeFrame() != null ? " such as " + ioPileup.representativeFrame() : "",
                    summarizeNames(ioPileup.ioThreadNames(), 4)
                ),
                "threads.downstream-io",
                severity,
                ConfidenceLevel.HIGH,
                ioPileup.ioThreadCount() >= 3 ? FindingStatus.CONFIRMED : FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, ioPileup.evidenceId(), "thread-dump-summary"),
                "When most workers in the same pool are sitting in socket or file I/O frames, the stall is more likely downstream latency or dependency backpressure than JVM lock contention alone."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-downstream-io-pileup",
                "Investigate the downstream dependency or I/O path before changing pool size",
                "The same worker pool is stacked behind slow socket or file operations rather than making forward progress.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the downstream call, file path, or dependency endpoint shown in the affected worker stacks and check its latency from the same incident window.",
                    "Capture a short JFR or application trace near the same interval to confirm whether socket or file latency matches the blocked workers.",
                    "Avoid increasing worker count first because more threads can simply deepen the same downstream pileup."
                ),
                List.of(findingId)
            ));
        }

        ForkJoinStarvation forkJoinStarvation = strongestForkJoinStarvation(threads, poolSummaries);
        if (forkJoinStarvation != null
            && forkJoinStarvation.joinBlockedThreadCount() >= 2
            && forkJoinStarvation.runnableCount() == 0) {
            String findingId = "thread-dump-forkjoin-starvation";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "ForkJoin workers appear starved or self-blocked",
                String.format(
                    "Pool %s has %d worker(s) parked in join or completion-wait paths and no RUNNABLE workers. Representative threads: %s.",
                    forkJoinStarvation.poolName(),
                    forkJoinStarvation.joinBlockedThreadCount(),
                    summarizeNames(forkJoinStarvation.waitingThreadNames(), 4)
                ),
                "threads.forkjoin",
                forkJoinStarvation.joinBlockedThreadCount() >= 3 ? SeverityLevel.HIGH : SeverityLevel.MEDIUM,
                ConfidenceLevel.HIGH,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, forkJoinStarvation.evidenceId(), "thread-dump-summary"),
                "A ForkJoin pool with workers mostly waiting on joins or completion signals and no runnable capacity often means blocking work is exhausting the pool's parallelism."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-forkjoin-starvation",
                "Inspect blocking joins and external waits on the ForkJoin pool",
                "The ForkJoin workers are mostly parked or self-blocked instead of actively executing work.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect whether tasks running on the ForkJoin pool are doing blocking I/O, synchronized work, or long joins that reduce available parallelism.",
                    "Check whether the affected workload should move blocking operations off the common or shared ForkJoin pool.",
                    "Capture a short JFR or another dump during the same stall to confirm whether parked workers stay concentrated in the same join path."
                ),
                List.of(findingId)
            ));
        }

        VirtualThreadPinning virtualThreadPinning = detectVirtualThreadPinning(threads);
        if (virtualThreadPinning != null && virtualThreadPinning.pinnedThreadCount() >= 1) {
            String findingId = "thread-dump-virtual-thread-pinning";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Virtual-thread pinning or carrier-thread blockage is visible in the dump",
                String.format(
                    "%d thread(s) show virtual-thread pinning markers%s. Representative threads: %s.",
                    virtualThreadPinning.pinnedThreadCount(),
                    virtualThreadPinning.representativeFrame() != null ? " such as " + virtualThreadPinning.representativeFrame() : "",
                    summarizeNames(virtualThreadPinning.threadNames(), 4)
                ),
                "threads.virtual-thread-pinning",
                virtualThreadPinning.pinnedThreadCount() >= 2 ? SeverityLevel.HIGH : SeverityLevel.MEDIUM,
                ConfidenceLevel.HIGH,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "thread-dump-summary"),
                "Pinned virtual-thread or carrier-thread stacks are a concrete sign that supposedly lightweight concurrency is being forced onto scarce carrier threads by blocking work."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-virtual-thread-pinning",
                "Inspect synchronized, native, or blocking sections running under virtual threads",
                "The dump shows carrier-thread pinning markers rather than normal lightweight virtual-thread parking.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the affected stacks for synchronized blocks, native calls, or blocking I/O that can pin carrier threads.",
                    "Capture a matching JFR with virtual-thread pinning events if possible so you can measure how often the pinning occurs.",
                    "Prefer removing the blocking or synchronized section from the virtual-thread path before increasing carrier parallelism."
                ),
                List.of(findingId)
            ));
        }

        BusySpinThread busySpinThread = busiestBusySpinThread(threads);
        if (busySpinThread != null && busySpinThread.cpuShare() >= 0.80d) {
            String findingId = "thread-dump-busy-spin-thread";
            SeverityLevel severity = busySpinThread.cpuShare() >= 0.95d || busySpinThread.cpuMs() >= 10_000.0d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "A RUNNABLE thread looks like a busy-spin CPU hotspot",
                String.format(
                    "%s consumed about %.0fms CPU over %.2fs elapsed (%.0f%%) while staying RUNNABLE%s.",
                    busySpinThread.threadName(),
                    busySpinThread.cpuMs(),
                    busySpinThread.elapsedSeconds(),
                    busySpinThread.cpuShare() * 100.0d,
                    busySpinThread.topFrame() != null ? " in " + busySpinThread.topFrame() : ""
                ),
                "threads.cpu-busy-spin",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, busySpinThread.evidenceId(), "thread-dump-summary"),
                "A thread that spends nearly all of its elapsed lifetime on CPU while remaining RUNNABLE is strong evidence of a spin loop or runaway CPU path rather than a blocked wait."
            ));
            actions.add(AssessmentSupport.action(
                "action-thread-dump-busy-spin-thread",
                "Inspect the hot RUNNABLE thread before tuning memory or lock settings",
                "The dump points to a thread that is burning CPU continuously rather than waiting on a resource.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Capture a short JFR or CPU profile and confirm whether the same method stays dominant while the thread remains RUNNABLE.",
                    "Inspect the loop condition, retry path, or polling logic around the dominant frame for missing backoff or exit conditions.",
                    "Treat this as a CPU-path investigation first because more threads or memory will not fix a hot spin loop."
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

    private DownstreamIoPileup strongestDownstreamIoPileup(List<ThreadSnapshot> threads, List<Map<String, Object>> poolSummaries) {
        Map<String, List<ThreadSnapshot>> threadsByPool = threadsByPool(threads);
        DownstreamIoPileup strongest = null;
        for (Map<String, Object> poolSummary : poolSummaries) {
            String poolName = stringValue(poolSummary.get("poolName"));
            if (poolName == null) {
                continue;
            }
            List<ThreadSnapshot> poolThreads = threadsByPool.getOrDefault(poolName, List.of());
            if (poolThreads.size() < 3) {
                continue;
            }

            List<ThreadSnapshot> ioThreads = poolThreads.stream()
                .filter(thread -> !thread.idleExecutorThread() && looksLikeDownstreamIoFrame(thread.topFrame()))
                .toList();
            long runnableNonIoThreadCount = poolThreads.stream()
                .filter(thread -> "RUNNABLE".equals(thread.state()) && !looksLikeDownstreamIoFrame(thread.topFrame()))
                .count();

            DownstreamIoPileup candidate = new DownstreamIoPileup(
                poolName,
                ioThreads.stream().map(ThreadSnapshot::name).toList(),
                ioThreads.size(),
                runnableNonIoThreadCount,
                stringValue(poolSummary.get("evidenceId")),
                ioThreads.isEmpty() ? null : ioThreads.getFirst().topFrame()
            );
            if (candidate.ioThreadCount() < 2) {
                continue;
            }
            if (strongest == null
                || candidate.ioThreadCount() > strongest.ioThreadCount()
                || (candidate.ioThreadCount() == strongest.ioThreadCount()
                    && candidate.runnableNonIoThreadCount() < strongest.runnableNonIoThreadCount())
                || (candidate.ioThreadCount() == strongest.ioThreadCount()
                    && candidate.runnableNonIoThreadCount() == strongest.runnableNonIoThreadCount()
                    && candidate.poolName().compareTo(strongest.poolName()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private ForkJoinStarvation strongestForkJoinStarvation(List<ThreadSnapshot> threads, List<Map<String, Object>> poolSummaries) {
        Map<String, List<ThreadSnapshot>> threadsByPool = threadsByPool(threads);
        ForkJoinStarvation strongest = null;
        for (Map<String, Object> poolSummary : poolSummaries) {
            String poolName = stringValue(poolSummary.get("poolName"));
            if (poolName == null || !poolName.toLowerCase().contains("forkjoin")) {
                continue;
            }

            List<ThreadSnapshot> poolThreads = threadsByPool.getOrDefault(poolName, List.of());
            if (poolThreads.size() < 3) {
                continue;
            }

            List<String> waitingThreadNames = poolThreads.stream()
                .filter(thread -> looksLikeForkJoinJoinFrame(thread.topFrame()))
                .map(ThreadSnapshot::name)
                .toList();
            ForkJoinStarvation candidate = new ForkJoinStarvation(
                poolName,
                waitingThreadNames,
                waitingThreadNames.size(),
                AssessmentSupport.longValue(poolSummary, "runnableCount"),
                stringValue(poolSummary.get("evidenceId"))
            );
            if (candidate.joinBlockedThreadCount() < 2) {
                continue;
            }
            if (strongest == null
                || candidate.joinBlockedThreadCount() > strongest.joinBlockedThreadCount()
                || (candidate.joinBlockedThreadCount() == strongest.joinBlockedThreadCount()
                    && candidate.runnableCount() < strongest.runnableCount())
                || (candidate.joinBlockedThreadCount() == strongest.joinBlockedThreadCount()
                    && candidate.runnableCount() == strongest.runnableCount()
                    && candidate.poolName().compareTo(strongest.poolName()) < 0)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private VirtualThreadPinning detectVirtualThreadPinning(List<ThreadSnapshot> threads) {
        List<ThreadSnapshot> pinnedThreads = threads.stream()
            .filter(thread -> looksLikeVirtualThreadPinningFrame(thread.topFrame()))
            .toList();
        if (pinnedThreads.isEmpty()) {
            return null;
        }
        return new VirtualThreadPinning(
            pinnedThreads.stream().map(ThreadSnapshot::name).toList(),
            pinnedThreads.size(),
            pinnedThreads.getFirst().topFrame()
        );
    }

    private BusySpinThread busiestBusySpinThread(List<ThreadSnapshot> threads) {
        BusySpinThread busiest = null;
        for (ThreadSnapshot thread : threads) {
            if (!"RUNNABLE".equals(thread.state())
                || thread.cpuMs() == null
                || thread.elapsedSeconds() == null
                || thread.elapsedSeconds() <= 0.0d
                || thread.cpuMs() < 1_000.0d
                || thread.elapsedSeconds() < 5.0d
                || looksLikeDownstreamIoFrame(thread.topFrame())
                || looksLikeWaitFrame(thread.topFrame())) {
                continue;
            }

            double cpuShare = thread.cpuMs() / (thread.elapsedSeconds() * 1_000.0d);
            if (cpuShare < 0.80d) {
                continue;
            }

            BusySpinThread candidate = new BusySpinThread(
                thread.name(),
                thread.cpuMs(),
                thread.elapsedSeconds(),
                cpuShare,
                thread.topFrame(),
                thread.poolName() != null ? "thread-dump-pool-" + thread.poolName() : "thread-dump-summary"
            );
            if (busiest == null
                || candidate.cpuShare() > busiest.cpuShare()
                || (candidate.cpuShare() == busiest.cpuShare() && candidate.cpuMs() > busiest.cpuMs())
                || (candidate.cpuShare() == busiest.cpuShare()
                    && candidate.cpuMs() == busiest.cpuMs()
                    && candidate.threadName().compareTo(busiest.threadName()) < 0)) {
                busiest = candidate;
            }
        }
        return busiest;
    }

    private Map<String, List<ThreadSnapshot>> threadsByPool(List<ThreadSnapshot> threads) {
        Map<String, List<ThreadSnapshot>> threadsByPool = new LinkedHashMap<>();
        for (ThreadSnapshot thread : threads) {
            if (thread.poolName() == null) {
                continue;
            }
            threadsByPool.computeIfAbsent(thread.poolName(), ignored -> new ArrayList<>()).add(thread);
        }
        return threadsByPool;
    }

    private ThreadSnapshot threadSnapshot(Map<String, Object> threadData) {
        return new ThreadSnapshot(
            stringValue(threadData.get("name")),
            stringValue(threadData.get("state")),
            stringValue(threadData.get("topFrame")),
            stringValue(threadData.get("poolName")),
            booleanValue(threadData.get("idleExecutorThread")),
            booleanValue(threadData.get("deadlocked")),
            doubleValue(threadData.get("cpuMs")),
            doubleValue(threadData.get("elapsedSeconds"))
        );
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

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private boolean looksLikeDownstreamIoFrame(String topFrame) {
        if (topFrame == null) {
            return false;
        }
        String lowerTopFrame = topFrame.toLowerCase();
        return lowerTopFrame.contains("socket")
            || lowerTopFrame.contains("dispatcher.read")
            || lowerTopFrame.contains("dispatcher.write")
            || lowerTopFrame.contains("read0(")
            || lowerTopFrame.contains("write0(")
            || lowerTopFrame.contains("filedispatcher")
            || lowerTopFrame.contains("socketchannelimpl")
            || lowerTopFrame.contains("filechannelimpl");
    }

    private boolean looksLikeForkJoinJoinFrame(String topFrame) {
        if (topFrame == null) {
            return false;
        }
        String lowerTopFrame = topFrame.toLowerCase();
        return lowerTopFrame.contains("forkjointask.awaitdone")
            || lowerTopFrame.contains("completablefuture$signaller.block")
            || lowerTopFrame.contains("forkjoinpool.awaitwork")
            || lowerTopFrame.contains("managedblock");
    }

    private boolean looksLikeVirtualThreadPinningFrame(String topFrame) {
        if (topFrame == null) {
            return false;
        }
        String lowerTopFrame = topFrame.toLowerCase();
        return lowerTopFrame.contains("virtualthread.parkoncarrierthread")
            || lowerTopFrame.contains("virtualthread.yieldcontinuation")
            || lowerTopFrame.contains("continuation.enter")
            || lowerTopFrame.contains("continuation.yield")
            || lowerTopFrame.contains("onpinned");
    }

    private boolean looksLikeWaitFrame(String topFrame) {
        if (topFrame == null) {
            return false;
        }
        String lowerTopFrame = topFrame.toLowerCase();
        return lowerTopFrame.contains("unsafe.park")
            || lowerTopFrame.contains("locksupport.park")
            || lowerTopFrame.contains("object.wait")
            || lowerTopFrame.contains("sleep");
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

    private record ThreadSnapshot(
        String name,
        String state,
        String topFrame,
        String poolName,
        boolean idleExecutorThread,
        boolean deadlocked,
        Double cpuMs,
        Double elapsedSeconds
    ) {
    }

    private record DownstreamIoPileup(
        String poolName,
        List<String> ioThreadNames,
        long ioThreadCount,
        long runnableNonIoThreadCount,
        String evidenceId,
        String representativeFrame
    ) {
    }

    private record ForkJoinStarvation(
        String poolName,
        List<String> waitingThreadNames,
        long joinBlockedThreadCount,
        long runnableCount,
        String evidenceId
    ) {
    }

    private record VirtualThreadPinning(
        List<String> threadNames,
        long pinnedThreadCount,
        String representativeFrame
    ) {
    }

    private record BusySpinThread(
        String threadName,
        double cpuMs,
        double elapsedSeconds,
        double cpuShare,
        String topFrame,
        String evidenceId
    ) {
    }
}
