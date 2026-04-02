package com.javaassistant.ingest;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads files from disk and classifies them into canonical input artifacts.
 */
public class ArtifactLoader {

    private static final String JFR_PLACEHOLDER_CONTENT =
        "Binary JFR recording analyzed from metadata.sourcePath. Raw recording bytes are not embedded in content.";
    private static final List<String> CONTAINER_MEMORY_COMPONENT_FILE_ORDER = List.of(
        "memory.current",
        "memory.max",
        "memory.high",
        "memory.events",
        "memory.stat",
        "memory.pressure"
    );
    private static final Set<String> CONTAINER_MEMORY_COMPONENT_FILES = Set.copyOf(CONTAINER_MEMORY_COMPONENT_FILE_ORDER);

    private final ArtifactClassifier classifier;

    public ArtifactLoader(ArtifactClassifier classifier) {
        this.classifier = classifier;
    }

    public InputArtifact load(Path path) throws IOException {
        return load(path, null);
    }

    public InputArtifact load(Path path, ArtifactType overrideType) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            InputArtifact synthesizedArtifact = loadSyntheticDirectoryArtifact(path, overrideType);
            if (synthesizedArtifact != null) {
                return synthesizedArtifact;
            }
            throw new IOException("Expected a file but found a directory: " + path);
        }

        String displayName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        boolean jfrSource = overrideType == ArtifactType.JFR || displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".jfr");
        String content = jfrSource ? JFR_PLACEHOLDER_CONTENT : Files.readString(path);
        ArtifactType artifactType = overrideType != null ? overrideType : classifier.classify(content, displayName);
        long contentLength = jfrSource ? Files.size(path) : content.length();
        Map<String, String> attributes = jfrSource
            ? Map.of("contentRepresentation", "external-binary-jfr")
            : Map.of();

        ArtifactMetadata metadata = new ArtifactMetadata(
            path.toString(),
            displayName,
            contentLength,
            LocalDateTime.now(),
            attributes
        );

        return new InputArtifact(artifactType, metadata, content);
    }

    public List<InputArtifact> discover(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Path not found: " + path);
        }

        if (Files.isRegularFile(path)) {
            return List.of(load(path));
        }

        return discoverWithInventory(path).supportedArtifacts();
    }

    public ArtifactDiscoveryResult discoverWithInventory(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Path not found: " + path);
        }

        if (Files.isRegularFile(path)) {
            return discoverFiles(List.of(path));
        }

        try (Stream<Path> stream = Files.walk(path)) {
            return discoverFiles(
                stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()
            );
        }
    }

    private ArtifactDiscoveryResult discoverFiles(List<Path> paths) {
        List<InputArtifact> supportedArtifacts = new ArrayList<>();
        List<ArtifactInventoryEntry> inventoryEntries = new ArrayList<>();
        Map<Path, ContainerMemoryDirectoryGroup> containerGroups = discoverContainerMemoryGroups(paths);
        Set<Path> emittedContainerGroupDirectories = new LinkedHashSet<>();

        for (Path path : paths) {
            ContainerMemoryDirectoryGroup containerGroup = containerGroups.get(path.getParent());
            if (containerGroup != null && containerGroup.componentPaths().contains(path)) {
                if (emittedContainerGroupDirectories.add(containerGroup.directory())) {
                    try {
                        supportedArtifacts.add(synthesizeContainerMemoryArtifact(containerGroup));
                        inventoryEntries.addAll(toSynthesizedInventoryEntries(containerGroup));
                    } catch (IOException e) {
                        inventoryEntries.addAll(toUnsupportedInventoryEntries(
                            containerGroup,
                            "Failed to synthesize container-memory artifact: " + e.getMessage()
                        ));
                    }
                }
                continue;
            }

            try {
                InputArtifact artifact = load(path);
                if (artifact.type() == ArtifactType.UNKNOWN) {
                    inventoryEntries.add(toUnsupportedInventoryEntry(path, "No supported artifact signature detected."));
                    continue;
                }

                supportedArtifacts.add(artifact);
                inventoryEntries.add(toSupportedInventoryEntry(artifact));
            } catch (IOException e) {
                inventoryEntries.add(toUnsupportedInventoryEntry(path, "Failed to read file: " + e.getMessage()));
            }
        }

        return new ArtifactDiscoveryResult(supportedArtifacts, inventoryEntries);
    }

    private InputArtifact loadSyntheticDirectoryArtifact(Path path, ArtifactType overrideType) throws IOException {
        ContainerMemoryDirectoryGroup containerGroup = discoverContainerMemoryGroup(path);
        if (containerGroup == null) {
            return null;
        }
        if (overrideType != null && overrideType != ArtifactType.CONTAINER_MEMORY) {
            throw new IOException("Directory loading is only supported for synthesized container-memory artifacts: " + path);
        }
        return synthesizeContainerMemoryArtifact(containerGroup);
    }

    private Map<Path, ContainerMemoryDirectoryGroup> discoverContainerMemoryGroups(List<Path> paths) {
        LinkedHashMap<Path, LinkedHashMap<String, Path>> groupedComponentFiles = new LinkedHashMap<>();

        for (Path path : paths) {
            String fileName = fileName(path);
            if (!CONTAINER_MEMORY_COMPONENT_FILES.contains(fileName)) {
                continue;
            }
            Path parent = path.getParent();
            if (parent == null) {
                continue;
            }
            groupedComponentFiles
                .computeIfAbsent(parent, ignored -> new LinkedHashMap<>())
                .put(fileName, path);
        }

        LinkedHashMap<Path, ContainerMemoryDirectoryGroup> groups = new LinkedHashMap<>();
        for (Map.Entry<Path, LinkedHashMap<String, Path>> entry : groupedComponentFiles.entrySet()) {
            if (!qualifiesAsContainerMemoryGroup(entry.getValue())) {
                continue;
            }
            groups.put(entry.getKey(), new ContainerMemoryDirectoryGroup(entry.getKey(), orderedComponentPaths(entry.getValue())));
        }
        return groups;
    }

    private ContainerMemoryDirectoryGroup discoverContainerMemoryGroup(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }

        LinkedHashMap<String, Path> filesByName = new LinkedHashMap<>();
        for (String fileName : CONTAINER_MEMORY_COMPONENT_FILE_ORDER) {
            Path candidate = directory.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                filesByName.put(fileName, candidate);
            }
        }

        if (!qualifiesAsContainerMemoryGroup(filesByName)) {
            return null;
        }
        return new ContainerMemoryDirectoryGroup(directory, orderedComponentPaths(filesByName));
    }

    private boolean qualifiesAsContainerMemoryGroup(Map<String, Path> filesByName) {
        return filesByName.containsKey("memory.current")
            && filesByName.containsKey("memory.events")
            && (
                filesByName.containsKey("memory.stat")
                    || filesByName.containsKey("memory.pressure")
                    || filesByName.containsKey("memory.max")
                    || filesByName.containsKey("memory.high")
            );
    }

    private List<Path> orderedComponentPaths(Map<String, Path> filesByName) {
        List<Path> orderedPaths = new ArrayList<>();
        for (String fileName : CONTAINER_MEMORY_COMPONENT_FILE_ORDER) {
            Path path = filesByName.get(fileName);
            if (path != null) {
                orderedPaths.add(path);
            }
        }
        return List.copyOf(orderedPaths);
    }

    private InputArtifact synthesizeContainerMemoryArtifact(ContainerMemoryDirectoryGroup group) throws IOException {
        StringBuilder content = new StringBuilder();
        List<String> componentFileNames = new ArrayList<>();

        for (Path componentPath : group.componentPaths()) {
            String fileName = fileName(componentPath);
            componentFileNames.add(fileName);
            content.append('[').append(fileName).append(']').append('\n');
            content.append(Files.readString(componentPath).stripTrailing());
            content.append('\n').append('\n');
        }

        String synthesizedContent = content.toString().stripTrailing();
        String displayName = fileName(group.directory()) + "-container-memory.snapshot";
        Map<String, String> attributes = Map.of(
            "contentRepresentation", "synthetic-container-memory-directory",
            "sourceKind", "directory-synthesis",
            "componentFiles", String.join(",", componentFileNames)
        );

        ArtifactMetadata metadata = new ArtifactMetadata(
            group.directory().toString(),
            displayName,
            synthesizedContent.length(),
            LocalDateTime.now(),
            attributes
        );

        return new InputArtifact(
            classifier.classify(synthesizedContent, displayName),
            metadata,
            synthesizedContent
        );
    }

    private List<ArtifactInventoryEntry> toSynthesizedInventoryEntries(ContainerMemoryDirectoryGroup group) {
        List<ArtifactInventoryEntry> entries = new ArrayList<>();
        String detail = "Included in synthesized container-memory analysis from raw cgroup files in "
            + fileName(group.directory())
            + ".";
        for (Path componentPath : group.componentPaths()) {
            entries.add(new ArtifactInventoryEntry(
                componentPath.toString(),
                fileName(componentPath),
                ArtifactType.CONTAINER_MEMORY,
                ArtifactInventoryStatus.SUPPORTED,
                detail
            ));
        }
        return List.copyOf(entries);
    }

    private List<ArtifactInventoryEntry> toUnsupportedInventoryEntries(ContainerMemoryDirectoryGroup group, String detail) {
        List<ArtifactInventoryEntry> entries = new ArrayList<>();
        for (Path componentPath : group.componentPaths()) {
            entries.add(toUnsupportedInventoryEntry(componentPath, detail));
        }
        return List.copyOf(entries);
    }

    private ArtifactInventoryEntry toSupportedInventoryEntry(InputArtifact artifact) {
        ArtifactMetadata metadata = artifact.metadata();
        return new ArtifactInventoryEntry(
            metadata != null ? metadata.sourcePath() : null,
            metadata != null ? metadata.displayName() : null,
            artifact.type(),
            ArtifactInventoryStatus.SUPPORTED,
            "Included in structured analysis."
        );
    }

    private ArtifactInventoryEntry toUnsupportedInventoryEntry(Path path, String detail) {
        String displayName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return new ArtifactInventoryEntry(
            path.toString(),
            displayName,
            ArtifactType.UNKNOWN,
            ArtifactInventoryStatus.UNSUPPORTED,
            detail
        );
    }

    private String fileName(Path path) {
        return path.getFileName() != null ? path.getFileName().toString() : path.toString();
    }

    private record ContainerMemoryDirectoryGroup(Path directory, List<Path> componentPaths) {
    }
}
