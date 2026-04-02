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

public class HsErrArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HS_ERR_LOG;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        String signal = String.valueOf(parsedArtifact.extractedData().get("signal"));
        String crashType = String.valueOf(parsedArtifact.extractedData().getOrDefault("crashType", "unknown"));
        String currentThreadName = stringValue(parsedArtifact.extractedData().get("currentThreadName"));
        Map<String, Object> nativeAllocationFailure = AssessmentSupport.map(parsedArtifact.extractedData(), "nativeAllocationFailure");
        @SuppressWarnings("unchecked")
        Map<String, String> problematicFrame = (Map<String, String>) parsedArtifact.extractedData().getOrDefault("problematicFrame", Map.of());

        if ("native_allocation_failure".equals(crashType)) {
            long requestedBytes = AssessmentSupport.longValue(nativeAllocationFailure, "bytes");
            String allocator = stringValue(nativeAllocationFailure.get("allocator"));
            String requestSite = stringValue(nativeAllocationFailure.get("requestSite"));
            String findingId = "hs-err-native-allocation-failure";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JVM terminated after a native memory allocation failure",
                String.format(
                    "The JVM failed to allocate %d bytes via %s for %s.",
                    requestedBytes,
                    allocator != null ? allocator : "an unknown allocator",
                    requestSite != null ? requestSite : "an unknown request site"
                ),
                "crash.native-oom",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "hs-err-native-allocation-failure", "hs-err-vm-error", "hs-err-current-thread"),
                "The hs_err header directly reports a native allocation failure, which is strong evidence of JVM-native memory exhaustion or fragmentation."
            ));
            actions.add(AssessmentSupport.action(
                "action-hs-err-native-allocation-failure",
                "Treat the incident as JVM native-memory exhaustion and collect supporting evidence",
                "The hs_err log shows the JVM could not satisfy a native allocation request.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Capture NMT and pmap evidence from a comparable live process if one is still available.",
                    "Review Java heap sizing, thread count, stack size, and other native-headroom consumers before increasing heap limits.",
                    "Check whether compressed-oops heap placement or container memory limits are constraining native growth."
                ),
                List.of(findingId)
            ));

            if (currentThreadName != null && currentThreadName.contains("CompilerThread")) {
                String compilerFindingId = "hs-err-compiler-thread-native-oom";
                findings.add(AssessmentSupport.finding(
                    parsedArtifact,
                    compilerFindingId,
                    "Native allocation failure occurred on the JVM compiler path",
                    String.format(
                        "The current thread was %s and the failing request site was %s.",
                        currentThreadName,
                        requestSite != null ? requestSite : "not captured"
                    ),
                    "crash.native-compiler",
                    SeverityLevel.HIGH,
                    ConfidenceLevel.MEDIUM,
                    FindingStatus.LIKELY,
                    evidenceIds(parsedArtifact, "hs-err-current-thread", "hs-err-native-allocation-failure"),
                    "A native allocation failure on a compiler thread narrows the failure context to JVM compiler or code-generation activity rather than general application code."
                ));
                actions.add(AssessmentSupport.action(
                    "action-hs-err-compiler-thread-native-oom",
                    "Inspect compiler and code-generation pressure around the crash",
                    "The failing thread was a compiler thread, which narrows the likely JVM subsystem involved.",
                    ActionType.INVESTIGATION,
                    ActionPriority.HIGH,
                    List.of(
                        "Review compiler thread activity, compilation volume, and code-cache settings around the failure window.",
                        "Correlate with NMT Code-category data or Compiler.codecache output from similar runs if available.",
                        "Check whether unusual generated-code or compilation pressure was present before the crash."
                    ),
                    List.of(compilerFindingId, findingId)
                ));
            }
        }

        if (signal == null || "null".equals(signal)) {
            if (!"native_allocation_failure".equals(crashType)) {
                missingData.add("Fatal signal could not be extracted from the hs_err log.");
            }
            return new AssessmentResult(findings, actions, missingData);
        }

        String findingId = "hs-err-fatal-signal";
        findings.add(AssessmentSupport.finding(
            parsedArtifact,
            findingId,
            "Fatal JVM crash recorded",
            String.format("The JVM terminated with signal %s.", signal),
            "crash.signal",
            SeverityLevel.CRITICAL,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            evidenceIds(parsedArtifact, "hs-err-signal", "hs-err-problematic-frame"),
            "A fatal signal in an hs_err log is direct evidence of an incident-grade JVM crash."
        ));

        if (!problematicFrame.isEmpty() && String.valueOf(problematicFrame.get("symbol")).contains("G1FullGCMarker")) {
            String frameFindingId = "hs-err-g1-fullgc-crash";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                frameFindingId,
                "Crash occurred in JVM GC internals during G1 full GC",
                String.format("The problematic frame is %s.", problematicFrame.get("symbol")),
                "crash.gc",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "hs-err-problematic-frame", "hs-err-signal"),
                "The crash frame points into JVM GC internals rather than application code, which narrows the investigation scope."
            ));
            actions.add(AssessmentSupport.action(
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
