package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OomSignalArtifactParser implements ArtifactParser {

    private static final Pattern KERNEL_KILLED_PROCESS_PATTERN = Pattern.compile(
        "(?i)^.*Killed process (\\d+) \\(([^)]+)\\)(?: total-vm:(\\d+)kB, anon-rss:(\\d+)kB, file-rss:(\\d+)kB, shmem-rss:(\\d+)kB)?.*$"
    );
    private static final Pattern OOM_KILL_CONTEXT_LINE_PATTERN = Pattern.compile("(?i)^.*oom-kill:.*$");
    private static final Pattern OOM_MEMCG_PATTERN = Pattern.compile("(?i)\\boom_memcg=([^,\\s]+)");
    private static final Pattern OOM_TASK_PID_PATTERN = Pattern.compile("(?i)\\btask=([^,\\s]+),pid=(\\d+)");
    private static final Pattern KERNEL_TIMESTAMP_PATTERN = Pattern.compile("^([A-Z][a-z]{2}\\s+\\d{1,2}\\s\\d{2}:\\d{2}:\\d{2})\\s+\\S+\\s+kernel:.*$");
    private static final Pattern POD_NAME_PATTERN = Pattern.compile("(?m)^Name:\\s+(\\S+)\\s*$");
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("(?m)^Namespace:\\s+(\\S+)\\s*$");
    private static final Pattern POD_START_TIME_PATTERN = Pattern.compile("(?m)^Start Time:\\s+(.+)$");
    private static final DateTimeFormatter KERNEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter POD_TIME_FORMATTER = DateTimeFormatter.ofPattern(
        "EEE, d MMM yyyy HH:mm:ss Z",
        Locale.ENGLISH
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.OOM_SIGNAL;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        List<String> lines = ParserUtils.lines(artifact.content());
        int inferredKernelYear = inferredKernelLogYear(artifact);
        List<Map<String, Object>> kernelEvents = parseKernelEvents(lines, inferredKernelYear);
        String podName = firstGroup(POD_NAME_PATTERN, artifact.content());
        String namespace = firstGroup(NAMESPACE_PATTERN, artifact.content());
        String podStartTimeText = firstGroup(POD_START_TIME_PATTERN, artifact.content());
        List<Map<String, Object>> podSignals = parsePodSignals(lines, podName, namespace);
        Set<String> processNames = new LinkedHashSet<>();
        Set<String> oomMemcgPaths = new LinkedHashSet<>();
        long maxRestartCount = 0L;
        long crashLoopBackOffCount = 0L;
        long podOomKilledCount = 0L;
        Instant earliestAbsoluteEventTime = parsePodTime(podStartTimeText);
        Instant latestAbsoluteEventTime = earliestAbsoluteEventTime;

        for (Map<String, Object> kernelEvent : kernelEvents) {
            String processName = stringValue(kernelEvent, "processName");
            if (processName != null && !processName.isBlank()) {
                processNames.add(processName);
            }
            String taskName = stringValue(kernelEvent, "taskName");
            if (taskName != null && !taskName.isBlank()) {
                processNames.add(taskName);
            }
            String oomMemcgPath = stringValue(kernelEvent, "oomMemcgPath");
            if (oomMemcgPath != null && !oomMemcgPath.isBlank()) {
                oomMemcgPaths.add(oomMemcgPath);
            }
            Instant kernelEventTime = instantValue(kernelEvent.get("eventTime"));
            earliestAbsoluteEventTime = earlierInstant(earliestAbsoluteEventTime, kernelEventTime);
            latestAbsoluteEventTime = laterInstant(latestAbsoluteEventTime, kernelEventTime);
        }

        for (Map<String, Object> podSignal : podSignals) {
            if (booleanValue(podSignal, "oomKilled")) {
                podOomKilledCount++;
            }
            if (booleanValue(podSignal, "crashLoopBackOff")) {
                crashLoopBackOffCount++;
            }
            maxRestartCount = Math.max(maxRestartCount, longValue(podSignal, "restartCount"));
            earliestAbsoluteEventTime = earlierInstant(
                earliestAbsoluteEventTime,
                instantValue(podSignal.get("startedAt")),
                instantValue(podSignal.get("finishedAt"))
            );
            latestAbsoluteEventTime = laterInstant(
                latestAbsoluteEventTime,
                instantValue(podSignal.get("startedAt")),
                instantValue(podSignal.get("finishedAt"))
            );
        }

        Set<String> sourceKinds = new LinkedHashSet<>();
        if (!kernelEvents.isEmpty()) {
            sourceKinds.add("kernel-log");
        }
        if (!podSignals.isEmpty()) {
            sourceKinds.add("kubernetes-status");
        }

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("kernelOomKillCount", kernelEvents.stream().filter(event -> booleanValue(event, "killedProcessLine")).count());
        summary.put("kernelContextCount", kernelEvents.stream().filter(event -> booleanValue(event, "oomContextLine")).count());
        summary.put("kernelMemcgCount", oomMemcgPaths.size());
        summary.put("podOomKilledCount", podOomKilledCount);
        summary.put("crashLoopBackOffCount", crashLoopBackOffCount);
        summary.put("maxRestartCount", maxRestartCount);
        if (!processNames.isEmpty()) {
            summary.put("processNames", List.copyOf(processNames));
        }
        if (!oomMemcgPaths.isEmpty()) {
            summary.put("oomMemcgPaths", List.copyOf(oomMemcgPaths));
        }
        if (!sourceKinds.isEmpty()) {
            summary.put("sourceKinds", List.copyOf(sourceKinds));
        }
        if (podName != null) {
            summary.put("podName", podName);
        }
        if (namespace != null) {
            summary.put("namespace", namespace);
        }
        if (podStartTimeText != null) {
            summary.put("podStartTimeText", podStartTimeText);
        }
        if (earliestAbsoluteEventTime != null) {
            summary.put("earliestAbsoluteEventTime", earliestAbsoluteEventTime.toString());
        }
        if (latestAbsoluteEventTime != null) {
            summary.put("latestAbsoluteEventTime", latestAbsoluteEventTime.toString());
        }

        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> firstKernelKilledEvent = kernelEvents.stream()
            .filter(event -> booleanValue(event, "killedProcessLine"))
            .findFirst()
            .orElse(null);
        if (firstKernelKilledEvent != null) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            putIfPresent(metrics, "pid", firstKernelKilledEvent.get("pid"));
            putIfPresent(metrics, "processName", firstKernelKilledEvent.get("processName"));
            putIfPresent(metrics, "totalVmKb", firstKernelKilledEvent.get("totalVmKb"));
            putIfPresent(metrics, "anonRssKb", firstKernelKilledEvent.get("anonRssKb"));
            putIfPresent(metrics, "fileRssKb", firstKernelKilledEvent.get("fileRssKb"));
            putIfPresent(metrics, "shmemRssKb", firstKernelKilledEvent.get("shmemRssKb"));
            evidence.add(ParserUtils.evidence(
                "oom-signal-kernel-event",
                artifact,
                "Kernel OOM kill event",
                "Linux kernel or memory-cgroup log line showing a process killed by OOM handling.",
                stringValue(firstKernelKilledEvent, "line"),
                metrics
            ));
        }

        Map<String, Object> firstKernelContext = kernelEvents.stream()
            .filter(event -> booleanValue(event, "oomContextLine"))
            .findFirst()
            .orElse(null);
        if (firstKernelContext != null) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            putIfPresent(metrics, "oomMemcgPath", firstKernelContext.get("oomMemcgPath"));
            putIfPresent(metrics, "taskName", firstKernelContext.get("taskName"));
            putIfPresent(metrics, "pid", firstKernelContext.get("pid"));
            evidence.add(ParserUtils.evidence(
                "oom-signal-kernel-context",
                artifact,
                "Kernel OOM context",
                "Kernel oom-kill context including cgroup path and task identity.",
                stringValue(firstKernelContext, "line"),
                metrics
            ));
        }

        if (!podSignals.isEmpty()) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("podOomKilledCount", podOomKilledCount);
            metrics.put("crashLoopBackOffCount", crashLoopBackOffCount);
            metrics.put("maxRestartCount", maxRestartCount);
            putIfPresent(metrics, "podName", podName);
            putIfPresent(metrics, "namespace", namespace);
            evidence.add(ParserUtils.evidence(
                "oom-signal-pod-summary",
                artifact,
                "Pod OOMKilled and restart signals",
                "Kubernetes-style pod status output showing OOMKilled terminations or restart loops.",
                artifact.content().contains("Reason: OOMKilled") ? "Reason: OOMKilled" : "Restart Count:",
                metrics
            ));
        }

        if (kernelEvents.isEmpty() && podSignals.isEmpty()) {
            warnings.add("No kernel OOM or restart signals were parsed from the artifact.");
        }

        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("summary", summary);
        extractedData.put("kernelEvents", List.copyOf(kernelEvents));
        extractedData.put("podSignals", List.copyOf(podSignals));

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "oom-signal-v1", extractedData, evidence, warnings);
    }

    private List<Map<String, Object>> parseKernelEvents(List<String> lines, int inferredYear) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : lines) {
            Instant kernelEventTime = parseKernelLogTime(line, inferredYear);
            Matcher killedMatcher = KERNEL_KILLED_PROCESS_PATTERN.matcher(line);
            if (killedMatcher.matches()) {
                LinkedHashMap<String, Object> event = new LinkedHashMap<>();
                event.put("line", line.trim());
                event.put("killedProcessLine", true);
                event.put("oomContextLine", false);
                putIfPresent(event, "eventTime", kernelEventTime != null ? kernelEventTime.toString() : null);
                event.put("pid", Long.parseLong(killedMatcher.group(1)));
                event.put("processName", killedMatcher.group(2));
                putIfPresent(event, "totalVmKb", parseLong(killedMatcher.group(3)));
                putIfPresent(event, "anonRssKb", parseLong(killedMatcher.group(4)));
                putIfPresent(event, "fileRssKb", parseLong(killedMatcher.group(5)));
                putIfPresent(event, "shmemRssKb", parseLong(killedMatcher.group(6)));
                events.add(Map.copyOf(event));
                continue;
            }

            Matcher contextMatcher = OOM_KILL_CONTEXT_LINE_PATTERN.matcher(line);
            if (contextMatcher.matches()) {
                LinkedHashMap<String, Object> event = new LinkedHashMap<>();
                event.put("line", line.trim());
                event.put("killedProcessLine", false);
                event.put("oomContextLine", true);
                putIfPresent(event, "eventTime", kernelEventTime != null ? kernelEventTime.toString() : null);
                Matcher memcgMatcher = OOM_MEMCG_PATTERN.matcher(line);
                if (memcgMatcher.find()) {
                    putIfPresent(event, "oomMemcgPath", blankToNull(memcgMatcher.group(1)));
                }
                Matcher taskMatcher = OOM_TASK_PID_PATTERN.matcher(line);
                if (taskMatcher.find()) {
                    putIfPresent(event, "taskName", blankToNull(taskMatcher.group(1)));
                    putIfPresent(event, "pid", parseLong(taskMatcher.group(2)));
                }
                events.add(Map.copyOf(event));
            }
        }
        return List.copyOf(events);
    }

    private int inferredKernelLogYear(InputArtifact artifact) {
        LocalDateTime discoveredAt = artifact != null && artifact.metadata() != null ? artifact.metadata().discoveredAt() : null;
        return discoveredAt != null ? discoveredAt.getYear() : LocalDateTime.now(ZoneId.systemDefault()).getYear();
    }

    private List<Map<String, Object>> parsePodSignals(List<String> lines, String podName, String namespace) {
        List<Map<String, Object>> podSignals = new ArrayList<>();
        boolean inContainersSection = false;
        String currentSection = null;
        PodSignalAccumulator currentContainer = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if ("Containers:".equals(trimmed)) {
                inContainersSection = true;
                currentSection = null;
                continue;
            }

            if (inContainersSection && !line.isBlank() && !line.startsWith(" ")) {
                addPodSignalIfInteresting(podSignals, currentContainer);
                inContainersSection = false;
                currentContainer = null;
                currentSection = null;
            }

            if (!inContainersSection) {
                continue;
            }

            if (line.matches("^  [A-Za-z0-9._-]+:\\s*$")) {
                addPodSignalIfInteresting(podSignals, currentContainer);
                currentContainer = new PodSignalAccumulator(trimmed.substring(0, trimmed.length() - 1), podName, namespace);
                currentSection = null;
                continue;
            }

            if (currentContainer == null) {
                continue;
            }

            if (line.startsWith("    ") && !line.startsWith("      ")) {
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex < 0) {
                    currentSection = null;
                    continue;
                }
                String key = trimmed.substring(0, colonIndex).trim();
                String value = trimmed.substring(colonIndex + 1).trim();
                switch (key) {
                    case "State" -> {
                        currentContainer.state = value;
                        currentSection = "State";
                    }
                    case "Last State" -> {
                        currentContainer.lastState = value;
                        currentSection = "Last State";
                    }
                    case "Restart Count" -> currentContainer.restartCount = parseLong(value);
                    default -> currentSection = null;
                }
                continue;
            }

            if (line.startsWith("      ") && currentSection != null) {
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex < 0) {
                    continue;
                }
                String key = trimmed.substring(0, colonIndex).trim();
                String value = trimmed.substring(colonIndex + 1).trim();
                if ("State".equals(currentSection)) {
                    if ("Reason".equals(key)) {
                        currentContainer.stateReason = value;
                    }
                } else if ("Last State".equals(currentSection)) {
                    if ("Reason".equals(key)) {
                        currentContainer.lastStateReason = value;
                    } else if ("Exit Code".equals(key)) {
                        currentContainer.exitCode = parseLong(value);
                    } else if ("Started".equals(key)) {
                        currentContainer.started = value;
                    } else if ("Finished".equals(key)) {
                        currentContainer.finished = value;
                    }
                }
            }
        }

        addPodSignalIfInteresting(podSignals, currentContainer);
        return List.copyOf(podSignals);
    }

    private void addPodSignalIfInteresting(List<Map<String, Object>> podSignals, PodSignalAccumulator candidate) {
        if (candidate == null || !candidate.isInteresting()) {
            return;
        }
        podSignals.add(candidate.toCanonicalMap());
    }

    private String firstGroup(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Instant parsePodTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim(), POD_TIME_FORMATTER).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Instant parseKernelLogTime(String line, int inferredYear) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher matcher = KERNEL_TIMESTAMP_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(inferredYear + " " + matcher.group(1), DateTimeFormatter.ofPattern("yyyy MMM d HH:mm:ss", Locale.ENGLISH));
            return parsed.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant earlierInstant(Instant baseline, Instant... candidates) {
        Instant earliest = baseline;
        for (Instant candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    private Instant laterInstant(Instant baseline, Instant... candidates) {
        Instant latest = baseline;
        for (Instant candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (latest == null || candidate.isAfter(latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean booleanValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static final class PodSignalAccumulator {
        private final String containerName;
        private final String podName;
        private final String namespace;
        private String state;
        private String stateReason;
        private String lastState;
        private String lastStateReason;
        private Long exitCode;
        private Long restartCount;
        private String started;
        private String finished;

        private PodSignalAccumulator(String containerName, String podName, String namespace) {
            this.containerName = containerName;
            this.podName = podName;
            this.namespace = namespace;
        }

        private boolean isInteresting() {
            return isOomKilled() || isCrashLoopBackOff() || (restartCount != null && restartCount > 0L);
        }

        private boolean isOomKilled() {
            return "OOMKilled".equalsIgnoreCase(lastStateReason);
        }

        private boolean isCrashLoopBackOff() {
            return "CrashLoopBackOff".equalsIgnoreCase(stateReason);
        }

        private Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("containerName", containerName);
            if (podName != null) {
                canonical.put("podName", podName);
            }
            if (namespace != null) {
                canonical.put("namespace", namespace);
            }
            if (state != null) {
                canonical.put("state", state);
            }
            if (stateReason != null) {
                canonical.put("stateReason", stateReason);
            }
            if (lastState != null) {
                canonical.put("lastState", lastState);
            }
            if (lastStateReason != null) {
                canonical.put("lastStateReason", lastStateReason);
            }
            if (exitCode != null) {
                canonical.put("exitCode", exitCode);
            }
            if (restartCount != null) {
                canonical.put("restartCount", restartCount);
            }
            if (started != null) {
                canonical.put("started", started);
                Instant startedAt = parsePodTime(started);
                if (startedAt != null) {
                    canonical.put("startedAt", startedAt.toString());
                }
            }
            if (finished != null) {
                canonical.put("finished", finished);
                Instant finishedAt = parsePodTime(finished);
                if (finishedAt != null) {
                    canonical.put("finishedAt", finishedAt.toString());
                }
            }
            canonical.put("oomKilled", isOomKilled());
            canonical.put("crashLoopBackOff", isCrashLoopBackOff());
            return Map.copyOf(canonical);
        }
    }
}
