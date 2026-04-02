package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadDumpArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();

    @Test
    void parsesThreadDumpIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));

        assertEquals(8L, parsed.extractedData().get("threadCount"));
        assertEquals(4L, parsed.extractedData().get("daemonThreadCount"));

        @SuppressWarnings("unchecked")
        Map<String, Long> stateCounts = (Map<String, Long>) parsed.extractedData().get("stateCounts");
        assertEquals(4L, stateCounts.get("BLOCKED"));
        assertEquals(3L, stateCounts.get("RUNNABLE"));
        assertEquals(1L, stateCounts.get("WAITING"));

        @SuppressWarnings("unchecked")
        Map<String, Object> deadlock = (Map<String, Object>) parsed.extractedData().get("deadlock");
        assertEquals(Boolean.TRUE, deadlock.get("detected"));
        assertTrue(((List<?>) deadlock.get("threadNames")).contains("Deadlock-Worker-1"));
        assertTrue(((List<?>) deadlock.get("threadNames")).contains("Deadlock-Worker-2"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hotspots = (List<Map<String, Object>>) parsed.extractedData().get("contentionHotspots");
        assertEquals(1, hotspots.size());
        assertEquals("0x0000000700010001", hotspots.getFirst().get("monitorId"));
        assertEquals(2L, ((Number) hotspots.getFirst().get("blockedWaiterCount")).longValue());
        assertEquals("OrderLockOwner", hotspots.getFirst().get("ownerThreadName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> poolSummaries = (List<Map<String, Object>>) parsed.extractedData().get("poolSummaries");
        Map<String, Object> httpPool = poolSummaries.stream()
            .filter(summary -> "http-nio-8080-exec".equals(summary.get("poolName")))
            .findFirst()
            .orElseThrow();
        assertEquals(3L, ((Number) httpPool.get("threadCount")).longValue());
        assertEquals(2L, ((Number) httpPool.get("blockedCount")).longValue());
        assertEquals(1L, ((Number) httpPool.get("idleExecutorCount")).longValue());
        assertEquals(0L, ((Number) httpPool.get("runnableCount")).longValue());

        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("thread-dump-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("thread-dump-deadlock")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("thread-dump-pool-http-nio-8080-exec")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().startsWith("thread-dump-contention-")));
    }
}
