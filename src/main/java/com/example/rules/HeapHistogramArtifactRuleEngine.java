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
import java.util.Locale;
import java.util.Map;

public class HeapHistogramArtifactRuleEngine implements ArtifactRuleEngine {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HEAP_HISTOGRAM;
    }

    @Override
    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        List<Map<String, Object>> entries = RuleSupport.mapList(parsedArtifact.extractedData(), "entries");
        if (entries.isEmpty()) {
            missingData.add("No heap histogram entries were parsed.");
            return new RuleEvaluation(findings, actions, missingData);
        }

        long totalParsedBytes = entries.stream().mapToLong(entry -> RuleSupport.longValue(entry, "bytes")).sum();
        Map<String, Object> topEntry = entries.get(0);
        long topBytes = RuleSupport.longValue(topEntry, "bytes");
        double topShare = totalParsedBytes > 0 ? (double) topBytes / totalParsedBytes : 0.0d;

        if (topShare >= 0.30d) {
            findings.add(RuleSupport.finding(
                parsedArtifact,
                "histogram-top-heavy-consumer",
                "A single heap class dominates retained bytes",
                String.format("%s accounts for %.1f%% of parsed heap histogram bytes.", topEntry.get("className"), topShare * 100.0),
                "heap.distribution",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of("histogram-top-consumer"),
                "A single dominant class can indicate a retention hotspot or an imbalanced object distribution."
            ));
        }

        for (Map<String, Object> entry : entries) {
            String className = String.valueOf(entry.get("className")).toLowerCase(Locale.ROOT);
            long bytes = RuleSupport.longValue(entry, "bytes");
            long instances = RuleSupport.longValue(entry, "instances");
            if ((className.contains("cache") || className.contains("concurrenthashmap") || className.contains("hashmap$entry"))
                && (bytes >= 200_000L || instances >= 5_000L)) {
                String findingId = "histogram-cache-retention";
                findings.add(RuleSupport.finding(
                    parsedArtifact,
                    findingId,
                    "Heap histogram suggests cache or collection retention",
                    String.format("%s retains %d instances and %d bytes.", entry.get("className"), instances, bytes),
                    "heap.retention",
                    SeverityLevel.MEDIUM,
                    ConfidenceLevel.MEDIUM,
                    FindingStatus.LIKELY,
                    List.of("histogram-top-consumer"),
                    "Large cache-like or map-entry populations often correlate with retained application state."
                ));
                actions.add(RuleSupport.action(
                    "action-histogram-cache-retention",
                    "Review cache growth and retention policies",
                    "The histogram shows substantial memory retained in cache-like structures.",
                    ActionType.INVESTIGATION,
                    ActionPriority.MEDIUM,
                    List.of(
                        "Review cache eviction settings and recent traffic growth.",
                        "Compare a later heap histogram to see whether these classes continue growing.",
                        "Check whether retained entries correspond to expected business state."
                    ),
                    List.of(findingId)
                ));
                break;
            }
        }

        return new RuleEvaluation(findings, actions, missingData);
    }
}
