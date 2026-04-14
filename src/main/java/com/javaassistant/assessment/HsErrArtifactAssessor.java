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

        String signal = stringValue(parsedArtifact.extractedData().get("signal"));
        String crashType = String.valueOf(parsedArtifact.extractedData().getOrDefault("crashType", "unknown"));
        String currentThreadName = stringValue(parsedArtifact.extractedData().get("currentThreadName"));
        Map<String, Object> nativeThreadExhaustion = AssessmentSupport.map(parsedArtifact.extractedData(), "nativeThreadExhaustion");
        Map<String, Object> nativeAllocationFailure = AssessmentSupport.map(parsedArtifact.extractedData(), "nativeAllocationFailure");
        Map<String, Object> compressedClassSpaceFailure = AssessmentSupport.map(parsedArtifact.extractedData(), "compressedClassSpaceFailure");
        Map<String, Object> codeCacheStatus = AssessmentSupport.map(parsedArtifact.extractedData(), "codeCacheStatus");
        @SuppressWarnings("unchecked")
        Map<String, String> problematicFrame = (Map<String, String>) parsedArtifact.extractedData().getOrDefault("problematicFrame", Map.of());

        if ("native_thread_exhaustion".equals(crashType)) {
            String reason = stringValue(nativeThreadExhaustion.get("reason"));
            String summary = reason != null && !reason.isBlank()
                ? reason
                : "The hs_err log reports that the JVM could not create another native thread.";
            String findingId = "hs-err-native-thread-exhaustion";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JVM failed after exhausting native thread creation headroom",
                currentThreadName != null && !currentThreadName.isBlank()
                    ? summary + " The current thread was " + currentThreadName + "."
                    : summary,
                "crash.native-threads",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "hs-err-native-thread-exhaustion", "hs-err-vm-error", "hs-err-current-thread"),
                "An hs_err log that explicitly reports inability to create a native thread is direct evidence of thread-limit or native-headroom exhaustion."
            ));
            actions.add(AssessmentSupport.action(
                "action-hs-err-native-thread-exhaustion",
                "Investigate thread growth, stack size, and operating-system thread limits together",
                "The hs_err log shows the JVM could not create another native thread.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Inspect live thread count, executor growth, and blocked thread pools before changing heap settings.",
                    "Check process and container thread limits such as ulimit -u, pid limits, and any recent spikes in thread creation.",
                    "Review Java thread stack sizing with -Xss because larger stacks reduce how many native threads the process can sustain."
                ),
                List.of(findingId)
            ));
        }

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

        if ("compressed_class_space_oom".equals(crashType)) {
            String reason = stringValue(compressedClassSpaceFailure.get("reason"));
            long requestedBytes = AssessmentSupport.longValue(compressedClassSpaceFailure, "requestedBytes");
            String summary = reason != null && !reason.isBlank()
                ? reason
                : "The hs_err log reports compressed class space exhaustion.";
            if (requestedBytes > 0L) {
                summary += " The failing class-space allocation request was " + requestedBytes + " bytes.";
            }
            if (currentThreadName != null && !currentThreadName.isBlank()) {
                summary += " The current thread was " + currentThreadName + ".";
            }

            String findingId = "hs-err-compressed-class-space-oom";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JVM terminated after exhausting compressed class space",
                summary,
                "crash.compressed-class-space",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "hs-err-compressed-class-space", "hs-err-vm-error", "hs-err-current-thread"),
                "An hs_err log that explicitly reports compressed class space exhaustion is direct evidence that class-metadata allocation headroom was exhausted."
            ));
            actions.add(AssessmentSupport.action(
                "action-hs-err-compressed-class-space-oom",
                "Treat the incident as class-metadata exhaustion, not heap-only pressure",
                "The hs_err log shows the JVM ran out of compressed class space.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Capture NMT or class-loader evidence from a comparable live process and inspect the Class section, class count, and compressed class space usage together.",
                    "Review class-loader churn, dynamic class generation, proxy creation, and redeploy or reload behavior before changing heap settings.",
                    "Review `CompressedClassSpaceSize` only as temporary mitigation until you understand why class definitions or loaders kept growing."
                ),
                List.of(findingId)
            ));
        }

        if ("code_cache_full".equals(crashType) || !codeCacheStatus.isEmpty()) {
            long sizeKb = AssessmentSupport.longValue(codeCacheStatus, "sizeKb");
            long usedKb = AssessmentSupport.longValue(codeCacheStatus, "usedKb");
            long freeKb = AssessmentSupport.longValue(codeCacheStatus, "freeKb");
            boolean compilerDisabled = Boolean.TRUE.equals(codeCacheStatus.get("compilerDisabled"));
            String reason = stringValue(codeCacheStatus.get("reason"));
            String findingId = "hs-err-code-cache-full";
            StringBuilder summaryText = new StringBuilder();
            if (reason != null && !reason.isBlank()) {
                summaryText.append(reason);
            } else {
                summaryText.append("The hs_err log reports code cache exhaustion.");
            }
            if (sizeKb > 0L && usedKb > 0L) {
                summaryText.append(String.format(" Code cache used %dKB of %dKB", usedKb, sizeKb));
                if (freeKb > 0L) {
                    summaryText.append(String.format(" with only %dKB free", freeKb));
                }
                summaryText.append('.');
            }
            if (currentThreadName != null && !currentThreadName.isBlank()) {
                summaryText.append(" The current thread was ").append(currentThreadName).append('.');
            }
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "hs_err log records code cache exhaustion or compiler disablement",
                summaryText.toString(),
                "crash.code-cache",
                signal != null && !signal.isBlank() ? SeverityLevel.CRITICAL : SeverityLevel.HIGH,
                (sizeKb > 0L && usedKb > 0L) || compilerDisabled ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "hs-err-code-cache-status", "hs-err-current-thread", "hs-err-problematic-frame"),
                "An hs_err log that explicitly reports a full code cache or compiler disablement is direct evidence that compiled-code headroom was exhausted."
            ));
            actions.add(AssessmentSupport.action(
                "action-hs-err-code-cache-full",
                "Inspect code-cache headroom and compilation pressure around the crash",
                "The hs_err log shows the compiler ran out of usable code-cache space.",
                ActionType.IMMEDIATE,
                ActionPriority.HIGH,
                List.of(
                    "Inspect `jcmd <pid> Compiler.codecache` or a comparable code-cache snapshot from the same workload if a similar process is still available.",
                    "Review recent bursts of generated code, rapid recompilation, or unusually hot method churn before treating a larger code cache as the whole fix.",
                    "Check `ReservedCodeCacheSize` and compilation settings only after you confirm why compiled-code pressure increased."
                ),
                List.of(findingId)
            ));
        }

        if (signal == null || signal.isBlank()) {
            if (!"native_allocation_failure".equals(crashType)
                && !"native_thread_exhaustion".equals(crashType)
                && !"compressed_class_space_oom".equals(crashType)
                && !"code_cache_full".equals(crashType)) {
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
