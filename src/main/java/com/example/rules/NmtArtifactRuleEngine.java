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

public class NmtArtifactRuleEngine implements ArtifactRuleEngine {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.NMT;
    }

    @Override
    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> metaspaceSummary = RuleSupport.map(extractedData, "metaspaceSummary");
        Map<String, Object> threadSummary = RuleSupport.map(extractedData, "threadSummary");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categories = (Map<String, Map<String, Long>>) extractedData.getOrDefault("categories", Map.of());

        long metaspaceUsedKb = RuleSupport.longValue(metaspaceSummary, "usedKb");
        long metaspaceCommittedKb = RuleSupport.longValue(metaspaceSummary, "committedKb");
        if (metaspaceCommittedKb > 0) {
            double utilization = (double) metaspaceUsedKb / metaspaceCommittedKb;
            if (utilization >= 0.85d) {
                String findingId = "nmt-metaspace-pressure";
                findings.add(RuleSupport.finding(
                    parsedArtifact,
                    findingId,
                    "Metaspace pressure is high",
                    String.format("Metaspace is using %.1f%% of committed memory (%dKB of %dKB).", utilization * 100.0, metaspaceUsedKb, metaspaceCommittedKb),
                    "memory.metaspace",
                    SeverityLevel.HIGH,
                    ConfidenceLevel.HIGH,
                    FindingStatus.CONFIRMED,
                    List.of("nmt-total"),
                    "High metaspace utilization increases the risk of class metadata allocation failures."
                ));
                actions.add(RuleSupport.action(
                    "action-nmt-metaspace-pressure",
                    "Inspect class loading growth and metaspace limits",
                    "The class metadata footprint is close to the currently committed metaspace.",
                    ActionType.INVESTIGATION,
                    ActionPriority.HIGH,
                    List.of(
                        "Review recent class loading growth and dynamic proxy generation.",
                        "Capture another NMT snapshot or diff to confirm whether Class or Metaspace continues to grow.",
                        "Review MaxMetaspaceSize and class unloading behavior."
                    ),
                    List.of(findingId)
                ));
            }
        } else {
            missingData.add("Metaspace committed and used values were not parsed from the NMT artifact.");
        }

        Map<String, Long> gcCategory = categories.get("GC");
        if (gcCategory != null && gcCategory.getOrDefault("committedKb", 0L) >= 32_768L) {
            String findingId = "nmt-gc-native-pressure";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "GC native memory usage is elevated",
                String.format("The GC native memory category has %dKB committed.", gcCategory.get("committedKb")),
                "memory.native.gc",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of("nmt-total"),
                "Elevated GC native memory can accompany heap pressure or aggressive collector activity."
            ));
            actions.add(RuleSupport.action(
                "action-nmt-gc-native-pressure",
                "Correlate NMT GC usage with heap pressure and GC logs",
                "GC native memory is large enough to justify cross-artifact correlation.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare this NMT snapshot with GC pause behavior and heap occupancy.",
                    "Collect an NMT diff if only a single snapshot is available."
                ),
                List.of(findingId)
            ));
        }

        long threadCount = RuleSupport.longValue(threadSummary, "threadCount");
        long stackReservedKb = RuleSupport.longValue(threadSummary, "stackReservedKb");
        if (threadCount > 100 || stackReservedKb >= 32_768L) {
            String findingId = "nmt-thread-stack-pressure";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                findingId,
                "Thread stack native memory is elevated",
                String.format("Thread count is %d with %dKB reserved for stacks.", threadCount, stackReservedKb),
                "memory.native.threads",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of("nmt-total"),
                "Large thread counts or stack reservations can consume significant native memory."
            ));
        }

        return new RuleEvaluation(findings, actions, missingData);
    }
}
