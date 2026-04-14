package com.javaassistant.correlate;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.assessment.AssessmentResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits deterministic cross-artifact findings from structured artifact findings.
 */
public class MultiArtifactCorrelator {

    public CorrelationResult correlate(List<ParsedArtifact> parsedArtifacts, List<AssessmentResult> evaluations) {
        List<Finding> evaluatedFindings = evaluations.stream().flatMap(evaluation -> evaluation.findings().stream()).toList();
        List<Finding> availableFindings = new ArrayList<>(evaluatedFindings);
        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary signalSummary = new CrossArtifactSignalAnalyzer().summarize(parsedArtifacts);
        CrossArtifactSignalAnalyzer.TimingAlignment jfrGcTimeAlignment = timingAlignment(signalSummary, "jfr-gc-time-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment threadDumpTimePlacement = timingAlignment(signalSummary, "thread-dump-time-placement");
        CrossArtifactSignalAnalyzer.TimingAlignment threadDumpNmtTimeAlignment = timingAlignment(signalSummary, "thread-dump-nmt-time-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment heapTimePlacement = timingAlignment(signalSummary, "heap-histogram-time-placement");
        CrossArtifactSignalAnalyzer.TimingAlignment hsErrNativeTimeAlignment = timingAlignment(signalSummary, "hs-err-native-time-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment containerOomTimeAlignment = timingAlignment(signalSummary, "container-oom-time-alignment");
        CrossArtifactSignalAnalyzer.TimingAlignment nmtPmapTimeAlignment = timingAlignment(signalSummary, "nmt-pmap-time-alignment");
        List<RecommendedAction> actions = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<String> allArtifactPaths = parsedArtifacts.stream()
            .map(artifact -> artifact.metadata() != null ? artifact.metadata().sourcePath() : null)
            .filter(path -> path != null && !path.isBlank())
            .distinct()
            .toList();

        boolean jfrAllocationOrRetentionSignals = hasAnyFinding(
            availableFindings,
            "jfr-allocation-churn",
            "jfr-dominant-allocation-class",
            "jfr-allocation-hot-path",
            "jfr-old-object-retention-candidates",
            "jfr-dominant-old-object-class",
            "jfr-old-object-reference-depth"
        );
        boolean jfrHeapSideSignals = hasAnyFinding(
            availableFindings,
            "jfr-old-object-retention-candidates",
            "jfr-dominant-old-object-class",
            "jfr-old-object-reference-depth"
        ) || (hasAnyFinding(
            availableFindings,
            "jfr-allocation-churn",
            "jfr-dominant-allocation-class",
            "jfr-allocation-hot-path"
        ) && hasAnyFinding(
            availableFindings,
            "jfr-gc-pause-events",
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "gc-heap-saturation"
        ));
        boolean jfrClassLoadingSignals = hasFinding(availableFindings, "jfr-class-loading-pressure");
        boolean jfrCodeCacheSignals = hasFinding(availableFindings, "jfr-code-cache-pressure");
        boolean reservationHeavyNativeFootprint = hasFinding(availableFindings, "nmt-reserved-committed-mismatch")
            && hasFinding(availableFindings, "pmap-virtual-resident-mismatch");
        boolean activeNativePressureBeyondReservations = hasAnyFinding(
            availableFindings,
            "nmt-native-allocation-growth",
            "compare-nmt-native-growth",
            "compare-pmap-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-code-cache-pressure"
        );

        if (hasAnyFinding(availableFindings, "gc-repeated-full-gcs", "gc-allocation-stall-pressure")
            && hasAnyFinding(availableFindings, "nmt-gc-native-pressure", "nmt-native-allocation-growth", "pmap-anon-pressure", "pmap-virtual-resident-mismatch")) {
            Finding finding = new Finding(
                "correlation-memory-pressure",
                "Cross-artifact memory pressure is likely driving GC distress",
                hasFinding(availableFindings, "gc-allocation-stall-pressure") && !hasFinding(availableFindings, "gc-repeated-full-gcs")
                    ? "GC allocation stalls appear alongside elevated native or anonymous memory pressure in other artifacts."
                    : "GC distress appears alongside elevated native or anonymous memory pressure in other artifacts.",
                "correlation.memory-pressure",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch"
                ),
                evidenceIds(
                    availableFindings,
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch"
                ),
                "When GC distress coincides with native-memory pressure signals, the incident is unlikely to be an isolated collector tuning issue."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-memory-pressure",
                "Treat the incident as mixed memory pressure, not GC-only behavior",
                "The GC, NMT, and/or pmap evidence points to a broader memory pressure event.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Review both heap and native memory signals before changing GC settings alone.",
                    "Capture a fresh NMT summary or diff and compare it to the time of the GC distress.",
                    "Use pmap and heap histogram snapshots together to separate heap from native growth."
                ),
                List.of("correlation-memory-pressure")
            ));
        }

        if (signalSummary.hasAlignment("jfr-gc-heap-pressure-alignment")
            && hasAnyFinding(availableFindings, "gc-repeated-full-gcs", "gc-allocation-stall-pressure", "gc-heap-saturation")
            && jfrAllocationOrRetentionSignals
            && !hasExplicitNoOverlap(jfrGcTimeAlignment, heapTimePlacement)) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("jfr-gc-heap-pressure-alignment");
            SeverityLevel severity = hasFinding(availableFindings, "gc-repeated-full-gcs") ? SeverityLevel.CRITICAL : SeverityLevel.HIGH;
            Finding finding = new Finding(
                "correlation-jfr-gc-heap-pressure",
                "JFR and heap data reinforce the GC-pressure picture",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "The JFR recording, the GC log, and the heap histogram all point at the same heap-pressure incident shape.",
                "correlation.jfr-gc-heap",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "gc-repeated-full-gcs",
                        "gc-allocation-stall-pressure",
                        "gc-heap-saturation",
                        "gc-g1-humongous-pressure",
                        "jfr-allocation-churn",
                        "jfr-dominant-allocation-class",
                        "jfr-allocation-hot-path",
                        "jfr-old-object-retention-candidates",
                        "jfr-dominant-old-object-class",
                        "jfr-old-object-reference-depth",
                        "histogram-top-heavy-consumer",
                        "histogram-cache-retention",
                        "histogram-collection-retention",
                        "histogram-payload-retention"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.JFR, ArtifactType.GC_LOG, ArtifactType.HEAP_HISTOGRAM)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "gc-repeated-full-gcs",
                        "gc-allocation-stall-pressure",
                        "gc-heap-saturation",
                        "gc-g1-humongous-pressure",
                        "jfr-allocation-churn",
                        "jfr-dominant-allocation-class",
                        "jfr-allocation-hot-path",
                        "jfr-old-object-retention-candidates",
                        "jfr-dominant-old-object-class",
                        "jfr-old-object-reference-depth",
                        "histogram-top-heavy-consumer",
                        "histogram-cache-retention",
                        "histogram-collection-retention",
                        "histogram-payload-retention"
                    ),
                    artifactEvidenceIds(
                        parsedArtifacts,
                        ArtifactType.JFR,
                        "jfr-allocation-field-summary",
                        "jfr-old-object-field-summary",
                        "jfr-memory-summary"
                    ),
                    artifactEvidenceIds(
                        parsedArtifacts,
                        ArtifactType.GC_LOG,
                        "gc-full-gc-summary",
                        "gc-longest-pause",
                        "gc-heap-occupancy-peak",
                        "gc-humongous-summary"
                    ),
                    artifactEvidenceIds(
                        parsedArtifacts,
                        ArtifactType.HEAP_HISTOGRAM,
                        "histogram-top-consumer",
                        "histogram-cache-like-entry",
                        "histogram-collection-summary",
                        "histogram-payload-summary"
                    )
                ),
                "When JFR heap-side signals, GC distress, and retained-heap concentration all point in the same direction, the agent has a much stronger base for explaining the incident than any one artifact alone."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-jfr-gc-heap-pressure",
                "Match the dominant JFR classes against the retained heap and the GC distress window",
                "The JFR recording, GC log, and heap histogram now reinforce the same heap-pressure picture.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Compare the dominant JFR allocation and old-object classes with the largest heap-histogram classes before changing GC settings in isolation.",
                    "Time-align the JFR recording window with the heaviest GC distress interval to see whether the same classes or paths intensify around the worst pauses.",
                    "If the same class family appears in both JFR and the heap histogram, capture a heap dump and inspect its retained owners or dominators."
                ),
                List.of("correlation-jfr-gc-heap-pressure")
            ));
        }

        if (signalSummary.hasAlignment("jfr-thread-dump-contention-alignment")
            && hasAnyFinding(availableFindings, "jfr-lock-contention-events", "jfr-thread-park-events")
            && hasAnyFinding(availableFindings, "thread-dump-java-deadlock", "thread-dump-lock-contention-hotspot", "thread-dump-stuck-thread-pool")
            && !hasExplicitNoOverlap(threadDumpTimePlacement)) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("jfr-thread-dump-contention-alignment");
            SeverityLevel severity = hasFinding(availableFindings, "thread-dump-java-deadlock") ? SeverityLevel.CRITICAL : SeverityLevel.HIGH;
            Finding finding = new Finding(
                "correlation-jfr-thread-contention",
                "JFR contention signals and the thread dump point to the same blocking incident",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "The JFR recording and the thread dump both show contention or blocked-thread behavior in the same incident shape.",
                "correlation.jfr-thread-contention",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "jfr-lock-contention-events",
                        "jfr-thread-park-events",
                        "thread-dump-java-deadlock",
                        "thread-dump-lock-contention-hotspot",
                        "thread-dump-stuck-thread-pool"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.JFR, ArtifactType.THREAD_DUMP)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "jfr-lock-contention-events",
                        "jfr-thread-park-events",
                        "thread-dump-java-deadlock",
                        "thread-dump-lock-contention-hotspot",
                        "thread-dump-stuck-thread-pool"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-lock-summary", "jfr-thread-park-summary", "jfr-recording-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-deadlock", "thread-dump-blocked-threads", "thread-dump-summary")
                ),
                "When the JFR recording and a thread dump both show lock or scheduling blockage, the blocking narrative is much stronger than either artifact alone."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-jfr-thread-contention",
                "Investigate the blocking threads and owners as one incident",
                "The JFR recording and thread dump now reinforce the same contention picture.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH,
                List.of(
                    "Match the blocked or parked threads in the thread dump with the hottest lock-contention paths in the JFR recording.",
                    "Identify the current lock owner or pool bottleneck before changing timeouts or thread counts.",
                    "Capture a follow-up thread dump near the same interval if the contention remains active."
                ),
                List.of("correlation-jfr-thread-contention")
            ));
        }

        if (hasFinding(availableFindings, "thread-dump-downstream-io-pileup")
            && hasFinding(availableFindings, "jfr-io-latency-events")
            && !hasExplicitNoOverlap(threadDumpTimePlacement)) {
            Finding finding = new Finding(
                "correlation-downstream-io-pileup",
                "Thread dump and JFR both point to downstream I/O pileup",
                "The thread dump shows worker threads stacked behind downstream I/O, and the JFR recording captured slow socket or file operations in the same incident window.",
                "correlation.io",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(availableFindings, "thread-dump-downstream-io-pileup", "jfr-io-latency-events"),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.JFR)
                ),
                mergeStrings(
                    evidenceIds(availableFindings, "thread-dump-downstream-io-pileup", "jfr-io-latency-events"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-io-summary", "jfr-recording-summary")
                ),
                "When a thread dump shows workers stacked in downstream I/O and JFR independently records slow I/O operations, the bottleneck is more credibly a dependency or I/O stall than a JVM-internal lock issue."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-downstream-io-pileup",
                "Investigate the downstream dependency or I/O path as the primary stall source",
                "The thread dump and JFR now reinforce the same downstream I/O pileup picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the slow socket or file path from the JFR recording and match it to the stacked worker threads in the dump.",
                    "Check dependency latency, timeout behavior, and request fan-out before increasing worker counts or JVM memory.",
                    "Capture a follow-up dump or recording if the stall persists so you can confirm the same dependency path remains dominant."
                ),
                List.of("correlation-downstream-io-pileup")
            ));
        }

        if (hasFinding(availableFindings, "thread-dump-forkjoin-starvation")
            && hasFinding(availableFindings, "jfr-thread-park-events")
            && !hasExplicitNoOverlap(threadDumpTimePlacement)) {
            Finding finding = new Finding(
                "correlation-forkjoin-starvation",
                "Thread dump and JFR both point to ForkJoin starvation",
                "ForkJoin workers are parked or self-blocked in the thread dump, and the JFR recording captured prolonged parked-thread behavior in the same incident window.",
                "correlation.forkjoin",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(availableFindings, "thread-dump-forkjoin-starvation", "jfr-thread-park-events"),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.JFR)
                ),
                mergeStrings(
                    evidenceIds(availableFindings, "thread-dump-forkjoin-starvation", "jfr-thread-park-events"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-thread-park-summary", "jfr-recording-summary")
                ),
                "When ForkJoin workers are parked or self-blocked in the dump and JFR independently shows heavy park time, the pool is more likely starved by blocking work than simply idle."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-forkjoin-starvation",
                "Inspect blocking joins and parked ForkJoin workers together",
                "The thread dump and JFR now reinforce the same ForkJoin starvation picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Check whether ForkJoin tasks are performing blocking I/O, synchronized work, or long joins that consume the pool's parallelism.",
                    "Review whether the affected workload should move blocking work off the common or shared ForkJoin pool.",
                    "Capture another dump or recording if needed to confirm the same parked-worker path remains dominant."
                ),
                List.of("correlation-forkjoin-starvation")
            ));
        }

        if (hasFinding(availableFindings, "thread-dump-virtual-thread-pinning")
            && hasFinding(availableFindings, "jfr-virtual-thread-pinning")
            && !hasExplicitNoOverlap(threadDumpTimePlacement)) {
            Finding finding = new Finding(
                "correlation-virtual-thread-pinning",
                "Thread dump and JFR both point to virtual-thread pinning",
                "The thread dump shows carrier-thread pinning markers, and the JFR recording explicitly captured virtual-thread pinning events in the same incident window.",
                "correlation.virtual-threads",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(availableFindings, "thread-dump-virtual-thread-pinning", "jfr-virtual-thread-pinning"),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.JFR)
                ),
                mergeStrings(
                    evidenceIds(availableFindings, "thread-dump-virtual-thread-pinning", "jfr-virtual-thread-pinning"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-top-event-types", "jfr-recording-summary")
                ),
                "When thread-dump carrier stacks and explicit JFR virtual-thread-pinning events line up, the virtual-thread concurrency model is being constrained by blocking work rather than behaving as intended."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-virtual-thread-pinning",
                "Inspect pinned virtual-thread paths before increasing carrier parallelism",
                "The thread dump and JFR now reinforce the same virtual-thread pinning picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect synchronized, native, and blocking I/O sections on the affected virtual-thread path.",
                    "Treat carrier-thread count as secondary until you understand what is pinning the carriers in the first place.",
                    "Capture a follow-up dump or recording after the first fix to confirm the pinning events disappear."
                ),
                List.of("correlation-virtual-thread-pinning")
            ));
        }

        if (hasFinding(availableFindings, "thread-dump-busy-spin-thread")
            && hasFinding(availableFindings, "jfr-execution-hot-path")
            && !hasExplicitNoOverlap(threadDumpTimePlacement)) {
            Finding finding = new Finding(
                "correlation-busy-spin-hot-path",
                "Thread dump and JFR both point to a busy-spin CPU path",
                "The thread dump shows a RUNNABLE thread consuming nearly all of its elapsed lifetime on CPU, and the JFR recording captured a dominant execution hot path in the same incident window.",
                "correlation.cpu-hot-path",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(availableFindings, "thread-dump-busy-spin-thread", "jfr-execution-hot-path"),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.JFR)
                ),
                mergeStrings(
                    evidenceIds(availableFindings, "thread-dump-busy-spin-thread", "jfr-execution-hot-path"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-execution-hotspots", "jfr-recording-summary")
                ),
                "When a thread dump shows a near-100% RUNNABLE CPU thread and JFR independently concentrates execution samples in one path, the incident is much more likely a runaway CPU loop than a memory or lock problem."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-busy-spin-hot-path",
                "Inspect the hot CPU loop before tuning memory or concurrency",
                "The thread dump and JFR now reinforce the same busy-spin hot-path picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the dominant execution path from JFR and compare it with the RUNNABLE hot thread from the dump.",
                    "Review retry loops, polling paths, and missing backoff or exit conditions before changing heap or pool settings.",
                    "Capture a short follow-up profile after the fix to confirm the same hot path no longer dominates CPU."
                ),
                List.of("correlation-busy-spin-hot-path")
            ));
        }

        if (signalSummary.hasAlignment("thread-dump-nmt-thread-pressure-alignment")
            && hasAnyFinding(availableFindings, "thread-dump-stuck-thread-pool", "thread-dump-lock-contention-hotspot")
            && hasFinding(availableFindings, "nmt-thread-stack-pressure")
            && !hasExplicitNoOverlap(threadDumpNmtTimeAlignment)) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("thread-dump-nmt-thread-pressure-alignment");
            Finding finding = new Finding(
                "correlation-thread-pool-thread-pressure",
                "Thread-pool pressure is corroborated by the thread dump and NMT",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "The thread dump shows a pressured executor or worker pool, and NMT shows elevated thread-stack footprint in the same incident shape.",
                "correlation.threads",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "thread-dump-stuck-thread-pool",
                        "thread-dump-lock-contention-hotspot",
                        "nmt-thread-stack-pressure",
                        "nmt-native-allocation-growth"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.NMT)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "thread-dump-stuck-thread-pool",
                        "thread-dump-lock-contention-hotspot",
                        "nmt-thread-stack-pressure",
                        "nmt-native-allocation-growth"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary", "thread-dump-blocked-threads"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-thread-summary", "nmt-thread-summary-delta", "nmt-category-thread", "nmt-total-delta")
                ),
                "When a thread dump shows a pressured worker pool and NMT shows elevated or growing thread-stack reservations, the incident is more likely to be driven by excessive thread footprint than by heap tuning alone."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-thread-pool-thread-pressure",
                "Investigate thread creation, pool backlog, and stack footprint together",
                "The thread dump and NMT both point to thread-driven native-memory pressure.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Identify which executor or request pool is dominating the dump and whether those workers are blocked, parking on downstream work, or simply accumulating.",
                    "Compare the observed live thread count with expected pool sizing, thread factory settings, and any recent changes that can increase concurrent thread creation.",
                    "Capture a follow-up thread dump and NMT snapshot or diff from the same incident window to confirm whether the same pool keeps growing and consuming stack headroom."
                ),
                List.of("correlation-thread-pool-thread-pressure")
            ));
        }

        if (signalSummary.hasAlignment("thread-dump-nmt-thread-pressure-alignment")
            && hasFinding(availableFindings, "hs-err-native-thread-exhaustion")
            && hasAnyFinding(availableFindings, "thread-dump-stuck-thread-pool", "thread-dump-lock-contention-hotspot")
            && hasFinding(availableFindings, "nmt-thread-stack-pressure")
            && !hasExplicitNoOverlap(threadDumpNmtTimeAlignment, hsErrNativeTimeAlignment)) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("thread-dump-nmt-thread-pressure-alignment");
            String detail = alignment != null && alignment.detail() != null
                ? alignment.detail() + " The hs_err log then reports that the JVM could not create another native thread."
                : "The thread dump and NMT both show thread-driven pressure, and the hs_err log reports that the JVM could not create another native thread.";
            if (hsErrNativeTimeAlignment != null && "ABSOLUTE_OVERLAP".equals(hsErrNativeTimeAlignment.status())) {
                detail += " The timed native-memory evidence overlaps the crash window.";
            } else if (hsErrNativeTimeAlignment != null
                && "ABSOLUTE_SEQUENCE_NEARBY".equals(hsErrNativeTimeAlignment.status())
                && "PRIMARY_AFTER_COMPANION".equals(stringValue(hsErrNativeTimeAlignment.metrics().get("sequenceDirection")))) {
                detail += " A timed native-memory snapshot was captured shortly before the crash window, which fits escalating thread exhaustion.";
            }

            Finding finding = new Finding(
                "correlation-native-thread-exhaustion-confirmed",
                "Native thread exhaustion is corroborated by thread dump and NMT evidence",
                detail,
                "correlation.native-threads",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "thread-dump-stuck-thread-pool",
                        "thread-dump-lock-contention-hotspot",
                        "nmt-thread-stack-pressure",
                        "hs-err-native-thread-exhaustion"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.THREAD_DUMP, ArtifactType.NMT, ArtifactType.HS_ERR_LOG)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "thread-dump-stuck-thread-pool",
                        "thread-dump-lock-contention-hotspot",
                        "nmt-thread-stack-pressure",
                        "hs-err-native-thread-exhaustion"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.THREAD_DUMP, "thread-dump-summary", "thread-dump-blocked-threads"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-thread-summary", "nmt-thread-summary-delta", "nmt-category-thread"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.HS_ERR_LOG, "hs-err-native-thread-exhaustion", "hs-err-current-thread", "hs-err-vm-error")
                ),
                "When thread-dump and NMT evidence already show thread-driven pressure, an hs_err report of failed native thread creation confirms a thread-exhaustion incident rather than a heap-only problem."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-native-thread-exhaustion-confirmed",
                "Treat the incident as confirmed native thread exhaustion",
                "The thread dump, NMT, and hs_err log all point to the process exhausting thread-creation headroom.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Inspect which executor or request pool keeps growing and why those threads are not draining.",
                    "Check operating-system and container thread or pid limits together with Java stack sizing and any recent concurrency changes.",
                    "Capture a follow-up thread dump and NMT snapshot from a comparable live process to confirm whether the same pool keeps consuming thread-stack headroom."
                ),
                List.of("correlation-native-thread-exhaustion-confirmed")
            ));
        }

        if (hasAnyFinding(
            availableFindings,
            "container-memory-limit-pressure",
            "container-memory-high-pressure",
            "container-memory-oom-events",
            "container-memory-reclaim-stalls"
        ) && hasAnyFinding(
            availableFindings,
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "hs-err-native-allocation-failure",
            "hs-err-compressed-class-space-oom",
            "nmt-gc-native-pressure",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-compressed-class-space-pressure",
            "nmt-code-cache-pressure",
            "nmt-class-metadata-growth",
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention",
            "correlation-memory-pressure",
            "correlation-native-pressure",
            "correlation-compressed-class-space-exhaustion",
            "correlation-mixed-heap-native-pressure",
            "correlation-native-oom-confirmed"
        )) {
            boolean containerOom = hasFinding(availableFindings, "container-memory-oom-events");
            SeverityLevel severity = containerOom || hasFinding(availableFindings, "correlation-native-oom-confirmed")
                || hasFinding(availableFindings, "hs-err-native-allocation-failure")
                || hasFinding(availableFindings, "hs-err-compressed-class-space-oom")
                || hasFinding(availableFindings, "correlation-compressed-class-space-exhaustion")
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            ActionPriority priority = severity == SeverityLevel.CRITICAL ? ActionPriority.URGENT : ActionPriority.HIGH;

            Finding finding = new Finding(
                "correlation-container-memory-pressure",
                "Container memory limits are constraining JVM behavior",
                containerOom
                    ? "Container OOM signals appear alongside JVM memory findings, so the incident is already breaching the cgroup memory budget."
                    : "Container memory-pressure signals appear alongside JVM memory findings, so the incident should be treated as a cgroup-budget problem rather than a JVM-only tuning issue.",
                "correlation.container-memory",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "container-memory-limit-pressure",
                    "container-memory-high-pressure",
                    "container-memory-oom-events",
                    "container-memory-reclaim-stalls",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "hs-err-native-allocation-failure",
                    "hs-err-compressed-class-space-oom",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-compressed-class-space-pressure",
                    "nmt-code-cache-pressure",
                    "nmt-class-metadata-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "correlation-memory-pressure",
                    "correlation-native-pressure",
                    "correlation-compressed-class-space-exhaustion",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed"
                ),
                evidenceIds(
                    availableFindings,
                    "container-memory-limit-pressure",
                    "container-memory-high-pressure",
                    "container-memory-oom-events",
                    "container-memory-reclaim-stalls",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "hs-err-native-allocation-failure",
                    "hs-err-compressed-class-space-oom",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-compressed-class-space-pressure",
                    "nmt-code-cache-pressure",
                    "nmt-class-metadata-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "correlation-memory-pressure",
                    "correlation-native-pressure",
                    "correlation-compressed-class-space-exhaustion",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed"
                ),
                "When JVM memory symptoms line up with cgroup limit, reclaim, or OOM counters, the safest interpretation is that the workload is exhausting its container memory budget."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-container-memory-pressure",
                "Re-budget JVM memory inside the container envelope",
                "The JVM memory findings now line up with concrete cgroup pressure or OOM evidence.",
                ActionType.IMMEDIATE,
                priority,
                List.of(
                    "Review container memory.max and memory.high together with heap sizing, native headroom, and thread stacks.",
                    "Capture container memory, NMT, heap, and pmap artifacts from the same interval so heap, native, and page-cache pressure can be separated cleanly.",
                    "Treat deployment memory limits and JVM tuning as one change set rather than changing Xmx alone."
                ),
                List.of("correlation-container-memory-pressure")
            ));
        }

        if (!hasExplicitNoOverlap(containerOomTimeAlignment) && hasAnyFinding(
            availableFindings,
            "oom-signal-kernel-oom-kill",
            "oom-signal-pod-oomkilled",
            "oom-signal-restart-loop"
        ) && hasAnyFinding(
            availableFindings,
            "container-memory-limit-pressure",
            "container-memory-high-pressure",
            "container-memory-oom-events",
            "container-memory-reclaim-stalls",
            "correlation-container-memory-pressure"
        )) {
            boolean restartDriven = hasAnyFinding(availableFindings, "oom-signal-pod-oomkilled", "oom-signal-restart-loop");
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("container-oom-pressure-alignment");
            Finding finding = new Finding(
                "correlation-container-oom-escalation",
                "Container memory pressure escalated into a confirmed OOM termination",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : restartDriven
                        ? "Container-budget pressure findings align with OOMKilled or restart-loop signals, so the workload is already being killed and restarted by platform memory enforcement."
                        : "Container-budget pressure findings align with a confirmed kernel OOM kill, so the workload is already being terminated by platform memory enforcement.",
                "correlation.container-oom",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "oom-signal-kernel-oom-kill",
                    "oom-signal-pod-oomkilled",
                    "oom-signal-restart-loop",
                    "container-memory-limit-pressure",
                    "container-memory-high-pressure",
                    "container-memory-oom-events",
                    "container-memory-reclaim-stalls",
                    "correlation-container-memory-pressure"
                ),
                evidenceIds(
                    availableFindings,
                    "oom-signal-kernel-oom-kill",
                    "oom-signal-pod-oomkilled",
                    "oom-signal-restart-loop",
                    "container-memory-limit-pressure",
                    "container-memory-high-pressure",
                    "container-memory-oom-events",
                    "container-memory-reclaim-stalls",
                    "correlation-container-memory-pressure"
                ),
                "When cgroup pressure signals and direct OOM kill or OOMKilled evidence appear together, the incident has already crossed from warning signs into enforced termination."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-container-oom-escalation",
                "Treat the incident as confirmed container-budget OOM",
                "The platform has already enforced the container memory budget with an OOM kill or OOMKilled restart.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve the kernel or pod OOM excerpt together with the matching container-memory snapshot from the same interval.",
                    "Review memory.max, memory.high, pod limits and requests, and JVM headroom together before restarting at the same settings.",
                    "Align the OOM timestamp with GC, NMT, heap histogram, and pmap artifacts to identify which memory component exhausted the budget."
                ),
                List.of("correlation-container-oom-escalation")
            ));
        }

        if (hasAnyFinding(
            availableFindings,
            "oom-signal-kernel-oom-kill",
            "oom-signal-pod-oomkilled",
            "oom-signal-restart-loop"
        ) && hasAnyFinding(
            availableFindings,
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "gc-metaspace-full-gcs",
            "nmt-gc-native-pressure",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-code-cache-pressure",
            "nmt-class-metadata-growth",
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention",
            "correlation-memory-pressure",
            "correlation-native-pressure",
            "correlation-metaspace-class-pressure",
            "correlation-mixed-heap-native-pressure",
            "correlation-native-oom-confirmed"
        )) {
            Finding finding = new Finding(
                "correlation-jvm-memory-escalated-to-oom",
                "JVM memory distress likely escalated into platform-enforced termination",
                "Confirmed OOM termination aligns with JVM memory findings, so the incident likely progressed from JVM memory distress into a platform kill or restart rather than ending at slowdowns alone.",
                "correlation.jvm-oom",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "oom-signal-kernel-oom-kill",
                    "oom-signal-pod-oomkilled",
                    "oom-signal-restart-loop",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "gc-metaspace-full-gcs",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-code-cache-pressure",
                    "nmt-class-metadata-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "correlation-memory-pressure",
                    "correlation-native-pressure",
                    "correlation-metaspace-class-pressure",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed"
                ),
                evidenceIds(
                    availableFindings,
                    "oom-signal-kernel-oom-kill",
                    "oom-signal-pod-oomkilled",
                    "oom-signal-restart-loop",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "gc-metaspace-full-gcs",
                    "nmt-gc-native-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-code-cache-pressure",
                    "nmt-class-metadata-growth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "correlation-memory-pressure",
                    "correlation-native-pressure",
                    "correlation-metaspace-class-pressure",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed"
                ),
                "A direct OOM kill or OOMKilled restart paired with JVM memory signals makes the earlier JVM findings materially more urgent because they now align with actual process termination."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-jvm-memory-escalated-to-oom",
                "Time-align JVM memory artifacts with the OOM termination",
                "The JVM memory signals are now tied to an actual platform kill or restart event.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Align the OOM timestamp with the last GC, NMT, pmap, and heap-histogram samples to identify the dominant growth vector.",
                    "Treat repeated full GC, native-pressure, or retained-heap findings as pre-kill lead indicators rather than separate incidents.",
                    "Adjust heap, native headroom, thread counts, and container limits as one memory-budget change set."
                ),
                List.of("correlation-jvm-memory-escalated-to-oom")
            ));
        }

        boolean gcAndNmtMetaspacePressure = hasFinding(availableFindings, "gc-metaspace-full-gcs")
            && hasAnyFinding(availableFindings, "nmt-metaspace-pressure", "nmt-class-metadata-growth", "compare-nmt-metaspace-growth");
        CrossArtifactSignalAnalyzer.SignalAlignment jfrMetaspaceAlignment = signalSummary.alignment("jfr-metaspace-class-pressure-alignment");
        if (gcAndNmtMetaspacePressure
            && jfrClassLoadingSignals
            && jfrMetaspaceAlignment != null
            && !hasExplicitNoOverlap(jfrGcTimeAlignment)) {
            Finding finding = new Finding(
                "correlation-jfr-metaspace-class-pressure",
                "JFR class-loading pressure is corroborated by GC and NMT metaspace signals",
                jfrMetaspaceAlignment.detail() != null
                    ? jfrMetaspaceAlignment.detail()
                    : "The JFR recording, GC log, and NMT output all point to the same class-loading and metaspace-pressure incident.",
                "correlation.jfr-metaspace",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "jfr-class-loading-pressure",
                        "gc-metaspace-full-gcs",
                        "nmt-metaspace-pressure",
                        "nmt-class-metadata-growth",
                        "compare-nmt-metaspace-growth"
                    ),
                    jfrMetaspaceAlignment.artifactPaths(),
                    artifactPaths(parsedArtifacts, ArtifactType.JFR, ArtifactType.GC_LOG, ArtifactType.NMT)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "jfr-class-loading-pressure",
                        "gc-metaspace-full-gcs",
                        "nmt-metaspace-pressure",
                        "nmt-class-metadata-growth",
                        "compare-nmt-metaspace-growth"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-class-loading-summary", "jfr-recording-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.GC_LOG, "gc-full-gc-summary", "gc-metaspace-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-metaspace-summary", "nmt-class-summary-delta", "nmt-metaspace-summary-delta")
                ),
                "When JFR class-loading activity lines up with GC metadata-triggered pressure and NMT class-metadata growth, the incident is more convincingly about class-loader churn or dynamic generation than about heap tuning."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-jfr-metaspace-class-pressure",
                "Investigate class-loader churn, dynamic generation, and metaspace headroom together",
                "The JFR recording, GC log, and NMT output now reinforce the same class-loading and metaspace-pressure picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use the JFR class-loading view together with NMT class or metaspace deltas to see whether one loader or package family is driving the growth.",
                    "Inspect `jcmd <pid> VM.classloader_stats` or a comparable class-loader view from the same workload window before raising metaspace limits.",
                    "Review proxy generation, bytecode generation, hot reload, or redeployment paths that can keep defining classes faster than they unload."
                ),
                List.of("correlation-jfr-metaspace-class-pressure")
            ));
        } else if (gcAndNmtMetaspacePressure) {
            Finding finding = new Finding(
                "correlation-metaspace-class-pressure",
                "Metaspace pressure is corroborated across GC and NMT",
                "GC metadata-triggered activity and NMT class-metadata signals both point to metaspace distress rather than a heap-only issue.",
                "correlation.metaspace",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "gc-metaspace-full-gcs",
                    "nmt-metaspace-pressure",
                    "nmt-class-metadata-growth",
                    "compare-nmt-metaspace-growth"
                ),
                evidenceIds(
                    availableFindings,
                    "gc-metaspace-full-gcs",
                    "nmt-metaspace-pressure",
                    "nmt-class-metadata-growth",
                    "compare-nmt-metaspace-growth"
                ),
                "Independent GC and NMT metadata signals make metaspace pressure much more credible than either artifact alone."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-metaspace-class-pressure",
                "Investigate class loading growth and metaspace headroom as one incident",
                "GC and NMT both point to class-metadata pressure.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Review dynamic class generation, proxy creation, and redeployment behavior in the affected interval.",
                    "Confirm whether class counts or metaspace usage continue rising in later NMT output.",
                    "Inspect metaspace sizing and class unloading before raising limits."
                ),
                List.of("correlation-metaspace-class-pressure")
            ));
        }

        if (signalSummary.hasAlignment("jfr-code-cache-pressure-alignment")
            && jfrCodeCacheSignals
            && hasAnyFinding(availableFindings, "nmt-code-cache-pressure", "hs-err-code-cache-full")) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("jfr-code-cache-pressure-alignment");
            boolean hsErrCodeCache = hasFinding(availableFindings, "hs-err-code-cache-full");
            String findingId = hsErrCodeCache
                ? "correlation-code-cache-exhaustion-confirmed"
                : "correlation-code-cache-pressure";
            SeverityLevel severity = hsErrCodeCache && hasFinding(availableFindings, "hs-err-fatal-signal")
                ? SeverityLevel.CRITICAL
                : SeverityLevel.HIGH;
            Finding finding = new Finding(
                findingId,
                hsErrCodeCache
                    ? "Code cache exhaustion is corroborated across JFR, NMT, and hs_err"
                    : "Code cache pressure is corroborated across JFR and NMT",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : hsErrCodeCache
                        ? "The JFR recording, NMT output, and hs_err log all point to code-cache exhaustion."
                        : "The JFR recording and NMT output both point to code-cache pressure.",
                "correlation.code-cache",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "jfr-code-cache-pressure",
                        "nmt-code-cache-pressure",
                        "hs-err-code-cache-full",
                        "hs-err-fatal-signal"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.JFR, ArtifactType.NMT, ArtifactType.HS_ERR_LOG)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "jfr-code-cache-pressure",
                        "nmt-code-cache-pressure",
                        "hs-err-code-cache-full",
                        "hs-err-fatal-signal"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.JFR, "jfr-code-cache-summary", "jfr-recording-summary"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-category-code", "nmt-category-delta-code"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.HS_ERR_LOG, "hs-err-code-cache-status", "hs-err-current-thread", "hs-err-problematic-frame")
                ),
                "When JFR compiler activity, NMT Code-category growth, and hs_err code-cache status reinforce each other, the incident is much more likely to be compiled-code headroom exhaustion than a heap-only or GC-only issue."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                hsErrCodeCache
                    ? "action-correlation-code-cache-exhaustion-confirmed"
                    : "action-correlation-code-cache-pressure",
                hsErrCodeCache
                    ? "Investigate why compiled-code pressure exhausted the code cache"
                    : "Investigate code-cache headroom and compilation pressure together",
                hsErrCodeCache
                    ? "The JFR recording, NMT output, and hs_err log reinforce the same code-cache exhaustion picture."
                    : "The JFR recording and NMT output reinforce the same code-cache pressure picture.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use the JFR compilation and code-cache view together with `jcmd <pid> Compiler.codecache` or a similar code-cache snapshot from the same workload.",
                    "Review frequent recompilation, generated-code bursts, or unusually hot method churn before treating a larger `ReservedCodeCacheSize` as more than temporary mitigation.",
                    "Check whether the same compiler thread, method family, or code-category growth pattern appears in repeat incidents before broad JVM tuning."
                ),
                List.of(findingId)
            ));
        }

        if (!hasExplicitNoOverlap(nmtPmapTimeAlignment)
            && signalSummary.hasAlignment("nmt-pmap-reservation-mismatch-alignment")
            && hasFinding(availableFindings, "nmt-reserved-committed-mismatch")
            && hasFinding(availableFindings, "pmap-virtual-resident-mismatch")) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("nmt-pmap-reservation-mismatch-alignment");
            String findingId = "correlation-native-reservation-mismatch";
            Finding finding = new Finding(
                findingId,
                "The large native footprint is corroborated as reservation-heavy, not resident-heavy",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "NMT and pmap both show that much of the large native footprint is reserved address space rather than committed or resident memory.",
                "correlation.native-reservation",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "nmt-reserved-committed-mismatch",
                        "pmap-virtual-resident-mismatch",
                        "pmap-anon-pressure"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.NMT, ArtifactType.PMAP)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "nmt-reserved-committed-mismatch",
                        "pmap-virtual-resident-mismatch",
                        "pmap-anon-pressure"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-total", "nmt-category-class", "nmt-category-code", "nmt-category-internal"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.PMAP, "pmap-resident-gap", "pmap-largest-mapping", "pmap-largest-resident-mapping")
                ),
                "When NMT and pmap both show a wide reserved-versus-committed or resident gap, total native footprint should be interpreted as address-space reservation first, not as proof of active RAM pressure or a leak."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-native-reservation-mismatch",
                "Interpret reserved footprint separately from active native memory use",
                "NMT and pmap agree that much of the apparent native footprint is reserved rather than committed or resident.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Compare NMT committed memory with pmap RSS before treating the total reserved footprint as active RAM pressure.",
                    "Prioritize categories and mappings whose committed or resident usage is actually growing, not only the largest reserved ranges.",
                    "Capture a later NMT snapshot or pmap sample to confirm whether committed memory or RSS eventually follows the reservation growth."
                ),
                List.of(findingId)
            ));
        }

        if (!(reservationHeavyNativeFootprint && !activeNativePressureBeyondReservations)
            && hasAnyFinding(availableFindings, "pmap-anon-pressure", "pmap-virtual-resident-mismatch")
            && hasAnyFinding(
                availableFindings,
                "nmt-thread-stack-pressure",
                "nmt-metaspace-pressure",
                "nmt-native-allocation-growth",
                "nmt-code-cache-pressure",
                "nmt-gc-native-pressure"
            )) {
            Finding finding = new Finding(
                "correlation-native-pressure",
                "Native memory pressure is supported by both pmap and NMT",
                "Pmap anonymous-mapping signals and NMT native categories both indicate meaningful native memory load.",
                "correlation.native-memory",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-code-cache-pressure",
                    "nmt-gc-native-pressure"
                ),
                evidenceIds(
                    availableFindings,
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-native-allocation-growth",
                    "nmt-code-cache-pressure",
                    "nmt-gc-native-pressure"
                ),
                "Independent artifacts pointing at native memory pressure increase confidence that the issue is not limited to Java heap occupancy."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-native-pressure",
                "Reconcile pmap anonymous mappings with NMT native categories",
                "Both pmap and NMT indicate meaningful native-memory load.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Compare pmap resident anonymous mappings with the dominant NMT categories to identify the most likely native consumer.",
                    "Use later pmap or NMT snapshots to confirm whether the same native signals continue growing.",
                    "Review heap sizing, thread count, stack size, and metaspace headroom together before changing a single subsystem in isolation."
                ),
                List.of("correlation-native-pressure")
            ));
        }

        if ((hasAnyFinding(
            availableFindings,
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention",
            "compare-heap-retention-pattern",
            "compare-heap-payload-growth",
            "compare-heap-growth"
        ) || jfrHeapSideSignals) && hasAnyFinding(
            availableFindings,
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-gc-native-pressure",
            "compare-pmap-growth",
            "compare-pmap-reserved-expansion",
            "compare-nmt-native-growth"
        )) {
            Finding finding = new Finding(
                "correlation-mixed-heap-native-pressure",
                "Heap-side memory signals coexist with native-memory pressure",
                jfrHeapSideSignals
                    ? "JFR heap-side signals and/or heap-retention findings both appear alongside native-memory findings, so the problem is unlikely to be heap-only or native-only."
                    : "Structured heap-retention findings and native-memory findings both appear in the same incident set, so the problem is unlikely to be heap-only.",
                "correlation.mixed-memory",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "compare-heap-retention-pattern",
                    "compare-heap-payload-growth",
                    "compare-heap-growth",
                    "jfr-gc-pause-events",
                    "jfr-allocation-churn",
                    "jfr-dominant-allocation-class",
                    "jfr-allocation-hot-path",
                    "jfr-old-object-retention-candidates",
                    "jfr-dominant-old-object-class",
                    "jfr-old-object-reference-depth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-gc-native-pressure",
                    "compare-pmap-growth",
                    "compare-pmap-reserved-expansion",
                    "compare-nmt-native-growth"
                ),
                evidenceIds(
                    availableFindings,
                    "histogram-cache-retention",
                    "histogram-collection-retention",
                    "histogram-payload-retention",
                    "compare-heap-retention-pattern",
                    "compare-heap-payload-growth",
                    "compare-heap-growth",
                    "jfr-gc-pause-events",
                    "jfr-allocation-churn",
                    "jfr-dominant-allocation-class",
                    "jfr-allocation-hot-path",
                    "jfr-old-object-retention-candidates",
                    "jfr-dominant-old-object-class",
                    "jfr-old-object-reference-depth",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-gc-native-pressure",
                    "compare-pmap-growth",
                    "compare-pmap-reserved-expansion",
                    "compare-nmt-native-growth"
                ),
                "When heap-side and native pressure signals appear together, tuning only one side of the memory footprint is likely to miss the real incident shape."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-mixed-heap-native-pressure",
                "Treat the incident as a split heap-plus-native problem",
                jfrHeapSideSignals
                    ? "The structured findings show heap-side pressure and native-memory pressure in the same incident."
                    : "The structured findings show both retained heap state and native-memory pressure.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect retained-heap owners or JFR heap-side hot paths together with native headroom instead of optimizing only heap or only native settings.",
                    "Time-align JFR, heap histograms, NMT, and pmap snapshots to determine which side is growing faster.",
                    "Review cache growth, thread count, metaspace usage, and container limits as one memory-budget problem."
                ),
                List.of("correlation-mixed-heap-native-pressure")
            ));
        }

        if (!hasExplicitNoOverlap(hsErrNativeTimeAlignment)
            && signalSummary.hasAlignment("compressed-class-space-pressure-alignment")
            && hasFinding(availableFindings, "hs-err-compressed-class-space-oom")
            && hasFinding(availableFindings, "nmt-compressed-class-space-pressure")) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("compressed-class-space-pressure-alignment");
            String findingId = "correlation-compressed-class-space-exhaustion";
            Finding finding = new Finding(
                findingId,
                "Compressed class space exhaustion is corroborated by hs_err and NMT",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "The hs_err log reports compressed class space exhaustion, and the NMT Class section also shows compressed class space close to full.",
                "correlation.compressed-class-space",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                mergeStrings(
                    contributingPaths(
                        availableFindings,
                        "hs-err-compressed-class-space-oom",
                        "hs-err-fatal-signal",
                        "nmt-compressed-class-space-pressure",
                        "nmt-class-metadata-growth"
                    ),
                    alignment != null ? alignment.artifactPaths() : List.of(),
                    artifactPaths(parsedArtifacts, ArtifactType.HS_ERR_LOG, ArtifactType.NMT)
                ),
                mergeStrings(
                    evidenceIds(
                        availableFindings,
                        "hs-err-compressed-class-space-oom",
                        "hs-err-fatal-signal",
                        "nmt-compressed-class-space-pressure",
                        "nmt-class-metadata-growth"
                    ),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.HS_ERR_LOG, "hs-err-compressed-class-space", "hs-err-vm-error", "hs-err-current-thread"),
                    artifactEvidenceIds(parsedArtifacts, ArtifactType.NMT, "nmt-class-space-summary", "nmt-class-space-summary-delta", "nmt-class-summary")
                ),
                "When the hs_err log and the NMT Class section both point to compressed class space exhaustion, the incident is much more likely to be class-metadata headroom failure than a generic heap or native-memory issue."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-compressed-class-space-exhaustion",
                "Treat the incident as confirmed compressed class space exhaustion",
                "The hs_err log and NMT output agree that class-metadata headroom in compressed class space was exhausted.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Inspect class-loader churn, dynamic class generation, proxy creation, and redeploy or reload behavior before changing heap settings.",
                    "Capture JFR class-loading evidence or `jcmd <pid> VM.classloader_stats` from a comparable live process to identify which loader or package family is driving class growth.",
                    "Review `CompressedClassSpaceSize` only as temporary mitigation after you understand why compressed class space kept filling."
                ),
                List.of(findingId)
            ));
        }

        if (!hasExplicitNoOverlap(hsErrNativeTimeAlignment) && hasFinding(availableFindings, "hs-err-native-allocation-failure")
            && hasAnyFinding(
                availableFindings,
                "pmap-anon-pressure",
                "pmap-virtual-resident-mismatch",
                "nmt-native-allocation-growth",
                "nmt-thread-stack-pressure",
                "nmt-metaspace-pressure",
                "nmt-code-cache-pressure",
                "correlation-native-pressure"
            )) {
            CrossArtifactSignalAnalyzer.SignalAlignment alignment = signalSummary.alignment("hs-err-native-pressure-alignment");
            Finding finding = new Finding(
                "correlation-native-oom-confirmed",
                "Native allocation failure is corroborated by other native-memory evidence",
                alignment != null && alignment.detail() != null
                    ? alignment.detail()
                    : "The hs_err native allocation failure is reinforced by structured native-memory findings from NMT and/or pmap.",
                "correlation.native-oom",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "hs-err-native-allocation-failure",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-code-cache-pressure",
                    "correlation-native-pressure"
                ),
                evidenceIds(
                    availableFindings,
                    "hs-err-native-allocation-failure",
                    "pmap-anon-pressure",
                    "pmap-virtual-resident-mismatch",
                    "nmt-native-allocation-growth",
                    "nmt-thread-stack-pressure",
                    "nmt-metaspace-pressure",
                    "nmt-code-cache-pressure",
                    "correlation-native-pressure"
                ),
                "A crash-time native allocation failure backed by independent native-memory findings strongly confirms a JVM native-memory exhaustion or fragmentation incident."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-native-oom-confirmed",
                "Treat the crash as confirmed native-memory exhaustion",
                "The hs_err log and supporting artifacts agree that native memory headroom was exhausted or fragmented.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Use the supporting NMT and pmap evidence to identify whether threads, metaspace, code cache, or large anonymous mappings were the dominant contributors.",
                    "Review heap sizing, native headroom, container memory limits, and reserved-versus-resident behavior before increasing heap limits.",
                    "Compare against a similar live process if one is still running to confirm which native category is still growing."
                ),
                List.of("correlation-native-oom-confirmed")
            ));
        }

        if (hasAnyFinding(
            availableFindings,
            "hs-err-fatal-signal",
            "hs-err-native-allocation-failure",
            "hs-err-native-thread-exhaustion",
            "hs-err-compressed-class-space-oom"
        )
            && hasAnyFinding(
                availableFindings,
                "gc-repeated-full-gcs",
                "gc-allocation-stall-pressure",
                "nmt-compressed-class-space-pressure",
                "correlation-memory-pressure",
                "correlation-metaspace-class-pressure",
                "correlation-compressed-class-space-exhaustion",
                "correlation-mixed-heap-native-pressure",
                "correlation-native-oom-confirmed",
                "correlation-thread-pool-thread-pressure",
                "correlation-native-thread-exhaustion-confirmed"
            )) {
            Finding finding = new Finding(
                "correlation-crash-under-memory-distress",
                "Crash likely occurred during a period of severe memory distress",
                "Crash evidence is accompanied by severe GC or memory-pressure findings in other artifacts.",
                "correlation.crash",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                contributingPaths(
                    availableFindings,
                    "hs-err-fatal-signal",
                    "hs-err-native-allocation-failure",
                    "hs-err-native-thread-exhaustion",
                    "hs-err-compressed-class-space-oom",
                    "hs-err-g1-fullgc-crash",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "nmt-compressed-class-space-pressure",
                    "correlation-memory-pressure",
                    "correlation-metaspace-class-pressure",
                    "correlation-compressed-class-space-exhaustion",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed",
                    "correlation-thread-pool-thread-pressure",
                    "correlation-native-thread-exhaustion-confirmed"
                ),
                evidenceIds(
                    availableFindings,
                    "hs-err-fatal-signal",
                    "hs-err-native-allocation-failure",
                    "hs-err-native-thread-exhaustion",
                    "hs-err-compressed-class-space-oom",
                    "hs-err-g1-fullgc-crash",
                    "gc-repeated-full-gcs",
                    "gc-allocation-stall-pressure",
                    "nmt-compressed-class-space-pressure",
                    "correlation-memory-pressure",
                    "correlation-metaspace-class-pressure",
                    "correlation-compressed-class-space-exhaustion",
                    "correlation-mixed-heap-native-pressure",
                    "correlation-native-oom-confirmed",
                    "correlation-thread-pool-thread-pressure",
                    "correlation-native-thread-exhaustion-confirmed"
                ),
                "A fatal crash alongside strong GC or broader memory-pressure signals narrows the likely incident window and investigation scope."
            );
            addFinding(findings, availableFindings, finding);
            actions.add(new RecommendedAction(
                "action-correlation-crash-under-memory-distress",
                "Preserve crash artifacts and align them with the memory-pressure timeline",
                "The crash appears to have happened while the JVM was already under severe memory distress.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve the hs_err file, GC logs, and any matching NMT or pmap snapshots from the same run.",
                    "Align the crash timestamp with the last clear GC, NMT, or pmap distress signal to narrow the failure window.",
                    "Use the combined memory signals before treating the crash as an isolated JVM bug or an application-only issue."
                ),
                List.of("correlation-crash-under-memory-distress")
            ));
        }

        List<String> contributingPaths = findings.isEmpty()
            ? allArtifactPaths
            : findings.stream()
                .flatMap(finding -> finding.artifactPaths().stream())
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();

        String summary;
        ConfidenceLevel confidence;
        if (findings.isEmpty()) {
            summary = noCorrelationSummary(evaluatedFindings, signalSummary, parsedArtifacts);
            confidence = ConfidenceLevel.LOW;
        } else {
            Finding topFinding = findings.stream()
                .max(Comparator.comparingInt(finding -> severityRank(finding.severity())))
                .orElseThrow();
            summary = findings.size() == 1
                ? "Across the provided diagnostics, the strongest shared signal is " + topFinding.title() + "."
                : "Across the provided diagnostics, multiple related issues line up; the strongest shared signal is "
                    + topFinding.title()
                    + ".";
            confidence = findings.stream()
                .map(Finding::confidence)
                .max(Comparator.comparingInt(this::confidenceRank))
                .orElse(ConfidenceLevel.LOW);
        }

        return new CorrelationResult(summary, confidence, findings, actions, contributingPaths);
    }

    private void addFinding(List<Finding> emittedFindings, List<Finding> availableFindings, Finding finding) {
        emittedFindings.add(finding);
        availableFindings.add(finding);
    }

    private String noCorrelationSummary(
        List<Finding> findings,
        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary signalSummary,
        List<ParsedArtifact> parsedArtifacts
    ) {
        List<String> hints = new ArrayList<>();

        if (hasFinding(findings, "gc-metaspace-full-gcs")
            && !hasAnyFinding(findings, "nmt-metaspace-pressure", "nmt-class-metadata-growth", "compare-nmt-metaspace-growth")) {
            hints.add("GC metadata pressure was present, but matching NMT metaspace evidence was not provided.");
        }

        if (hasFinding(findings, "hs-err-native-allocation-failure")
            && !hasAnyFinding(
                findings,
                "pmap-anon-pressure",
                "pmap-virtual-resident-mismatch",
                "nmt-native-allocation-growth",
                "nmt-thread-stack-pressure",
                "nmt-metaspace-pressure",
                "nmt-code-cache-pressure"
            )) {
            hints.add("The hs_err log shows a native allocation failure, but matching NMT or pmap evidence was not provided.");
        }

        if (hasFinding(findings, "hs-err-compressed-class-space-oom")
            && !hasAnyFinding(findings, "nmt-compressed-class-space-pressure", "correlation-compressed-class-space-exhaustion")) {
            hints.add("The hs_err log shows compressed class space exhaustion, but matching NMT Class-section evidence was not provided.");
        }

        if (hasFinding(findings, "hs-err-native-thread-exhaustion")
            && !hasAnyFinding(
                findings,
                "thread-dump-stuck-thread-pool",
                "thread-dump-lock-contention-hotspot",
                "nmt-thread-stack-pressure",
                "correlation-thread-pool-thread-pressure"
            )) {
            hints.add("The hs_err log shows native thread exhaustion, but matching thread-dump or NMT evidence was not provided to show which pool or stack footprint drove it.");
        }

        if (hasAnyFinding(
            findings,
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention",
            "compare-heap-retention-pattern",
            "compare-heap-payload-growth",
            "compare-heap-growth"
        ) && !hasAnyFinding(
            findings,
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-gc-native-pressure",
            "compare-pmap-growth",
            "compare-pmap-reserved-expansion",
            "compare-nmt-native-growth"
        )) {
            hints.add("Heap-retention signals were present, but no matching native-memory evidence was available to decide whether the incident is heap-only or mixed.");
        }

        if (hasAnyFinding(
            findings,
            "jfr-allocation-churn",
            "jfr-dominant-allocation-class",
            "jfr-allocation-hot-path",
            "jfr-old-object-retention-candidates",
            "jfr-dominant-old-object-class",
            "jfr-old-object-reference-depth"
        ) && !hasAnyFinding(
            findings,
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "gc-heap-saturation",
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch"
        )) {
            hints.add("JFR heap-side signals were present, but no matching GC, heap-histogram, or native-memory artifact was available to show whether the pressure was reclaimed, retained, or mixed.");
        }

        if (hasAnyFinding(findings, "thread-dump-stuck-thread-pool", "thread-dump-lock-contention-hotspot")
            && !hasFinding(findings, "nmt-thread-stack-pressure")) {
            hints.add("The thread dump shows blocked or stalled pool threads, but no matching NMT thread-stack evidence was provided to show whether thread growth is materially affecting native memory.");
        }

        if (hasFinding(findings, "nmt-thread-stack-pressure")
            && !hasAnyFinding(findings, "thread-dump-stuck-thread-pool", "thread-dump-lock-contention-hotspot")) {
            hints.add("NMT shows elevated thread-stack footprint, but no matching thread dump was provided to identify which pool or workload owns the extra threads.");
        }

        if (hasAnyFinding(
            findings,
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "hs-err-native-allocation-failure",
            "nmt-gc-native-pressure",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-code-cache-pressure",
            "nmt-class-metadata-growth",
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention"
        ) && !hasAnyFinding(
            findings,
            "container-memory-limit-pressure",
            "container-memory-high-pressure",
            "container-memory-oom-events",
            "container-memory-reclaim-stalls"
        )) {
            hints.add("JVM memory-pressure signals were present, but no container-memory snapshot was provided to show whether cgroup limits or reclaim pressure were involved.");
        }

        if (hasAnyFinding(
            findings,
            "oom-signal-kernel-oom-kill",
            "oom-signal-pod-oomkilled",
            "oom-signal-restart-loop"
        ) && !hasAnyFinding(
            findings,
            "container-memory-limit-pressure",
            "container-memory-high-pressure",
            "container-memory-oom-events",
            "container-memory-reclaim-stalls",
            "gc-repeated-full-gcs",
            "gc-allocation-stall-pressure",
            "gc-metaspace-full-gcs",
            "nmt-gc-native-pressure",
            "nmt-native-allocation-growth",
            "nmt-thread-stack-pressure",
            "nmt-metaspace-pressure",
            "nmt-code-cache-pressure",
            "nmt-class-metadata-growth",
            "pmap-anon-pressure",
            "pmap-virtual-resident-mismatch",
            "histogram-cache-retention",
            "histogram-collection-retention",
            "histogram-payload-retention"
        )) {
            hints.add("A kernel or pod-level OOM signal was present, but matching JVM-memory or container-memory artifacts were not provided to show what exhausted the budget.");
        }

        ParsedArtifact oomSignalArtifact = firstParsedArtifact(parsedArtifacts, ArtifactType.OOM_SIGNAL);
        long kernelMemcgCount = 0L;
        if (oomSignalArtifact != null) {
            Object summaryObject = oomSignalArtifact.extractedData().get("summary");
            if (summaryObject instanceof Map<?, ?> summaryMap) {
                Object kernelMemcgValue = summaryMap.get("kernelMemcgCount");
                if (kernelMemcgValue instanceof Number number) {
                    kernelMemcgCount = number.longValue();
                } else if (kernelMemcgValue != null) {
                    try {
                        kernelMemcgCount = Long.parseLong(String.valueOf(kernelMemcgValue));
                    } catch (NumberFormatException ignored) {
                        kernelMemcgCount = 0L;
                    }
                }
            }
        }
        if (hasFinding(findings, "oom-signal-kernel-oom-kill")
            && hasArtifactType(parsedArtifacts, ArtifactType.CONTAINER_MEMORY)
            && !hasAnyFinding(
                findings,
                "container-memory-limit-pressure",
                "container-memory-high-pressure",
                "container-memory-oom-events",
                "container-memory-reclaim-stalls",
                "correlation-container-memory-pressure",
                "correlation-container-oom-escalation"
            )
            && kernelMemcgCount == 0L) {
            hints.add("The kernel OOM signal does not identify a memory cgroup, and the provided container-memory snapshot does not show local cgroup pressure, so the remaining ambiguity is whether the kill came from host-wide pressure or a container-local budget.");
        }

        if (signalSummary != null) {
            CrossArtifactSignalAnalyzer.TimingAlignment jfrGcTimeAlignment = timingAlignment(signalSummary, "jfr-gc-time-alignment");
            CrossArtifactSignalAnalyzer.TimingAlignment threadDumpTimePlacement = timingAlignment(signalSummary, "thread-dump-time-placement");
            CrossArtifactSignalAnalyzer.TimingAlignment heapTimePlacement = timingAlignment(signalSummary, "heap-histogram-time-placement");
            if ((signalSummary.alignment("jfr-heap-class-overlap") != null || signalSummary.alignment("jfr-gc-pressure-alignment") != null)
                && hasExplicitNoOverlap(jfrGcTimeAlignment, heapTimePlacement)) {
                hints.add("JFR, GC, and heap-side signals overlapped structurally, but the explicit timing metadata places those artifacts outside the same incident window.");
            }

            if (signalSummary.alignment("jfr-thread-dump-contention-alignment") != null
                && hasExplicitNoOverlap(threadDumpTimePlacement)) {
                hints.add("JFR contention signals and the thread dump overlapped structurally, but the explicit thread-dump capture time sits outside the timed JVM incident window.");
            }

            CrossArtifactSignalAnalyzer.TimingAlignment nmtTimePlacement = timingAlignment(signalSummary, "nmt-time-placement");
            CrossArtifactSignalAnalyzer.TimingAlignment pmapTimePlacement = timingAlignment(signalSummary, "pmap-time-placement");
            if (signalSummary.alignment("jfr-native-pressure-alignment") != null
                && hasAnyExplicitNoOverlap(nmtTimePlacement, pmapTimePlacement)
                && !hasAnyAbsoluteOverlap(nmtTimePlacement, pmapTimePlacement)) {
                hints.add("JFR and native-memory signals coexisted across artifacts, but the explicitly timed native-memory snapshots sat outside the timed JVM incident window.");
            }

            CrossArtifactSignalAnalyzer.TimingAlignment threadDumpNmtTimeAlignment = timingAlignment(signalSummary, "thread-dump-nmt-time-alignment");
            if (signalSummary.alignment("thread-dump-nmt-thread-pressure-alignment") != null
                && hasExplicitNoOverlap(threadDumpNmtTimeAlignment)) {
                hints.add("The thread dump and NMT both point at thread pressure, but their explicit capture times do not overlap.");
            }

            CrossArtifactSignalAnalyzer.TimingAlignment hsErrNativeTimeAlignment = timingAlignment(signalSummary, "hs-err-native-time-alignment");
            if (signalSummary.alignment("hs-err-native-pressure-alignment") != null
                && hasExplicitNoOverlap(hsErrNativeTimeAlignment)) {
                hints.add("The hs_err log and native-memory artifacts both show native-pressure symptoms, but their explicit times do not overlap.");
            }
            if (signalSummary.alignment("compressed-class-space-pressure-alignment") != null
                && hasExplicitNoOverlap(hsErrNativeTimeAlignment)) {
                hints.add("The hs_err log and NMT output both show compressed class space pressure, but their explicit times do not overlap.");
            }

            CrossArtifactSignalAnalyzer.TimingAlignment containerOomTimeAlignment = timingAlignment(signalSummary, "container-oom-time-alignment");
            if (signalSummary.alignment("container-oom-pressure-alignment") != null
                && hasExplicitNoOverlap(containerOomTimeAlignment)) {
                hints.add("The container-memory snapshot and the OOM or restart signals both indicate memory-budget trouble, but their explicit times do not overlap.");
            }
        }

        if (hints.isEmpty()) {
            return "No deterministic cross-artifact correlations were strong enough to emit a unified finding.";
        }
        return "No deterministic cross-artifact correlations were strong enough to emit a unified finding. " + String.join(" ", hints);
    }

    private boolean hasArtifactType(List<ParsedArtifact> parsedArtifacts, ArtifactType artifactType) {
        if (parsedArtifacts == null || artifactType == null) {
            return false;
        }
        return parsedArtifacts.stream().anyMatch(parsedArtifact -> parsedArtifact.type() == artifactType);
    }

    private ParsedArtifact firstParsedArtifact(List<ParsedArtifact> parsedArtifacts, ArtifactType artifactType) {
        if (parsedArtifacts == null || artifactType == null) {
            return null;
        }
        return parsedArtifacts.stream()
            .filter(parsedArtifact -> parsedArtifact.type() == artifactType)
            .findFirst()
            .orElse(null);
    }

    private List<String> evidenceIds(List<Finding> findings, String... ids) {
        Set<String> merged = new LinkedHashSet<>();
        for (Finding finding : matchingFindings(findings, ids)) {
            merged.addAll(finding.evidenceIds());
        }
        return List.copyOf(merged);
    }

    private List<String> contributingPaths(List<Finding> findings, String... ids) {
        Set<String> merged = new LinkedHashSet<>();
        for (Finding finding : matchingFindings(findings, ids)) {
            for (String path : finding.artifactPaths()) {
                if (path != null && !path.isBlank()) {
                    merged.add(path);
                }
            }
        }
        return List.copyOf(merged);
    }

    private List<String> artifactEvidenceIds(List<ParsedArtifact> parsedArtifacts, ArtifactType artifactType, String... candidateIds) {
        if (parsedArtifacts == null || parsedArtifacts.isEmpty() || artifactType == null || candidateIds == null || candidateIds.length == 0) {
            return List.of();
        }
        Set<String> candidates = Set.of(candidateIds);
        Set<String> selected = new LinkedHashSet<>();
        for (ParsedArtifact parsedArtifact : parsedArtifacts) {
            if (parsedArtifact == null || parsedArtifact.type() != artifactType) {
                continue;
            }
            parsedArtifact.evidence().stream()
                .map(evidence -> evidence.id())
                .filter(candidates::contains)
                .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private List<String> artifactPaths(List<ParsedArtifact> parsedArtifacts, ArtifactType... artifactTypes) {
        if (parsedArtifacts == null || parsedArtifacts.isEmpty() || artifactTypes == null || artifactTypes.length == 0) {
            return List.of();
        }
        Set<ArtifactType> typeSet = Set.of(artifactTypes);
        Set<String> paths = new LinkedHashSet<>();
        for (ParsedArtifact parsedArtifact : parsedArtifacts) {
            if (parsedArtifact == null || !typeSet.contains(parsedArtifact.type())) {
                continue;
            }
            String path = parsedArtifact.metadata() != null ? parsedArtifact.metadata().sourcePath() : null;
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private List<String> mergeStrings(List<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        if (groups == null) {
            return List.of();
        }
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String value : group) {
                if (value != null && !value.isBlank()) {
                    merged.add(value);
                }
            }
        }
        return List.copyOf(merged);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<Finding> matchingFindings(List<Finding> findings, String... ids) {
        Set<String> idSet = Set.of(ids);
        return findings.stream()
            .filter(finding -> idSet.contains(finding.id()))
            .toList();
    }

    private boolean hasFinding(List<Finding> findings, String id) {
        return findings.stream().anyMatch(finding -> finding.id().equals(id));
    }

    private boolean hasAnyFinding(List<Finding> findings, String... ids) {
        for (String id : ids) {
            if (hasFinding(findings, id)) {
                return true;
            }
        }
        return false;
    }

    private int severityRank(SeverityLevel severityLevel) {
        return switch (severityLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    private int confidenceRank(ConfidenceLevel confidenceLevel) {
        return switch (confidenceLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    private CrossArtifactSignalAnalyzer.TimingAlignment timingAlignment(
        CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary signalSummary,
        String alignmentId
    ) {
        if (signalSummary == null || signalSummary.crossArtifactTiming() == null || alignmentId == null || alignmentId.isBlank()) {
            return null;
        }
        return signalSummary.crossArtifactTiming().alignment(alignmentId);
    }

    private boolean hasExplicitNoOverlap(CrossArtifactSignalAnalyzer.TimingAlignment... alignments) {
        if (alignments == null) {
            return false;
        }
        for (CrossArtifactSignalAnalyzer.TimingAlignment alignment : alignments) {
            if (alignment != null && "ABSOLUTE_NO_OVERLAP".equals(alignment.status())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyExplicitNoOverlap(CrossArtifactSignalAnalyzer.TimingAlignment... alignments) {
        return hasExplicitNoOverlap(alignments);
    }

    private boolean hasAnyAbsoluteOverlap(CrossArtifactSignalAnalyzer.TimingAlignment... alignments) {
        if (alignments == null) {
            return false;
        }
        for (CrossArtifactSignalAnalyzer.TimingAlignment alignment : alignments) {
            if (alignment != null && "ABSOLUTE_OVERLAP".equals(alignment.status())) {
                return true;
            }
        }
        return false;
    }
}
