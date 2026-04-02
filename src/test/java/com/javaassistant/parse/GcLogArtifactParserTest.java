package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcLogArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser parser = new GcLogArtifactParser();

    @Test
    void parsesG1GcLogSummary() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("eventCount")).longValue() > 0);
        assertTrue(((Number) summary.get("maxPauseMs")).doubleValue() > 0.0);
        assertTrue(((Number) summary.get("fullGcCount")).longValue() >= 3L);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-full-gc-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-heap-occupancy-peak")));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void detectsZgcCollector() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_fullgc.log")));

        assertEquals("ZGC", parsed.extractedData().get("collector"));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void parsesZgcAllocationStallSummary() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_allocation_stall.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");

        assertEquals("ZGC", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("allocationStallCount")).longValue() >= 3L);
        assertTrue(((Number) summary.get("gcCycleCount")).longValue() > 0L);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-allocation-stall-summary")));
    }

    @Test
    void parsesMetaspaceTriggeredFullGcSignals() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> metaspace = (Map<String, Object>) parsed.extractedData().get("metaspace");

        assertEquals("G1", parsed.extractedData().get("collector"));
        assertTrue(((Number) summary.get("metaspaceTriggeredFullGcCount")).longValue() >= 2L);
        assertTrue(((Number) metaspace.get("peakUsageRatio")).doubleValue() >= 0.80d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-metaspace-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("gc-full-gc-summary")));
    }
}
