package com.javaassistant.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactLoaderTest {

    private final ArtifactLoader loader = new ArtifactLoader();

    @TempDir
    Path tempDir;

    @Test
    void loadsAndClassifiesSingleFile() throws Exception {
        var artifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));

        assertEquals(ArtifactType.NMT, artifact.type());
        assertEquals("java_nmt_summary_3391237.txt", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Native Memory Tracking"));
    }

    @Test
    void loadsAndClassifiesThreadDumpFile() throws Exception {
        var artifact = loader.load(Path.of("samples/thread_dump_deadlock.txt"));

        assertEquals(ArtifactType.THREAD_DUMP, artifact.type());
        assertEquals("thread_dump_deadlock.txt", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Found one Java-level deadlock"));
    }

    @Test
    void loadsAndClassifiesContainerMemorySnapshot() throws Exception {
        var artifact = loader.load(Path.of("samples/container_memory_pressure_snapshot.txt"));

        assertEquals(ArtifactType.CONTAINER_MEMORY, artifact.type());
        assertEquals("container_memory_pressure_snapshot.txt", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("[memory.current]"));
    }

    @Test
    void loadsAndClassifiesKernelOomSignalLog() throws Exception {
        var artifact = loader.load(Path.of("samples/kernel_oom_kill.log"));

        assertEquals(ArtifactType.OOM_SIGNAL, artifact.type());
        assertEquals("kernel_oom_kill.log", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Killed process 4242 (java)"));
    }

    @Test
    void loadsAndClassifiesPodRestartSignalLog() throws Exception {
        var artifact = loader.load(Path.of("samples/pod_oomkilled_describe.txt"));

        assertEquals(ArtifactType.OOM_SIGNAL, artifact.type());
        assertEquals("pod_oomkilled_describe.txt", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Reason:       OOMKilled"));
    }

    @Test
    void loadsRawContainerMemoryDirectoryAsSyntheticArtifact() throws Exception {
        Path containerDirectory = writeRawContainerMemoryDirectory(tempDir.resolve("cgroup"));

        var artifact = loader.load(containerDirectory);

        assertEquals(ArtifactType.CONTAINER_MEMORY, artifact.type());
        assertEquals("cgroup-container-memory.snapshot", artifact.metadata().displayName());
        assertEquals("synthetic-container-memory-directory", artifact.metadata().attributes().get("contentRepresentation"));
        assertTrue(artifact.content().contains("[memory.current]"));
        assertTrue(artifact.content().contains("[memory.pressure]"));
    }

    @Test
    void loadsJfrRecordingWithoutEmbeddingBinaryContent() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("recording.jfr"));

        var artifact = loader.load(recordingPath);

        assertEquals(ArtifactType.JFR, artifact.type());
        assertEquals("recording.jfr", artifact.metadata().displayName());
        assertTrue(artifact.content().contains("Binary JFR recording"));
        assertEquals(Files.size(recordingPath), artifact.metadata().contentLength());
        assertEquals("external-binary-jfr", artifact.metadata().attributes().get("contentRepresentation"));
    }

    @Test
    void loadsUnknownFilesAsUnknownArtifactsWithoutDroppingContent() throws Exception {
        Path unknownFile = tempDir.resolve("notes.txt");
        Files.writeString(unknownFile, "just some handwritten troubleshooting notes");

        var artifact = loader.load(unknownFile);

        assertEquals(ArtifactType.UNKNOWN, artifact.type());
        assertEquals("notes.txt", artifact.metadata().displayName());
        assertEquals("just some handwritten troubleshooting notes", artifact.content());
    }

    @Test
    void discoversSupportedArtifactsFromDirectory() throws Exception {
        var artifacts = loader.discover(Path.of("samples/single_process_data"));
        Set<ArtifactType> discoveredTypes = artifacts.stream().map(artifact -> artifact.type()).collect(java.util.stream.Collectors.toSet());

        assertTrue(artifacts.size() >= 4);
        assertTrue(discoveredTypes.contains(ArtifactType.GC_LOG));
        assertTrue(discoveredTypes.contains(ArtifactType.NMT));
        assertTrue(discoveredTypes.contains(ArtifactType.PMAP));
    }

    @Test
    void preservesFullContentForDeterministicParsing() throws Exception {
        String content = "HEADER\n" + "A".repeat(250_000) + "\nFOOTER";
        Path artifactPath = tempDir.resolve("large-gc.log");
        Files.writeString(artifactPath, content);

        var artifact = loader.load(artifactPath, ArtifactType.GC_LOG);

        assertEquals(ArtifactType.GC_LOG, artifact.type());
        assertEquals(content, artifact.content());
        assertEquals(content.length(), artifact.metadata().contentLength());
        assertEquals("large-gc.log", artifact.metadata().displayName());
        assertTrue(artifact.metadata().attributes().isEmpty());
    }

    @Test
    void discoversInventoryWithSupportedAndUnsupportedFiles() throws Exception {
        Path supportedFile = tempDir.resolve("java_nmt_summary_3391237.txt");
        Path unsupportedFile = tempDir.resolve("process_info.txt");

        Files.copy(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"), supportedFile);
        Files.writeString(unsupportedFile, "hostname=example\ncommand=java -jar service.jar\n");

        var discovery = loader.discoverWithInventory(tempDir);

        assertEquals(1, discovery.supportedArtifacts().size());
        assertEquals(2, discovery.inventoryEntries().size());
        assertTrue(discovery.inventoryEntries().stream().anyMatch(entry ->
            "java_nmt_summary_3391237.txt".equals(entry.displayName()) && entry.status() == ArtifactInventoryStatus.SUPPORTED
        ));
        assertTrue(discovery.inventoryEntries().stream().anyMatch(entry ->
            "process_info.txt".equals(entry.displayName()) && entry.status() == ArtifactInventoryStatus.UNSUPPORTED
        ));
    }

    @Test
    void discoversSynthesizedContainerMemoryArtifactFromRawDirectoryInventory() throws Exception {
        Path bundleDirectory = tempDir.resolve("support-bundle");
        Path containerDirectory = writeRawContainerMemoryDirectory(bundleDirectory.resolve("cgroup"));
        Path unsupportedFile = bundleDirectory.resolve("notes.txt");
        Files.writeString(unsupportedFile, "user notes");

        var discovery = loader.discoverWithInventory(bundleDirectory);

        assertEquals(1, discovery.supportedArtifacts().size());
        assertEquals(ArtifactType.CONTAINER_MEMORY, discovery.supportedArtifacts().getFirst().type());
        assertEquals(7, discovery.inventoryEntries().size());
        assertTrue(discovery.inventoryEntries().stream().filter(entry -> entry.status() == ArtifactInventoryStatus.SUPPORTED).count() >= 6);
        assertTrue(discovery.inventoryEntries().stream().anyMatch(entry ->
            "memory.current".equals(entry.displayName())
                && entry.status() == ArtifactInventoryStatus.SUPPORTED
                && entry.artifactType() == ArtifactType.CONTAINER_MEMORY
                && entry.detail().contains("synthesized container-memory analysis")
        ));
        assertTrue(discovery.inventoryEntries().stream().anyMatch(entry ->
            "notes.txt".equals(entry.displayName()) && entry.status() == ArtifactInventoryStatus.UNSUPPORTED
        ));
        assertEquals(containerDirectory.toString(), discovery.supportedArtifacts().getFirst().metadata().sourcePath());
    }

    private Path writeRawContainerMemoryDirectory(Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("memory.current"), "1040187392\n");
        Files.writeString(directory.resolve("memory.max"), "1073741824\n");
        Files.writeString(directory.resolve("memory.high"), "943718400\n");
        Files.writeString(directory.resolve("memory.events"), "low 0\nhigh 128\nmax 14\noom 2\noom_kill 1\noom_group_kill 0\n");
        Files.writeString(
            directory.resolve("memory.stat"),
            "anon 775946240\nfile 188743680\nkernel 62914560\nkernel_stack 2097152\npagetables 5242880\npercpu 1048576\n"
                + "sock 0\nshmem 0\nfile_mapped 16777216\ninactive_anon 671088640\nactive_anon 104857600\n"
                + "inactive_file 125829120\nactive_file 62914560\nslab_reclaimable 16777216\nslab_unreclaimable 8388608\nslab 25165824\n"
        );
        Files.writeString(directory.resolve("memory.pressure"), "some avg10=6.50 avg60=4.25 avg300=1.75 total=98231\nfull avg10=1.20 avg60=0.60 avg300=0.20 total=14150\n");
        return directory;
    }
}
