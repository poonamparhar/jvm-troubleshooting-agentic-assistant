package com.javaassistant.diagnostics;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical report contract that later renderers can turn into console, JSON, Markdown, or HTML.
 */
public record AnalysisReport(
    int schemaVersion,
    String analysisId,
    LocalDateTime createdAt,
    String incidentSummary,
    String userNarrative,
    List<AgentTraceability> agentTraceability,
    SupervisorTrace supervisorTrace,
    SeverityLevel overallSeverity,
    ConfidenceLevel confidence,
    List<InputArtifact> inputArtifacts,
    List<ParsedArtifact> parsedArtifacts,
    List<Evidence> evidence,
    List<Finding> findings,
    List<RecommendedAction> recommendedActions,
    List<String> missingData,
    List<String> followUpCommands,
    List<ArtifactInventoryEntry> artifactInventory,
    CorrelationResult correlationResult
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String SHAREABLE_REDACTION_PROFILE = "internal-safe-v1";

    public AnalysisReport {
        agentTraceability = agentTraceability == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(agentTraceability));
        inputArtifacts = inputArtifacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(inputArtifacts));
        parsedArtifacts = parsedArtifacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(parsedArtifacts));
        evidence = evidence == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(evidence));
        findings = findings == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(findings));
        recommendedActions = recommendedActions == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(recommendedActions));
        missingData = missingData == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(missingData));
        followUpCommands = followUpCommands == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(followUpCommands));
        artifactInventory = artifactInventory == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(artifactInventory));
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", schemaVersion);
        canonical.put("analysisId", analysisId);
        canonical.put("createdAt", createdAt != null ? createdAt.toString() : null);
        canonical.put("incidentSummary", incidentSummary);
        canonical.put("userNarrative", userNarrative);
        canonical.put("agentTraceability", agentTraceability.stream().map(AgentTraceability::toCanonicalMap).toList());
        canonical.put("supervisorTrace", supervisorTrace != null ? supervisorTrace.toCanonicalMap() : null);
        canonical.put("overallSeverity", overallSeverity != null ? overallSeverity.name() : null);
        canonical.put("confidence", confidence != null ? confidence.name() : null);
        canonical.put("inputArtifacts", inputArtifacts.stream().map(InputArtifact::toCanonicalMap).toList());
        canonical.put("parsedArtifacts", parsedArtifacts.stream().map(ParsedArtifact::toCanonicalMap).toList());
        canonical.put("evidence", evidence.stream().map(Evidence::toCanonicalMap).toList());
        canonical.put("findings", findings.stream().map(Finding::toCanonicalMap).toList());
        canonical.put("recommendedActions", recommendedActions.stream().map(RecommendedAction::toCanonicalMap).toList());
        canonical.put("missingData", missingData);
        canonical.put("followUpCommands", followUpCommands);
        canonical.put("artifactInventory", artifactInventory.stream().map(ArtifactInventoryEntry::toCanonicalMap).toList());
        canonical.put("artifactSummaries", parsedArtifacts.stream().map(this::artifactSummary).toList());
        canonical.put("reportMetadata", reportMetadata());
        canonical.put("correlationResult", correlationResult != null ? correlationResult.toCanonicalMap() : null);
        return canonical;
    }

    public AnalysisReport withArtifactInventory(List<ArtifactInventoryEntry> updatedArtifactInventory) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            incidentSummary,
            userNarrative,
            agentTraceability,
            supervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            updatedArtifactInventory,
            correlationResult
        );
    }

    public AnalysisReport withUserNarrative(String updatedUserNarrative) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            incidentSummary,
            updatedUserNarrative,
            agentTraceability,
            supervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            artifactInventory,
            correlationResult
        );
    }

    public AnalysisReport withIncidentSummary(String updatedIncidentSummary) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            updatedIncidentSummary,
            userNarrative,
            agentTraceability,
            supervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            artifactInventory,
            correlationResult
        );
    }

    public AnalysisReport withAgentTraceability(List<AgentTraceability> updatedAgentTraceability) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            incidentSummary,
            userNarrative,
            updatedAgentTraceability,
            supervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            artifactInventory,
            correlationResult
        );
    }

    public AnalysisReport withUserNarrativeAndTraceability(
        String updatedUserNarrative,
        List<AgentTraceability> updatedAgentTraceability
    ) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            incidentSummary,
            updatedUserNarrative,
            updatedAgentTraceability,
            supervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            artifactInventory,
            correlationResult
        );
    }

    public AnalysisReport withSupervisorTrace(SupervisorTrace updatedSupervisorTrace) {
        return new AnalysisReport(
            schemaVersion,
            analysisId,
            createdAt,
            incidentSummary,
            userNarrative,
            agentTraceability,
            updatedSupervisorTrace,
            overallSeverity,
            confidence,
            inputArtifacts,
            parsedArtifacts,
            evidence,
            findings,
            recommendedActions,
            missingData,
            followUpCommands,
            artifactInventory,
            correlationResult
        );
    }

    public boolean hasUserNarrative() {
        return userNarrative != null && !userNarrative.isBlank();
    }

    public AgentTraceability selectedNarrativeTraceability() {
        return agentTraceability.stream()
            .filter(AgentTraceability::selectedForUserNarrative)
            .findFirst()
            .orElse(null);
    }

    public ModelExecutionTraceability selectedNarrativeModelExecutionTraceability() {
        AgentTraceability selectedNarrative = selectedNarrativeTraceability();
        return selectedNarrative != null ? selectedNarrative.modelExecutionTraceability() : null;
    }

    public long aiAgentAttemptCount() {
        return agentTraceability.stream()
            .filter(traceability -> usesAiAgent(traceability.narrativeSource()))
            .count();
    }

    public boolean aiAgentAttempted() {
        return aiAgentAttemptCount() > 0;
    }

    public boolean aiAgentSelectedForUserNarrative() {
        AgentTraceability selectedNarrative = selectedNarrativeTraceability();
        return selectedNarrative != null && usesAiAgent(selectedNarrative.narrativeSource());
    }

    public boolean hasAiAgentBackedUserNarrative() {
        return hasUserNarrative() && aiAgentSelectedForUserNarrative();
    }

    public boolean llmNarrativeSelectedForUserNarrative() {
        AgentTraceability selectedNarrative = selectedNarrativeTraceability();
        return selectedNarrative != null && usesLlm(selectedNarrative.narrativeSource());
    }

    public String analysisPathLabel() {
        AgentTraceability selectedNarrative = selectedNarrativeTraceability();
        if (selectedNarrative == null || selectedNarrative.narrativeSource() == null) {
            return hasUserNarrative()
                ? "User narrative present; saved traceability unavailable"
                : "Deterministic findings only";
        }
        return switch (selectedNarrative.narrativeSource()) {
            case SPECIALIST_AGENT -> "Specialist AI agent selected";
            case SYNTHESIS_AGENT -> "Synthesis AI agent selected";
            case FALLBACK_SUMMARIZER -> "Fallback LLM summarizer selected";
            case DETERMINISTIC_FALLBACK -> "Deterministic fallback selected";
        };
    }

    public String aiAgentInvolvementLabel() {
        if (aiAgentSelectedForUserNarrative()) {
            return "yes, selected for the final narrative";
        }
        long aiAgentAttempts = aiAgentAttemptCount();
        if (aiAgentAttempts == 1) {
            return "attempted, but not selected for the final narrative";
        }
        if (aiAgentAttempts > 1) {
            return "attempted in %d stages, but not selected for the final narrative".formatted(aiAgentAttempts);
        }
        return "no";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> artifactSummary(ParsedArtifact artifact) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", artifact.type() != null ? artifact.type().name() : null);
        summary.put("sourcePath", artifact.metadata() != null ? artifact.metadata().sourcePath() : null);
        summary.put("parserVersion", artifact.parserVersion());

        Object snapshotKind = artifact.extractedData().get("snapshotKind");
        if (snapshotKind != null) {
            summary.put("snapshotKind", snapshotKind);
        }

        if (artifact.type() == ArtifactType.NMT && "diff".equals(snapshotKind)) {
            Map<String, Object> nmtDiffSummary = new LinkedHashMap<>();
            copyIfPresent(nmtDiffSummary, "totalDeltaKb", artifact.extractedData().get("totalDeltaKb"));
            copyIfPresent(nmtDiffSummary, "categoryDeltas", artifact.extractedData().get("categoryDeltas"));
            copyIfPresent(nmtDiffSummary, "metaspaceSummaryDeltas", artifact.extractedData().get("metaspaceSummaryDeltas"));
            copyIfPresent(nmtDiffSummary, "threadSummaryDeltas", artifact.extractedData().get("threadSummaryDeltas"));
            copyIfPresent(nmtDiffSummary, "classSummaryDeltas", artifact.extractedData().get("classSummaryDeltas"));
            if (!nmtDiffSummary.isEmpty()) {
                summary.put("nmtDiffSummary", Collections.unmodifiableMap(new LinkedHashMap<>(nmtDiffSummary)));
            }
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    private Map<String, Object> reportMetadata() {
        Map<String, Object> shareableFormats = new LinkedHashMap<>();
        shareableFormats.put("redactionProfile", SHAREABLE_REDACTION_PROFILE);
        shareableFormats.put("redactedFormats", List.of("text", "markdown", "html"));
        shareableFormats.put("fullFidelityFormats", List.of("json"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inputArtifactCount", inputArtifacts.size());
        metadata.put("parsedArtifactCount", parsedArtifacts.size());
        metadata.put("evidenceCount", evidence.size());
        metadata.put("findingCount", findings.size());
        metadata.put("agentTraceabilityCount", agentTraceability.size());
        metadata.put(
            "selectedAgentTraceabilityCount",
            agentTraceability.stream().filter(AgentTraceability::selectedForUserNarrative).count()
        );
        metadata.put(
            "agentQualityGateCount",
            agentTraceability.stream().mapToLong(traceability -> traceability.qualityGates().size()).sum()
        );
        metadata.put("agentParticipationSummary", agentParticipationSummary());
        metadata.put("hasSupervisorTrace", supervisorTrace != null);
        metadata.put("supervisorTraceStepCount", supervisorTrace != null ? supervisorTrace.steps().size() : 0);
        metadata.put("recommendedActionCount", recommendedActions.size());
        metadata.put("missingDataCount", missingData.size());
        metadata.put("followUpCommandCount", followUpCommands.size());
        metadata.put("artifactInventoryCount", artifactInventory.size());
        metadata.put("hasCorrelationResult", correlationResult != null);
        metadata.put("shareableFormats", shareableFormats);
        metadata.put("catalogSummary", catalogSummary());
        return metadata;
    }

    public Map<String, Object> catalogSummary() {
        List<String> artifactTypes = inputArtifacts.stream()
            .map(InputArtifact::type)
            .filter(Objects::nonNull)
            .map(Enum::name)
            .distinct()
            .toList();

        if (artifactTypes.isEmpty()) {
            artifactTypes = parsedArtifacts.stream()
                .map(ParsedArtifact::type)
                .filter(Objects::nonNull)
                .map(Enum::name)
                .distinct()
                .toList();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        AgentTraceability selectedNarrative = agentTraceability.stream()
            .filter(AgentTraceability::selectedForUserNarrative)
            .findFirst()
            .orElse(null);
        ModelExecutionTraceability modelExecutionTraceability = selectedNarrative != null
            ? selectedNarrative.modelExecutionTraceability()
            : null;

        summary.put("analysisId", analysisId);
        summary.put("createdAt", createdAt != null ? createdAt.toString() : null);
        summary.put("overallSeverity", overallSeverity != null ? overallSeverity.name() : null);
        summary.put("confidence", confidence != null ? confidence.name() : null);
        summary.put("hasUserNarrative", userNarrative != null && !userNarrative.isBlank());
        summary.put("artifactTypes", artifactTypes);
        summary.put("redactionProfile", SHAREABLE_REDACTION_PROFILE);
        summary.put("inputArtifactCount", inputArtifacts.size());
        summary.put("hasCorrelationResult", correlationResult != null);
        summary.put("aiAgentAttempted", aiAgentAttempted());
        summary.put("aiAgentSelectedForUserNarrative", aiAgentSelectedForUserNarrative());
        summary.put("llmNarrativeSelectedForUserNarrative", llmNarrativeSelectedForUserNarrative());
        if (supervisorTrace != null && supervisorTrace.workflowType() != null) {
            summary.put("workflowType", supervisorTrace.workflowType().name());
        }
        if (selectedNarrative != null) {
            summary.put("userNarrativeAgent", selectedNarrative.agentName());
            summary.put(
                "userNarrativeSource",
                selectedNarrative.narrativeSource() != null ? selectedNarrative.narrativeSource().name() : null
            );
            if (modelExecutionTraceability != null) {
                summary.put("userNarrativeProvider", modelExecutionTraceability.providerId());
                summary.put("userNarrativeProviderLabel", modelExecutionTraceability.providerLabel());
                summary.put("userNarrativeModel", modelExecutionTraceability.modelName());
                summary.put("userNarrativeModelFamily", modelExecutionTraceability.modelFamily());
                summary.put("userNarrativeTemplateId", modelExecutionTraceability.templateId());
                summary.put("userNarrativeTemplateVersion", modelExecutionTraceability.templateVersion());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    private void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> agentParticipationSummary() {
        AgentTraceability selectedNarrative = selectedNarrativeTraceability();
        ModelExecutionTraceability modelExecutionTraceability = selectedNarrativeModelExecutionTraceability();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("analysisPath", analysisPathLabel());
        summary.put("aiAgentAttempted", aiAgentAttempted());
        summary.put("aiAgentAttemptCount", aiAgentAttemptCount());
        summary.put("aiAgentSelectedForUserNarrative", aiAgentSelectedForUserNarrative());
        summary.put("llmNarrativeSelectedForUserNarrative", llmNarrativeSelectedForUserNarrative());
        summary.put("selectedNarrativeAgent", selectedNarrative != null ? selectedNarrative.agentName() : null);
        summary.put(
            "selectedNarrativeSource",
            selectedNarrative != null && selectedNarrative.narrativeSource() != null
                ? selectedNarrative.narrativeSource().name()
                : null
        );
        summary.put(
            "selectedNarrativeProvider",
            modelExecutionTraceability != null ? modelExecutionTraceability.providerId() : null
        );
        summary.put(
            "selectedNarrativeProviderLabel",
            modelExecutionTraceability != null ? modelExecutionTraceability.providerLabel() : null
        );
        summary.put(
            "selectedNarrativeModel",
            modelExecutionTraceability != null ? modelExecutionTraceability.modelName() : null
        );
        summary.put(
            "selectedNarrativeModelFamily",
            modelExecutionTraceability != null ? modelExecutionTraceability.modelFamily() : null
        );
        summary.put(
            "selectedNarrativeTemplateId",
            modelExecutionTraceability != null ? modelExecutionTraceability.templateId() : null
        );
        summary.put(
            "selectedNarrativeTemplateVersion",
            modelExecutionTraceability != null ? modelExecutionTraceability.templateVersion() : null
        );
        return Collections.unmodifiableMap(summary);
    }

    private boolean usesAiAgent(AgentNarrativeSource narrativeSource) {
        return narrativeSource == AgentNarrativeSource.SPECIALIST_AGENT
            || narrativeSource == AgentNarrativeSource.SYNTHESIS_AGENT;
    }

    private boolean usesLlm(AgentNarrativeSource narrativeSource) {
        return usesAiAgent(narrativeSource) || narrativeSource == AgentNarrativeSource.FALLBACK_SUMMARIZER;
    }
}
