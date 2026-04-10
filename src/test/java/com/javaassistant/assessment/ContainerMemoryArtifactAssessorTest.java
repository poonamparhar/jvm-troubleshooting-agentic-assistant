package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContainerMemoryArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ContainerMemoryArtifactParser parser = new ContainerMemoryArtifactParser();
    private final ContainerMemoryArtifactAssessor engine = new ContainerMemoryArtifactAssessor();

    @Test
    void emitsContainerLimitPressureOomAndPsiFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/container_memory_pressure_snapshot.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("container-memory-limit-pressure") && finding.evidenceIds().contains("container-memory-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-high-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-oom-events")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-reclaim-stalls")));
        assertTrue(evaluation.missingData().isEmpty());
        assertFalse(evaluation.recommendedActions().isEmpty());
    }

    @Test
    void emitsContainerBudgetFindingsFromCgroupV1Snapshot() {
        var parsed = parser.parse(new InputArtifact(
            ArtifactType.CONTAINER_MEMORY,
            new ArtifactMetadata("samples/container_memory_v1_snapshot.txt", "container_memory_v1_snapshot.txt", 0L),
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
                """.strip()
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-limit-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-high-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("container-memory-oom-events")));
        assertTrue(evaluation.missingData().stream().anyMatch(item -> item.contains("memory.pressure")));
        assertFalse(evaluation.recommendedActions().isEmpty());
    }

    @Test
    void emitsContainerCpuQuotaAndThrottlingFinding() {
        var parsed = parser.parse(new InputArtifact(
            ArtifactType.CONTAINER_MEMORY,
            new ArtifactMetadata("samples/container_cpu_pressure_snapshot.txt", "container_cpu_pressure_snapshot.txt", 0L),
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
                50000 100000

                [cpu.stat]
                usage_usec 4820000
                user_usec 3520000
                system_usec 1300000
                nr_periods 640
                nr_throttled 224
                throttled_usec 21100000

                [cpu.pressure]
                some avg10=2.10 avg60=1.40 avg300=0.55 total=9831
                full avg10=0.12 avg60=0.08 avg300=0.03 total=1180

                [cpuset.cpus.effective]
                0
                """.strip()
        ));
        var evaluation = engine.evaluate(parsed);

        var finding = evaluation.findings().stream()
            .filter(candidate -> candidate.id().equals("container-cpu-quota-or-processor-mis-sizing"))
            .findFirst()
            .orElseThrow();

        assertTrue(finding.evidenceIds().contains("container-cpu-summary"));
        assertTrue(finding.summary().contains("cpu quota"));
        assertTrue(finding.summary().contains("throttled"));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-container-cpu-quota-or-processor-mis-sizing")
        ));
    }
}
