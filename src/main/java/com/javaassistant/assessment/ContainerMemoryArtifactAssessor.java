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

public class ContainerMemoryArtifactAssessor implements ArtifactAssessor {

    private static final long SIXTEEN_MIB = 16L * 1024L * 1024L;
    private static final long SIXTY_FOUR_MIB = 64L * 1024L * 1024L;

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.CONTAINER_MEMORY;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> summary = AssessmentSupport.map(parsedArtifact.extractedData(), "summary");
        Map<String, Object> events = AssessmentSupport.map(parsedArtifact.extractedData(), "events");
        Map<String, Object> pressure = AssessmentSupport.map(parsedArtifact.extractedData(), "pressure");
        Map<String, Object> cpuSummary = AssessmentSupport.map(parsedArtifact.extractedData(), "cpuSummary");
        Map<String, Object> cpuStat = AssessmentSupport.map(parsedArtifact.extractedData(), "cpuStat");
        Map<String, Object> cpuPressure = AssessmentSupport.map(parsedArtifact.extractedData(), "cpuPressure");
        Map<String, Object> somePressure = AssessmentSupport.map(pressure, "some");
        Map<String, Object> fullPressure = AssessmentSupport.map(pressure, "full");
        Map<String, Object> cpuSomePressure = AssessmentSupport.map(cpuPressure, "some");
        Map<String, Object> cpuFullPressure = AssessmentSupport.map(cpuPressure, "full");

        long currentBytes = AssessmentSupport.longValue(summary, "currentBytes");
        boolean maxDefined = boolValue(summary, "maxDefined");
        long maxBytes = AssessmentSupport.longValue(summary, "maxBytes");
        long headroomToMaxBytes = AssessmentSupport.longValue(summary, "headroomToMaxBytes");
        double usageOfMaxRatio = AssessmentSupport.doubleValue(summary, "usageOfMaxRatio");
        boolean highDefined = boolValue(summary, "highDefined");
        long highBytes = AssessmentSupport.longValue(summary, "highBytes");
        double usageOfHighRatio = AssessmentSupport.doubleValue(summary, "usageOfHighRatio");

        long highEvents = AssessmentSupport.longValue(events, "high");
        long maxEvents = AssessmentSupport.longValue(events, "max");
        long oomEvents = AssessmentSupport.longValue(events, "oom");
        long oomKillEvents = AssessmentSupport.longValue(events, "oom_kill");

        double someAvg10 = AssessmentSupport.doubleValue(somePressure, "avg10");
        double someAvg60 = AssessmentSupport.doubleValue(somePressure, "avg60");
        double fullAvg10 = AssessmentSupport.doubleValue(fullPressure, "avg10");
        double fullAvg60 = AssessmentSupport.doubleValue(fullPressure, "avg60");
        boolean cpuQuotaDefined = boolValue(cpuSummary, "quotaDefined");
        double cpuQuotaCores = AssessmentSupport.doubleValue(cpuSummary, "quotaCores");
        double configuredCpuCeilingCores = AssessmentSupport.doubleValue(cpuSummary, "configuredCpuCeilingCores");
        long effectiveCpuCount = AssessmentSupport.longValue(cpuSummary, "effectiveCpuCount");
        long nrPeriods = AssessmentSupport.longValue(cpuStat, "nr_periods");
        long nrThrottled = AssessmentSupport.longValue(cpuStat, "nr_throttled");
        double throttledRatio = AssessmentSupport.doubleValue(cpuStat, "throttledRatio");
        long throttledMillis = AssessmentSupport.longValue(cpuStat, "throttledMillis");
        double cpuSomeAvg10 = AssessmentSupport.doubleValue(cpuSomePressure, "avg10");
        double cpuFullAvg10 = AssessmentSupport.doubleValue(cpuFullPressure, "avg10");

        if (currentBytes == 0L && !maxDefined && !highDefined && events.isEmpty() && pressure.isEmpty()) {
            missingData.add("The container memory snapshot did not contain parseable current, limit, event, or PSI sections.");
            return new AssessmentResult(findings, actions, missingData);
        }

        if (!maxDefined) {
            missingData.add("memory.max is missing or set to max, so hard-limit headroom cannot be evaluated.");
        }
        if (!highDefined) {
            missingData.add("memory.high is missing or set to max, so reclaim-threshold headroom cannot be evaluated.");
        }
        if (pressure.isEmpty()) {
            missingData.add("memory.pressure is missing, so reclaim stall pressure cannot be evaluated.");
        }

        if (maxDefined && maxBytes > 0L && (usageOfMaxRatio >= 0.90d || headroomToMaxBytes <= SIXTY_FOUR_MIB || maxEvents > 0L)) {
            String findingId = "container-memory-limit-pressure";
            SeverityLevel severity = (usageOfMaxRatio >= 0.98d || headroomToMaxBytes <= SIXTEEN_MIB || oomEvents > 0L || oomKillEvents > 0L)
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            ConfidenceLevel confidence = (usageOfMaxRatio >= 0.95d || maxEvents > 0L)
                ? ConfidenceLevel.HIGH
                : ConfidenceLevel.MEDIUM;

            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Container memory budget is nearly exhausted",
                String.format(
                    Locale.ROOT,
                    "memory.current is %s of %s (%.1f%%) with %s headroom remaining; memory.events max=%d, oom=%d, oom_kill=%d.",
                    humanBytes(currentBytes),
                    humanBytes(maxBytes),
                    usageOfMaxRatio * 100.0d,
                    humanBytes(headroomToMaxBytes),
                    maxEvents,
                    oomEvents,
                    oomKillEvents
                ),
                "memory.container.limit",
                severity,
                confidence,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "container-memory-summary", "container-memory-events"),
                "When cgroup usage is close to memory.max or the max counter is already incrementing, the JVM is operating inside a very small remaining container memory budget."
            ));
            actions.add(AssessmentSupport.action(
                "action-container-memory-limit-pressure",
                "Treat the incident as container-budget pressure, not a JVM-only tuning issue",
                "The cgroup is close to or already touching its configured memory.max ceiling.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Reconcile container limits with JVM heap, native memory, thread stacks, and page-cache expectations.",
                    "Capture a matching NMT summary, heap histogram, or pmap snapshot from the same interval to separate heap, native, and page-cache pressure.",
                    "Review deployment memory requests and limits before increasing Xmx in isolation."
                ),
                List.of(findingId)
            ));
        }

        if (highDefined && highBytes > 0L && (usageOfHighRatio >= 1.0d || highEvents > 0L)) {
            String findingId = "container-memory-high-pressure";
            SeverityLevel severity = (usageOfHighRatio >= 1.10d || highEvents >= 50L || fullAvg10 >= 0.10d)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = highEvents > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;

            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Container memory.high is being breached",
                String.format(
                    Locale.ROOT,
                    "memory.current is %s against memory.high %s (%.1f%%) and memory.events high=%d.",
                    humanBytes(currentBytes),
                    humanBytes(highBytes),
                    usageOfHighRatio * 100.0d,
                    highEvents
                ),
                "memory.container.reclaim",
                severity,
                confidence,
                highEvents > 0L ? FindingStatus.CONFIRMED : FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "container-memory-summary", "container-memory-events"),
                "Repeated memory.high breaches indicate the cgroup is already reclaiming or throttling around its soft limit before a hard OOM occurs."
            ));
            actions.add(AssessmentSupport.action(
                "action-container-memory-high-pressure",
                "Investigate why the container is living above memory.high",
                "Repeated high-limit breaches can cause reclaim churn and latency even before a hard OOM.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Check whether recent memory growth is heap, native, or file-cache driven before raising memory.high.",
                    "Align the memory.high setting with the expected steady-state footprint rather than only the hard limit.",
                    "Capture another container snapshot plus JVM memory artifacts to see whether the same pressure persists."
                ),
                List.of(findingId)
            ));
        }

        if (oomEvents > 0L || oomKillEvents > 0L) {
            String findingId = "container-memory-oom-events";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Container cgroup recorded OOM activity",
                String.format(
                    Locale.ROOT,
                    "memory.events reports oom=%d and oom_kill=%d.",
                    oomEvents,
                    oomKillEvents
                ),
                "memory.container.oom",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "container-memory-events", "container-memory-summary"),
                "OOM and oom_kill counters are direct kernel-level evidence that the cgroup ran out of memory budget."
            ));
            actions.add(AssessmentSupport.action(
                "action-container-memory-oom-events",
                "Preserve container OOM evidence and align it with JVM memory artifacts",
                "The cgroup has already recorded OOM activity, so the limit was not just theoretical.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve the container memory snapshot, pod or container restart reason, and any matching JVM crash or exit logs.",
                    "Compare the same time window with GC, NMT, heap histogram, and pmap evidence to identify the dominant memory consumer.",
                    "Review container limits, pod QoS, and JVM heap sizing before retrying the workload."
                ),
                List.of(findingId)
            ));
        }

        if (someAvg10 >= 1.0d || someAvg60 >= 0.5d || fullAvg10 >= 0.10d || fullAvg60 >= 0.05d) {
            String findingId = "container-memory-reclaim-stalls";
            SeverityLevel severity = (fullAvg10 >= 0.50d || someAvg10 >= 5.0d)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = (fullAvg10 > 0.0d || someAvg10 >= 2.0d)
                ? ConfidenceLevel.HIGH
                : ConfidenceLevel.MEDIUM;

            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Container memory pressure is causing reclaim stalls",
                String.format(
                    Locale.ROOT,
                    "memory.pressure shows some avg10=%.2f avg60=%.2f and full avg10=%.2f avg60=%.2f.",
                    someAvg10,
                    someAvg60,
                    fullAvg10,
                    fullAvg60
                ),
                "memory.container.psi",
                severity,
                confidence,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "container-memory-pressure", "container-memory-events"),
                "PSI memory pressure means the container has recently spent measurable time waiting on reclaim, which can directly translate into latency and throughput loss."
            ));
            actions.add(AssessmentSupport.action(
                "action-container-memory-reclaim-stalls",
                "Correlate PSI reclaim stalls with JVM latency and memory growth",
                "The cgroup is spending enough time under memory reclaim pressure to affect the workload.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare the PSI interval with GC pauses, allocator stalls, and request latency from the same window.",
                    "Check whether memory.high breaches, file cache growth, or native-memory growth line up with the PSI spikes.",
                    "Capture another snapshot after load changes to confirm whether reclaim pressure drops."
                ),
                List.of(findingId)
            ));
        }

        boolean tightCpuBudget = configuredCpuCeilingCores > 0.0d && configuredCpuCeilingCores <= 1.0d;
        boolean throttledCpu = nrThrottled > 0L && (throttledRatio >= 0.10d || throttledMillis >= 1_000L);
        boolean cpuPressureObserved = cpuSomeAvg10 >= 1.0d || cpuFullAvg10 >= 0.05d;
        if (tightCpuBudget || throttledCpu || cpuPressureObserved) {
            String findingId = "container-cpu-quota-or-processor-mis-sizing";
            SeverityLevel severity = throttledRatio >= 0.25d
                || throttledMillis >= 5_000L
                || cpuFullAvg10 >= 0.10d
                || configuredCpuCeilingCores > 0.0d && configuredCpuCeilingCores <= 0.5d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = throttledCpu || cpuPressureObserved ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;

            StringBuilder summaryText = new StringBuilder();
            if (cpuQuotaDefined && cpuQuotaCores > 0.0d) {
                summaryText.append(String.format(Locale.ROOT, "cpu quota limits the container to %s", humanCores(cpuQuotaCores)));
            }
            if (effectiveCpuCount > 0L) {
                if (!summaryText.isEmpty()) {
                    summaryText.append("; ");
                }
                summaryText.append(String.format(Locale.ROOT, "cpuset exposes %d effective CPU(s)", effectiveCpuCount));
            }
            if (nrThrottled > 0L) {
                if (!summaryText.isEmpty()) {
                    summaryText.append("; ");
                }
                summaryText.append(String.format(
                    Locale.ROOT,
                    "cpu.stat shows %d throttled period(s) out of %d (%.1f%%) with %s throttled time",
                    nrThrottled,
                    nrPeriods,
                    throttledRatio * 100.0d,
                    humanDurationMillis(throttledMillis)
                ));
            }
            if (cpuPressureObserved) {
                if (!summaryText.isEmpty()) {
                    summaryText.append("; ");
                }
                summaryText.append(String.format(
                    Locale.ROOT,
                    "cpu.pressure shows some avg10=%.2f and full avg10=%.2f",
                    cpuSomeAvg10,
                    cpuFullAvg10
                ));
            }
            if (!summaryText.isEmpty()) {
                summaryText.append('.');
            }

            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Container CPU budget is constrained or actively throttling",
                summaryText.isEmpty()
                    ? "The container snapshot indicates a constrained CPU budget or active throttling."
                    : summaryText.toString(),
                "cpu.container.quota",
                severity,
                confidence,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "container-cpu-summary", "container-cpu-stat", "container-cpu-pressure"),
                "A small configured CPU ceiling, repeated cgroup throttling, or CPU PSI pressure is direct evidence that the container is CPU-constrained before JVM tuning enters the picture."
            ));
            actions.add(AssessmentSupport.action(
                "action-container-cpu-quota-or-processor-mis-sizing",
                "Treat the incident as container CPU budget pressure before tuning JVM internals",
                "The cgroup CPU budget is either too small for the workload or already being throttled.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare the CPU quota, cpuset, and throttling counters with application runnable-thread counts and request latency from the same interval.",
                    "Check whether container CPU limits, pod requests, or processor affinity are smaller than the workload and thread-pool design expect.",
                    "Correlate this CPU budget view with JFR execution hot paths before treating GC or heap sizing as the primary fix."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private boolean boolValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
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

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1fGiB", bytes / (1024.0d * 1024.0d * 1024.0d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1fMiB", bytes / (1024.0d * 1024.0d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1fKiB", bytes / 1024.0d);
        }
        return bytes + "B";
    }

    private String humanCores(double cores) {
        return String.format(Locale.ROOT, "%.2f CPU", cores);
    }

    private String humanDurationMillis(long durationMs) {
        if (durationMs >= 1_000L) {
            return String.format(Locale.ROOT, "%.2fs", durationMs / 1_000.0d);
        }
        return durationMs + "ms";
    }
}
