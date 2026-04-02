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

public class OomSignalArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.OOM_SIGNAL;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        Map<String, Object> summary = AssessmentSupport.map(parsedArtifact.extractedData(), "summary");
        List<Map<String, Object>> kernelEvents = AssessmentSupport.mapList(parsedArtifact.extractedData(), "kernelEvents");
        List<Map<String, Object>> podSignals = AssessmentSupport.mapList(parsedArtifact.extractedData(), "podSignals");

        long kernelOomKillCount = AssessmentSupport.longValue(summary, "kernelOomKillCount");
        long kernelMemcgCount = AssessmentSupport.longValue(summary, "kernelMemcgCount");
        long podOomKilledCount = AssessmentSupport.longValue(summary, "podOomKilledCount");
        long crashLoopBackOffCount = AssessmentSupport.longValue(summary, "crashLoopBackOffCount");
        long maxRestartCount = AssessmentSupport.longValue(summary, "maxRestartCount");

        if (kernelOomKillCount == 0L && podOomKilledCount == 0L && crashLoopBackOffCount == 0L) {
            missingData.add("No kernel OOM kill or OOMKilled restart signal could be confirmed from the artifact.");
            return new AssessmentResult(findings, actions, missingData);
        }

        if (kernelOomKillCount > 0L) {
            Map<String, Object> firstKernelEvent = kernelEvents.stream()
                .filter(event -> booleanValue(event, "killedProcessLine"))
                .findFirst()
                .orElseGet(() -> kernelEvents.getFirst());
            String processName = stringValue(firstKernelEvent, "processName");
            String memcgPath = kernelEvents.stream()
                .map(event -> stringValue(event, "oomMemcgPath"))
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .orElse(null);
            String summaryText = String.format(
                Locale.ROOT,
                "Kernel OOM handling killed %d process(es)%s%s.",
                kernelOomKillCount,
                processName != null ? ", including " + processName : "",
                memcgPath != null ? " inside cgroup " + memcgPath : ""
            );
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                "oom-signal-kernel-oom-kill",
                "Kernel OOM killer terminated a process",
                summaryText,
                "platform.oom.kernel",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "oom-signal-kernel-event", "oom-signal-kernel-context"),
                kernelMemcgCount > 0L
                    ? "Kernel OOM-kill context and killed-process lines are direct evidence that memory pressure escalated beyond what the cgroup or host could sustain."
                    : "A kernel killed-process line is direct evidence that the workload hit an OOM-kill condition."
            ));
            actions.add(AssessmentSupport.action(
                "action-oom-signal-kernel-oom-kill",
                "Preserve kernel OOM evidence and align it with JVM memory artifacts",
                "A kernel OOM kill confirms that the workload was terminated by platform memory enforcement, not by a clean application shutdown.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve the matching kernel or journal excerpt before log rotation removes it.",
                    "Correlate the kill time with container memory, GC, NMT, heap histogram, and pmap artifacts from the same interval.",
                    "Review cgroup memory limits, JVM heap sizing, and native-memory headroom before restarting at the same settings."
                ),
                List.of("oom-signal-kernel-oom-kill")
            ));
        }

        if (podOomKilledCount > 0L) {
            String podName = stringValue(summary, "podName");
            String namespace = stringValue(summary, "namespace");
            String podLabel = podName != null
                ? podName + (namespace != null ? " in namespace " + namespace : "")
                : "the pod";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                "oom-signal-pod-oomkilled",
                "Pod restart signals show OOMKilled terminations",
                String.format(
                    Locale.ROOT,
                    "%s reported %d OOMKilled container termination(s) with max restart count %d.",
                    podLabel,
                    podOomKilledCount,
                    maxRestartCount
                ),
                "platform.oom.kubernetes",
                SeverityLevel.CRITICAL,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "oom-signal-pod-summary"),
                "OOMKilled in pod status is direct orchestrator-level evidence that the container was terminated for memory exhaustion."
            ));
            actions.add(AssessmentSupport.action(
                "action-oom-signal-pod-oomkilled",
                "Capture pod restart evidence before the next rollout or restart",
                "The pod status already confirms OOMKilled terminations, so the incident needs platform-limit and JVM-memory review together.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Preserve `kubectl describe pod` or equivalent status output showing OOMKilled and restart counts.",
                    "Compare the OOMKilled interval with container memory, JVM memory, and request-load signals from the same pod.",
                    "Review pod memory limits, requests, and JVM sizing before increasing only application-level retries or restart budget."
                ),
                List.of("oom-signal-pod-oomkilled")
            ));
        }

        if (crashLoopBackOffCount > 0L && maxRestartCount >= 3L) {
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                "oom-signal-restart-loop",
                "OOMKilled terminations have escalated into a restart loop",
                String.format(
                    Locale.ROOT,
                    "CrashLoopBackOff-style restart signals are present and the affected container has restarted up to %d time(s).",
                    maxRestartCount
                ),
                "platform.restart-loop",
                maxRestartCount >= 5L ? SeverityLevel.CRITICAL : SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                evidenceIds(parsedArtifact, "oom-signal-pod-summary"),
                "Repeated OOMKilled restarts can erase in-memory evidence and turn a memory incident into an availability incident."
            ));
            actions.add(AssessmentSupport.action(
                "action-oom-signal-restart-loop",
                "Stabilize the service before further evidence is lost to restarts",
                "The workload is now repeatedly restarting after memory-related termination.",
                ActionType.IMMEDIATE,
                ActionPriority.URGENT,
                List.of(
                    "Pause or slow restart churn if your incident process allows it, so supporting evidence is not constantly replaced.",
                    "Capture the latest container-memory snapshot, pod status, and JVM memory artifacts before the next restart.",
                    "Treat restart policy, platform memory limit, and JVM sizing as one mitigation decision."
                ),
                List.of("oom-signal-restart-loop")
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

    private boolean booleanValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
