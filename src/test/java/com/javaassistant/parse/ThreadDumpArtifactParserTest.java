package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadDumpArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();

    @TempDir
    Path tempDir;

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
        assertEquals("2026-03-30T09:42:11Z", parsed.metadata().attributes().get("captureTime"));
        assertEquals("2026-03-30 09:42:11", parsed.metadata().attributes().get("captureTimeRaw"));
        assertEquals("assumed-utc-no-offset", parsed.metadata().attributes().get("captureTimeNormalization"));
    }

    @Test
    void extractsCaptureTimeFromExplicitIsoMarker() throws Exception {
        Path threadDumpPath = tempDir.resolve("thread_dump_capture_time.txt");
        Files.writeString(
            threadDumpPath,
            """
                Capture time: 2026-04-06T17:10:05Z
                Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

                "main" #1 prio=5 os_prio=31 cpu=31.15ms elapsed=120.35s tid=0x0000000102800000 nid=0x5703 runnable [0x000000016f9d3000]
                   java.lang.Thread.State: RUNNABLE
                    at com.example.cli.CommandLoop.read(CommandLoop.java:118)
                """
        );

        var parsed = parser.parse(loader.load(threadDumpPath));

        assertEquals("2026-04-06T17:10:05Z", parsed.metadata().attributes().get("captureTime"));
        assertEquals("2026-04-06T17:10:05Z", parsed.metadata().attributes().get("captureTimeRaw"));
        assertTrue(!parsed.metadata().attributes().containsKey("captureTimeNormalization"));
        assertEquals(1L, parsed.extractedData().get("threadCount"));
    }
}
