package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OomSignalArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
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
        assertTrue(((List<?>) summary.get("processNames")).contains("java"));
        assertTrue(((List<?>) summary.get("sourceKinds")).contains("kernel-log"));
        assertTrue(kernelEvents.stream().anyMatch(event -> Boolean.TRUE.equals(event.get("killedProcessLine"))));
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
        assertTrue(((List<?>) summary.get("sourceKinds")).contains("kubernetes-status"));
        assertEquals(1, podSignals.size());
        assertTrue(Boolean.TRUE.equals(podSignals.getFirst().get("oomKilled")));
        assertTrue(Boolean.TRUE.equals(podSignals.getFirst().get("crashLoopBackOff")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("oom-signal-pod-summary")));
    }
}
