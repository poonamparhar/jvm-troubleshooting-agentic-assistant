package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.testsupport.ReferenceIncidentBundle;
import com.javaassistant.testsupport.ReferenceIncidentBundleLoader;
import com.javaassistant.testsupport.ScenarioCatalogSupport;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosticScenarioCatalogTest {

    private static final Set<String> ALLOWED_PRIORITIES = Set.of("P0", "P1", "P2");
    private static final Set<String> ALLOWED_WORKFLOWS = Set.of("ingest", "analyze", "compare", "sequence", "correlate");
    private static final Set<String> ALLOWED_STRATEGIES = Set.of(
        "simulator_bundle",
        "captured_fixture",
        "synthetic_fixture",
        "mixed_bundle"
    );
    private static final Set<String> ALLOWED_COVERAGE = Set.of(
        "covered_reference_bundle",
        "covered_fixture_or_unit",
        "planned"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReferenceIncidentBundleLoader bundleLoader = new ReferenceIncidentBundleLoader();

    @Test
    void scenarioCatalogIsWellFormedAndCoversTheSupportedArtifactSurface() throws Exception {
        JsonNode catalog = loadCatalog();
        assertEquals(1, catalog.path("schemaVersion").asInt(), "scenario catalog schemaVersion changed");

        JsonNode strategies = catalog.path("strategies");
        assertTrue(strategies.isArray(), "strategies must be an array");
        assertEquals(ALLOWED_STRATEGIES.size(), strategies.size(), "strategy list should stay aligned with the validation rules");

        JsonNode groups = catalog.path("scenarioGroups");
        assertTrue(groups.isArray(), "scenarioGroups must be an array");
        assertFalse(groups.isEmpty(), "scenarioGroups must not be empty");

        Set<String> strategyIds = new HashSet<>();
        for (JsonNode strategy : strategies) {
            String strategyId = requiredText(strategy, "id");
            assertTrue(ALLOWED_STRATEGIES.contains(strategyId), "unexpected strategy id: " + strategyId);
            assertTrue(strategyIds.add(strategyId), "duplicate strategy id: " + strategyId);
            assertFalse(requiredText(strategy, "summary").isBlank(), "strategy summary must not be blank");
        }

        Set<String> groupIds = new HashSet<>();
        Set<String> scenarioIds = new HashSet<>();
        Set<String> workflowsSeen = new HashSet<>();
        Set<String> strategiesSeen = new HashSet<>();
        Set<String> coverageStatesSeen = new HashSet<>();
        Set<ArtifactType> artifactTypesSeen = EnumSet.noneOf(ArtifactType.class);
        int scenarioCount = 0;

        for (JsonNode group : groups) {
            String groupId = requiredText(group, "groupId");
            assertTrue(groupIds.add(groupId), "duplicate scenario group id: " + groupId);
            assertFalse(requiredText(group, "title").isBlank(), "scenario group title must not be blank");

            JsonNode scenarios = group.path("scenarios");
            assertTrue(scenarios.isArray(), "scenarios must be an array for group " + groupId);
            assertFalse(scenarios.isEmpty(), "scenario group must contain at least one scenario: " + groupId);

            for (JsonNode scenario : scenarios) {
                scenarioCount++;

                String scenarioId = requiredText(scenario, "id");
                assertTrue(scenarioIds.add(scenarioId), "duplicate scenario id: " + scenarioId);
                assertFalse(requiredText(scenario, "title").isBlank(), "scenario title must not be blank for " + scenarioId);

                String priority = requiredText(scenario, "priority");
                assertTrue(ALLOWED_PRIORITIES.contains(priority), "unexpected priority for " + scenarioId + ": " + priority);

                String strategy = requiredText(scenario, "corpusStrategy");
                assertTrue(ALLOWED_STRATEGIES.contains(strategy), "unexpected strategy for " + scenarioId + ": " + strategy);
                strategiesSeen.add(strategy);

                String coverageStatus = requiredText(scenario, "coverageStatus");
                assertTrue(ALLOWED_COVERAGE.contains(coverageStatus), "unexpected coverageStatus for " + scenarioId + ": " + coverageStatus);
                coverageStatesSeen.add(coverageStatus);

                JsonNode artifactTypes = scenario.path("artifactTypes");
                assertTrue(artifactTypes.isArray(), "artifactTypes must be an array for " + scenarioId);
                assertFalse(artifactTypes.isEmpty(), "artifactTypes must not be empty for " + scenarioId);
                for (JsonNode artifactTypeNode : artifactTypes) {
                    ArtifactType artifactType = ArtifactType.valueOf(artifactTypeNode.asText());
                    artifactTypesSeen.add(artifactType);
                }

                JsonNode workflows = scenario.path("workflows");
                assertTrue(workflows.isArray(), "workflows must be an array for " + scenarioId);
                assertFalse(workflows.isEmpty(), "workflows must not be empty for " + scenarioId);
                for (JsonNode workflowNode : workflows) {
                    String workflow = workflowNode.asText();
                    assertTrue(ALLOWED_WORKFLOWS.contains(workflow), "unexpected workflow for " + scenarioId + ": " + workflow);
                    workflowsSeen.add(workflow);
                }

                JsonNode coverageRefs = scenario.path("coverageRefs");
                if (!"planned".equals(coverageStatus)) {
                    assertTrue(coverageRefs.isArray(), "coverageRefs must be present for covered scenario " + scenarioId);
                    assertFalse(coverageRefs.isEmpty(), "coverageRefs must not be empty for covered scenario " + scenarioId);
                    for (JsonNode coverageRef : coverageRefs) {
                        Path path = Path.of(coverageRef.asText());
                        assertTrue(Files.exists(path), "coverage reference does not exist for " + scenarioId + ": " + path);
                    }
                } else if (!coverageRefs.isMissingNode()) {
                    assertTrue(coverageRefs.isArray(), "optional coverageRefs must stay array-shaped for planned scenario " + scenarioId);
                }
            }
        }

        assertTrue(scenarioCount >= 80, "scenario catalog should stay comprehensive");
        assertEquals(ALLOWED_WORKFLOWS, workflowsSeen, "workflow coverage drifted");
        assertEquals(ALLOWED_STRATEGIES, strategiesSeen, "strategy coverage drifted");
        assertTrue(coverageStatesSeen.contains("covered_reference_bundle"), "reference-bundle coverage drifted out of the catalog");
        assertTrue(coverageStatesSeen.contains("covered_fixture_or_unit"), "fixture-or-unit coverage drifted out of the catalog");

        EnumSet<ArtifactType> expectedSupportedArtifacts = EnumSet.allOf(ArtifactType.class);
        expectedSupportedArtifacts.remove(ArtifactType.UNKNOWN);
        assertTrue(
            artifactTypesSeen.containsAll(expectedSupportedArtifacts),
            "catalog must cover every supported artifact type. Missing: " + missingArtifactTypes(expectedSupportedArtifacts, artifactTypesSeen)
        );
    }

    @Test
    void coveredReferenceBundlesStayLinkedToRunnableManifests() throws Exception {
        ScenarioCatalogSupport.ScenarioCatalog catalog = ScenarioCatalogSupport.loadDefaultCatalog();
        assertFalse(catalog.coveredReferenceManifestPaths().isEmpty(), "covered reference manifest set must not be empty");

        for (String scenarioId : catalog.coveredReferenceScenarioIds()) {
            ScenarioCatalogSupport.ScenarioCatalogEntry entry = catalog.entriesById().get(scenarioId);
            assertNotNull(entry, "catalog entry missing for scenario " + scenarioId);
            assertTrue(
                entry.coverageRefs().stream().anyMatch(ref -> ref.endsWith("manifest.properties")),
                "covered reference scenario must include at least one manifest reference: " + scenarioId
            );
        }

        for (String manifestPathText : catalog.coveredReferenceManifestPaths()) {
            Path manifestPath = Path.of(manifestPathText);
            assertTrue(Files.exists(manifestPath), "catalog manifest reference is missing: " + manifestPath);

            ReferenceIncidentBundle bundle = bundleLoader.loadBundle(manifestPath.getParent());
            assertEquals(manifestPath.normalize(), bundle.manifestPath().normalize(), "manifest path drifted for " + manifestPath);
            assertFalse(bundle.scenarioIds().isEmpty(), "catalog-linked bundle must expose at least one scenario id: " + manifestPath);
            assertEquals(
                catalog.scenarioIdsForManifest(manifestPath),
                bundle.scenarioIds(),
                "bundle-to-catalog scenario mapping drifted for " + manifestPath
            );
        }
    }

    private JsonNode loadCatalog() throws Exception {
        try (InputStream inputStream = DiagnosticScenarioCatalogTest.class.getResourceAsStream("/scenario-catalog/diagnostic-scenario-catalog.json")) {
            assertNotNull(inputStream, "scenario catalog resource is missing");
            return objectMapper.readTree(inputStream);
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        assertFalse(field.isMissingNode(), "missing required field " + fieldName);
        String value = field.asText();
        assertFalse(value.isBlank(), "blank required field " + fieldName);
        return value;
    }

    private Set<ArtifactType> missingArtifactTypes(Set<ArtifactType> expected, Set<ArtifactType> actual) {
        EnumSet<ArtifactType> missing = EnumSet.copyOf(expected);
        missing.removeAll(actual);
        return missing;
    }
}
