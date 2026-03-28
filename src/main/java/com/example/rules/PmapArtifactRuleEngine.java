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

public class PmapArtifactRuleEngine implements ArtifactRuleEngine {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PMAP;
    }

    @Override
    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Map<String, Long> categoryBreakdown = (Map<String, Long>) parsedArtifact.extractedData().getOrDefault("categoryBreakdown", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Long> totals = (Map<String, Long>) parsedArtifact.extractedData().getOrDefault("totals", Map.of());

        long anonKb = categoryBreakdown.getOrDefault("anon", 0L);
        long totalSizeKb = totals.getOrDefault("sizeKb", 0L);
        if (totalSizeKb == 0L) {
            totalSizeKb = categoryBreakdown.values().stream().mapToLong(Long::longValue).sum();
        }

        if (totalSizeKb == 0L) {
            missingData.add("No pmap totals or category breakdown could be parsed.");
            return new RuleEvaluation(findings, actions, missingData);
        }

        double anonRatio = (double) anonKb / totalSizeKb;
        if (anonRatio >= 0.70d) {
            String findingId = "pmap-anon-pressure";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "Anonymous mappings dominate process memory",
                String.format("Anonymous mappings account for %.1f%% of parsed pmap size (%dKB of %dKB).", anonRatio * 100.0, anonKb, totalSizeKb),
                "memory.native.anon",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of("pmap-largest-mapping"),
                "Heavy anonymous memory usage is a strong signal of native or heap-backed allocation pressure."
            ));
            actions.add(RuleSupport.action(
                "action-pmap-anon-pressure",
                "Correlate anonymous memory growth with NMT and heap evidence",
                "The process address space is dominated by anonymous mappings.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare this pmap output with NMT categories to separate heap from other native allocations.",
                    "Look for large or growing anonymous mappings across snapshots."
                ),
                List.of(findingId)
            ));
        }

        return new RuleEvaluation(findings, actions, missingData);
    }
}
