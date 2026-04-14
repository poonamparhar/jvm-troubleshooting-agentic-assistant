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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class JfrArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.JFR;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> parseFailure = AssessmentSupport.map(extractedData, "parseFailure");
        Map<String, Object> summary = AssessmentSupport.map(extractedData, "summary");
        Map<String, Object> coverage = AssessmentSupport.map(extractedData, "coverage");
        List<Map<String, Object>> eventTypeDetails = AssessmentSupport.mapList(extractedData, "eventTypeDetails");
        Map<String, Object> lockSummary = AssessmentSupport.map(extractedData, "lockSummary");
        Map<String, Object> gcSummary = AssessmentSupport.map(extractedData, "gcSummary");
        Map<String, Object> monitorWaitSummary = AssessmentSupport.map(extractedData, "monitorWaitSummary");
        Map<String, Object> threadParkSummary = AssessmentSupport.map(extractedData, "threadParkSummary");
        Map<String, Object> ioSummary = AssessmentSupport.map(extractedData, "ioSummary");
        Map<String, Object> exceptionSummary = AssessmentSupport.map(extractedData, "exceptionSummary");
        Map<String, Object> safepointSummary = AssessmentSupport.map(extractedData, "safepointSummary");
        Map<String, Object> classLoadingSummary = AssessmentSupport.map(extractedData, "classLoadingSummary");
        Map<String, Object> codeCacheSummary = AssessmentSupport.map(extractedData, "codeCacheSummary");
        Map<String, Object> cpuLoadSummary = AssessmentSupport.map(extractedData, "cpuLoadSummary");
        Map<String, Object> allocationFieldSummary = AssessmentSupport.map(extractedData, "allocationFieldSummary");
        Map<String, Object> allocationHotspotSummary = AssessmentSupport.map(extractedData, "allocationHotspotSummary");
        Map<String, Object> oldObjectFieldSummary = AssessmentSupport.map(extractedData, "oldObjectFieldSummary");
        Map<String, Object> executionHotspotSummary = AssessmentSupport.map(extractedData, "executionHotspotSummary");
        Map<String, Object> runtimeHotspotSummary = AssessmentSupport.map(extractedData, "runtimeHotspotSummary");

        if (!parseFailure.isEmpty()) {
            String parseFailureMessage = stringValue(parseFailure, "message");
            missingData.add(
                parseFailureMessage != null && !parseFailureMessage.isBlank()
                    ? "The JFR recording could not be parsed: " + parseFailureMessage
                    : "The JFR recording could not be parsed."
            );
            return new AssessmentResult(findings, actions, missingData);
        }

        long eventCount = AssessmentSupport.longValue(summary, "eventCount");
        if (eventCount == 0L) {
            missingData.add("No events were extracted from the JFR recording.");
            return new AssessmentResult(findings, actions, missingData);
        }

        long durationMs = AssessmentSupport.longValue(summary, "durationMs");
        if (durationMs > 0L && durationMs < 30_000L) {
            missingData.add(
                "JFR recording duration is only " + humanDuration(durationMs) + ", which may miss intermittent behavior."
            );
        }

        long lockEventCount = AssessmentSupport.longValue(lockSummary, "eventCount");
        long lockTotalDurationMs = AssessmentSupport.longValue(lockSummary, "totalDurationMs");
        long lockMaxDurationMs = AssessmentSupport.longValue(lockSummary, "maxDurationMs");
        if (lockEventCount > 0L && (lockMaxDurationMs >= 100L || lockTotalDurationMs >= 500L || lockEventCount >= 5L)) {
            String findingId = "jfr-lock-contention-events";
            SeverityLevel severity = lockMaxDurationMs >= 1_000L || lockTotalDurationMs >= 5_000L || lockEventCount >= 20L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured meaningful monitor-blocked events",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d monitor-blocked event(s) with max blocked time %s and cumulative blocked time %s.",
                    lockEventCount,
                    humanDuration(lockMaxDurationMs),
                    humanDuration(lockTotalDurationMs)
                ),
                "jfr.lock-contention",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-lock-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Repeated or long monitor-blocked events inside the recording are direct evidence of lock contention during the capture window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-lock-contention-events",
                "Inspect the contended monitor paths captured by the JFR recording",
                "The recording contains enough blocked-monitor time to justify a lock-contention investigation.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.JavaMonitorBlocked <recording.jfr>` to inspect the contended classes and stacks.",
                    "Capture a thread dump or another recording from the same interval to confirm which code path owns the monitor.",
                    "Review synchronized sections or lock implementations before increasing worker counts."
                ),
                List.of(findingId)
            ));
        }

        long gcEventCount = AssessmentSupport.longValue(gcSummary, "eventCount");
        long gcTotalDurationMs = AssessmentSupport.longValue(gcSummary, "totalDurationMs");
        long gcMaxDurationMs = AssessmentSupport.longValue(gcSummary, "maxDurationMs");
        if (gcEventCount > 0L && (gcMaxDurationMs >= 200L || gcTotalDurationMs >= 1_000L)) {
            String findingId = "jfr-gc-pause-events";
            SeverityLevel severity = gcMaxDurationMs >= 1_000L || gcTotalDurationMs >= 5_000L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured GC pause activity worth investigation",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d GC pause event(s) with max pause %s and cumulative pause time %s.",
                    gcEventCount,
                    humanDuration(gcMaxDurationMs),
                    humanDuration(gcTotalDurationMs)
                ),
                "jfr.gc-pause",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-gc-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Measured GC pause time inside the recording is direct evidence that stop-the-world or pause-heavy GC activity occurred during the capture window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-gc-pause-events",
                "Inspect GC pause causes and correlate them with heap or allocation pressure",
                "The recording contains measurable GC pause time that may explain latency or throughput issues.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.GarbageCollection,jdk.GCPhasePause <recording.jfr>` to inspect pause timing and causes.",
                    "Correlate the pause window with GC logs, heap pressure, and allocation behavior from the same incident interval.",
                    "Check whether the longest pauses align with allocation spikes, promotion pressure, or metadata growth."
                ),
                List.of(findingId)
            ));
        }

        long monitorWaitEventCount = AssessmentSupport.longValue(monitorWaitSummary, "eventCount");
        long monitorWaitTotalDurationMs = AssessmentSupport.longValue(monitorWaitSummary, "totalDurationMs");
        long monitorWaitMaxDurationMs = AssessmentSupport.longValue(monitorWaitSummary, "maxDurationMs");
        if (monitorWaitEventCount > 0L
            && (monitorWaitMaxDurationMs >= 100L || monitorWaitTotalDurationMs >= 400L || monitorWaitEventCount >= 4L)) {
            String findingId = "jfr-monitor-wait-events";
            SeverityLevel severity = monitorWaitMaxDurationMs >= 1_000L
                || monitorWaitTotalDurationMs >= 3_000L
                || monitorWaitEventCount >= 12L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured meaningful monitor-wait backlog",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d monitor-wait event(s) with max wait %s and cumulative wait time %s.",
                    monitorWaitEventCount,
                    humanDuration(monitorWaitMaxDurationMs),
                    humanDuration(monitorWaitTotalDurationMs)
                ),
                "jfr.monitor-wait",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-monitor-wait-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Measured monitor-wait time is direct evidence that threads were queued on monitor-based coordination during the capture window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-monitor-wait-events",
                "Inspect the monitor-wait backlog before adding more worker threads",
                "The recording shows threads repeatedly waiting on monitor-based coordination paths.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.JavaMonitorWait <recording.jfr>` to inspect the waiting threads, reasons, and durations.",
                    "Check whether producer-consumer handoffs, queue drains, or notify patterns are causing backlog rather than harmless short waits.",
                    "Capture a matching thread dump if the issue is active so the waiting and notifying sides can be tied back to live code."
                ),
                List.of(findingId)
            ));
        }

        long threadParkEventCount = AssessmentSupport.longValue(threadParkSummary, "eventCount");
        long threadParkTotalDurationMs = AssessmentSupport.longValue(threadParkSummary, "totalDurationMs");
        long threadParkMaxDurationMs = AssessmentSupport.longValue(threadParkSummary, "maxDurationMs");
        if (threadParkEventCount > 0L && (threadParkMaxDurationMs >= 100L || threadParkTotalDurationMs >= 300L || threadParkEventCount >= 10L)) {
            String findingId = "jfr-thread-park-events";
            SeverityLevel severity = threadParkMaxDurationMs >= 1_000L || threadParkTotalDurationMs >= 3_000L || threadParkEventCount >= 50L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured prolonged thread parking or scheduler waiting",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d thread-park event(s) with max park time %s and cumulative park time %s.",
                    threadParkEventCount,
                    humanDuration(threadParkMaxDurationMs),
                    humanDuration(threadParkTotalDurationMs)
                ),
                "jfr.thread-park",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-thread-park-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Measured thread-park time inside the recording is direct evidence that threads spent meaningful time parked or waiting during the capture window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-thread-park-events",
                "Inspect why threads are parking or waiting for long intervals",
                "The recording contains enough parked-thread time to justify a deeper wait-state investigation.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.ThreadPark <recording.jfr>` to inspect the parked threads and durations.",
                    "Capture a matching thread dump to determine whether the waits are expected backpressure or stalled work.",
                    "Review executor saturation, queue handoffs, and timeout behavior before adding more worker threads."
                ),
                List.of(findingId)
            ));
        }

        long ioEventCount = AssessmentSupport.longValue(ioSummary, "eventCount");
        long ioTotalDurationMs = AssessmentSupport.longValue(ioSummary, "totalDurationMs");
        long ioMaxDurationMs = AssessmentSupport.longValue(ioSummary, "maxDurationMs");
        if (ioEventCount > 0L && (ioMaxDurationMs >= 100L || ioTotalDurationMs >= 300L || ioEventCount >= 5L)) {
            String findingId = "jfr-io-latency-events";
            SeverityLevel severity = ioMaxDurationMs >= 1_000L || ioTotalDurationMs >= 3_000L || ioEventCount >= 20L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured slow file or socket I/O activity",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d file or socket I/O event(s) with max latency %s and cumulative latency %s.",
                    ioEventCount,
                    humanDuration(ioMaxDurationMs),
                    humanDuration(ioTotalDurationMs)
                ),
                "jfr.io-latency",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-io-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Measured file or socket latency inside the recording is direct evidence that external I/O delays contributed to the observed runtime behavior."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-io-latency-events",
                "Inspect the slow I/O paths captured by the recording",
                "The recording shows file or socket operations that were slow enough to merit direct investigation.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.SocketRead,jdk.SocketWrite,jdk.FileRead,jdk.FileWrite <recording.jfr>` to inspect the slowest operations.",
                    "Correlate the I/O interval with application request latency, downstream dependency health, and host storage or network metrics.",
                    "Confirm whether the latency is isolated to one dependency path before tuning the JVM itself."
                ),
                List.of(findingId)
            ));
        }

        Map<String, Object> virtualThreadPinnedSummary = eventTypeDetail(eventTypeDetails, "jdk.VirtualThreadPinned", "VirtualThreadPinned");
        long virtualThreadPinnedCount = AssessmentSupport.longValue(virtualThreadPinnedSummary, "eventCount");
        long virtualThreadPinnedMaxDurationMs = AssessmentSupport.longValue(virtualThreadPinnedSummary, "maxDurationMs");
        String topPinnedThread = topHotspotValue(virtualThreadPinnedSummary, "topThreads");
        if (virtualThreadPinnedCount > 0L) {
            String findingId = "jfr-virtual-thread-pinning";
            SeverityLevel severity = virtualThreadPinnedCount >= 3L || virtualThreadPinnedMaxDurationMs >= 100L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String summaryText = String.format(
                Locale.ROOT,
                "The recording contains %d virtual-thread pinning event(s)%s.",
                virtualThreadPinnedCount,
                topPinnedThread != null ? " involving " + topPinnedThread : ""
            );
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured virtual-thread pinning",
                summaryText,
                "jfr.virtual-thread-pinning",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-top-event-types", "jfr-recording-summary"),
                "Explicit virtual-thread pinning events are direct evidence that blocking work forced virtual-thread execution onto carrier threads."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-virtual-thread-pinning",
                "Inspect synchronized, native, or blocking sections on the virtual-thread path",
                "The recording explicitly captured virtual-thread pinning rather than normal lightweight parking.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the pinned stacks and surrounding code for synchronized blocks, native calls, or blocking I/O that can pin carrier threads.",
                    "Compare the pinning interval with request latency or thread-pool symptoms before treating carrier parallelism as the main fix.",
                    "Capture a matching thread dump if the issue is still active so the pinned carrier or virtual-thread stacks can be tied back to live code."
                ),
                List.of(findingId)
            ));
        }

        Map<String, Object> virtualThreadSubmitFailedSummary = eventTypeDetail(
            eventTypeDetails,
            "jdk.VirtualThreadSubmitFailed",
            "VirtualThreadSubmitFailed"
        );
        long virtualThreadSubmitFailedCount = AssessmentSupport.longValue(virtualThreadSubmitFailedSummary, "eventCount");
        String topSubmitFailedThread = topHotspotValue(virtualThreadSubmitFailedSummary, "topThreads");
        String submitFailedReason = sampleEventFieldValue(virtualThreadSubmitFailedSummary, "reason");
        String queuedTaskCount = sampleEventFieldValue(virtualThreadSubmitFailedSummary, "queuedTaskCount");
        if (virtualThreadSubmitFailedCount > 0L) {
            String findingId = "jfr-virtual-thread-submit-failed";
            SeverityLevel severity = virtualThreadSubmitFailedCount >= 3L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            StringBuilder summaryText = new StringBuilder(String.format(
                Locale.ROOT,
                "The recording contains %d virtual-thread submit-failed event(s)",
                virtualThreadSubmitFailedCount
            ));
            if (submitFailedReason != null) {
                summaryText.append(" with representative reason ").append(submitFailedReason);
            }
            if (queuedTaskCount != null) {
                summaryText.append(" and queued task count ").append(queuedTaskCount);
            }
            if (topSubmitFailedThread != null) {
                summaryText.append(" on ").append(topSubmitFailedThread);
            }
            summaryText.append('.');
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured virtual-thread submit failures",
                summaryText.toString(),
                "jfr.virtual-thread-submit-failed",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-top-event-types", "jfr-recording-summary"),
                "Explicit virtual-thread submit-failed events are direct evidence that the scheduler could not hand work to carrier execution as expected during the recording."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-virtual-thread-submit-failed",
                "Inspect carrier-thread resource pressure and scheduler saturation",
                "The recording captured failed virtual-thread scheduling rather than ordinary parking or pinning.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the submit-failed event details and compare them with CPU saturation, carrier-thread backlog, or worker-pool exhaustion from the same interval.",
                    "Check whether the carrier scheduler is starved by blocking work, tight CPU budgets, or a parallelism cap that is too small for the workload.",
                    "Treat the failed-submit path as a concrete scheduler-pressure signal before tuning unrelated JVM subsystems."
                ),
                List.of(findingId)
            ));
        }

        long exceptionEventCount = AssessmentSupport.longValue(exceptionSummary, "eventCount");
        if (exceptionEventCount >= 20L) {
            String findingId = "jfr-exception-burst";
            SeverityLevel severity = exceptionEventCount >= 100L ? SeverityLevel.HIGH : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured a burst of thrown exceptions",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d exception-related event(s), which suggests exception-heavy behavior during the capture window.",
                    exceptionEventCount
                ),
                "jfr.exceptions",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-exception-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Exception-throw events inside the recording are direct evidence that the runtime spent meaningful time creating or propagating exceptions."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-exception-burst",
                "Inspect the dominant exception paths captured by the recording",
                "The recording shows exception-heavy behavior that may be contributing to CPU load, latency, or noisy logs.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.JavaExceptionThrow,jdk.ExceptionStatistics <recording.jfr>` to inspect the dominant exception activity.",
                    "Check whether the exceptions align with retries, timeout handling, or repeated validation failures in the same interval.",
                    "Prioritize fixing the repeated exception path before tuning GC or thread counts."
                ),
                List.of(findingId)
            ));
        }

        long safepointEventCount = AssessmentSupport.longValue(safepointSummary, "eventCount");
        long safepointTotalDurationMs = AssessmentSupport.longValue(safepointSummary, "totalDurationMs");
        long safepointMaxDurationMs = AssessmentSupport.longValue(safepointSummary, "maxDurationMs");
        if (safepointEventCount > 0L && (safepointMaxDurationMs >= 50L || safepointTotalDurationMs >= 200L || safepointEventCount >= 5L)) {
            String findingId = "jfr-safepoint-pause-events";
            SeverityLevel severity = safepointMaxDurationMs >= 500L || safepointTotalDurationMs >= 1_500L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured safepoint pause activity worth investigation",
                String.format(
                    Locale.ROOT,
                    "The recording contains %d safepoint-style event(s) with max pause %s and cumulative pause time %s.",
                    safepointEventCount,
                    humanDuration(safepointMaxDurationMs),
                    humanDuration(safepointTotalDurationMs)
                ),
                "jfr.safepoint",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-safepoint-summary", "jfr-top-event-types", "jfr-recording-summary"),
                "Measured safepoint-style pauses inside the recording are direct evidence that VM coordination work interrupted normal execution during the capture window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-safepoint-pause-events",
                "Inspect the VM operations and pauses behind the safepoint activity",
                "The recording shows enough safepoint pause time to justify direct inspection of VM coordination events.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.SafepointBegin,jdk.ExecuteVMOperation <recording.jfr>` to inspect the pause sources.",
                    "Correlate the safepoint interval with GC, deoptimization, biased-lock, or class-loading activity from the same incident window.",
                    "Check whether a deployment, class redefinition, or diagnostic command triggered the longest pauses."
                ),
                List.of(findingId)
            ));
        }

        long classLoadingEventCount = AssessmentSupport.longValue(classLoadingSummary, "eventCount");
        long definedClassCount = AssessmentSupport.longValue(classLoadingSummary, "definedClassCount");
        long classNamedEventCount = AssessmentSupport.longValue(classLoadingSummary, "classNamedEventCount");
        long loaderTaggedEventCount = AssessmentSupport.longValue(classLoadingSummary, "loaderTaggedEventCount");
        long totalMetadataBytes = AssessmentSupport.longValue(classLoadingSummary, "totalMetadataBytes");
        long topLoaderEventCount = AssessmentSupport.longValue(classLoadingSummary, "topLoaderEventCount");
        double topLoaderShare = AssessmentSupport.doubleValue(classLoadingSummary, "topLoaderShare");
        String topLoader = stringValue(classLoadingSummary, "topLoader");
        String topPackage = stringValue(classLoadingSummary, "topPackage");
        String topThread = stringValue(classLoadingSummary, "topThread");
        if (classLoadingEventCount >= 5L
            && (definedClassCount >= 5L || totalMetadataBytes >= 1_000_000L || topLoaderEventCount >= 4L)) {
            String findingId = "jfr-class-loading-pressure";
            SeverityLevel severity = definedClassCount >= 10L
                || totalMetadataBytes >= 2_000_000L
                || (topLoaderShare >= 0.80d && classLoadingEventCount >= 8L)
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            StringBuilder summaryText = new StringBuilder(String.format(
                Locale.ROOT,
                "The recording contains %d class-loading event(s) across %d defined class(es)",
                classLoadingEventCount,
                definedClassCount
            ));
            if (totalMetadataBytes > 0L) {
                summaryText.append(String.format(Locale.ROOT, " with about %s of attributed class metadata", humanBytes(totalMetadataBytes)));
            }
            if (topLoader != null) {
                summaryText.append(String.format(
                    Locale.ROOT,
                    ", mostly through %s (%d event(s), %.0f%% share)",
                    topLoader,
                    topLoaderEventCount,
                    topLoaderShare * 100.0d
                ));
            }
            if (topPackage != null) {
                summaryText.append(" in ").append(topPackage);
            }
            if (topThread != null) {
                summaryText.append(" on thread ").append(topThread);
            }
            summaryText.append(".");
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured concentrated class-loading pressure",
                summaryText.toString(),
                "jfr.class-loading",
                severity,
                classNamedEventCount > 0L && loaderTaggedEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-class-loading-summary", "jfr-recording-summary"),
                "Repeated class-definition activity concentrated in one loader, package family, or short interval is strong evidence of class-loader churn or dynamic-generation pressure during the recording."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-class-loading-pressure",
                "Inspect class-loader churn and class-metadata growth from the same interval",
                "The recording shows concentrated class-loading activity that may be contributing to metaspace pressure.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Compare the JFR class-loading activity with NMT class or metaspace growth, or run `jcmd <pid> VM.classloader_stats` from a similar interval.",
                    "Review dynamic proxy generation, bytecode generation, redeploy or reload behavior, and whether the dominant loader is expected to unload classes.",
                    "If class counts or metaspace keep climbing, capture a longer JFR focused on class loading before treating a larger MaxMetaspaceSize as the main fix."
                ),
                List.of(findingId)
            ));
        }

        long codeCacheEventCount = AssessmentSupport.longValue(codeCacheSummary, "eventCount");
        long compilationEventCount = AssessmentSupport.longValue(codeCacheSummary, "compilationEventCount");
        long codeCacheFullEventCount = AssessmentSupport.longValue(codeCacheSummary, "codeCacheFullEventCount");
        long peakCodeCacheUsedBytes = AssessmentSupport.longValue(codeCacheSummary, "peakCodeCacheUsedBytes");
        long peakCodeCacheCapacityBytes = AssessmentSupport.longValue(codeCacheSummary, "peakCodeCacheCapacityBytes");
        long minCodeCacheFreeBytes = AssessmentSupport.longValue(codeCacheSummary, "minCodeCacheFreeBytes");
        long maxCompilationQueueSize = AssessmentSupport.longValue(codeCacheSummary, "maxCompilationQueueSize");
        long totalCompilationDurationMs = AssessmentSupport.longValue(codeCacheSummary, "totalCompilationDurationMs");
        boolean compilerDisabled = Boolean.TRUE.equals(codeCacheSummary.get("compilerDisabled"));
        String topCompiler = stringValue(codeCacheSummary, "topCompiler");
        String topCompilationMethod = stringValue(codeCacheSummary, "topCompilationMethod");
        double peakUsageRatio = AssessmentSupport.doubleValue(codeCacheSummary, "peakUsageRatio");
        if (codeCacheEventCount > 0L
            && (codeCacheFullEventCount > 0L
                || compilerDisabled
                || peakUsageRatio >= 0.95d
                || (minCodeCacheFreeBytes > 0L && minCodeCacheFreeBytes <= 2_000_000L)
                || maxCompilationQueueSize >= 12L)) {
            String findingId = "jfr-code-cache-pressure";
            SeverityLevel severity = codeCacheFullEventCount > 0L || compilerDisabled || peakUsageRatio >= 0.98d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            StringBuilder summaryText = new StringBuilder(String.format(
                Locale.ROOT,
                "The recording captured %d compilation or code-cache event(s)",
                codeCacheEventCount
            ));
            if (compilationEventCount > 0L) {
                summaryText.append(String.format(Locale.ROOT, ", including %d compilation event(s)", compilationEventCount));
            }
            if (codeCacheFullEventCount > 0L) {
                summaryText.append(String.format(Locale.ROOT, " and %d code-cache-full event(s)", codeCacheFullEventCount));
            }
            if (peakUsageRatio > 0.0d && peakCodeCacheCapacityBytes > 0L) {
                summaryText.append(String.format(
                    Locale.ROOT,
                    "; peak code-cache use reached %s of %s",
                    humanBytes(peakCodeCacheUsedBytes),
                    humanBytes(peakCodeCacheCapacityBytes)
                ));
            }
            if (minCodeCacheFreeBytes > 0L) {
                summaryText.append(String.format(Locale.ROOT, "; minimum free code-cache space fell to %s", humanBytes(minCodeCacheFreeBytes)));
            }
            if (maxCompilationQueueSize > 0L) {
                summaryText.append(String.format(Locale.ROOT, "; the compilation queue peaked at %d", maxCompilationQueueSize));
            }
            if (topCompiler != null) {
                summaryText.append(" on ").append(topCompiler);
            }
            if (topCompilationMethod != null) {
                summaryText.append(" while compiling ").append(topCompilationMethod);
            }
            if (compilerDisabled) {
                summaryText.append("; compiler disablement was recorded");
            }
            summaryText.append('.');
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR recording captured code-cache pressure on compiler threads",
                summaryText.toString(),
                "jfr.code-cache",
                severity,
                (peakUsageRatio > 0.0d || codeCacheFullEventCount > 0L || compilerDisabled)
                    ? ConfidenceLevel.HIGH
                    : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-code-cache-summary", "jfr-recording-summary"),
                "Compilation activity paired with near-full code-cache occupancy or explicit code-cache-full events is direct evidence of code-cache pressure during the recording window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-code-cache-pressure",
                "Inspect code-cache occupancy and compilation pressure from the same interval",
                "The recording shows the compiler running close to code-cache exhaustion.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Compare the JFR compilation and code-cache signals with `jcmd <pid> Compiler.codecache` or a similar code-cache view from the same workload.",
                    "Review whether frequent recompilation, dynamic code generation, or a burst of hot methods is filling the code cache faster than expected.",
                    "If code-cache space is genuinely undersized for the workload, treat `ReservedCodeCacheSize` as a mitigation to validate after you understand why compiler pressure rose."
                ),
                List.of(findingId)
            ));
            if (totalCompilationDurationMs > 0L && totalCompilationDurationMs >= 500L) {
                actions.add(AssessmentSupport.action(
                    "action-jfr-code-cache-compilation-focus",
                    "Inspect the busiest compiled methods before broad JVM tuning",
                    "The recording includes enough compiler activity to name a concrete compilation hot path.",
                    ActionType.INVESTIGATION,
                    ActionPriority.MEDIUM,
                    List.of(
                        "Use the compilation-focused JFR view to see whether one method family or compiler thread dominates the incident window.",
                        "Check whether the hottest compiled methods line up with recent code changes, generated classes, or rapidly changing call-site profiles.",
                        "Keep the investigation centered on compiled-code growth and compiler backlog before treating the issue as generic CPU or GC pressure."
                    ),
                    List.of(findingId)
                ));
            }
        }

        long cpuLoadEventCount = AssessmentSupport.longValue(cpuLoadSummary, "cpuLoadEventCount");
        long threadCpuLoadEventCount = AssessmentSupport.longValue(cpuLoadSummary, "threadCpuLoadEventCount");
        double averageMachineTotal = AssessmentSupport.doubleValue(cpuLoadSummary, "averageMachineTotal");
        double peakMachineTotal = AssessmentSupport.doubleValue(cpuLoadSummary, "peakMachineTotal");
        double averageJvmTotal = AssessmentSupport.doubleValue(cpuLoadSummary, "averageJvmTotal");
        double peakJvmTotal = AssessmentSupport.doubleValue(cpuLoadSummary, "peakJvmTotal");
        double topThreadPeakTotal = AssessmentSupport.doubleValue(cpuLoadSummary, "topThreadPeakTotal");
        String topCpuThread = stringValue(cpuLoadSummary, "topThread");
        if (cpuLoadEventCount > 0L || threadCpuLoadEventCount > 0L) {
            boolean saturated = peakMachineTotal >= 0.90d
                || averageMachineTotal >= 0.75d
                || peakJvmTotal >= 0.80d
                || topThreadPeakTotal >= 0.75d;
            if (saturated) {
                String findingId = "jfr-cpu-load-saturation";
                SeverityLevel severity = peakMachineTotal >= 0.97d
                    || averageMachineTotal >= 0.85d
                    || peakJvmTotal >= 0.90d
                    || topThreadPeakTotal >= 0.90d
                    ? SeverityLevel.HIGH
                    : SeverityLevel.MEDIUM;
                StringBuilder summaryText = new StringBuilder();
                summaryText.append(String.format(
                    Locale.ROOT,
                    "CPU-load samples peaked at %s machine total and %s JVM total",
                    humanPercent(peakMachineTotal),
                    humanPercent(peakJvmTotal)
                ));
                if (averageMachineTotal > 0.0d || averageJvmTotal > 0.0d) {
                    summaryText.append(String.format(
                        Locale.ROOT,
                        " with averages of %s machine and %s JVM",
                        humanPercent(averageMachineTotal),
                        humanPercent(averageJvmTotal)
                    ));
                }
                if (topCpuThread != null && topThreadPeakTotal > 0.0d) {
                    summaryText.append(String.format(
                        Locale.ROOT,
                        "; %s peaked at %s thread CPU load",
                        topCpuThread,
                        humanPercent(topThreadPeakTotal)
                    ));
                }
                summaryText.append('.');
                findings.add(AssessmentSupport.finding(
                    parsedArtifact,
                    findingId,
                    "JFR recording captured CPU-load saturation",
                    summaryText.toString(),
                    "jfr.cpu-load",
                    severity,
                    (cpuLoadEventCount >= 2L || threadCpuLoadEventCount >= 2L) ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                    FindingStatus.CONFIRMED,
                    evidenceIds(parsedArtifact, "jfr-cpu-load-summary", "jfr-recording-summary"),
                    "Process and thread CPU-load samples near saturation are direct evidence that the workload was CPU-constrained during the capture window."
                ));
                actions.add(AssessmentSupport.action(
                    "action-jfr-cpu-load-saturation",
                    "Correlate the CPU saturation with execution hot paths before tuning GC",
                    "The recording already shows CPU saturation at the process or thread level.",
                    ActionType.INVESTIGATION,
                    ActionPriority.HIGH,
                    List.of(
                        "Use execution-sample hotspots from the same recording to identify which method or thread consumed the saturated CPU budget.",
                        "Check container or host CPU quotas, throttling, and runnable-thread pressure before treating the issue as primarily GC-related.",
                        "Prioritize the dominant CPU path and scheduler constraints before broad JVM flag tuning."
                    ),
                    List.of(findingId)
                ));
            }
        }

        long executionHotspotCount = AssessmentSupport.longValue(executionHotspotSummary, "stackEventCount");
        long topExecutionMethodCount = AssessmentSupport.longValue(executionHotspotSummary, "topMethodCount");
        double topExecutionMethodShare = AssessmentSupport.doubleValue(executionHotspotSummary, "topMethodShare");
        String topExecutionMethod = stringValue(executionHotspotSummary, "topMethod");
        String topExecutionStack = stringValue(executionHotspotSummary, "topStack");
        if (executionHotspotCount >= 5L && topExecutionMethodCount >= 3L && topExecutionMethodShare >= 0.60d && topExecutionMethod != null) {
            String findingId = "jfr-execution-hot-path";
            SeverityLevel severity = executionHotspotCount >= 10L || topExecutionMethodShare >= 0.80d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR execution samples concentrate in a dominant hot path",
                String.format(
                    Locale.ROOT,
                    "Execution samples concentrate in %s (%d of %d sampled stacks, %.0f%% share)%s.",
                    topExecutionMethod,
                    topExecutionMethodCount,
                    executionHotspotCount,
                    topExecutionMethodShare * 100.0d,
                    topExecutionStack != null ? " with dominant stack " + topExecutionStack : ""
                ),
                "jfr.hot-path.execution",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-execution-hotspots", "jfr-recording-summary"),
                "When execution-sample stacks repeatedly cluster in the same method or short stack signature, the recording has identified a credible hot path rather than random scattered activity."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-execution-hot-path",
                "Inspect the dominant execution-sample hot path in the recording",
                "Execution samples are concentrated enough to point at a specific hot method or short stack path.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.ExecutionSample <recording.jfr>` to inspect the sampled stacks around the dominant method.",
                    "Compare the hot path with application request profiles, thread-pool activity, and recent code changes in the same interval.",
                    "Treat the dominant sampled method as a lead for CPU or throughput investigation before tuning unrelated JVM subsystems."
                ),
                List.of(findingId)
            ));
        }

        long runtimeHotspotCount = AssessmentSupport.longValue(runtimeHotspotSummary, "stackEventCount");
        long topRuntimeMethodCount = AssessmentSupport.longValue(runtimeHotspotSummary, "topMethodCount");
        double topRuntimeMethodShare = AssessmentSupport.doubleValue(runtimeHotspotSummary, "topMethodShare");
        String topRuntimeMethod = stringValue(runtimeHotspotSummary, "topMethod");
        String topRuntimeStack = stringValue(runtimeHotspotSummary, "topStack");
        if (runtimeHotspotCount >= 3L && topRuntimeMethodCount >= 2L && topRuntimeMethodShare >= 0.50d && topRuntimeMethod != null) {
            String findingId = "jfr-runtime-hot-path";
            SeverityLevel severity = runtimeHotspotCount >= 6L || topRuntimeMethodShare >= 0.75d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR stack-bearing runtime events cluster around a dominant hot path",
                String.format(
                    Locale.ROOT,
                    "Stack-bearing runtime events frequently originate from %s (%d of %d sampled stacks, %.0f%% share)%s.",
                    topRuntimeMethod,
                    topRuntimeMethodCount,
                    runtimeHotspotCount,
                    topRuntimeMethodShare * 100.0d,
                    topRuntimeStack != null ? " with dominant stack " + topRuntimeStack : ""
                ),
                "jfr.hot-path.runtime",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-runtime-hotspots", "jfr-recording-summary"),
                "When stack-bearing wait, latency, or exception events cluster in the same method or short stack signature, the recording points to a concrete runtime path behind the delay or churn."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-runtime-hot-path",
                "Inspect the dominant runtime stack path in the recording",
                "The stack-bearing runtime events are concentrated enough to point at a concrete call path.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Use `jfr print --events jdk.ThreadPark,jdk.SocketRead,jdk.SocketWrite,jdk.FileRead,jdk.FileWrite,jdk.JavaExceptionThrow,jdk.SafepointBegin <recording.jfr>` to inspect the affected stacks.",
                    "Capture a matching thread dump or application trace to confirm whether the dominant path reflects intentional backpressure or an unexpected stall.",
                    "Prioritize the dominant stack path before broad JVM tuning because the recording has already narrowed the incident to one runtime path."
                ),
                List.of(findingId)
            ));
        }

        long allocationEventCount = AssessmentSupport.longValue(allocationFieldSummary, "eventCount");
        long sizedAllocationEventCount = AssessmentSupport.longValue(allocationFieldSummary, "sizedEventCount");
        long classedAllocationEventCount = AssessmentSupport.longValue(allocationFieldSummary, "classedEventCount");
        long totalAllocatedBytes = AssessmentSupport.longValue(allocationFieldSummary, "totalAllocatedBytes");
        long maxAllocatedBytes = AssessmentSupport.longValue(allocationFieldSummary, "maxAllocatedBytes");
        String topAllocationClass = stringValue(allocationFieldSummary, "topClass");
        long topAllocationClassEventCount = AssessmentSupport.longValue(allocationFieldSummary, "topClassEventCount");
        double topAllocationClassEventShare = AssessmentSupport.doubleValue(allocationFieldSummary, "topClassEventShare");
        long topAllocationClassAllocatedBytes = AssessmentSupport.longValue(allocationFieldSummary, "topClassAllocatedBytes");
        double topAllocationClassAllocatedByteShare = AssessmentSupport.doubleValue(allocationFieldSummary, "topClassAllocatedByteShare");

        if (allocationEventCount >= 5L && (totalAllocatedBytes >= 8_000_000L || maxAllocatedBytes >= 1_000_000L || topAllocationClassEventCount >= 5L)) {
            String findingId = "jfr-allocation-churn";
            SeverityLevel severity = totalAllocatedBytes >= 16_000_000L
                || topAllocationClassAllocatedByteShare >= 0.80d
                || allocationEventCount >= 10L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR allocation events show concentrated allocation pressure",
                String.format(
                    Locale.ROOT,
                    "Allocation-related events account for %s across %d event(s)%s.",
                    totalAllocatedBytes > 0L ? "about " + humanBytes(totalAllocatedBytes) : "repeated allocation activity",
                    allocationEventCount,
                    topAllocationClass != null
                        ? String.format(
                            Locale.ROOT,
                            totalAllocatedBytes > 0L
                                ? " with %s leading at %s across %d event(s)"
                                : " with %s leading across %d event(s)",
                            topAllocationClass,
                            totalAllocatedBytes > 0L ? humanBytes(topAllocationClassAllocatedBytes) : topAllocationClassEventCount,
                            topAllocationClassEventCount
                        )
                        : ""
                ),
                "jfr.allocation",
                severity,
                sizedAllocationEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-allocation-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "Allocation events that repeatedly attribute large byte counts or high event volume provide direct evidence of allocation churn during the recording window."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-allocation-churn",
                "Inspect the dominant allocation classes and byte-heavy allocation events",
                "The recording attributes enough allocation activity to justify a direct allocation-pressure investigation.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ObjectAllocationSample <recording.jfr>` to inspect allocation fields and classes.",
                    "Correlate the dominant allocation classes with GC pauses, heap histograms, or class histograms from the same incident window.",
                    "Treat the leading allocation class and call path as a stronger lead than broad GC tuning until the source of the churn is understood."
                ),
                List.of(findingId)
            ));
        }

        if (topAllocationClass != null
            && topAllocationClassEventCount >= 4L
            && (topAllocationClassEventShare >= 0.60d || topAllocationClassAllocatedByteShare >= 0.60d)) {
            String findingId = "jfr-dominant-allocation-class";
            SeverityLevel severity = topAllocationClassEventShare >= 0.80d || topAllocationClassAllocatedByteShare >= 0.80d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR allocation fields point to a dominant allocating class",
                String.format(
                    Locale.ROOT,
                    totalAllocatedBytes > 0L
                        ? "%s accounts for %d of %d allocation events (%.0f%% share) and about %s of attributed bytes (%.0f%% share)."
                        : "%s accounts for %d of %d allocation events (%.0f%% share).",
                    topAllocationClass,
                    topAllocationClassEventCount,
                    allocationEventCount,
                    topAllocationClassEventShare * 100.0d,
                    totalAllocatedBytes > 0L ? humanBytes(topAllocationClassAllocatedBytes) : null,
                    totalAllocatedBytes > 0L ? topAllocationClassAllocatedByteShare * 100.0d : null
                ),
                "jfr.allocation.class",
                severity,
                classedAllocationEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-allocation-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "When allocation event fields repeatedly name the same class and that class dominates the attributed bytes or event share, the recording has isolated a concrete allocating object type."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-dominant-allocation-class",
                "Inspect why one class dominates allocation activity",
                "A single class accounts for most of the captured allocation pressure in the recording.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Check whether the dominant class reflects expected transient payloads, buffering, or a recent application regression.",
                    "Capture `jcmd <pid> GC.class_histogram` or a later heap histogram to confirm whether the same class also dominates retained memory.",
                    "Review the allocation path for the dominant class before adjusting thread counts or collector settings."
                ),
                List.of(findingId)
            ));
        }

        long allocationHotspotCount = AssessmentSupport.longValue(allocationHotspotSummary, "stackEventCount");
        long topAllocationMethodCount = AssessmentSupport.longValue(allocationHotspotSummary, "topMethodCount");
        double topAllocationMethodShare = AssessmentSupport.doubleValue(allocationHotspotSummary, "topMethodShare");
        long topAllocationMethodAllocatedBytes = AssessmentSupport.longValue(allocationHotspotSummary, "topMethodAllocatedBytes");
        double topAllocationMethodAllocatedByteShare = AssessmentSupport.doubleValue(allocationHotspotSummary, "topMethodAllocatedByteShare");
        String topAllocationMethod = stringValue(allocationHotspotSummary, "topMethod");
        String topAllocationStack = stringValue(allocationHotspotSummary, "topStack");
        if (allocationHotspotCount >= 5L
            && topAllocationMethodCount >= 3L
            && (topAllocationMethodShare >= 0.60d || topAllocationMethodAllocatedByteShare >= 0.60d)
            && topAllocationMethod != null) {
            String findingId = "jfr-allocation-hot-path";
            SeverityLevel severity = allocationHotspotCount >= 8L
                || topAllocationMethodShare >= 0.80d
                || topAllocationMethodAllocatedByteShare >= 0.80d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR allocation stacks cluster around a dominant allocation path",
                String.format(
                    Locale.ROOT,
                    totalAllocatedBytes > 0L
                        ? "Stack-bearing allocation events frequently originate from %s (%d of %d sampled allocation stacks, %.0f%% share) with about %s of attributed bytes%s."
                        : "Stack-bearing allocation events frequently originate from %s (%d of %d sampled allocation stacks, %.0f%% share)%s.",
                    topAllocationMethod,
                    topAllocationMethodCount,
                    allocationHotspotCount,
                    topAllocationMethodShare * 100.0d,
                    totalAllocatedBytes > 0L ? humanBytes(topAllocationMethodAllocatedBytes) : topAllocationStack != null ? " with dominant stack " + topAllocationStack : "",
                    totalAllocatedBytes > 0L && topAllocationStack != null ? " and dominant stack " + topAllocationStack : ""
                ),
                "jfr.hot-path.allocation",
                severity,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-allocation-hotspots", "jfr-allocation-field-summary", "jfr-recording-summary"),
                "When stack-bearing allocation events repeatedly cluster in the same method or short stack signature, the recording has narrowed the allocation pressure to a specific code path."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-allocation-hot-path",
                "Inspect the dominant allocation stack path in the recording",
                "Allocation stacks are concentrated enough to point at a concrete code path behind the churn.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ObjectAllocationSample <recording.jfr>` and inspect the repeated stacks around the dominant method.",
                    "Correlate the allocation path with the dominant allocating class and with any matching GC pressure from the same interval.",
                    "Prioritize the dominant allocation path before broad JVM tuning because the recording has already localized the source of the churn."
                ),
                List.of(findingId)
            ));
        }

        long oldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "eventCount");
        long sizedOldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "sizedEventCount");
        long classedOldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "classedEventCount");
        long agedOldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "agedEventCount");
        long rootedOldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "rootedEventCount");
        long depthOldObjectEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "depthEventCount");
        long totalSampledOldObjectBytes = AssessmentSupport.longValue(oldObjectFieldSummary, "totalSampledObjectBytes");
        long maxOldObjectAgeMs = AssessmentSupport.longValue(oldObjectFieldSummary, "maxObjectAgeMs");
        long maxReferenceDepth = AssessmentSupport.longValue(oldObjectFieldSummary, "maxReferenceDepth");
        double averageReferenceDepth = AssessmentSupport.doubleValue(oldObjectFieldSummary, "averageReferenceDepth");
        String topOldObjectClass = stringValue(oldObjectFieldSummary, "topClass");
        long topOldObjectClassEventCount = AssessmentSupport.longValue(oldObjectFieldSummary, "topClassEventCount");
        double topOldObjectClassEventShare = AssessmentSupport.doubleValue(oldObjectFieldSummary, "topClassEventShare");
        long topOldObjectClassSampledBytes = AssessmentSupport.longValue(oldObjectFieldSummary, "topClassSampledObjectBytes");
        double topOldObjectClassSampledByteShare = AssessmentSupport.doubleValue(oldObjectFieldSummary, "topClassSampledObjectByteShare");
        String topRootType = stringValue(oldObjectFieldSummary, "topRootType");
        String topRootSystem = stringValue(oldObjectFieldSummary, "topRootSystem");

        if (oldObjectEventCount >= 2L
            && (totalSampledOldObjectBytes >= 512_000L || maxOldObjectAgeMs >= 60_000L || maxReferenceDepth >= 4L)) {
            String findingId = "jfr-old-object-retention-candidates";
            SeverityLevel severity = totalSampledOldObjectBytes >= 2_000_000L
                || maxOldObjectAgeMs >= 300_000L
                || maxReferenceDepth >= 6L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            StringBuilder summaryText = new StringBuilder(String.format(
                Locale.ROOT,
                "The recording contains %d old-object sample(s)",
                oldObjectEventCount
            ));
            if (totalSampledOldObjectBytes > 0L) {
                summaryText.append(String.format(Locale.ROOT, " covering about %s of sampled old-object size", humanBytes(totalSampledOldObjectBytes)));
            }
            if (maxOldObjectAgeMs > 0L) {
                summaryText.append(String.format(Locale.ROOT, " with max observed age %s", humanDuration(maxOldObjectAgeMs)));
            }
            if (topRootType != null || topRootSystem != null) {
                summaryText.append(" and GC root hints from ");
                if (topRootSystem != null) {
                    summaryText.append(topRootSystem);
                    if (topRootType != null) {
                        summaryText.append("/");
                    }
                }
                if (topRootType != null) {
                    summaryText.append(topRootType);
                }
            }
            summaryText.append(".");
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR old-object samples point to long-lived retained-object candidates",
                summaryText.toString(),
                "jfr.old-object",
                severity,
                sizedOldObjectEventCount > 0L && agedOldObjectEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-old-object-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "Old-object samples are emitted specifically to highlight long-lived objects that survived into older generations, so repeated samples with meaningful size, age, or root context are strong retention-candidate evidence."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-old-object-retention-candidates",
                "Inspect the long-lived object candidates captured by the recording",
                "The recording includes old-object samples that may point to classes or roots keeping memory alive longer than expected.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.OldObjectSample <recording.jfr>` to inspect the sampled classes, ages, and GC-root hints.",
                    "Compare the dominant sampled classes with `jcmd <pid> GC.class_histogram` or a later heap histogram from the same incident window.",
                    "If the same class or root path keeps reappearing, capture a heap dump and inspect dominators before tuning the JVM."
                ),
                List.of(findingId)
            ));
        }

        if (topOldObjectClass != null
            && topOldObjectClassEventCount >= 2L
            && (topOldObjectClassEventShare >= 0.60d || topOldObjectClassSampledByteShare >= 0.60d)) {
            String findingId = "jfr-dominant-old-object-class";
            SeverityLevel severity = topOldObjectClassEventShare >= 0.80d || topOldObjectClassSampledByteShare >= 0.80d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            String summaryText = totalSampledOldObjectBytes > 0L
                ? String.format(
                    Locale.ROOT,
                    "%s accounts for %d of %d old-object samples (%.0f%% share) and about %s of sampled old-object size (%.0f%% share).",
                    topOldObjectClass,
                    topOldObjectClassEventCount,
                    oldObjectEventCount,
                    topOldObjectClassEventShare * 100.0d,
                    humanBytes(topOldObjectClassSampledBytes),
                    topOldObjectClassSampledByteShare * 100.0d
                )
                : String.format(
                    Locale.ROOT,
                    "%s accounts for %d of %d old-object samples (%.0f%% share).",
                    topOldObjectClass,
                    topOldObjectClassEventCount,
                    oldObjectEventCount,
                    topOldObjectClassEventShare * 100.0d
                );
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR old-object samples concentrate in a dominant long-lived class",
                summaryText,
                "jfr.old-object.class",
                severity,
                classedOldObjectEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-old-object-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "When old-object samples repeatedly name the same class and that class dominates the sampled object-size share, the recording has isolated a concrete retention candidate."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-dominant-old-object-class",
                "Inspect why one long-lived class dominates the old-object samples",
                "A single class accounts for most of the old-object samples captured by the recording.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Review whether the dominant class represents an expected cache, buffer, session object, or a recent regression in object lifetime.",
                    "Check whether the same class also dominates heap histograms or class histograms taken near the recording window.",
                    "Treat the dominant long-lived class as a better lead than broad GC tuning until its retention path is understood."
                ),
                List.of(findingId)
            ));
        }

        if (depthOldObjectEventCount > 0L && (maxReferenceDepth >= 4L || averageReferenceDepth >= 2.5d)) {
            String findingId = "jfr-old-object-reference-depth";
            SeverityLevel severity = maxReferenceDepth >= 8L || averageReferenceDepth >= 4.0d
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            StringBuilder summaryText = new StringBuilder(String.format(
                Locale.ROOT,
                "Old-object samples reach max reference depth %d with average depth %.1f",
                maxReferenceDepth,
                averageReferenceDepth
            ));
            if (topRootType != null || topRootSystem != null) {
                summaryText.append(" from GC root hints");
                if (topRootSystem != null) {
                    summaryText.append(" in ").append(topRootSystem);
                }
                if (topRootType != null) {
                    summaryText.append(topRootSystem != null ? "/" : " of type ").append(topRootType);
                }
            }
            summaryText.append(".");
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "JFR old-object samples show deep GC-root reference chains",
                summaryText.toString(),
                "jfr.old-object.depth",
                severity,
                rootedOldObjectEventCount > 0L ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "jfr-old-object-field-summary", "jfr-memory-summary", "jfr-recording-summary"),
                "Deep referrer chains or GC-root distance in old-object samples suggest retention is happening through a multi-hop object graph rather than one obvious direct reference."
            ));
            actions.add(AssessmentSupport.action(
                "action-jfr-old-object-reference-depth",
                "Inspect the GC-root path behind the deepest old-object samples",
                "The recording captured old-object candidates with deeper-than-expected reference chains.",
                ActionType.INVESTIGATION,
                severity == SeverityLevel.HIGH ? ActionPriority.HIGH : ActionPriority.MEDIUM,
                List.of(
                    "Use `jfr print --events jdk.OldObjectSample <recording.jfr>` to inspect the GC root and referrer path for the deepest samples.",
                    "If the root path is truncated or still ambiguous, capture a heap dump and inspect dominators or retained paths for the same class.",
                    "Enable path-to-gc-roots for future old-object recordings if you need fuller retention-chain detail."
                ),
                List.of(findingId)
            ));
        }

        boolean allocationEventsPresent = booleanValue(coverage.get("allocationEventsPresent"));
        boolean oldObjectSamplingPresent = booleanValue(coverage.get("oldObjectSamplingPresent"));
        if (!allocationEventsPresent && !oldObjectSamplingPresent) {
            missingData.add(
                "The JFR recording does not include allocation or old-object-sample events, so it cannot assess allocation churn or retained-object candidates."
            );
        } else {
            if (allocationEventsPresent) {
                boolean allocationFieldDetailsPresent = booleanValue(coverage.get("allocationFieldDetailsPresent"));
                boolean allocationHotspotsPresent = booleanValue(coverage.get("allocationHotspotsPresent"));
                if (!allocationFieldDetailsPresent) {
                    missingData.add(
                        "Allocation events were present, but the recording did not expose usable class, size, or allocator fields for deeper allocation analysis."
                    );
                } else {
                    if (sizedAllocationEventCount == 0L) {
                        missingData.add(
                            "Allocation events were present, but no allocation-size or sample-weight field could be extracted."
                        );
                    }
                    if (classedAllocationEventCount == 0L) {
                        missingData.add(
                            "Allocation events were present, but no allocating class field could be extracted."
                        );
                    }
                }
                if (!allocationHotspotsPresent) {
                    missingData.add(
                        "Allocation events were present, but no stack traces were available to derive allocation hot paths."
                    );
                }
            }

            if (oldObjectSamplingPresent) {
                boolean oldObjectFieldDetailsPresent = booleanValue(coverage.get("oldObjectFieldDetailsPresent"));
                boolean oldObjectRootDetailsPresent = booleanValue(coverage.get("oldObjectRootDetailsPresent"));
                boolean oldObjectDepthDetailsPresent = booleanValue(coverage.get("oldObjectDepthDetailsPresent"));
                if (!oldObjectFieldDetailsPresent) {
                    missingData.add(
                        "Old-object samples were present, but the recording did not expose usable class, size, age, or root fields for retained-object analysis."
                    );
                } else {
                    if (sizedOldObjectEventCount == 0L) {
                        missingData.add(
                            "Old-object samples were present, but no sampled object-size field could be extracted."
                        );
                    }
                    if (classedOldObjectEventCount == 0L) {
                        missingData.add(
                            "Old-object samples were present, but no old-object class field could be extracted."
                        );
                    }
                    if (agedOldObjectEventCount == 0L) {
                        missingData.add(
                            "Old-object samples were present, but no object-age field could be extracted."
                        );
                    }
                }
                if (!oldObjectRootDetailsPresent) {
                    missingData.add(
                        "Old-object samples were present, but the recording did not expose GC-root details for the retained-object candidates."
                    );
                }
                if (!oldObjectDepthDetailsPresent) {
                    missingData.add(
                        "Old-object samples were present, but the recording did not expose referrer-chain depth details."
                    );
                }
            } else if (allocationEventsPresent) {
                missingData.add(
                    "Allocation events were present, but the recording did not include old-object samples, so it cannot show which objects stayed retained."
                );
            }
        }

        boolean executionSamplesPresent = booleanValue(coverage.get("executionSamplesPresent"));
        boolean cpuLoadEventsPresent = booleanValue(coverage.get("cpuLoadEventsPresent"));
        if (!executionSamplesPresent) {
            missingData.add(
                "The JFR recording does not include execution samples, so it cannot identify hot code paths from CPU sampling."
            );
        } else if (executionHotspotCount == 0L) {
            missingData.add(
                "Execution samples were present, but no stack traces were available to derive hot paths from the recording."
            );
        }

        long runtimeSignalEventCount = lockEventCount
            + gcEventCount
            + monitorWaitEventCount
            + threadParkEventCount
            + ioEventCount
            + exceptionEventCount
            + safepointEventCount;
        if (durationMs > 0L
            && durationMs < 30_000L
            && eventCount >= 3L
            && executionSamplesPresent
            && !cpuLoadEventsPresent
            && runtimeSignalEventCount == 0L) {
            missingData.add(
                "This short JFR recording may hide low-latency waits or stalls because many runtime events are thresholded; capture a longer recording or lower wait and I/O thresholds if latency is suspected."
            );
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

    private Map<String, Object> eventTypeDetail(List<Map<String, Object>> eventTypeDetails, String... candidateNames) {
        for (Map<String, Object> eventTypeDetail : eventTypeDetails) {
            String name = stringValue(eventTypeDetail, "name");
            if (name == null) {
                continue;
            }
            for (String candidateName : candidateNames) {
                if (candidateName != null && (name.equals(candidateName) || name.endsWith(candidateName))) {
                    return eventTypeDetail;
                }
            }
        }
        return Map.of();
    }

    private String topHotspotValue(Map<String, Object> detail, String key) {
        Object value = detail.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> topEntry)) {
            return null;
        }
        Object hotspotValue = topEntry.get("value");
        return hotspotValue != null ? String.valueOf(hotspotValue) : null;
    }

    private String sampleEventFieldValue(Map<String, Object> detail, String fieldName) {
        Object value = detail.get("sampleEvents");
        if (!(value instanceof List<?> sampleEvents)) {
            return null;
        }
        for (Object sampleEvent : sampleEvents) {
            if (!(sampleEvent instanceof Map<?, ?> sampleEventMap)) {
                continue;
            }
            Object fields = sampleEventMap.get("fields");
            if (!(fields instanceof Map<?, ?> fieldMap)) {
                continue;
            }
            Object fieldValue = fieldMap.get(fieldName);
            if (fieldValue != null) {
                return String.valueOf(fieldValue);
            }
        }
        return null;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String humanDuration(long durationMs) {
        if (durationMs >= 1_000L) {
            return String.format(Locale.ROOT, "%.2fs", durationMs / 1_000.0d);
        }
        return durationMs + "ms";
    }

    private String humanPercent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100.0d);
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0d);
        }
        if (bytes >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / 1_048_576.0d);
        }
        if (bytes >= 1_024L) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / 1_024.0d);
        }
        return bytes + " B";
    }
}
