package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OomSignalArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final OomSignalArtifactParser parser = new OomSignalArtifactParser();

    @Test
    void parsesKernelOomKillIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/kernel_oom_kill.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kernelEvents = (List<Map<String, Object>>) parsed.extractedData().get("kernelEvents");

        assertEquals(1L, ((Number) summary.get("kernelOomKillCount")).longValue());
        assertEquals(1L, ((Number) summary.get("kernelContextCount")).longValue());
        assertEquals(1L, ((Number) summary.get("kernelMemcgCount")).longValue());
        assertEquals("2026-03-30T18:42:13Z", summary.get("earliestAbsoluteEventTime"));
        assertEquals("2026-03-30T18:42:13Z", summary.get("latestAbsoluteEventTime"));
        assertTrue(((List<?>) summary.get("processNames")).contains("java"));
        assertTrue(((List<?>) summary.get("sourceKinds")).contains("kernel-log"));
        assertTrue(kernelEvents.stream().anyMatch(event -> Boolean.TRUE.equals(event.get("killedProcessLine"))));
        assertTrue(kernelEvents.stream().allMatch(event -> "2026-03-30T18:42:13Z".equals(event.get("eventTime"))));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("oom-signal-kernel-event")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("oom-signal-kernel-context")));
    }

    @Test
    void parsesPodRestartSignalsIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/pod_oomkilled_describe.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> podSignals = (List<Map<String, Object>>) parsed.extractedData().get("podSignals");

        assertEquals(1L, ((Number) summary.get("podOomKilledCount")).longValue());
        assertEquals(1L, ((Number) summary.get("crashLoopBackOffCount")).longValue());
        assertEquals(4L, ((Number) summary.get("maxRestartCount")).longValue());
        assertEquals("checkout-api-7b8897b9c9-6l2q9", summary.get("podName"));
        assertEquals("production", summary.get("namespace"));
        assertEquals("2026-03-30T18:32:01Z", summary.get("earliestAbsoluteEventTime"));
        assertEquals("2026-03-30T18:42:13Z", summary.get("latestAbsoluteEventTime"));
        assertTrue(((List<?>) summary.get("sourceKinds")).contains("kubernetes-status"));
        assertEquals(1, podSignals.size());
        assertTrue(Boolean.TRUE.equals(podSignals.getFirst().get("oomKilled")));
        assertTrue(Boolean.TRUE.equals(podSignals.getFirst().get("crashLoopBackOff")));
        assertEquals("2026-03-30T18:41:14Z", podSignals.getFirst().get("startedAt"));
        assertEquals("2026-03-30T18:42:13Z", podSignals.getFirst().get("finishedAt"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("oom-signal-pod-summary")));
    }

    @Test
    void parsesRestartLoopWithoutExplicitOomIntoStructuredData() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/pod_crashloopbackoff_describe.txt",
            """
                Name:           checkout-api-7b8897b9c9-6l2q9
                Namespace:      production
                Start Time:     Sun, 30 Mar 2026 18:32:01 +0000
                Containers:
                  checkout:
                    Container ID:   containerd://abc123
                    Image:          checkout-api:1.4.7
                    State:          Waiting
                      Reason:       CrashLoopBackOff
                    Last State:     Terminated
                      Reason:       Error
                      Exit Code:    1
                      Started:      Sun, 30 Mar 2026 18:41:14 +0000
                      Finished:     Sun, 30 Mar 2026 18:42:13 +0000
                    Restart Count:  6
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> podSignals = (List<Map<String, Object>>) parsed.extractedData().get("podSignals");

        assertEquals(0L, ((Number) summary.get("podOomKilledCount")).longValue());
        assertEquals(1L, ((Number) summary.get("crashLoopBackOffCount")).longValue());
        assertEquals(6L, ((Number) summary.get("maxRestartCount")).longValue());
        assertTrue(Boolean.FALSE.equals(podSignals.getFirst().get("oomKilled")));
        assertTrue(Boolean.TRUE.equals(podSignals.getFirst().get("crashLoopBackOff")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("oom-signal-pod-summary")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.OOM_SIGNAL,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
