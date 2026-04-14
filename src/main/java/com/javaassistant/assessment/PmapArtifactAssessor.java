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

public class PmapArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PMAP;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Map<String, Long> categoryBreakdown = (Map<String, Long>) parsedArtifact.extractedData().getOrDefault("categoryBreakdown", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Long> totals = (Map<String, Long>) parsedArtifact.extractedData().getOrDefault("totals", Map.of());
        Map<String, Object> summary = AssessmentSupport.map(parsedArtifact.extractedData(), "summary");

        long totalSizeKb = AssessmentSupport.longValue(summary, "totalSizeKb");
        if (totalSizeKb == 0L) {
            totalSizeKb = totals.getOrDefault("sizeKb", 0L);
        }
        if (totalSizeKb == 0L) {
            totalSizeKb = categoryBreakdown.values().stream().mapToLong(Long::longValue).sum();
        }

        long anonSizeKb = AssessmentSupport.longValue(summary, "anonSizeKb");
        if (anonSizeKb == 0L) {
            anonSizeKb = categoryBreakdown.getOrDefault("anon", 0L);
        }

        if (totalSizeKb == 0L) {
            missingData.add("No pmap totals or category breakdown could be parsed.");
            return new AssessmentResult(findings, actions, missingData);
        }

        boolean rssAvailable = boolValue(summary, "rssAvailable") || totals.containsKey("rssKb");
        long totalRssKb = AssessmentSupport.longValue(summary, "totalRssKb");
        if (totalRssKb == 0L) {
            totalRssKb = totals.getOrDefault("rssKb", 0L);
        }
        long anonRssKb = AssessmentSupport.longValue(summary, "anonRssKb");
        long reservedGapKb = AssessmentSupport.longValue(summary, "reservedGapKb");

        double anonVirtualShare = AssessmentSupport.doubleValue(summary, "anonVirtualShare");
        if (anonVirtualShare == 0.0d && anonSizeKb > 0L) {
            anonVirtualShare = (double) anonSizeKb / (double) totalSizeKb;
        }

        double anonResidentShare = AssessmentSupport.doubleValue(summary, "anonResidentShare");
        if (anonResidentShare == 0.0d && rssAvailable && anonRssKb > 0L && totalRssKb > 0L) {
            anonResidentShare = (double) anonRssKb / (double) totalRssKb;
        }

        double anonResidentRatio = AssessmentSupport.doubleValue(summary, "anonResidentRatio");
        if (anonResidentRatio == 0.0d && rssAvailable && anonRssKb > 0L && anonSizeKb > 0L) {
            anonResidentRatio = (double) anonRssKb / (double) anonSizeKb;
        }

        double reservedGapRatio = AssessmentSupport.doubleValue(summary, "reservedGapRatio");
        if (reservedGapRatio == 0.0d && rssAvailable && reservedGapKb > 0L) {
            reservedGapRatio = (double) reservedGapKb / (double) totalSizeKb;
        }

        if (!rssAvailable) {
            missingData.add("This pmap snapshot does not include RSS or Dirty columns, so only virtual-size signals are available.");
        }

        if (anonVirtualShare >= 0.70d) {
            String findingId = "pmap-anon-pressure";
            SeverityLevel severity = anonVirtualShare >= 0.85d || (rssAvailable && anonResidentShare >= 0.60d)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            ConfidenceLevel confidence = anonVirtualShare >= 0.85d || !rssAvailable
                ? ConfidenceLevel.HIGH
                : ConfidenceLevel.MEDIUM;

            String summaryText = rssAvailable
                ? String.format(
                    "Anonymous mappings account for %.1f%% of parsed virtual size (%dKB of %dKB) and %.1f%% of RSS (%dKB of %dKB); only %.1f%% of anonymous space is currently resident.",
                    anonVirtualShare * 100.0d,
                    anonSizeKb,
                    totalSizeKb,
                    anonResidentShare * 100.0d,
                    anonRssKb,
                    totalRssKb,
                    anonResidentRatio * 100.0d
                )
                : String.format(
                    "Anonymous mappings account for %.1f%% of parsed pmap size (%dKB of %dKB).",
                    anonVirtualShare * 100.0d,
                    anonSizeKb,
                    totalSizeKb
                );

            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Anonymous mappings dominate process memory",
                summaryText,
                "memory.native.anon",
                severity,
                confidence,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "pmap-largest-mapping", "pmap-largest-resident-mapping", "pmap-resident-gap"),
                "Heavy anonymous mappings are a strong correlation signal, but RSS determines whether the pressure is mostly reserved address space or resident native load."
            ));
            actions.add(AssessmentSupport.action(
                "action-pmap-anon-pressure",
                "Correlate anonymous mappings with NMT, heap, and RSS",
                "The process address space is heavily anonymous and needs resident-versus-reserved interpretation.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare anonymous RSS and reserved gap with NMT committed categories to separate reserved space from resident native growth.",
                    "Check whether the biggest anonymous mappings are mostly reserved (for example -----) or actively resident rw--- segments.",
                    "Capture a later pmap snapshot to see whether RSS, not just virtual size, continues to grow."
                ),
                List.of(findingId)
            ));
        }

        if (rssAvailable && anonVirtualShare >= 0.85d && anonResidentRatio <= 0.15d && reservedGapRatio >= 0.80d) {
            String findingId = "pmap-virtual-resident-mismatch";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Virtual pmap footprint is much larger than the resident footprint",
                String.format(
                    "The process maps %dKB of anonymous space but only %dKB is resident; total reserved-but-not-resident space is %dKB.",
                    anonSizeKb,
                    anonRssKb,
                    reservedGapKb
                ),
                "memory.native.mismatch",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.HIGH,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "pmap-resident-gap", "pmap-largest-mapping", "pmap-largest-resident-mapping"),
                "Large anonymous virtual reservations with low resident coverage usually mean much of the address space is reserved but not actively consuming RAM."
            ));
            actions.add(AssessmentSupport.action(
                "action-pmap-virtual-resident-mismatch",
                "Treat reserved address space separately from resident RAM pressure",
                "The pmap output shows a wide gap between mapped size and resident usage.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare pmap RSS with NMT committed values and current heap occupancy before calling the total mapping size a leak.",
                    "Focus on the largest resident anonymous mappings and any growing rw--- regions, not only the biggest reserved ----- segments.",
                    "Use a second snapshot or NMT diff to confirm whether resident memory follows the reserved growth."
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
}
