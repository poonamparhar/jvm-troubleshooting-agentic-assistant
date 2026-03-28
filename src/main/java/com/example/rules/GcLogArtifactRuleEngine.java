package com.example.rules;

import com.example.model.ActionPriority;
import com.example.model.ActionType;
import com.example.model.ArtifactType;
import com.example.model.ConfidenceLevel;
import com.example.model.Finding;
import com.example.model.FindingStatus;
import com.example.model.ParsedArtifact;
import com.example.model.RecommendedAction;
import com.example.model.SeverityLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GcLogArtifactRuleEngine implements ArtifactRuleEngine {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> summary = RuleSupport.map(parsedArtifact.extractedData(), "summary");
        long eventCount = RuleSupport.longValue(summary, "eventCount");
        long fullGcCount = RuleSupport.longValue(summary, "fullGcCount");
        double maxPauseMs = RuleSupport.doubleValue(summary, "maxPauseMs");
        double avgPauseMs = RuleSupport.doubleValue(summary, "averagePauseMs");
        double peakOccupancyRatio = RuleSupport.doubleValue(summary, "peakHeapOccupancyRatio");

        if (eventCount == 0) {
            missingData.add("No GC pause events were parsed from the GC log.");
            return new RuleEvaluation(findings, actions, missingData);
        }

        if (fullGcCount >= 3 && maxPauseMs >= 200.0d) {
            String findingId = "gc-repeated-full-gcs";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "Repeated long full GCs detected",
                String.format("The GC log contains %d full GCs with a maximum pause of %.3fms.", fullGcCount, maxPauseMs),
                "gc.full",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of("gc-longest-pause"),
                "Repeated multi-hundred-millisecond full GCs are incident-grade and indicate severe heap or allocation pressure."
            ));
            actions.add(RuleSupport.action(
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
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "Heap occupancy is near capacity after GC",
                String.format("Peak post-GC occupancy reached %.1f%% of heap capacity.", peakOccupancyRatio * 100.0),
                "gc.heap-pressure",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of("gc-longest-pause"),
                "High post-GC occupancy indicates the collector is not recovering enough live data."
            ));
        }

        if (avgPauseMs >= 100.0d && fullGcCount == 0) {
            String findingId = "gc-pause-latency";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "GC pause latency is elevated",
                String.format("Average pause time is %.3fms.", avgPauseMs),
                "gc.latency",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of("gc-longest-pause"),
                "Sustained pause latency can affect application responsiveness even without repeated full GCs."
            ));
        }

        return new RuleEvaluation(findings, actions, missingData);
    }
}
