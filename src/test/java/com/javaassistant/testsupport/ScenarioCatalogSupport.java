package com.javaassistant.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScenarioCatalogSupport {

    public static final Path DEFAULT_CATALOG_PATH = Path.of("src/test/resources/scenario-catalog/diagnostic-scenario-catalog.json");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ScenarioCatalogSupport() {
    }

    public static ScenarioCatalog loadDefaultCatalog() {
        return load(DEFAULT_CATALOG_PATH);
    }

    public static ScenarioCatalog load(Path catalogPath) {
        try (InputStream inputStream = Files.newInputStream(catalogPath)) {
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            LinkedHashMap<String, ScenarioCatalogEntry> entriesById = new LinkedHashMap<>();
            LinkedHashMap<String, List<String>> scenarioIdsByManifestPath = new LinkedHashMap<>();
            LinkedHashSet<String> coveredReferenceManifestPaths = new LinkedHashSet<>();
            LinkedHashSet<String> coveredReferenceScenarioIds = new LinkedHashSet<>();

            for (JsonNode groupNode : root.path("scenarioGroups")) {
                String groupId = text(groupNode, "groupId");
                for (JsonNode scenarioNode : groupNode.path("scenarios")) {
                    String scenarioId = text(scenarioNode, "id");
                    String coverageStatus = text(scenarioNode, "coverageStatus");
                    List<String> coverageRefs = new ArrayList<>();
                    for (JsonNode coverageRefNode : scenarioNode.path("coverageRefs")) {
                        String normalized = normalizePath(coverageRefNode.asText());
                        coverageRefs.add(normalized);
                        if ("covered_reference_bundle".equals(coverageStatus) && normalized.endsWith("manifest.properties")) {
                            scenarioIdsByManifestPath.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(scenarioId);
                            coveredReferenceManifestPaths.add(normalized);
                            coveredReferenceScenarioIds.add(scenarioId);
                        }
                    }
                    entriesById.put(
                        scenarioId,
                        new ScenarioCatalogEntry(
                            scenarioId,
                            groupId,
                            text(scenarioNode, "title"),
                            coverageStatus,
                            List.copyOf(coverageRefs)
                        )
                    );
                }
            }

            return new ScenarioCatalog(
                Map.copyOf(entriesById),
                immutableListMap(scenarioIdsByManifestPath),
                Set.copyOf(coveredReferenceManifestPaths),
                Set.copyOf(coveredReferenceScenarioIds)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load diagnostic scenario catalog from " + catalogPath, exception);
        }
    }

    private static Map<String, List<String>> immutableListMap(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() ? "" : field.asText("").strip();
    }

    private static String normalizePath(String pathText) {
        return Path.of(pathText).normalize().toString();
    }

    public record ScenarioCatalog(
        Map<String, ScenarioCatalogEntry> entriesById,
        Map<String, List<String>> scenarioIdsByManifestPath,
        Set<String> coveredReferenceManifestPaths,
        Set<String> coveredReferenceScenarioIds
    ) {

        public List<String> scenarioIdsForManifest(Path manifestPath) {
            return scenarioIdsByManifestPath.getOrDefault(manifestPath.normalize().toString(), List.of());
        }
    }

    public record ScenarioCatalogEntry(
        String scenarioId,
        String groupId,
        String title,
        String coverageStatus,
        List<String> coverageRefs
    ) {
    }
}
