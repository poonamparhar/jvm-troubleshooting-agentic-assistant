package com.javaassistant.orchestration;

import com.javaassistant.context.ContextCoverage;
import com.javaassistant.diagnostics.AgentToolInvocation;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies lightweight trust checks to agent narratives before they are treated as user-facing output.
 */
public class AgentQualityGateEvaluator {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^a-z0-9]+");
    private static final Pattern MARKDOWN_HEADING_PREFIX = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern LIST_PREFIX = Pattern.compile("^[-*+]\\s+");
    private static final Pattern EMPHASIZED_HEADING = Pattern.compile("^(\\*\\*|__|\\*|_)(.+?:)\\1\\s*(.*)$");
    private static final List<InternalPhrasePattern> INTERNAL_USER_LANGUAGE_PATTERNS = List.of(
        new InternalPhrasePattern("packet", Pattern.compile("\\b(?:the|this|that|provided|analysis|diagnostic)\\s+packet\\b")),
        new InternalPhrasePattern("packet", Pattern.compile("\\bpacket\\s+(?:contains|shows|suggests|indicates|represents|includes|is|was|carries|proves|confirms|points)\\b")),
        new InternalPhrasePattern("packet", Pattern.compile("\\b(?:from|in|within|inside)\\s+the\\s+packet\\b")),
        new InternalPhrasePattern("evidence anchors", Pattern.compile("\\bevidence\\s+anchors?\\b")),
        new InternalPhrasePattern("agent traceability", Pattern.compile("\\bagent\\s+traceability\\b")),
        new InternalPhrasePattern("supervisor trace", Pattern.compile("\\bsupervisor\\s+trace\\b")),
        new InternalPhrasePattern("artifact grounding", Pattern.compile("\\bartifact\\s+grounding\\b"))
    );
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is",
        "it", "of", "on", "or", "that", "the", "this", "to", "with"
    );
    private static final Set<String> MISSING_DATA_TERMS = Set.of(
        "missing", "need", "needs", "additional", "uncertain", "uncertainty",
        "unknown", "limited", "insufficient"
    );
    private static final Set<String> UNCERTAINTY_TERMS = Set.of(
        "may", "might", "likely", "appears", "suggests", "possible",
        "possibly", "probably", "uncertain", "unknown", "limited"
    );
    private static final List<Pattern> HIGH_CERTAINTY_PATTERNS = List.of(
        Pattern.compile("\\bdefinitely\\b"),
        Pattern.compile("\\bcertainly\\b"),
        Pattern.compile("\\bclearly\\b"),
        Pattern.compile("\\bconfirmed\\b"),
        Pattern.compile("\\bproves?\\b"),
        Pattern.compile("\\bno\\s+doubt\\b"),
        Pattern.compile("\\broot\\s+cause\\s+is\\b")
    );
    private static final List<String> REQUIRED_TROUBLESHOOTING_SECTIONS = List.of(
        "Summary",
        "Key metrics",
        "Likely issues",
        "Recommended actions"
    );

    public List<AgentQualityGateResult> evaluate(
        String narrative,
        List<String> deterministicSignals,
        List<String> evidenceIds,
        List<String> missingData,
        List<ContextCoverage> coverageMetadata,
        List<AgentToolInvocation> toolInvocations,
        ModelExecutionTraceability modelExecutionTraceability
    ) {
        return evaluate(
            narrative,
            deterministicSignals,
            evidenceIds,
            missingData,
            coverageMetadata,
            toolInvocations,
            modelExecutionTraceability,
            null
        );
    }

    public List<AgentQualityGateResult> evaluate(
        String narrative,
        List<String> deterministicSignals,
        List<String> evidenceIds,
        List<String> missingData,
        List<ContextCoverage> coverageMetadata,
        List<AgentToolInvocation> toolInvocations,
        ModelExecutionTraceability modelExecutionTraceability,
        String invocationFailureDetail
    ) {
        AgentQualityGateResult responseGate = narrative == null || narrative.isBlank()
            ? new AgentQualityGateResult(
                "response-not-empty",
                AgentQualityGateStatus.FAILED,
                responseFailureDetail(invocationFailureDetail)
            )
            : new AgentQualityGateResult(
                "response-not-empty",
                AgentQualityGateStatus.PASSED,
                "The agent returned narrative text."
            );

        AgentQualityGateResult modelExecutionGate = modelExecutionTraceabilityGate(modelExecutionTraceability);
        AgentQualityGateResult signalGate = deterministicSignalGate(narrative, deterministicSignals);
        AgentQualityGateResult evidenceGate = evidenceAvailabilityGate(evidenceIds);
        AgentQualityGateResult missingDataGate = missingDataAwarenessGate(narrative, missingData);
        AgentQualityGateResult userLanguageGate = userLanguageGate(narrative);
        AgentQualityGateResult troubleshootingStructureGate = troubleshootingStructureGate(narrative);
        AgentQualityGateResult coverageAwareConfidenceGate = coverageAwareConfidenceGate(
            narrative,
            coverageMetadata,
            toolInvocations
        );

        return List.of(
            responseGate,
            modelExecutionGate,
            signalGate,
            evidenceGate,
            missingDataGate,
            userLanguageGate,
            troubleshootingStructureGate,
            coverageAwareConfidenceGate
        );
    }

    public boolean passesBlockingGates(List<AgentQualityGateResult> qualityGates) {
        return qualityGates.stream().noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED);
    }

    private String responseFailureDetail(String invocationFailureDetail) {
        if (invocationFailureDetail == null || invocationFailureDetail.isBlank()) {
            return "The narrative was empty, so the user-facing report should fall back.";
        }
        return "The agent call failed before returning a response: " + invocationFailureDetail.strip();
    }

    private AgentQualityGateResult modelExecutionTraceabilityGate(ModelExecutionTraceability modelExecutionTraceability) {
        if (modelExecutionTraceability == null) {
            return new AgentQualityGateResult(
                "model-execution-traceability",
                AgentQualityGateStatus.FAILED,
                "The AI narrative was missing provider, model, and template traceability."
            );
        }

        if (modelExecutionTraceability.isComplete()) {
            return new AgentQualityGateResult(
                "model-execution-traceability",
                AgentQualityGateStatus.PASSED,
                "The AI narrative carried provider, model, and template traceability."
            );
        }

        return new AgentQualityGateResult(
            "model-execution-traceability",
            AgentQualityGateStatus.FAILED,
            "The AI narrative had incomplete provider, model, or template traceability."
        );
    }

    private AgentQualityGateResult deterministicSignalGate(String narrative, List<String> deterministicSignals) {
        List<String> signals = deterministicSignals == null ? List.of() : deterministicSignals.stream()
            .filter(signal -> signal != null && !signal.isBlank())
            .distinct()
            .toList();

        if (signals.isEmpty()) {
            return new AgentQualityGateResult(
                "deterministic-signal-overlap",
                AgentQualityGateStatus.NOT_APPLICABLE,
                "No deterministic signal phrases were available for overlap checking."
            );
        }

        if (narrative == null || narrative.isBlank()) {
            return new AgentQualityGateResult(
                "deterministic-signal-overlap",
                AgentQualityGateStatus.WARNING,
                "Signal overlap could not be checked because the narrative was empty."
            );
        }

        String matchingSignal = matchingSignal(narrative, signals);
        if (matchingSignal != null) {
            return new AgentQualityGateResult(
                "deterministic-signal-overlap",
                AgentQualityGateStatus.PASSED,
                "The narrative overlaps with deterministic signal \"" + matchingSignal + "\"."
            );
        }

        return new AgentQualityGateResult(
            "deterministic-signal-overlap",
            AgentQualityGateStatus.WARNING,
            "The narrative did not obviously reuse deterministic finding or evidence phrases."
        );
    }

    private AgentQualityGateResult evidenceAvailabilityGate(List<String> evidenceIds) {
        List<String> ids = evidenceIds == null ? List.of() : evidenceIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();

        if (ids.isEmpty()) {
            return new AgentQualityGateResult(
                "evidence-anchor-availability",
                AgentQualityGateStatus.WARNING,
                "No evidence IDs were attached to this narrative path."
            );
        }

        return new AgentQualityGateResult(
            "evidence-anchor-availability",
            AgentQualityGateStatus.PASSED,
            "The narrative path carried " + ids.size() + " evidence anchor(s)."
        );
    }

    private AgentQualityGateResult missingDataAwarenessGate(String narrative, List<String> missingData) {
        List<String> missingItems = missingData == null ? List.of() : missingData.stream()
            .filter(item -> item != null && !item.isBlank())
            .toList();

        if (missingItems.isEmpty()) {
            return new AgentQualityGateResult(
                "missing-data-awareness",
                AgentQualityGateStatus.NOT_APPLICABLE,
                "No missing-data notes were present for this narrative path."
            );
        }

        if (narrative != null && containsAnyToken(normalize(narrative), MISSING_DATA_TERMS)) {
            return new AgentQualityGateResult(
                "missing-data-awareness",
                AgentQualityGateStatus.PASSED,
                "The narrative acknowledges limited or missing data."
            );
        }

        return new AgentQualityGateResult(
            "missing-data-awareness",
            AgentQualityGateStatus.WARNING,
            "Missing-data notes exist, but the narrative did not explicitly call out uncertainty."
        );
    }

    private AgentQualityGateResult userLanguageGate(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return new AgentQualityGateResult(
                "user-language-only",
                AgentQualityGateStatus.NOT_APPLICABLE,
                "User-language checks were skipped because the narrative was empty."
            );
        }

        String normalizedNarrative = normalize(narrative);
        for (InternalPhrasePattern phrasePattern : INTERNAL_USER_LANGUAGE_PATTERNS) {
            if (phrasePattern.pattern().matcher(normalizedNarrative).find()) {
                return new AgentQualityGateResult(
                    "user-language-only",
                    AgentQualityGateStatus.FAILED,
                    "The narrative exposed internal term \"" + phrasePattern.label() + "\" instead of referring directly to the diagnostics."
                );
            }
        }

        return new AgentQualityGateResult(
            "user-language-only",
            AgentQualityGateStatus.PASSED,
            "The narrative stayed focused on user-facing diagnostic language."
        );
    }

    private AgentQualityGateResult troubleshootingStructureGate(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return new AgentQualityGateResult(
                "troubleshooting-response-structure",
                AgentQualityGateStatus.NOT_APPLICABLE,
                "Troubleshooting-structure checks were skipped because the narrative was empty."
            );
        }

        LinkedHashMap<String, String> sections = parseTroubleshootingSections(narrative);
        List<String> missingSections = new ArrayList<>();
        for (String section : REQUIRED_TROUBLESHOOTING_SECTIONS) {
            if (!hasText(sections.get(section))) {
                missingSections.add(section + ":");
            }
        }

        boolean outOfOrder = !sectionsAppearInRequiredOrder(sections);
        if (missingSections.isEmpty() && !outOfOrder) {
            return new AgentQualityGateResult(
                "troubleshooting-response-structure",
                AgentQualityGateStatus.PASSED,
                "The narrative included the required troubleshooting sections in user-friendly order."
            );
        }

        String detail;
        if (!missingSections.isEmpty() && outOfOrder) {
            detail = "The narrative is missing required troubleshooting sections and did not keep them in the expected order: "
                + String.join(", ", missingSections);
        } else if (!missingSections.isEmpty()) {
            detail = "The narrative is missing required troubleshooting sections: " + String.join(", ", missingSections);
        } else {
            detail = "The narrative included the required troubleshooting sections, but they were out of the expected order.";
        }

        return new AgentQualityGateResult(
            "troubleshooting-response-structure",
            AgentQualityGateStatus.FAILED,
            detail
        );
    }

    private AgentQualityGateResult coverageAwareConfidenceGate(
        String narrative,
        List<ContextCoverage> coverageMetadata,
        List<AgentToolInvocation> toolInvocations
    ) {
        List<ContextCoverage> coverage = coverageMetadata == null ? List.of() : coverageMetadata.stream()
            .filter(item -> item != null)
            .toList();
        List<ContextCoverage> incompleteCoverage = coverage.stream()
            .filter(this::coverageNeedsAttention)
            .toList();
        if (incompleteCoverage.isEmpty()) {
            return new AgentQualityGateResult(
                "coverage-aware-confidence",
                AgentQualityGateStatus.NOT_APPLICABLE,
                "The starting context did not report omissions or truncation."
            );
        }

        if (narrative == null || narrative.isBlank()) {
            return new AgentQualityGateResult(
                "coverage-aware-confidence",
                AgentQualityGateStatus.WARNING,
                "Coverage-aware confidence could not be checked because the narrative was empty."
            );
        }

        String normalizedNarrative = normalize(narrative);
        boolean uncertaintyLanguage = containsAnyToken(normalizedNarrative, UNCERTAINTY_TERMS)
            || normalizedNarrative.contains("not enough")
            || normalizedNarrative.contains("need more");
        boolean highCertainty = HIGH_CERTAINTY_PATTERNS.stream()
            .anyMatch(pattern -> pattern.matcher(normalizedNarrative).find());
        boolean budgetExhausted = toolInvocations != null && toolInvocations.stream()
            .anyMatch(this::isBudgetExhaustionNotice);

        LinkedHashSet<String> artifactsMissingExpansion = new LinkedHashSet<>();
        boolean unresolvedExpansion = false;
        boolean parseGapsRemain = false;
        for (int index = 0; index < incompleteCoverage.size(); index++) {
            ContextCoverage item = incompleteCoverage.get(index);
            boolean retrievableCoverage = coverageHasRetrievableExpansion(item);
            boolean parseGapCoverage = !item.parseGaps().isEmpty();
            AgentToolInvocation latestExpansion = latestExpansionForCoverage(item, index, toolInvocations);

            if (retrievableCoverage && latestExpansion == null) {
                artifactsMissingExpansion.add(coverageLabel(item, index));
            }
            if (retrievableCoverage && latestExpansion != null && (latestExpansion.moreAvailable() || latestExpansion.truncated())) {
                unresolvedExpansion = true;
            }
            if (parseGapCoverage) {
                parseGapsRemain = true;
            }
        }

        if (!artifactsMissingExpansion.isEmpty()) {
            if (highCertainty && !uncertaintyLanguage) {
                return new AgentQualityGateResult(
                    "coverage-aware-confidence",
                    AgentQualityGateStatus.FAILED,
                    "The starting context reported omissions or truncation for "
                        + artifactsMissingExpansion.size()
                        + " artifact(s), but the agent stayed highly certain without expanding context."
                );
            }
            if (uncertaintyLanguage) {
                return new AgentQualityGateResult(
                    "coverage-aware-confidence",
                    AgentQualityGateStatus.PASSED,
                    "The starting context reported omissions or truncation, and the narrative clearly communicated that additional context could still change the interpretation."
                );
            }
            return new AgentQualityGateResult(
                "coverage-aware-confidence",
                AgentQualityGateStatus.WARNING,
                "The starting context reported omissions or truncation, but the agent concluded without expanding context or clearly describing the remaining uncertainty."
            );
        }

        if (unresolvedExpansion || parseGapsRemain) {
            if (highCertainty) {
                return new AgentQualityGateResult(
                    "coverage-aware-confidence",
                    AgentQualityGateStatus.FAILED,
                    "The agent expanded context, but the latest expansion state still left unresolved detail or parse gaps while the narrative stayed highly certain."
                );
            }
            if (uncertaintyLanguage) {
                return new AgentQualityGateResult(
                    "coverage-aware-confidence",
                    AgentQualityGateStatus.PASSED,
                    budgetExhausted
                        ? "The agent expanded context, hit a tool limit, and clearly communicated the remaining uncertainty."
                        : "The agent expanded context and clearly communicated the remaining uncertainty from incomplete coverage or parser gaps."
                );
            }
            return new AgentQualityGateResult(
                "coverage-aware-confidence",
                AgentQualityGateStatus.WARNING,
                budgetExhausted
                    ? "The agent expanded context and exhausted the available tool budget, but the narrative did not clearly describe the remaining uncertainty."
                    : "The agent expanded context, but the latest expansion state or parser coverage still left uncertainty that the narrative did not clearly describe."
            );
        }

        return new AgentQualityGateResult(
            "coverage-aware-confidence",
            AgentQualityGateStatus.PASSED,
            "The agent expanded context before concluding, and the latest retrieval state did not leave unresolved coverage for the analyzed artifacts."
        );
    }

    private boolean coverageNeedsAttention(ContextCoverage coverage) {
        return coverage != null
            && (coverageHasRetrievableExpansion(coverage) || !coverage.parseGaps().isEmpty());
    }

    private boolean coverageHasRetrievableExpansion(ContextCoverage coverage) {
        return coverage != null
            && (!coverage.omittedStructuredBlocks().isEmpty()
                || !coverage.omittedRawSlices().isEmpty()
                || !coverage.truncationMarkers().isEmpty());
    }

    private AgentToolInvocation latestExpansionForCoverage(
        ContextCoverage coverage,
        int coverageIndex,
        List<AgentToolInvocation> toolInvocations
    ) {
        List<AgentToolInvocation> expansions = toolInvocations == null ? List.of() : toolInvocations.stream()
            .filter(this::isCoverageExpansionInvocation)
            .toList();
        if (expansions.isEmpty()) {
            return null;
        }

        String artifactPath = coverage != null ? coverage.artifactPath() : null;
        if (artifactPath != null && !artifactPath.isBlank()) {
            AgentToolInvocation latest = null;
            for (AgentToolInvocation expansion : expansions) {
                if (artifactPath.equals(expansion.artifactPath())) {
                    latest = expansion;
                }
            }
            return latest;
        }

        if (coverageMetadataHasSingleArtifactReference(expansions, coverageIndex)) {
            return expansions.getLast();
        }
        return null;
    }

    private boolean coverageMetadataHasSingleArtifactReference(List<AgentToolInvocation> retrievals, int coverageIndex) {
        LinkedHashSet<String> artifactPaths = new LinkedHashSet<>();
        for (AgentToolInvocation retrieval : retrievals) {
            if (retrieval.artifactPath() != null && !retrieval.artifactPath().isBlank()) {
                artifactPaths.add(retrieval.artifactPath());
            }
        }
        return coverageIndex == 0 && artifactPaths.size() <= 1;
    }

    private String coverageLabel(ContextCoverage coverage, int coverageIndex) {
        if (coverage != null && coverage.artifactPath() != null && !coverage.artifactPath().isBlank()) {
            return coverage.artifactPath();
        }
        return "artifact-" + (coverageIndex + 1);
    }

    private boolean isCoverageExpansionInvocation(AgentToolInvocation invocation) {
        if (invocation == null) {
            return false;
        }
        String toolFamily = invocation.toolFamily();
        if (!"RETRIEVAL".equalsIgnoreCase(toolFamily) && !"COMPUTATION".equalsIgnoreCase(toolFamily)) {
            return false;
        }
        String sliceId = invocation.sliceId();
        if (sliceId == null || sliceId.isBlank()) {
            return false;
        }
        String normalizedSliceId = sliceId.toLowerCase(Locale.ROOT);
        return !normalizedSliceId.contains("budget-exhausted")
            && !"unresolved-context".equals(normalizedSliceId)
            && !"unavailable".equals(normalizedSliceId);
    }

    private boolean isBudgetExhaustionNotice(AgentToolInvocation invocation) {
        if (invocation == null || invocation.sliceId() == null) {
            return false;
        }
        return invocation.sliceId().toLowerCase(Locale.ROOT).contains("budget-exhausted");
    }

    private String matchingSignal(String narrative, List<String> signals) {
        Set<String> narrativeTokens = tokenize(narrative);
        String normalizedNarrative = normalize(narrative);
        for (String signal : signals) {
            String normalizedSignal = normalize(signal);
            if (!normalizedSignal.isBlank() && normalizedNarrative.contains(normalizedSignal)) {
                return signal;
            }

            List<String> significantTokens = tokenize(signal).stream()
                .filter(token -> token.length() > 2 && !STOP_WORDS.contains(token))
                .toList();
            if (significantTokens.size() >= 2 && narrativeTokens.containsAll(significantTokens)) {
                return signal;
            }
            if (significantTokens.size() == 1 && significantTokens.getFirst().length() >= 6
                && narrativeTokens.contains(significantTokens.getFirst())) {
                return signal;
            }
        }
        return null;
    }

    private boolean containsAnyToken(String normalizedText, Set<String> candidates) {
        Set<String> tokens = tokenize(normalizedText);
        for (String candidate : candidates) {
            if (tokens.contains(candidate)) {
                return true;
            }
        }
        return normalizedText.contains("not enough");
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(normalize(text))) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('-', ' ').trim();
    }

    private LinkedHashMap<String, String> parseTroubleshootingSections(String narrative) {
        LinkedHashMap<String, StringBuilder> buffers = new LinkedHashMap<>();
        String currentSection = null;

        for (String rawLine : narrative.lines().toList()) {
            String line = rawLine.stripTrailing();
            SectionMatch sectionMatch = matchSection(line);
            if (sectionMatch != null) {
                currentSection = sectionMatch.section();
                buffers.computeIfAbsent(currentSection, ignored -> new StringBuilder());
                if (!sectionMatch.remainder().isBlank()) {
                    buffers.get(currentSection).append(sectionMatch.remainder()).append('\n');
                }
                continue;
            }

            if (currentSection == null) {
                continue;
            }
            buffers.get(currentSection).append(line).append('\n');
        }

        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        for (String section : REQUIRED_TROUBLESHOOTING_SECTIONS) {
            if (!buffers.containsKey(section)) {
                continue;
            }
            sections.put(section, buffers.get(section).toString().strip());
        }
        return sections;
    }

    private boolean sectionsAppearInRequiredOrder(LinkedHashMap<String, String> sections) {
        if (sections.isEmpty()) {
            return true;
        }

        List<String> encounteredSections = List.copyOf(sections.keySet());
        int previousIndex = -1;
        for (String requiredSection : REQUIRED_TROUBLESHOOTING_SECTIONS) {
            int sectionIndex = encounteredSections.indexOf(requiredSection);
            if (sectionIndex < 0) {
                continue;
            }
            if (sectionIndex < previousIndex) {
                return false;
            }
            previousIndex = sectionIndex;
        }
        return true;
    }

    private SectionMatch matchSection(String line) {
        String normalized = normalizeSectionLine(line);
        for (String section : REQUIRED_TROUBLESHOOTING_SECTIONS) {
            String heading = section + ":";
            if (normalized.regionMatches(true, 0, heading, 0, heading.length())) {
                return new SectionMatch(section, normalized.substring(heading.length()).trim());
            }
        }
        return null;
    }

    private String normalizeSectionLine(String line) {
        if (line == null) {
            return "";
        }

        String normalized = line.strip();
        normalized = MARKDOWN_HEADING_PREFIX.matcher(normalized).replaceFirst("");
        normalized = LIST_PREFIX.matcher(normalized).replaceFirst("");
        var emphasizedHeadingMatcher = EMPHASIZED_HEADING.matcher(normalized);
        if (emphasizedHeadingMatcher.matches()) {
            String heading = emphasizedHeadingMatcher.group(2).trim();
            String remainder = emphasizedHeadingMatcher.group(3).trim();
            return remainder.isEmpty() ? heading : heading + " " + remainder;
        }

        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record InternalPhrasePattern(String label, Pattern pattern) { }

    private record SectionMatch(String section, String remainder) { }
}
