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

public class GcLogArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> summary = AssessmentSupport.map(parsedArtifact.extractedData(), "summary");
        Map<String, Object> metaspace = AssessmentSupport.map(parsedArtifact.extractedData(), "metaspace");

        long eventCount = AssessmentSupport.longValue(summary, "eventCount");
        long pauseEventCount = AssessmentSupport.longValue(summary, "pauseEventCount");
        long fullGcCount = AssessmentSupport.longValue(summary, "fullGcCount");
        long metaspaceTriggeredFullGcCount = AssessmentSupport.longValue(summary, "metaspaceTriggeredFullGcCount");
        long allocationStallCount = AssessmentSupport.longValue(summary, "allocationStallCount");
        double averagePauseMs = AssessmentSupport.doubleValue(summary, "averagePauseMs");
        double p95PauseMs = AssessmentSupport.doubleValue(summary, "p95PauseMs");
        double maxFullGcPauseMs = AssessmentSupport.doubleValue(summary, "maxFullGcPauseMs");
        double peakOccupancyRatio = AssessmentSupport.doubleValue(summary, "peakHeapOccupancyRatio");
        double totalAllocationStallMs = AssessmentSupport.doubleValue(summary, "totalAllocationStallMs");
        double maxAllocationStallMs = AssessmentSupport.doubleValue(summary, "maxAllocationStallMs");
        double stopTheWorldOverheadPct = AssessmentSupport.doubleValue(summary, "stopTheWorldOverheadPct");
        double peakMetaspaceUsageRatio = AssessmentSupport.doubleValue(metaspace, "peakUsageRatio");

        if (eventCount == 0L) {
            missingData.add("No major GC events were parsed from the GC log.");
            return new AssessmentResult(findings, actions, missingData);
        }

        if (fullGcCount >= 3L && maxFullGcPauseMs >= 200.0d) {
            String findingId = "gc-repeated-full-gcs";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Repeated long full GCs detected",
                String.format("The GC log contains %d full GCs with a maximum full-GC pause of %.3fms.", fullGcCount, maxFullGcPauseMs),
                "gc.full",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-full-gc-summary", "gc-longest-pause"),
                "Repeated multi-hundred-millisecond full GCs are incident-grade and indicate severe heap or allocation pressure."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-repeated-full-gcs",
                "Treat the heap as saturated and gather immediate supporting evidence",
                "Repeated long full GCs suggest the JVM is close to exhausting viable heap headroom.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Capture a heap histogram or heap dump if safe to do so.",
                    "Review recent allocation spikes and cache growth.",
                    "Correlate with NMT or pmap output to determine whether pressure is heap-only or mixed native plus heap."
                ),
                List.of(findingId)
            ));
        }

        if (peakOccupancyRatio >= 0.95d) {
            String findingId = "gc-heap-saturation";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Heap occupancy is near capacity after GC",
                String.format("Peak post-GC occupancy reached %.1f%% of heap capacity.", peakOccupancyRatio * 100.0d),
                "gc.heap-pressure",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-heap-occupancy-peak", "gc-longest-pause"),
                "High post-GC occupancy indicates the collector is not recovering enough live data."
            ));
        }

        if (pauseEventCount >= 3L && fullGcCount == 0L && (p95PauseMs >= 100.0d || averagePauseMs >= 100.0d)) {
            String findingId = "gc-pause-latency";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "GC pause latency is elevated",
                String.format("Average pause time is %.3fms and the p95 pause is %.3fms.", averagePauseMs, p95PauseMs),
                "gc.latency",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "gc-pause-distribution", "gc-longest-pause"),
                "Sustained pause latency can affect application responsiveness even without repeated full GCs."
            ));
        }

        if (allocationStallCount >= 3L && (maxAllocationStallMs >= 5.0d || totalAllocationStallMs >= 20.0d)) {
            String findingId = "gc-allocation-stall-pressure";
            SeverityLevel severity = allocationStallCount >= 10L
                || maxAllocationStallMs >= 10.0d
                || totalAllocationStallMs >= 100.0d
                || stopTheWorldOverheadPct >= 10.0d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Application threads are stalling on allocation pressure",
                String.format(
                    "The log records %d allocation stalls with a maximum stall of %.3fms and %.3fms total stall time.",
                    allocationStallCount,
                    maxAllocationStallMs,
                    totalAllocationStallMs
                ),
                "gc.allocation-stall",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-allocation-stall-summary", "gc-heap-occupancy-peak"),
                "Repeated allocation stalls mean the application is blocking while the collector scrambles for memory headroom."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-allocation-stall-pressure",
                "Reduce allocation pressure and capture supporting memory evidence",
                "The GC log shows repeated application-thread allocation stalls.",
                ActionType.IMMEDIATE,
                ActionPriority.HIGH,
                List.of(
                    "Capture a heap histogram and recent allocation profile from a comparable live process if available.",
                    "Review the allocation rate, cache growth, and burst traffic around the stall window.",
                    "Correlate with heap occupancy, NMT, or pmap data to see whether headroom is constrained by heap sizing or mixed native pressure."
                ),
                List.of(findingId)
            ));
        }

        if (metaspaceTriggeredFullGcCount >= 2L || (metaspaceTriggeredFullGcCount >= 1L && peakMetaspaceUsageRatio >= 0.80d)) {
            String findingId = "gc-metaspace-full-gcs";
            SeverityLevel severity = metaspaceTriggeredFullGcCount >= 4L || peakMetaspaceUsageRatio >= 0.85d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Metaspace pressure is triggering full GC activity",
                String.format(
                    "The log shows %d metaspace-triggered full GCs and peak metaspace usage reached %.1f%% of committed capacity.",
                    metaspaceTriggeredFullGcCount,
                    peakMetaspaceUsageRatio * 100.0d
                ),
                "gc.metaspace",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-full-gc-summary", "gc-metaspace-summary"),
                "Metadata-triggered full GCs are strong evidence that class metadata growth is contributing directly to GC distress."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-metaspace-full-gcs",
                "Inspect class loading growth and metaspace headroom",
                "The GC log shows full GCs being triggered by metadata pressure.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Collect a class histogram and NMT summary from a comparable live process if one is still running.",
                    "Review recent deployment behavior for dynamic class generation, proxy creation, or classloader churn.",
                    "Inspect metaspace sizing and unloading behavior before raising limits."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
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
}
