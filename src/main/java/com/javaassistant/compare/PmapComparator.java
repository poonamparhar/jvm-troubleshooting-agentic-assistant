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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PmapComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PMAP;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Long> baselineTotals = totals(baselineParsed);
        Map<String, Long> currentTotals = totals(currentParsed);
        Map<String, Long> baselineCategories = categories(baselineParsed);
        Map<String, Long> currentCategories = categories(currentParsed);
        Map<String, Object> baselineSummary = summary(baselineParsed);
        Map<String, Object> currentSummary = summary(currentParsed);

        if (baselineTotals.isEmpty() || currentTotals.isEmpty()) {
            missingData.add("PMAP comparison could not determine total memory sizes for one or both snapshots.");
            return new AssessmentResult(findings, actions, missingData);
        }

        long sizeDeltaKb = currentTotals.getOrDefault("sizeKb", 0L) - baselineTotals.getOrDefault("sizeKb", 0L);
        long anonSizeDeltaKb = currentCategories.getOrDefault("anon", 0L) - baselineCategories.getOrDefault("anon", 0L);

        boolean baselineRssAvailable = boolValue(baselineSummary, "rssAvailable") || baselineTotals.containsKey("rssKb");
        boolean currentRssAvailable = boolValue(currentSummary, "rssAvailable") || currentTotals.containsKey("rssKb");

        if (baselineRssAvailable != currentRssAvailable) {
            missingData.add("Only one pmap snapshot includes RSS metrics, so resident-memory comparison is partial.");
        }

        if (baselineRssAvailable && currentRssAvailable) {
            long baselineRssKb = totalRssKb(baselineParsed, baselineTotals, baselineSummary);
            long currentRssKb = totalRssKb(currentParsed, currentTotals, currentSummary);
            long baselineAnonRssKb = anonRssKb(baselineParsed, baselineSummary);
            long currentAnonRssKb = anonRssKb(currentParsed, currentSummary);
            long rssDeltaKb = currentRssKb - baselineRssKb;
            long anonRssDeltaKb = currentAnonRssKb - baselineAnonRssKb;

            if (rssDeltaKb >= 8_000L || anonRssDeltaKb >= 8_000L) {
                String findingId = "compare-pmap-growth";
                findings.add(new Finding(
                    findingId,
                    "Resident pmap footprint grew between snapshots",
                    String.format(
                        "Total RSS changed by %dKB, anonymous RSS changed by %dKB, and total mapped size changed by %dKB.",
                        rssDeltaKb,
                        anonRssDeltaKb,
                        sizeDeltaKb
                    ),
                    "compare.pmap-growth",
                    SeverityLevel.HIGH,
                    ConfidenceLevel.HIGH,
                    FindingStatus.CONFIRMED,
                    List.of(path(baselineParsed), path(currentParsed)),
                    evidenceIds(currentParsed, "pmap-largest-resident-mapping", "pmap-resident-gap", "pmap-largest-mapping"),
                    "Resident-memory growth between pmap snapshots is a stronger signal of real footprint increase than virtual-size changes alone."
                ));
                actions.add(new RecommendedAction(
                    "action-compare-pmap-growth",
                    "Trace the resident-memory growth between pmap snapshots",
                    "The process RSS increased materially between the two snapshots.",
                    ActionType.INVESTIGATION,
                    ActionPriority.HIGH,
                    List.of(
                        "Correlate anonymous RSS growth with NMT committed categories and heap evidence from the same time window.",
                        "Check whether the resident growth is concentrated in rw--- anonymous mappings, libraries, or another category.",
                        "Repeat the snapshot if the process is still alive to confirm the RSS trend."
                    ),
                    List.of(findingId)
                ));
            } else if (sizeDeltaKb >= 32_000L || anonSizeDeltaKb >= 32_000L) {
                String findingId = "compare-pmap-reserved-expansion";
                findings.add(new Finding(
                    findingId,
                    "Virtual address space expanded without matching RSS growth",
                    String.format(
                        "Total mapped size changed by %dKB and anonymous mappings changed by %dKB, but total RSS changed by only %dKB.",
                        sizeDeltaKb,
                        anonSizeDeltaKb,
                        rssDeltaKb
                    ),
                    "compare.pmap-reservation",
                    SeverityLevel.MEDIUM,
                    ConfidenceLevel.MEDIUM,
                    FindingStatus.LIKELY,
                    List.of(path(baselineParsed), path(currentParsed)),
                    evidenceIds(currentParsed, "pmap-resident-gap", "pmap-largest-mapping"),
                    "When mapped size grows but resident memory stays flat, the change is more likely to be reserved address space than active RAM pressure."
                ));
                actions.add(new RecommendedAction(
                    "action-compare-pmap-reserved-expansion",
                    "Verify whether the new mappings are reserved or committed",
                    "The virtual footprint grew, but the resident set stayed mostly flat.",
                    ActionType.INVESTIGATION,
                    ActionPriority.MEDIUM,
                    List.of(
                        "Compare the pmap deltas with NMT reserved-versus-committed changes from the same interval.",
                        "Inspect whether the new large mappings are mostly ----- reservations rather than resident rw--- regions.",
                        "Use another later snapshot to see whether RSS eventually follows the new address-space reservations."
                    ),
                    List.of(findingId)
                ));
            }

            return new AssessmentResult(findings, actions, missingData);
        }

        if (sizeDeltaKb > 5_000L || anonSizeDeltaKb > 5_000L) {
            String findingId = "compare-pmap-growth";
            findings.add(new Finding(
                findingId,
                "Process memory map grew between snapshots",
                String.format("Total mapped size changed by %dKB and anonymous mappings changed by %dKB.", sizeDeltaKb, anonSizeDeltaKb),
                "compare.pmap-growth",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "pmap-largest-mapping"),
                "Large positive deltas in total or anonymous mappings are the best available growth signal when RSS metrics are unavailable."
            ));
            actions.add(new RecommendedAction(
                "action-compare-pmap-growth",
                "Trace the address-space growth between baseline and current pmap snapshots",
                "The process mapping footprint increased materially between the two snapshots.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Correlate pmap growth with NMT and heap histogram snapshots from the same time window.",
                    "Check whether anonymous mapping growth tracks native memory categories or heap growth.",
                    "Repeat the snapshot if the process is still alive to confirm the trend."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> totals(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("totals");
        return value instanceof Map<?, ?> ? (Map<String, Long>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> categories(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("categoryBreakdown");
        return value instanceof Map<?, ?> ? (Map<String, Long>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> rssCategories(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("rssCategoryBreakdown");
        return value instanceof Map<?, ?> ? (Map<String, Long>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("summary");
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private long totalRssKb(ParsedArtifact parsedArtifact, Map<String, Long> totals, Map<String, Object> summary) {
        long summaryValue = longValue(summary, "totalRssKb");
        if (summaryValue > 0L) {
            return summaryValue;
        }
        return totals.getOrDefault("rssKb", 0L);
    }

    private long anonRssKb(ParsedArtifact parsedArtifact, Map<String, Object> summary) {
        long summaryValue = longValue(summary, "anonRssKb");
        if (summaryValue > 0L) {
            return summaryValue;
        }
        return rssCategories(parsedArtifact).getOrDefault("anon", 0L);
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
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

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
