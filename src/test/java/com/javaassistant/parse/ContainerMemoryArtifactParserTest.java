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

class ContainerMemoryArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ContainerMemoryArtifactParser parser = new ContainerMemoryArtifactParser();

    @Test
    void parsesContainerMemorySnapshotIntoStructuredData() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) parsed.extractedData().get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> stat = (Map<String, Object>) parsed.extractedData().get("stat");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> pressure = (Map<String, Map<String, Object>>) parsed.extractedData().get("pressure");
        @SuppressWarnings("unchecked")
        List<String> sectionsPresent = (List<String>) parsed.extractedData().get("sectionsPresent");

        assertEquals(1_040_187_392L, ((Number) summary.get("currentBytes")).longValue());
        assertEquals(Boolean.TRUE, summary.get("maxDefined"));
        assertEquals(1_073_741_824L, ((Number) summary.get("maxBytes")).longValue());
        assertEquals(Boolean.TRUE, summary.get("highDefined"));
        assertEquals(943_718_400L, ((Number) summary.get("highBytes")).longValue());
        assertEquals(0.96875d, ((Number) summary.get("usageOfMaxRatio")).doubleValue(), 0.0001d);
        assertEquals(128L, ((Number) events.get("high")).longValue());
        assertEquals(1L, ((Number) events.get("oom_kill")).longValue());
        assertEquals(775_946_240L, ((Number) stat.get("anon")).longValue());
        assertEquals(188_743_680L, ((Number) stat.get("file")).longValue());
        assertEquals(6.50d, ((Number) pressure.get("some").get("avg10")).doubleValue(), 0.0001d);
        assertTrue(sectionsPresent.contains("memory.pressure"));

        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-events")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-breakdown")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-pressure")));
    }

    @Test
    void parsesCgroupV1SnapshotIntoStructuredData() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/container_memory_v1_snapshot.txt",
            """
                [memory.usage_in_bytes]
                1040187392

                [memory.limit_in_bytes]
                1073741824

                [memory.soft_limit_in_bytes]
                943718400

                [memory.failcnt]
                14

                [memory.oom_control]
                oom_kill_disable 0
                under_oom 1

                [memory.stat]
                anon 775946240
                file 188743680
                kernel_stack 2097152
                pagetables 5242880
                percpu 1048576
                sock 0
                slab_reclaimable 16777216
                slab_unreclaimable 8388608

                [cpu.cfs_quota_us]
                50000

                [cpu.cfs_period_us]
                100000

                [cpu.stat]
                nr_periods 220
                nr_throttled 88
                throttled_time 4300000000

                [cpuacct.usage]
                12900000000

                [cpuacct.stat]
                user 1200
                system 340

                [cpuset.cpus]
                0-1
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) parsed.extractedData().get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuSummary = (Map<String, Object>) parsed.extractedData().get("cpuSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuStat = (Map<String, Object>) parsed.extractedData().get("cpuStat");
        @SuppressWarnings("unchecked")
        List<String> sectionsPresent = (List<String>) parsed.extractedData().get("sectionsPresent");

        assertEquals("cgroup-v1", parsed.extractedData().get("snapshotVariant"));
        assertEquals(1_040_187_392L, ((Number) summary.get("currentBytes")).longValue());
        assertEquals(1_073_741_824L, ((Number) summary.get("maxBytes")).longValue());
        assertEquals(943_718_400L, ((Number) summary.get("highBytes")).longValue());
        assertEquals(14L, ((Number) events.get("max")).longValue());
        assertEquals(14L, ((Number) events.get("failcnt")).longValue());
        assertEquals(1L, ((Number) events.get("oom")).longValue());
        assertEquals(Boolean.TRUE, cpuSummary.get("quotaDefined"));
        assertEquals(0.50d, ((Number) cpuSummary.get("quotaCores")).doubleValue(), 0.0001d);
        assertEquals(2L, ((Number) cpuSummary.get("effectiveCpuCount")).longValue());
        assertEquals(0.50d, ((Number) cpuSummary.get("configuredCpuCeilingCores")).doubleValue(), 0.0001d);
        assertEquals(88L, ((Number) cpuStat.get("nr_throttled")).longValue());
        assertEquals(4_300L, ((Number) cpuStat.get("throttledMillis")).longValue());
        assertTrue(sectionsPresent.contains("memory.usage_in_bytes"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-memory-events")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-cpu-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-cpu-stat")));
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("memory.pressure PSI")));
    }

    @Test
    void parsesContainerCpuQuotaAndPressureDetailsFromCgroupV2Snapshot() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/container_cpu_v2_snapshot.txt",
            """
                [memory.current]
                1040187392

                [memory.max]
                1073741824

                [memory.events]
                low 0
                high 8
                max 1
                oom 0
                oom_kill 0

                [memory.stat]
                anon 775946240
                file 188743680
                slab 25165824
                kernel 62914560

                [cpu.max]
                100000 100000

                [cpu.stat]
                usage_usec 4820000
                user_usec 3520000
                system_usec 1300000
                nr_periods 640
                nr_throttled 192
                throttled_usec 18400000

                [cpu.pressure]
                some avg10=2.10 avg60=1.40 avg300=0.55 total=9831
                full avg10=0.12 avg60=0.08 avg300=0.03 total=1180

                [cpuset.cpus.effective]
                0
                """
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> cpuSummary = (Map<String, Object>) parsed.extractedData().get("cpuSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuStat = (Map<String, Object>) parsed.extractedData().get("cpuStat");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> cpuPressure = (Map<String, Map<String, Object>>) parsed.extractedData().get("cpuPressure");

        assertEquals(Boolean.TRUE, cpuSummary.get("quotaDefined"));
        assertEquals(1.0d, ((Number) cpuSummary.get("quotaCores")).doubleValue(), 0.0001d);
        assertEquals(1L, ((Number) cpuSummary.get("effectiveCpuCount")).longValue());
        assertEquals(1.0d, ((Number) cpuSummary.get("configuredCpuCeilingCores")).doubleValue(), 0.0001d);
        assertEquals(192L, ((Number) cpuStat.get("nr_throttled")).longValue());
        assertEquals(0.30d, ((Number) cpuStat.get("throttledRatio")).doubleValue(), 0.0001d);
        assertEquals(18_400L, ((Number) cpuStat.get("throttledMillis")).longValue());
        assertEquals(2.10d, ((Number) cpuPressure.get("some").get("avg10")).doubleValue(), 0.0001d);
        assertEquals(0.12d, ((Number) cpuPressure.get("full").get("avg10")).doubleValue(), 0.0001d);
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-cpu-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-cpu-stat")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("container-cpu-pressure")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.CONTAINER_MEMORY,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
