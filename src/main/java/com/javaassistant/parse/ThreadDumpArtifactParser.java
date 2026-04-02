package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThreadDumpArtifactParser implements ArtifactParser {

    private static final Pattern THREAD_HEADER_PATTERN = Pattern.compile("^\"([^\"]+)\"(.*\\bnid=\\S+.*)$");
    private static final Pattern NID_PATTERN = Pattern.compile("\\bnid=([^\\s]+)");
    private static final Pattern THREAD_STATE_PATTERN = Pattern.compile("^\\s*java\\.lang\\.Thread\\.State:\\s+([A-Z_]+).*");
    private static final Pattern WAITING_TO_LOCK_PATTERN = Pattern.compile("- waiting to lock <([^>]+)>");
    private static final Pattern PARKING_TO_WAIT_PATTERN = Pattern.compile("- parking to wait for\\s+<([^>]+)>");
    private static final Pattern LOCKED_MONITOR_PATTERN = Pattern.compile("- locked <([^>]+)>");
    private static final Pattern DEADLOCK_HEADER_PATTERN = Pattern.compile("(?im)^Found (one|\\d+) Java-level deadlock(?:s)?:");
    private static final Pattern DEADLOCK_THREAD_PATTERN = Pattern.compile("(?m)^\"([^\"]+)\":\\s*$");
    private static final Pattern THREAD_DUMP_HEADER_PATTERN = Pattern.compile("(?im)^Full thread dump.*$");
    private static final Pattern TRAILING_THREAD_INDEX_PATTERN = Pattern.compile("(.+?)-\\d+$");
    private static final String DEADLOCK_STACK_INFO_MARKER = "Java stack information for the threads listed above";
    private static final String JNI_GLOBAL_REFS_MARKER = "JNI global refs:";

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.THREAD_DUMP;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        List<String> lines = ParserUtils.lines(artifact.content());
        DeadlockSummary deadlockSummary = parseDeadlockSummary(artifact.content());
        Set<String> deadlockedThreadNames = new LinkedHashSet<>(deadlockSummary.threadNames());
        List<ParsedThread> threads = parseThreads(lines, deadlockedThreadNames);

        Map<String, Long> stateCounts = stateCounts(threads);
        long threadCount = threads.size();
        long daemonThreadCount = threads.stream().filter(ParsedThread::daemon).count();
        long blockedThreadCount = stateCounts.getOrDefault("BLOCKED", 0L);
        long waitingThreadCount = stateCounts.getOrDefault("WAITING", 0L);
        long timedWaitingThreadCount = stateCounts.getOrDefault("TIMED_WAITING", 0L);
        long runnableThreadCount = stateCounts.getOrDefault("RUNNABLE", 0L);

        List<String> blockedThreadNames = threadNamesForState(threads, "BLOCKED");
        List<String> waitingThreadNames = threadNamesForState(threads, "WAITING");
        List<String> timedWaitingThreadNames = threadNamesForState(threads, "TIMED_WAITING");

        List<ContentionHotspot> contentionHotspots = buildContentionHotspots(threads);
        List<PoolSummary> poolSummaries = buildPoolSummaries(threads);

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("threadCount", threadCount);
        extractedData.put("daemonThreadCount", daemonThreadCount);
        extractedData.put("stateCounts", immutableObjectMap(new LinkedHashMap<>(stateCounts)));
        extractedData.put("blockedThreadCount", blockedThreadCount);
        extractedData.put("waitingThreadCount", waitingThreadCount);
        extractedData.put("timedWaitingThreadCount", timedWaitingThreadCount);
        extractedData.put("runnableThreadCount", runnableThreadCount);
        extractedData.put("blockedThreadNames", List.copyOf(blockedThreadNames));
        extractedData.put("waitingThreadNames", List.copyOf(waitingThreadNames));
        extractedData.put("timedWaitingThreadNames", List.copyOf(timedWaitingThreadNames));
        extractedData.put("threads", threads.stream().map(ParsedThread::toCanonicalMap).toList());
        extractedData.put("deadlock", deadlockSummary.toCanonicalMap());
        extractedData.put("contentionHotspots", contentionHotspots.stream().map(ContentionHotspot::toCanonicalMap).toList());
        extractedData.put("poolSummaries", poolSummaries.stream().map(PoolSummary::toCanonicalMap).toList());

        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (threads.isEmpty()) {
            warnings.add("No thread entries were parsed from the thread dump.");
        } else if (stateCounts.isEmpty()) {
            warnings.add("Thread states could not be extracted from the thread dump.");
        }

        if (deadlockSummary.detected() && deadlockSummary.threadNames().isEmpty()) {
            warnings.add("A deadlock marker was present, but the involved thread names were not extracted.");
        }

        String summarySnippet = firstMatch(THREAD_DUMP_HEADER_PATTERN, artifact.content());
        if (summarySnippet == null && !threads.isEmpty()) {
            summarySnippet = "\"" + threads.get(0).name() + "\"";
        }

        Map<String, Object> summaryMetrics = new LinkedHashMap<>();
        summaryMetrics.put("threadCount", threadCount);
        summaryMetrics.put("daemonThreadCount", daemonThreadCount);
        summaryMetrics.put("blockedThreadCount", blockedThreadCount);
        summaryMetrics.put("waitingThreadCount", waitingThreadCount);
        summaryMetrics.put("timedWaitingThreadCount", timedWaitingThreadCount);
        summaryMetrics.put("runnableThreadCount", runnableThreadCount);
        summaryMetrics.put("deadlockDetected", deadlockSummary.detected());
        summaryMetrics.put("stateCounts", stateCounts);
        evidence.add(ParserUtils.evidence(
            "thread-dump-summary",
            artifact,
            "Thread dump summary",
            "Top-level thread counts and state distribution extracted from the thread dump.",
            summarySnippet,
            summaryMetrics
        ));

        if (blockedThreadCount > 0) {
            evidence.add(ParserUtils.evidence(
                "thread-dump-blocked-threads",
                artifact,
                "Blocked threads",
                "Threads currently reported in BLOCKED state.",
                "java.lang.Thread.State: BLOCKED",
                Map.of(
                    "blockedThreadCount", blockedThreadCount,
                    "blockedThreadNames", blockedThreadNames
                )
            ));
        }

        if (deadlockSummary.detected()) {
            evidence.add(ParserUtils.evidence(
                "thread-dump-deadlock",
                artifact,
                "Java-level deadlock",
                "Deadlock section reported by the thread dump.",
                deadlockSummary.snippet(),
                deadlockSummary.evidenceMetrics()
            ));
        }

        for (ContentionHotspot contentionHotspot : contentionHotspots) {
            evidence.add(ParserUtils.evidence(
                contentionHotspot.evidenceId(),
                artifact,
                "Lock contention hotspot",
                contentionHotspot.evidenceDetail(),
                contentionHotspot.snippet(),
                contentionHotspot.evidenceMetrics()
            ));
        }

        for (PoolSummary poolSummary : poolSummaries) {
            evidence.add(ParserUtils.evidence(
                poolSummary.evidenceId(),
                artifact,
                "Thread pool summary",
                poolSummary.evidenceDetail(),
                poolSummary.snippet(),
                poolSummary.evidenceMetrics()
            ));
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "thread-dump-v1", extractedData, evidence, warnings);
    }

    private List<ParsedThread> parseThreads(List<String> lines, Set<String> deadlockedThreadNames) {
        List<ParsedThread> threads = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!THREAD_HEADER_PATTERN.matcher(line).matches()) {
                index++;
                continue;
            }

            List<String> sectionLines = new ArrayList<>();
            sectionLines.add(line);
            index++;
            while (index < lines.size()) {
                String nextLine = lines.get(index);
                if (THREAD_HEADER_PATTERN.matcher(nextLine).matches()
                    || DEADLOCK_HEADER_PATTERN.matcher(nextLine).find()
                    || nextLine.startsWith(JNI_GLOBAL_REFS_MARKER)) {
                    break;
                }
                sectionLines.add(nextLine);
                index++;
            }

            ParsedThread thread = parseThreadSection(sectionLines, deadlockedThreadNames);
            if (thread != null) {
                threads.add(thread);
            }
        }
        return List.copyOf(threads);
    }

    private ParsedThread parseThreadSection(List<String> sectionLines, Set<String> deadlockedThreadNames) {
        if (sectionLines.isEmpty()) {
            return null;
        }

        String headerLine = sectionLines.get(0);
        Matcher headerMatcher = THREAD_HEADER_PATTERN.matcher(headerLine);
        if (!headerMatcher.matches()) {
            return null;
        }

        String name = headerMatcher.group(1).trim();
        String headerTail = headerMatcher.group(2);
        boolean daemon = containsWord(headerTail, "daemon");
        String nid = firstGroup(NID_PATTERN, headerLine, 1);
        String state = null;
        String stateDetail = null;
        String topFrame = null;
        List<String> waitingToLockIds = new ArrayList<>();
        List<String> parkingToWaitForIds = new ArrayList<>();
        List<String> lockedMonitorIds = new ArrayList<>();

        for (String line : sectionLines) {
            String trimmed = line.trim();
            if (state == null) {
                Matcher stateMatcher = THREAD_STATE_PATTERN.matcher(line);
                if (stateMatcher.find()) {
                    state = stateMatcher.group(1).trim();
                    stateDetail = trimmed.substring("java.lang.Thread.State:".length()).trim();
                }
            }

            if (topFrame == null && trimmed.startsWith("at ")) {
                topFrame = trimmed;
            }

            addMatches(waitingToLockIds, WAITING_TO_LOCK_PATTERN, line);
            addMatches(parkingToWaitForIds, PARKING_TO_WAIT_PATTERN, line);
            addMatches(lockedMonitorIds, LOCKED_MONITOR_PATTERN, line);
        }

        if (state == null) {
            state = inferStateFromHeader(headerLine);
            stateDetail = state;
        }

        return new ParsedThread(
            name,
            daemon,
            nid,
            state != null ? state : "UNKNOWN",
            stateDetail,
            topFrame,
            List.copyOf(waitingToLockIds),
            List.copyOf(parkingToWaitForIds),
            List.copyOf(lockedMonitorIds),
            detectPoolName(name),
            looksLikeIdleExecutorThread(sectionLines),
            deadlockedThreadNames.contains(name)
        );
    }

    private DeadlockSummary parseDeadlockSummary(String content) {
        Matcher headerMatcher = DEADLOCK_HEADER_PATTERN.matcher(content);
        if (!headerMatcher.find()) {
            return DeadlockSummary.notDetected();
        }

        int start = headerMatcher.start();
        int end = content.indexOf(DEADLOCK_STACK_INFO_MARKER, start);
        String section = end >= 0 ? content.substring(start, end) : content.substring(start);
        LinkedHashSet<String> threadNames = new LinkedHashSet<>();
        Matcher threadMatcher = DEADLOCK_THREAD_PATTERN.matcher(section);
        while (threadMatcher.find()) {
            threadNames.add(threadMatcher.group(1).trim());
        }

        String rawCount = headerMatcher.group(1);
        long reportedDeadlockCount = "one".equalsIgnoreCase(rawCount) ? 1L : Long.parseLong(rawCount);
        return new DeadlockSummary(
            true,
            reportedDeadlockCount,
            List.copyOf(threadNames),
            section.lines().findFirst().orElse("Found one Java-level deadlock:")
        );
    }

    private List<ContentionHotspot> buildContentionHotspots(List<ParsedThread> threads) {
        Map<String, List<ParsedThread>> waitersByMonitor = new LinkedHashMap<>();
        Map<String, ParsedThread> ownersByMonitor = new LinkedHashMap<>();

        for (ParsedThread thread : threads) {
            for (String monitorId : thread.waitingToLockIds()) {
                waitersByMonitor.computeIfAbsent(monitorId, ignored -> new ArrayList<>()).add(thread);
            }
            for (String monitorId : thread.lockedMonitorIds()) {
                ownersByMonitor.putIfAbsent(monitorId, thread);
            }
        }

        List<ContentionHotspot> contentionHotspots = new ArrayList<>();
        for (Map.Entry<String, List<ParsedThread>> entry : waitersByMonitor.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }

            String monitorId = entry.getKey();
            List<ParsedThread> waiters = entry.getValue();
            ParsedThread owner = ownersByMonitor.get(monitorId);
            long blockedWaiterCount = waiters.stream().filter(thread -> "BLOCKED".equals(thread.state())).count();
            List<String> waitingThreadNames = waiters.stream().map(ParsedThread::name).toList();

            contentionHotspots.add(new ContentionHotspot(
                monitorId,
                List.copyOf(waitingThreadNames),
                blockedWaiterCount,
                owner != null ? owner.name() : null,
                owner != null ? owner.state() : null,
                "thread-dump-contention-" + normalizeLockId(monitorId)
            ));
        }

        contentionHotspots.sort((left, right) -> {
            int compare = Long.compare(right.blockedWaiterCount(), left.blockedWaiterCount());
            if (compare != 0) {
                return compare;
            }
            compare = Integer.compare(right.waitingThreadNames().size(), left.waitingThreadNames().size());
            if (compare != 0) {
                return compare;
            }
            return left.monitorId().compareTo(right.monitorId());
        });
        return List.copyOf(contentionHotspots);
    }

    private List<PoolSummary> buildPoolSummaries(List<ParsedThread> threads) {
        Map<String, List<ParsedThread>> threadsByPool = new LinkedHashMap<>();
        for (ParsedThread thread : threads) {
            if (thread.poolName() != null) {
                threadsByPool.computeIfAbsent(thread.poolName(), ignored -> new ArrayList<>()).add(thread);
            }
        }

        List<PoolSummary> poolSummaries = new ArrayList<>();
        for (Map.Entry<String, List<ParsedThread>> entry : threadsByPool.entrySet()) {
            String poolName = entry.getKey();
            List<ParsedThread> poolThreads = entry.getValue();
            long runnableCount = countState(poolThreads, "RUNNABLE");
            long blockedCount = countState(poolThreads, "BLOCKED");
            long waitingCount = countState(poolThreads, "WAITING");
            long timedWaitingCount = countState(poolThreads, "TIMED_WAITING");
            long idleExecutorCount = poolThreads.stream().filter(ParsedThread::idleExecutorThread).count();
            long stalledThreadCount = poolThreads.stream()
                .filter(thread -> "BLOCKED".equals(thread.state())
                    || (("WAITING".equals(thread.state()) || "TIMED_WAITING".equals(thread.state())) && !thread.idleExecutorThread()))
                .count();

            poolSummaries.add(new PoolSummary(
                poolName,
                poolThreads.stream().map(ParsedThread::name).toList(),
                runnableCount,
                blockedCount,
                waitingCount,
                timedWaitingCount,
                idleExecutorCount,
                stalledThreadCount,
                "thread-dump-pool-" + slugify(poolName)
            ));
        }

        poolSummaries.sort((left, right) -> {
            int compare = Integer.compare(right.threadNames().size(), left.threadNames().size());
            if (compare != 0) {
                return compare;
            }
            return left.poolName().compareTo(right.poolName());
        });
        return List.copyOf(poolSummaries);
    }

    private List<String> threadNamesForState(List<ParsedThread> threads, String state) {
        return threads.stream()
            .filter(thread -> state.equals(thread.state()))
            .map(ParsedThread::name)
            .toList();
    }

    private Map<String, Long> stateCounts(List<ParsedThread> threads) {
        List<String> preferredOrder = List.of("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED", "UNKNOWN");
        Map<String, Long> discoveredCounts = new LinkedHashMap<>();
        for (ParsedThread thread : threads) {
            discoveredCounts.merge(thread.state(), 1L, Long::sum);
        }

        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String state : preferredOrder) {
            Long count = discoveredCounts.remove(state);
            if (count != null && count > 0) {
                ordered.put(state, count);
            }
        }

        for (Map.Entry<String, Long> entry : discoveredCounts.entrySet()) {
            if (entry.getValue() > 0) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        return ordered;
    }

    private String detectPoolName(String threadName) {
        if (threadName == null || threadName.isBlank()) {
            return null;
        }

        Matcher matcher = TRAILING_THREAD_INDEX_PATTERN.matcher(threadName);
        if (matcher.matches() && looksLikePoolName(matcher.group(1))) {
            return matcher.group(1);
        }

        return looksLikePoolName(threadName) ? threadName : null;
    }

    private boolean looksLikePoolName(String value) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return lowerValue.contains("pool")
            || lowerValue.contains("exec")
            || lowerValue.contains("worker")
            || lowerValue.contains("executor")
            || lowerValue.contains("scheduler");
    }

    private boolean looksLikeIdleExecutorThread(List<String> sectionLines) {
        String lowerSection = String.join("\n", sectionLines).toLowerCase(Locale.ROOT);
        return lowerSection.contains("threadpoolexecutor.gettask(")
            || lowerSection.contains("linkedblockingqueue.take(")
            || lowerSection.contains("arrayblockingqueue.take(")
            || lowerSection.contains("delayqueue.take(")
            || lowerSection.contains("delayedworkqueue.take(")
            || lowerSection.contains("synchronousqueue$transferstack.transfer(")
            || lowerSection.contains("synchronousqueue$transferqueue.transfer(")
            || lowerSection.contains("forkjoinpool.awaitwork(");
    }

    private String inferStateFromHeader(String headerLine) {
        String lowerHeader = headerLine.toLowerCase(Locale.ROOT);
        if (lowerHeader.contains("waiting for monitor entry")) {
            return "BLOCKED";
        }
        if (lowerHeader.contains("timed_waiting")) {
            return "TIMED_WAITING";
        }
        if (lowerHeader.contains("waiting on condition") || lowerHeader.contains("in object.wait()")) {
            return "WAITING";
        }
        if (lowerHeader.contains(" runnable ")) {
            return "RUNNABLE";
        }
        return "UNKNOWN";
    }

    private long countState(List<ParsedThread> threads, String state) {
        return threads.stream().filter(thread -> state.equals(thread.state())).count();
    }

    private void addMatches(List<String> target, Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            target.add(matcher.group(1).trim());
        }
    }

    private String firstGroup(Pattern pattern, String content, int group) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(group).trim() : null;
    }

    private String firstMatch(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group().trim() : null;
    }

    private boolean containsWord(String content, String word) {
        return content != null && Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(content).find();
    }

    private String normalizeLockId(String lockId) {
        return lockId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private String slugify(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
    }

    private Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        return source.isEmpty() ? Map.of() : Map.copyOf(source);
    }

    private record ParsedThread(
        String name,
        boolean daemon,
        String nid,
        String state,
        String stateDetail,
        String topFrame,
        List<String> waitingToLockIds,
        List<String> parkingToWaitForIds,
        List<String> lockedMonitorIds,
        String poolName,
        boolean idleExecutorThread,
        boolean deadlocked
    ) {
        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("name", name);
            canonical.put("daemon", daemon);
            if (nid != null) {
                canonical.put("nid", nid);
            }
            canonical.put("state", state);
            if (stateDetail != null) {
                canonical.put("stateDetail", stateDetail);
            }
            if (topFrame != null) {
                canonical.put("topFrame", topFrame);
            }
            canonical.put("waitingToLockIds", waitingToLockIds);
            canonical.put("parkingToWaitForIds", parkingToWaitForIds);
            canonical.put("lockedMonitorIds", lockedMonitorIds);
            if (poolName != null) {
                canonical.put("poolName", poolName);
            }
            canonical.put("idleExecutorThread", idleExecutorThread);
            canonical.put("deadlocked", deadlocked);
            return Map.copyOf(canonical);
        }
    }

    private record DeadlockSummary(
        boolean detected,
        long reportedDeadlockCount,
        List<String> threadNames,
        String snippet
    ) {
        private static DeadlockSummary notDetected() {
            return new DeadlockSummary(false, 0L, List.of(), null);
        }

        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("detected", detected);
            canonical.put("reportedDeadlockCount", reportedDeadlockCount);
            canonical.put("threadNames", threadNames);
            if (snippet != null) {
                canonical.put("snippet", snippet);
            }
            return Map.copyOf(canonical);
        }

        private Map<String, Object> evidenceMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("reportedDeadlockCount", reportedDeadlockCount);
            metrics.put("deadlockedThreadNames", threadNames);
            return Map.copyOf(metrics);
        }
    }

    private record ContentionHotspot(
        String monitorId,
        List<String> waitingThreadNames,
        long blockedWaiterCount,
        String ownerThreadName,
        String ownerState,
        String evidenceId
    ) {
        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("monitorId", monitorId);
            canonical.put("waiterCount", waitingThreadNames.size());
            canonical.put("blockedWaiterCount", blockedWaiterCount);
            canonical.put("waitingThreadNames", waitingThreadNames);
            if (ownerThreadName != null) {
                canonical.put("ownerThreadName", ownerThreadName);
            }
            if (ownerState != null) {
                canonical.put("ownerState", ownerState);
            }
            canonical.put("evidenceId", evidenceId);
            return Map.copyOf(canonical);
        }

        private String snippet() {
            return "waiting to lock <" + monitorId + ">";
        }

        private String evidenceDetail() {
            if (ownerThreadName == null) {
                return "Multiple threads are waiting for the same monitor in the thread dump.";
            }
            return "Multiple threads are waiting for the same monitor while another thread holds it.";
        }

        private Map<String, Object> evidenceMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("monitorId", monitorId);
            metrics.put("waiterCount", waitingThreadNames.size());
            metrics.put("blockedWaiterCount", blockedWaiterCount);
            metrics.put("waitingThreadNames", waitingThreadNames);
            if (ownerThreadName != null) {
                metrics.put("ownerThreadName", ownerThreadName);
            }
            if (ownerState != null) {
                metrics.put("ownerState", ownerState);
            }
            return Map.copyOf(metrics);
        }
    }

    private record PoolSummary(
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
        private Map<String, Object> toCanonicalMap() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("poolName", poolName);
            canonical.put("threadCount", threadNames.size());
            canonical.put("runnableCount", runnableCount);
            canonical.put("blockedCount", blockedCount);
            canonical.put("waitingCount", waitingCount);
            canonical.put("timedWaitingCount", timedWaitingCount);
            canonical.put("idleExecutorCount", idleExecutorCount);
            canonical.put("stalledThreadCount", stalledThreadCount);
            canonical.put("threadNames", threadNames);
            canonical.put("evidenceId", evidenceId);
            return Map.copyOf(canonical);
        }

        private String snippet() {
            return poolName;
        }

        private String evidenceDetail() {
            return "Aggregate state distribution for executor-style threads sharing the same pool prefix.";
        }

        private Map<String, Object> evidenceMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("poolName", poolName);
            metrics.put("threadCount", threadNames.size());
            metrics.put("runnableCount", runnableCount);
            metrics.put("blockedCount", blockedCount);
            metrics.put("waitingCount", waitingCount);
            metrics.put("timedWaitingCount", timedWaitingCount);
            metrics.put("idleExecutorCount", idleExecutorCount);
            metrics.put("stalledThreadCount", stalledThreadCount);
            metrics.put("threadNames", threadNames);
            return Map.copyOf(metrics);
        }
    }
}
