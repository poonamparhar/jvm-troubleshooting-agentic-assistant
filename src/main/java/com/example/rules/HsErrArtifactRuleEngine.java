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

public class HsErrArtifactRuleEngine implements ArtifactRuleEngine {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HS_ERR_LOG;
    }

    @Override
    public RuleEvaluation evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        String signal = String.valueOf(parsedArtifact.extractedData().get("signal"));
        @SuppressWarnings("unchecked")
        Map<String, String> problematicFrame = (Map<String, String>) parsedArtifact.extractedData().getOrDefault("problematicFrame", Map.of());

        if (signal == null || "null".equals(signal)) {
            missingData.add("Fatal signal could not be extracted from the hs_err log.");
            return new RuleEvaluation(findings, actions, missingData);
        }

        String findingId = "hs-err-fatal-signal";
        findings.add(RuleSupport.finding(
            parsedArtifact,
            findingId,
            "Fatal JVM crash recorded",
            String.format("The JVM terminated with signal %s.", signal),
            "crash.signal",
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            List.of("hs-err-problematic-frame"),
            "A fatal signal in an hs_err log is direct evidence of an incident-grade JVM crash."
        ));

        if (!problematicFrame.isEmpty() && String.valueOf(problematicFrame.get("symbol")).contains("G1FullGCMarker")) {
            String frameFindingId = "hs-err-g1-fullgc-crash";
            findings.add(RuleSupport.finding(
                parsedArtifact,
                frameFindingId,
                "Crash occurred in JVM GC internals during G1 full GC",
                String.format("The problematic frame is %s.", problematicFrame.get("symbol")),
                "crash.gc",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of("hs-err-problematic-frame"),
                "The crash frame points into JVM GC internals rather than application code, which narrows the investigation scope."
            ));
            actions.add(RuleSupport.action(
                "action-hs-err-g1-fullgc-crash",
                "Preserve the crash artifact and correlate with memory pressure signals",
                "The problematic frame suggests the crash happened during GC work inside the JVM.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve the hs_err file and any matching GC logs from the same run.",
                    "Check whether heap pressure or repeated full GCs preceded the crash.",
                    "Capture JVM version and compare against known issues before changing application code."
                ),
                List.of(findingId, frameFindingId)
            ));
        }

        return new RuleEvaluation(findings, actions, missingData);
    }
}
