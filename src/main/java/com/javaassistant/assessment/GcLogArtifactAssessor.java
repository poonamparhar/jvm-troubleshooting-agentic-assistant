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
        Map<String, Object> humongousSummary = AssessmentSupport.map(parsedArtifact.extractedData(), "humongousSummary");
        Map<String, Object> collectorPressure = AssessmentSupport.map(parsedArtifact.extractedData(), "collectorPressureSummary");
        Map<String, Object> failureSummary = AssessmentSupport.map(parsedArtifact.extractedData(), "failureSummary");
        String collector = String.valueOf(parsedArtifact.extractedData().get("collector"));

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
        long humongousSampleCount = AssessmentSupport.longValue(humongousSummary, "sampleCount");
        long peakHumongousAfterRegions = AssessmentSupport.longValue(humongousSummary, "peakAfterRegions");
        long humongousGrowthEventCount = AssessmentSupport.longValue(humongousSummary, "growthEventCount");
        long humongousMaxGrowthRegions = AssessmentSupport.longValue(humongousSummary, "maxGrowthRegions");
        long evacuationFailurePauseCount = AssessmentSupport.longValue(collectorPressure, "evacuationFailurePauseCount");
        long toSpaceExhaustedCount = AssessmentSupport.longValue(collectorPressure, "toSpaceExhaustedCount");
        long fullCompactionAttemptCount = AssessmentSupport.longValue(collectorPressure, "fullCompactionAttemptCount");
        long concurrentModeFailureCount = AssessmentSupport.longValue(failureSummary, "concurrentModeFailureCount");
        long promotionFailedCount = AssessmentSupport.longValue(failureSummary, "promotionFailedCount");

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

        if ("G1".equals(collector)
            && humongousSampleCount >= 2L
            && peakHumongousAfterRegions >= 12L
            && (humongousGrowthEventCount >= 2L || humongousMaxGrowthRegions >= 4L)
            && (peakOccupancyRatio >= 0.85d || p95PauseMs >= 75.0d || fullGcCount >= 1L || evacuationFailurePauseCount >= 1L)) {
            String findingId = "gc-g1-humongous-pressure";
            SeverityLevel severity = (peakHumongousAfterRegions >= 128L && peakOccupancyRatio >= 0.95d)
                || toSpaceExhaustedCount >= 1L
                || evacuationFailurePauseCount >= 1L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "G1 humongous-region pressure is materially elevated",
                String.format(
                    "Humongous regions peaked at %d after GC across %d sampled windows, with %d growth events and a largest jump of +%d regions.",
                    peakHumongousAfterRegions,
                    humongousSampleCount,
                    humongousGrowthEventCount,
                    humongousMaxGrowthRegions
                ),
                "gc.g1-humongous",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-humongous-summary", "gc-heap-occupancy-peak", "gc-pause-distribution", "gc-full-gc-summary"),
                "Rising humongous-region counts reduce contiguous free-region headroom for G1 and can crowd out normal evacuation and mixed-collection recovery."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-g1-humongous-pressure",
                "Inspect the large objects or buffers driving humongous-region growth",
                "The GC log shows humongous-region usage growing enough to matter to G1 headroom.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Capture a heap histogram or heap dump if safe and identify the dominant large arrays, payload buffers, or retained objects that fit humongous allocation size.",
                    "Correlate the humongous-growth window with JFR allocation or old-object data to see whether the same large object family is being allocated or retained.",
                    "Treat region-size or heap-size tuning as secondary until you confirm which object shapes are consuming humongous regions."
                ),
                List.of(findingId)
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

        if (evacuationFailurePauseCount >= 1L && (toSpaceExhaustedCount >= 1L || fullCompactionAttemptCount >= 1L)) {
            String findingId = "gc-g1-evacuation-failure-distress";
            SeverityLevel severity = toSpaceExhaustedCount >= 1L || fullCompactionAttemptCount >= 2L || peakOccupancyRatio >= 0.98d
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "G1 evacuation failure and compaction distress detected",
                String.format(
                    "The log records %d evacuation-failure pauses, %d to-space exhaustion signals, and %d full-compaction attempts.",
                    evacuationFailurePauseCount,
                    toSpaceExhaustedCount,
                    fullCompactionAttemptCount
                ),
                "gc.g1-distress",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-pause-distribution", "gc-full-gc-summary", "gc-heap-occupancy-peak"),
                "Evacuation failure, to-space exhaustion, and compaction attempts are strong evidence that G1 can no longer preserve enough free-region headroom for normal progress."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-g1-evacuation-failure-distress",
                "Investigate the live-set growth or allocation burst behind G1 evacuation failure",
                "The GC log shows G1 failing to evacuate normally and falling back toward full compaction.",
                ActionType.IMMEDIATE,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Capture a heap histogram or heap dump if it is safe to do so and compare retained growth around the failure window.",
                    "Inspect whether humongous allocation, cache growth, or a sudden live-set increase reduced free-region headroom before the first evacuation failure.",
                    "Treat heap tuning as secondary until you understand what changed in retention or allocation behavior during the distress window."
                ),
                List.of(findingId)
            ));
        }

        if ("CMS".equals(collector) && promotionFailedCount >= 1L) {
            String findingId = "gc-cms-promotion-failure";
            SeverityLevel severity = promotionFailedCount >= 2L
                || fullGcCount >= 2L
                || peakOccupancyRatio >= 0.95d
                || concurrentModeFailureCount >= 1L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "CMS promotion failure indicates old-generation fragmentation or exhausted promotion headroom",
                String.format(
                    "The CMS log records %d promotion-failed signal(s), %d concurrent-mode-failure signal(s), and %d full GC(s).",
                    promotionFailedCount,
                    concurrentModeFailureCount,
                    fullGcCount
                ),
                "gc.cms-promotion-failure",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "gc-full-gc-summary", "gc-pause-distribution", "gc-longest-pause"),
                "Promotion-failed behavior in CMS is strong evidence that the old generation is too fragmented or too full to absorb more promoted objects cleanly."
            ));
            actions.add(AssessmentSupport.action(
                "action-gc-cms-promotion-failure",
                "Investigate CMS old-generation fragmentation and promotion pressure",
                "The GC log shows CMS failing to promote or compact cleanly under the current live-set conditions.",
                ActionType.IMMEDIATE,
                ActionPriority.HIGH,
                List.of(
                    "Compare survivor pressure, old-generation occupancy, and promotion volume around the first promotion-failed interval.",
                    "Inspect whether long-lived objects, fragmentation, or allocation bursts are exhausting contiguous CMS old-generation space.",
                    "Treat collector tuning as secondary until you understand what changed in retention or promotion behavior."
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
