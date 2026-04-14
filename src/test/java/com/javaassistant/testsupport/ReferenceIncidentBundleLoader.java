package com.javaassistant.testsupport;

import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

public final class ReferenceIncidentBundleLoader {

    public static final Path DEFAULT_ROOT = Path.of("src/test/resources/reference-incidents");

    private final Path rootDirectory;
    private final ScenarioCatalogSupport.ScenarioCatalog scenarioCatalog;

    public ReferenceIncidentBundleLoader() {
        this(DEFAULT_ROOT, ScenarioCatalogSupport.loadDefaultCatalog());
    }

    public ReferenceIncidentBundleLoader(Path rootDirectory, ScenarioCatalogSupport.ScenarioCatalog scenarioCatalog) {
        this.rootDirectory = rootDirectory;
        this.scenarioCatalog = scenarioCatalog;
    }

    public List<ReferenceIncidentBundle> loadBundles() throws IOException {
        try (Stream<Path> paths = Files.list(rootDirectory)) {
            return paths
                .filter(Files::isDirectory)
                .sorted()
                .map(this::loadBundle)
                .toList();
        }
    }

    public ReferenceIncidentBundle loadBundle(Path bundleDirectory) {
        Properties properties = new Properties();
        Path manifestPath = bundleDirectory.resolve("manifest.properties");
        try (InputStream inputStream = Files.newInputStream(manifestPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load reference incident manifest: " + manifestPath, exception);
        }

        return new ReferenceIncidentBundle(
            bundleDirectory.getFileName().toString(),
            manifestPath,
            OrchestrationWorkflowType.valueOf(required(properties, "workflowType")),
            split(properties.getProperty("artifactPaths")),
            bool(properties.getProperty("expectUserNarrative"), true),
            required(properties, "expectedSelectedAgent"),
            AgentNarrativeSource.valueOf(required(properties, "expectedSelectedSource")),
            split(properties.getProperty("requiredTraceabilityAgents")),
            split(properties.getProperty("requiredTraceAgents")),
            split(properties.getProperty("requiredStepIds")),
            split(properties.getProperty("expectedFindingIds")),
            split(properties.getProperty("requiredStepTypes")).stream()
                .map(SupervisorTraceStepType::valueOf)
                .toList(),
            integer(properties.getProperty("minFindings"), 1),
            integer(properties.getProperty("minSupervisorTraceSteps"), 1),
            enumValue(properties.getProperty("stubMode"), StubMode.NON_TOOLING, StubMode.class),
            split(properties.getProperty("requiredSelectedToolNames")),
            integer(properties.getProperty("minSelectedToolInvocations"), 0),
            scenarioCatalog.scenarioIdsForManifest(manifestPath)
        );
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required reference incident property: " + key);
        }
        return value.strip();
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private int integer(String value, int defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.strip());
    }

    private boolean bool(String value, boolean defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.strip());
    }

    private <E extends Enum<E>> E enumValue(String value, E defaultValue, Class<E> enumType) {
        return value == null || value.isBlank() ? defaultValue : Enum.valueOf(enumType, value.strip());
    }
}
