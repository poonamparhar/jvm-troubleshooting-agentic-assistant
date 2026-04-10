package com.javaassistant.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeneratedScenarioRegistry {

    private final List<ScenarioLab> labs;
    private final Map<String, Map<String, Path>> generatedArtifactsByScenario = new LinkedHashMap<>();

    public GeneratedScenarioRegistry(List<ScenarioLab> labs) {
        this.labs = List.copyOf(labs);
        validateUniqueScenarioOwnership(this.labs);
    }

    public static GeneratedScenarioRegistry defaultRegistry() {
        return new GeneratedScenarioRegistry(List.of(
            new JfrScenarioLab(),
            new GcScenarioLab(),
            new CorrelationScenarioLab(),
            new ThreadingScenarioLab(),
            new NativeMemoryScenarioLab(),
            new ControlAndUncertaintyScenarioLab()
        ));
    }

    public Path resolveArtifactPath(String artifactRef) throws Exception {
        if (artifactRef == null || artifactRef.isBlank()) {
            throw new IllegalStateException("Generated scenario artifact reference must not be blank.");
        }
        if (artifactRef.startsWith("generated-jfr:")) {
            String scenarioId = artifactRef.substring("generated-jfr:".length()).strip();
            return requiredArtifact(scenarioId, "jfr", artifactRef);
        }
        if (artifactRef.startsWith("generated-correlation:")) {
            String remainder = artifactRef.substring("generated-correlation:".length()).strip();
            String[] parts = remainder.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("Unsupported generated correlation artifact reference: " + artifactRef);
            }
            return requiredArtifact(parts[0].strip(), parts[1].strip(), artifactRef);
        }
        if (artifactRef.startsWith("generated-scenario:")) {
            String remainder = artifactRef.substring("generated-scenario:".length()).strip();
            String[] parts = remainder.split(":", 2);
            if (parts.length == 0 || parts[0].isBlank()) {
                throw new IllegalStateException("Unsupported generated scenario artifact reference: " + artifactRef);
            }
            String scenarioId = parts[0].strip();
            String artifactKey = parts.length == 2 && !parts[1].isBlank() ? parts[1].strip() : "primary";
            return requiredArtifact(scenarioId, artifactKey, artifactRef);
        }
        return Path.of(artifactRef);
    }

    public Map<String, Path> generatedArtifacts(String scenarioId) throws Exception {
        Map<String, Path> cached = generatedArtifactsByScenario.get(scenarioId);
        if (cached != null) {
            return cached;
        }

        ScenarioLab lab = labs.stream()
            .filter(candidate -> candidate.supports(scenarioId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unsupported generated scenario: " + scenarioId));

        Path tempDirectory = Files.createTempDirectory("scenario-lab-" + sanitizeScenarioId(scenarioId));
        tempDirectory.toFile().deleteOnExit();
        Map<String, Path> generated = Map.copyOf(lab.generate(scenarioId, tempDirectory));
        generatedArtifactsByScenario.put(scenarioId, generated);
        return generated;
    }

    private Path requiredArtifact(String scenarioId, String artifactKey, String artifactRef) throws Exception {
        Map<String, Path> generated = generatedArtifacts(scenarioId);
        Path artifactPath = generated.get(artifactKey);
        if (artifactPath == null) {
            throw new IllegalStateException(
                "Unsupported generated artifact key " + artifactKey + " for " + scenarioId + " via " + artifactRef
                    + ". Available keys: " + generated.keySet()
            );
        }
        return artifactPath;
    }

    private String sanitizeScenarioId(String scenarioId) {
        return scenarioId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void validateUniqueScenarioOwnership(List<ScenarioLab> labs) {
        LinkedHashMap<String, String> ownersByScenarioId = new LinkedHashMap<>();
        for (ScenarioLab lab : labs) {
            for (String scenarioId : lab.supportedScenarioIds()) {
                if (scenarioId == null || scenarioId.isBlank()) {
                    throw new IllegalArgumentException("ScenarioLab " + lab.getClass().getSimpleName() + " declared a blank scenario id.");
                }
                String previousOwner = ownersByScenarioId.putIfAbsent(scenarioId, lab.getClass().getSimpleName());
                if (previousOwner != null) {
                    throw new IllegalArgumentException(
                        "Scenario id " + scenarioId + " is declared by both " + previousOwner
                            + " and " + lab.getClass().getSimpleName() + "."
                    );
                }
            }
        }
    }
}
