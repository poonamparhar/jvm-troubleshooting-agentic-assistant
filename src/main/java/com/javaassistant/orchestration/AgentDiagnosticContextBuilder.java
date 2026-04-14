package com.javaassistant.orchestration;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.compare.ArtifactSequenceAnalysisService.ArtifactSequenceAnalysis;
import com.javaassistant.compare.ArtifactSequenceAnalysisService.PairwiseSequenceComparison;
import com.javaassistant.context.ArtifactDiagnosticContext;
import com.javaassistant.context.ContextCoverage;
import com.javaassistant.context.ContextSlice;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.context.DiagnosticContextRenderSupport;
import com.javaassistant.context.DiagnosticHighlight;
import com.javaassistant.context.IndexedArtifactDiagnosticContext;
import com.javaassistant.correlate.CrossArtifactSignalAnalyzer;
import com.javaassistant.correlate.CrossArtifactSignalAnalyzer.CrossArtifactSignalSummary;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds bounded starting diagnostic context inputs for agents from fully parsed artifacts and coverage metadata.
 */
public class AgentDiagnosticContextBuilder {

    private static final int MAX_COVERAGE_ITEMS_RENDERED = 12;

    private final DiagnosticContextIndexer contextIndexer;
    private final CrossArtifactSignalAnalyzer crossArtifactSignalAnalyzer;

    public AgentDiagnosticContextBuilder() {
        this(new DiagnosticContextIndexer());
    }

    public AgentDiagnosticContextBuilder(DiagnosticContextIndexer contextIndexer) {
        this.contextIndexer = contextIndexer;
        this.crossArtifactSignalAnalyzer = new CrossArtifactSignalAnalyzer();
    }

    public record ArtifactGrounding(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        AssessmentResult assessmentResult
    ) { }

    public record SpecialistObservation(
        ArtifactType artifactType,
        String agentName,
        String sourcePath,
        String narrative
    ) { }

    public String buildSingleArtifactContext(ArtifactGrounding grounding) {
        return buildSingleArtifactContext(grounding, contextIndexer.index(grounding.inputArtifact(), grounding.parsedArtifact()));
    }

    public String buildSingleArtifactContext(ArtifactGrounding grounding, IndexedArtifactDiagnosticContext indexedContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("MODE: SINGLE_ARTIFACT\n");
        appendSingleArtifactSummary(builder, indexedContext);
        appendContext(builder, "ARTIFACT", grounding.inputArtifact(), indexedContext.diagnosticContext());
        return builder.toString().strip();
    }

    public String buildComparisonContext(
        ArtifactGrounding baseline,
        IndexedArtifactDiagnosticContext baselineContext,
        ArtifactGrounding current,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("MODE: ARTIFACT_COMPARISON\n");
        builder.append("ARTIFACT_TYPE: ").append(current.inputArtifact().type()).append('\n');
        appendComparisonSummary(builder, current.inputArtifact().type(), baselineContext, currentContext);
        appendContext(builder, "BASELINE_ARTIFACT", baseline.inputArtifact(), baselineContext.diagnosticContext());
        appendContext(builder, "CURRENT_ARTIFACT", current.inputArtifact(), currentContext.diagnosticContext());
        return builder.toString().strip();
    }

    public String buildSequenceContext(
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts,
        ArtifactSequenceAnalysis sequenceAnalysis
    ) {
        StringBuilder builder = new StringBuilder();
        ArtifactType artifactType = groundings != null && !groundings.isEmpty()
            ? groundings.getLast().inputArtifact().type()
            : ArtifactType.UNKNOWN;
        builder.append("MODE: ARTIFACT_SEQUENCE\n");
        builder.append("ARTIFACT_TYPE: ").append(artifactType).append('\n');
        appendSequenceSummary(builder, artifactType, groundings, indexedContexts, sequenceAnalysis);
        if (groundings != null && indexedContexts != null) {
            int limit = Math.min(groundings.size(), indexedContexts.size());
            for (int index = 0; index < limit; index++) {
                appendContext(
                    builder,
                    "SNAPSHOT_" + (index + 1) + "_ARTIFACT",
                    groundings.get(index).inputArtifact(),
                    indexedContexts.get(index).diagnosticContext()
                );
            }
        }
        return builder.toString().strip();
    }

    public String buildCorrelationContext(
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts,
        List<SpecialistObservation> observations
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("MODE: MULTI_ARTIFACT_CORRELATION\n");
        appendCrossArtifactSignalSummary(
            builder,
            crossArtifactSignalAnalyzer.summarize(groundings.stream().map(ArtifactGrounding::parsedArtifact).toList())
        );
        appendArtifactOverview(builder, groundings, indexedContexts);
        for (int index = 0; index < groundings.size(); index++) {
            ArtifactGrounding grounding = groundings.get(index);
            IndexedArtifactDiagnosticContext indexedContext = indexedContexts.get(index);
            appendContext(builder, "ARTIFACT_CONTEXT_" + (index + 1), grounding.inputArtifact(), indexedContext.diagnosticContext());
        }
        appendSpecialistObservations(builder, observations);
        return builder.toString().strip();
    }

    private void appendCrossArtifactSignalSummary(StringBuilder builder, CrossArtifactSignalSummary signalSummary) {
        appendSectionHeader(builder, "CROSS_ARTIFACT_SIGNAL_SUMMARY");
        Map<String, Object> canonical = signalSummary != null ? signalSummary.toCanonicalMap() : Map.of();
        if (canonical.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        builder.append(DiagnosticContextRenderSupport.indent(
            DiagnosticContextRenderSupport.renderFullValue(canonical),
            "  "
        )).append('\n');
    }

    private void appendArtifactOverview(
        StringBuilder builder,
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts
    ) {
        appendSectionHeader(builder, "ARTIFACT_OVERVIEW");
        if (groundings == null || groundings.isEmpty()) {
            builder.append("- none\n");
            return;
        }

        for (int index = 0; index < groundings.size(); index++) {
            InputArtifact artifact = groundings.get(index).inputArtifact();
            ArtifactDiagnosticContext context = indexedContexts.get(index).diagnosticContext();
            builder.append("- artifact-").append(index + 1).append(": ");
            builder.append(artifact.type());
            builder.append(" | source=").append(renderScalar(sourcePath(artifact)));
            builder.append(" | moreContext=").append(context.coverage().additionalContextAvailable());
            builder.append('\n');
        }
    }

    private void appendContext(
        StringBuilder builder,
        String title,
        InputArtifact artifact,
        ArtifactDiagnosticContext context
    ) {
        appendSectionHeader(builder, title);
        appendArtifactMetadata(builder, artifact);
        appendStructuredFacts(builder, context != null ? context.structuredFacts() : Map.of());
        appendHighlights(builder, context != null ? context.diagnosticHighlights() : List.of());
        appendSlices(builder, "STRUCTURED_CONTEXT_SLICES", context != null ? context.structuredSlices() : List.of());
        appendSlices(builder, "REPRESENTATIVE_CONTEXT_SLICES", context != null ? context.representativeSlices() : List.of());
        appendCoverage(builder, context != null ? context.coverage() : null);
    }

    private void appendComparisonSummary(
        StringBuilder builder,
        ArtifactType artifactType,
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        String sectionTitle = null;
        Map<String, Object> summary = Map.of();
        if (artifactType == ArtifactType.GC_LOG) {
            sectionTitle = "GC_COMPARISON_SUMMARY";
            summary = gcComparisonSummary(
                baselineContext,
                currentContext
            );
        } else if (artifactType == ArtifactType.JFR) {
            sectionTitle = "JFR_COMPARISON_SUMMARY";
            summary = jfrComparisonSummary(
                baselineContext,
                currentContext
            );
        }

        if (sectionTitle == null || summary.isEmpty()) {
            return;
        }

        appendSectionHeader(builder, sectionTitle);
        builder.append(DiagnosticContextRenderSupport.indent(
            DiagnosticContextRenderSupport.renderFullValue(summary),
            "  "
        )).append('\n');
    }

    private void appendSequenceSummary(
        StringBuilder builder,
        ArtifactType artifactType,
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts,
        ArtifactSequenceAnalysis sequenceAnalysis
    ) {
        appendSectionHeader(builder, "ARTIFACT_SEQUENCE_SUMMARY");
        Map<String, Object> summary = sequenceSummary(artifactType, groundings, indexedContexts, sequenceAnalysis);
        if (summary.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        builder.append(DiagnosticContextRenderSupport.indent(
            DiagnosticContextRenderSupport.renderFullValue(summary),
            "  "
        )).append('\n');
    }

    private Map<String, Object> sequenceSummary(
        ArtifactType artifactType,
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts,
        ArtifactSequenceAnalysis sequenceAnalysis
    ) {
        if (groundings == null || indexedContexts == null || groundings.isEmpty() || indexedContexts.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderAssumption", "snapshots appear in the order supplied to analyze");
        summary.put("snapshotCount", Math.min(groundings.size(), indexedContexts.size()));
        summary.put("firstSnapshotAlias", "baseline");
        summary.put("lastSnapshotAlias", "current");

        List<Map<String, Object>> snapshotOverview = new ArrayList<>();
        int snapshotCount = Math.min(groundings.size(), indexedContexts.size());
        for (int index = 0; index < snapshotCount; index++) {
            Map<String, Object> snapshot = snapshotOverviewEntry(index, snapshotCount, groundings.get(index), indexedContexts.get(index));
            if (!snapshot.isEmpty()) {
                snapshotOverview.add(snapshot);
            }
        }
        if (!snapshotOverview.isEmpty()) {
            summary.put("snapshotOverview", List.copyOf(snapshotOverview));
        }

        if (sequenceAnalysis != null) {
            Map<String, Object> firstToLast = comparisonDigest(
                artifactType,
                indexedContexts.getFirst(),
                indexedContexts.getLast(),
                sequenceAnalysis.firstToLastEvaluation()
            );
            if (!firstToLast.isEmpty()) {
                LinkedHashMap<String, Object> decorated = new LinkedHashMap<>(firstToLast);
                decorated.put("fromSnapshot", "baseline");
                decorated.put("toSnapshot", "current");
                summary.put("firstToLastProgression", Map.copyOf(decorated));
            }

            List<Map<String, Object>> pairwiseProgression = new ArrayList<>();
            for (PairwiseSequenceComparison pairwise : sequenceAnalysis.pairwiseComparisons()) {
                int fromIndex = pairwise.fromSnapshotNumber() - 1;
                int toIndex = pairwise.toSnapshotNumber() - 1;
                if (fromIndex < 0 || toIndex < 0 || fromIndex >= indexedContexts.size() || toIndex >= indexedContexts.size()) {
                    continue;
                }
                Map<String, Object> digest = comparisonDigest(
                    artifactType,
                    indexedContexts.get(fromIndex),
                    indexedContexts.get(toIndex),
                    pairwise.evaluation()
                );
                LinkedHashMap<String, Object> decorated = new LinkedHashMap<>();
                decorated.put("fromSnapshot", snapshotAlias(fromIndex, snapshotCount));
                decorated.put("toSnapshot", snapshotAlias(toIndex, snapshotCount));
                decorated.put("fromSource", sourcePath(pairwise.fromArtifact()));
                decorated.put("toSource", sourcePath(pairwise.toArtifact()));
                decorated.putAll(digest);
                pairwiseProgression.add(Map.copyOf(decorated));
            }
            if (!pairwiseProgression.isEmpty()) {
                summary.put("pairwiseProgression", List.copyOf(pairwiseProgression));
            }
        }

        return Map.copyOf(summary);
    }

    private Map<String, Object> snapshotOverviewEntry(
        int index,
        int snapshotCount,
        ArtifactGrounding grounding,
        IndexedArtifactDiagnosticContext indexedContext
    ) {
        if (grounding == null || indexedContext == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("snapshot", snapshotAlias(index, snapshotCount));
        entry.put("source", sourcePath(grounding.inputArtifact()));
        entry.put("moreContextAvailable", indexedContext.diagnosticContext().coverage().additionalContextAvailable());

        List<String> summaryLines = singleArtifactSummaryLines(grounding.inputArtifact().type(), indexedContext);
        if (!summaryLines.isEmpty()) {
            entry.put("summaryLines", summaryLines);
        } else {
            List<String> highlightLabels = indexedContext.diagnosticContext().diagnosticHighlights().stream()
                .map(DiagnosticHighlight::label)
                .filter(this::hasText)
                .limit(3)
                .toList();
            if (!highlightLabels.isEmpty()) {
                entry.put("highlights", highlightLabels);
            }
        }
        return Map.copyOf(entry);
    }

    private List<String> singleArtifactSummaryLines(ArtifactType artifactType, IndexedArtifactDiagnosticContext indexedContext) {
        if (artifactType == ArtifactType.GC_LOG) {
            return summaryLines(mapValue(gcSingleArtifactSummary(indexedContext).get("synopsis")));
        }
        if (artifactType == ArtifactType.JFR) {
            return summaryLines(jfrSingleArtifactSummary(indexedContext));
        }
        return List.of();
    }

    private Map<String, Object> comparisonDigest(
        ArtifactType artifactType,
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext,
        AssessmentResult evaluation
    ) {
        LinkedHashMap<String, Object> digest = new LinkedHashMap<>();
        if (artifactType == ArtifactType.GC_LOG) {
            Map<String, Object> summary = gcComparisonSummary(baselineContext, currentContext);
            Map<String, Object> regressionSynopsis = mapValue(summary.get("regressionSynopsis"));
            List<String> summaryLines = summaryLines(regressionSynopsis);
            if (!summaryLines.isEmpty()) {
                digest.put("summaryLines", summaryLines);
            }
            Map<String, Object> collectorComparison = mapValue(summary.get("collectorComparison"));
            if (!collectorComparison.isEmpty()) {
                digest.put("collectorComparison", collectorComparison);
            }
        } else if (artifactType == ArtifactType.JFR) {
            Map<String, Object> summary = jfrComparisonSummary(baselineContext, currentContext);
            List<String> summaryLines = summaryLines(mapValue(summary.get("regressionSynopsis")));
            if (!summaryLines.isEmpty()) {
                digest.put("summaryLines", summaryLines);
            }
        }

        if (evaluation != null) {
            List<String> topFindings = evaluation.findings().stream()
                .map(Finding::title)
                .filter(this::hasText)
                .distinct()
                .limit(3)
                .toList();
            if (!topFindings.isEmpty()) {
                digest.put("topFindings", topFindings);
            }
            if (!evaluation.missingData().isEmpty()) {
                digest.put("missingData", evaluation.missingData().stream().limit(3).toList());
            }
            if (!evaluation.findings().isEmpty()) {
                digest.put("findingCount", evaluation.findings().size());
            }
        }
        return digest.isEmpty() ? Map.of() : Map.copyOf(digest);
    }

    private List<String> summaryLines(Map<String, Object> map) {
        Object value = map != null ? map.get("summaryLines") : null;
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Object item : list) {
            String line = stringValue(item);
            if (hasText(line)) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private String snapshotAlias(int index, int snapshotCount) {
        if (index <= 0) {
            return "baseline";
        }
        if (index >= snapshotCount - 1) {
            return "current";
        }
        return "snapshot-" + (index + 1);
    }

    private void appendSingleArtifactSummary(StringBuilder builder, IndexedArtifactDiagnosticContext indexedContext) {
        if (indexedContext == null || indexedContext.parsedArtifact() == null) {
            return;
        }

        ArtifactType artifactType = indexedContext.parsedArtifact().type();
        String sectionTitle = null;
        Map<String, Object> summary = Map.of();
        if (artifactType == ArtifactType.GC_LOG) {
            sectionTitle = "GC_STARTING_SUMMARY";
            summary = gcSingleArtifactSummary(indexedContext);
        } else if (artifactType == ArtifactType.JFR) {
            sectionTitle = "JFR_STARTING_SUMMARY";
            summary = jfrSingleArtifactSummary(indexedContext);
        }

        if (sectionTitle == null || summary.isEmpty()) {
            return;
        }

        appendSectionHeader(builder, sectionTitle);
        builder.append(DiagnosticContextRenderSupport.indent(
            DiagnosticContextRenderSupport.renderFullValue(summary),
            "  "
        )).append('\n');
    }

    private void appendArtifactMetadata(StringBuilder builder, InputArtifact artifact) {
        builder.append("- Type: ").append(artifact != null ? artifact.type() : "(none)").append('\n');
        if (artifact != null && artifact.metadata() != null && artifact.metadata().sourcePath() != null) {
            builder.append("- Source: ").append(renderScalar(sourcePath(artifact))).append('\n');
        }
    }

    private void appendStructuredFacts(StringBuilder builder, Map<String, Object> structuredFacts) {
        appendSectionHeader(builder, "STRUCTURED_FACTS");
        if (structuredFacts == null || structuredFacts.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        for (Map.Entry<String, Object> entry : structuredFacts.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ").append(renderScalar(entry.getValue())).append('\n');
        }
    }

    private void appendHighlights(StringBuilder builder, List<DiagnosticHighlight> highlights) {
        appendSectionHeader(builder, "DIAGNOSTIC_HIGHLIGHTS");
        if (highlights == null || highlights.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        int limit = Math.min(DiagnosticContextIndexer.MAX_STARTING_HIGHLIGHTS, highlights.size());
        for (int index = 0; index < limit; index++) {
            DiagnosticHighlight highlight = highlights.get(index);
            builder.append(index + 1).append(". ").append(renderScalar(highlight.label())).append('\n');
            if (highlight.detail() != null && !highlight.detail().isBlank()) {
                builder.append("   Detail:\n");
                builder.append(DiagnosticContextRenderSupport.indent(
                    DiagnosticContextRenderSupport.renderFullValue(highlight.detail()),
                    "     "
                )).append('\n');
            }
            if (highlight.traceability() != null && !highlight.traceability().isBlank()) {
                builder.append("   Source: ").append(renderSource(highlight.traceability())).append('\n');
            }
            if (highlight.metrics() != null && !highlight.metrics().isEmpty()) {
                builder.append("   Metrics:\n");
                builder.append(DiagnosticContextRenderSupport.indent(
                    DiagnosticContextRenderSupport.renderFullValue(highlight.metrics()),
                    "     "
                )).append('\n');
            }
        }
        if (highlights.size() > limit) {
            builder.append("- ... ").append(highlights.size() - limit).append(" more highlight(s) omitted.\n");
        }
    }

    private void appendSlices(StringBuilder builder, String title, List<ContextSlice> slices) {
        appendSectionHeader(builder, title);
        if (slices == null || slices.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        int index = 1;
        for (ContextSlice slice : slices) {
            builder.append("- Slice ").append(index).append(": ").append(renderScalar(slice.label())).append('\n');
            builder.append("  ID: ").append(renderScalar(slice.sliceId())).append('\n');
            if (slice.traceability() != null && !slice.traceability().isBlank()) {
                builder.append("  Source: ").append(renderSource(slice.traceability())).append('\n');
            }
            if (slice.truncated()) {
                builder.append("  Truncated: true\n");
            }
            builder.append("  Content:\n");
            builder.append(DiagnosticContextRenderSupport.indent(slice.content() != null ? slice.content() : "(none)", "    ")).append('\n');
            index++;
        }
    }

    private void appendCoverage(StringBuilder builder, ContextCoverage coverage) {
        appendSectionHeader(builder, "CONTEXT_COVERAGE");
        if (coverage == null) {
            builder.append("- none\n");
            return;
        }
        builder.append("- Additional Context Available: ").append(coverage.additionalContextAvailable()).append('\n');
        appendCoverageItems(builder, "Omitted Structured Sections", coverage.omittedStructuredBlocks());
        appendCoverageItems(builder, "Omitted Context Slices", coverage.omittedRawSlices());
        appendCoverageItems(builder, "Parse Gaps", coverage.parseGaps());
        appendCoverageItems(builder, "Truncated Starting Items", coverage.truncationMarkers());
        if (coverage.additionalContextAvailable()) {
            builder.append("- Retrieval Hint: leave the selector blank to fetch the next omitted item. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.\n");
        }
    }

    private void appendCoverageItems(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("- ").append(label).append(" (").append(values.size()).append("): ");
        int limit = Math.min(MAX_COVERAGE_ITEMS_RENDERED, values.size());
        builder.append(String.join(", ", values.subList(0, limit)));
        if (values.size() > limit) {
            builder.append(", ... ").append(values.size() - limit).append(" more");
        }
        builder.append('\n');
    }

    private void appendSpecialistObservations(StringBuilder builder, List<SpecialistObservation> observations) {
        appendSectionHeader(builder, "SPECIALIST_AGENT_OBSERVATIONS");
        if (observations == null || observations.isEmpty()) {
            builder.append("- none\n");
            return;
        }
        int limit = Math.min(6, observations.size());
        for (int index = 0; index < limit; index++) {
            SpecialistObservation observation = observations.get(index);
            builder.append("- Agent: ").append(renderScalar(observation.agentName())).append('\n');
            builder.append("  Artifact Type: ").append(observation.artifactType()).append('\n');
            builder.append("  Source: ").append(renderScalar(observation.sourcePath())).append('\n');
            builder.append("  Narrative:\n");
            builder.append(DiagnosticContextRenderSupport.indent(
                DiagnosticContextRenderSupport.truncateBlock(observation.narrative(), 1200),
                "    "
            )).append('\n');
        }
        if (observations.size() > limit) {
            builder.append("- ... ").append(observations.size() - limit).append(" more observation(s) omitted.\n");
        }
    }

    private void appendSectionHeader(StringBuilder builder, String title) {
        builder.append('\n').append(title).append(":\n");
    }

    private String sourcePath(InputArtifact artifact) {
        return artifact != null && artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }

    private String renderScalar(Object value) {
        return DiagnosticContextRenderSupport.renderScalar(value);
    }

    private String renderSource(String value) {
        return DiagnosticContextRenderSupport.renderSourceAnchor(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMapsValue(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nested : map.entrySet()) {
                    entry.put(String.valueOf(nested.getKey()), nested.getValue());
                }
                converted.add(Map.copyOf(entry));
            }
        }
        return List.copyOf(converted);
    }

    private Map<String, Object> gcComparisonSummary(
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        ParsedArtifact baselineParsed = baselineContext != null ? baselineContext.parsedArtifact() : null;
        ParsedArtifact currentParsed = currentContext != null ? currentContext.parsedArtifact() : null;
        if (baselineParsed == null || currentParsed == null) {
            return Map.of();
        }

        Map<String, Object> baselineSummary = mapValue(baselineParsed.extractedData().get("summary"));
        Map<String, Object> currentSummary = mapValue(currentParsed.extractedData().get("summary"));
        Map<String, Object> baselinePressure = mapValue(baselineParsed.extractedData().get("collectorPressureSummary"));
        Map<String, Object> currentPressure = mapValue(currentParsed.extractedData().get("collectorPressureSummary"));
        Map<String, Object> baselineRecovery = mapValue(baselineParsed.extractedData().get("recoverySummary"));
        Map<String, Object> currentRecovery = mapValue(currentParsed.extractedData().get("recoverySummary"));
        Map<String, Object> baselineFailure = mapValue(baselineParsed.extractedData().get("failureSummary"));
        Map<String, Object> currentFailure = mapValue(currentParsed.extractedData().get("failureSummary"));
        Map<String, Object> baselineG1 = mapValue(baselineParsed.extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> currentG1 = mapValue(currentParsed.extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> baselineConcurrent = mapValue(baselineParsed.extractedData().get("concurrentSummary"));
        Map<String, Object> currentConcurrent = mapValue(currentParsed.extractedData().get("concurrentSummary"));
        Map<String, Object> baselinePauseBreakdown = mapValue(baselineParsed.extractedData().get("pauseBreakdown"));
        Map<String, Object> currentPauseBreakdown = mapValue(currentParsed.extractedData().get("pauseBreakdown"));

        if (baselineSummary.isEmpty() && currentSummary.isEmpty()) {
            return Map.of();
        }

        String baselineCollector = collectorName(baselineParsed, baselinePressure);
        String currentCollector = collectorName(currentParsed, currentPressure);
        boolean sameCollector = hasText(baselineCollector)
            && hasText(currentCollector)
            && baselineCollector.equalsIgnoreCase(currentCollector);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();

        LinkedHashMap<String, Object> collectorComparison = new LinkedHashMap<>();
        collectorComparison.put("baselineCollector", hasText(baselineCollector) ? baselineCollector : "UNKNOWN");
        collectorComparison.put("currentCollector", hasText(currentCollector) ? currentCollector : "UNKNOWN");
        collectorComparison.put("sameCollector", sameCollector);
        String baselineDominantPauseCause = firstNonBlank(
            stringValue(baselinePressure.get("dominantPauseCauseByTotalPauseMs")),
            stringValue(baselinePauseBreakdown.get("dominantPauseCauseByTotalPauseMs"))
        );
        String currentDominantPauseCause = firstNonBlank(
            stringValue(currentPressure.get("dominantPauseCauseByTotalPauseMs")),
            stringValue(currentPauseBreakdown.get("dominantPauseCauseByTotalPauseMs"))
        );
        if (hasText(baselineDominantPauseCause)) {
            collectorComparison.put("baselineDominantPauseCauseByTotalPauseMs", baselineDominantPauseCause);
        }
        if (hasText(currentDominantPauseCause)) {
            collectorComparison.put("currentDominantPauseCauseByTotalPauseMs", currentDominantPauseCause);
        }
        if (!sameCollector && hasText(baselineCollector) && hasText(currentCollector)) {
            collectorComparison.put("collectorSpecificComparisonLimited", true);
        }
        summary.put("collectorComparison", Map.copyOf(collectorComparison));

        Map<String, Object> dominantPauseCauseShift = gcDominantPauseCauseShift(
            baselineDominantPauseCause,
            currentDominantPauseCause
        );
        if (!dominantPauseCauseShift.isEmpty()) {
            summary.put("dominantPauseCauseShift", dominantPauseCauseShift);
        }

        Map<String, Object> causeMixPair = gcCauseMixPair(baselinePauseBreakdown, currentPauseBreakdown);
        if (!causeMixPair.isEmpty()) {
            summary.put("causeMixPair", causeMixPair);
        }

        Map<String, Object> regressionSynopsis = gcRegressionSynopsis(
            baselineCollector,
            currentCollector,
            sameCollector,
            baselineSummary,
            currentSummary,
            baselinePressure,
            currentPressure,
            baselineRecovery,
            currentRecovery,
            baselineFailure,
            currentFailure,
            baselineG1,
            currentG1,
            baselineConcurrent,
            currentConcurrent,
            baselineDominantPauseCause,
            currentDominantPauseCause
        );
        if (!regressionSynopsis.isEmpty()) {
            summary.put("regressionSynopsis", regressionSynopsis);
        }

        LinkedHashMap<String, Object> pauseAndOverheadDelta = new LinkedHashMap<>();
        putLongComparison(
            pauseAndOverheadDelta,
            "pauseEventCount",
            longValue(baselineSummary, "pauseEventCount"),
            longValue(currentSummary, "pauseEventCount")
        );
        putDoubleComparison(
            pauseAndOverheadDelta,
            "p95PauseMs",
            firstPositiveDouble(baselinePressure, "p95PauseMs", baselineSummary, "p95PauseMs"),
            firstPositiveDouble(currentPressure, "p95PauseMs", currentSummary, "p95PauseMs")
        );
        putDoubleComparison(
            pauseAndOverheadDelta,
            "maxPauseMs",
            firstPositiveDouble(baselinePressure, "maxPauseMs", baselineSummary, "maxPauseMs"),
            firstPositiveDouble(currentPressure, "maxPauseMs", currentSummary, "maxPauseMs")
        );
        putDoubleComparison(
            pauseAndOverheadDelta,
            "stopTheWorldOverheadPct",
            doubleValue(baselineSummary, "stopTheWorldOverheadPct"),
            doubleValue(currentSummary, "stopTheWorldOverheadPct")
        );
        if (!pauseAndOverheadDelta.isEmpty()) {
            summary.put("pauseAndOverheadDelta", Map.copyOf(pauseAndOverheadDelta));
        }

        LinkedHashMap<String, Object> pressureAndRecoveryDelta = new LinkedHashMap<>();
        putLongComparison(
            pressureAndRecoveryDelta,
            "fullGcCount",
            firstPositiveLong(baselinePressure, "fullGcCount", baselineSummary, "fullGcCount"),
            firstPositiveLong(currentPressure, "fullGcCount", currentSummary, "fullGcCount")
        );
        putDoubleComparison(
            pressureAndRecoveryDelta,
            "maxFullGcPauseMs",
            firstPositiveDouble(baselineSummary, "maxFullGcPauseMs", baselinePressure, "maxPauseMs"),
            firstPositiveDouble(currentSummary, "maxFullGcPauseMs", currentPressure, "maxPauseMs")
        );
        putDoubleComparison(
            pressureAndRecoveryDelta,
            "peakPostGcOccupancyRatio",
            firstPositiveDouble(baselinePressure, "peakPostGcOccupancyRatio", baselineRecovery, "peakPostGcOccupancyRatio", baselineSummary, "peakHeapOccupancyRatio"),
            firstPositiveDouble(currentPressure, "peakPostGcOccupancyRatio", currentRecovery, "peakPostGcOccupancyRatio", currentSummary, "peakHeapOccupancyRatio")
        );
        putLongComparison(
            pressureAndRecoveryDelta,
            "nearCapacityAfterGcCount",
            firstPositiveLong(baselinePressure, "nearCapacityAfterGcCount", baselineRecovery, "nearCapacityAfterGcCount"),
            firstPositiveLong(currentPressure, "nearCapacityAfterGcCount", currentRecovery, "nearCapacityAfterGcCount")
        );
        putDoubleComparison(
            pressureAndRecoveryDelta,
            "averagePostGcOccupancyRatio",
            firstPositiveDouble(baselinePressure, "averagePostGcOccupancyRatio", baselineRecovery, "averagePostGcOccupancyRatio"),
            firstPositiveDouble(currentPressure, "averagePostGcOccupancyRatio", currentRecovery, "averagePostGcOccupancyRatio")
        );
        putDoubleComparison(
            pressureAndRecoveryDelta,
            "averageFullPostGcOccupancyRatio",
            firstPositiveDouble(baselinePressure, "averageFullPostGcOccupancyRatio", baselineRecovery, "averageFullPostGcOccupancyRatio"),
            firstPositiveDouble(currentPressure, "averageFullPostGcOccupancyRatio", currentRecovery, "averageFullPostGcOccupancyRatio")
        );
        putLongComparison(
            pressureAndRecoveryDelta,
            "metaspaceTriggeredFullGcCount",
            firstPositiveLong(baselinePressure, "metaspaceTriggeredFullGcCount", baselineSummary, "metaspaceTriggeredFullGcCount"),
            firstPositiveLong(currentPressure, "metaspaceTriggeredFullGcCount", currentSummary, "metaspaceTriggeredFullGcCount")
        );
        if (!pressureAndRecoveryDelta.isEmpty()) {
            summary.put("pressureAndRecoveryDelta", Map.copyOf(pressureAndRecoveryDelta));
        }

        Map<String, Object> recoveryShapePair = gcRecoveryShapePair(
            baselineCollector,
            baselinePressure,
            baselineRecovery,
            baselineG1,
            currentCollector,
            currentPressure,
            currentRecovery,
            currentG1
        );
        if (!recoveryShapePair.isEmpty()) {
            summary.put("recoveryShapePair", recoveryShapePair);
        }

        LinkedHashMap<String, Object> collectorSpecificDelta = new LinkedHashMap<>();
        if (sameCollector) {
            switch ((hasText(currentCollector) ? currentCollector : "").toUpperCase(Locale.ROOT)) {
                case "G1" -> {
                    putLongComparison(
                        collectorSpecificDelta,
                        "evacuationFailurePauseCount",
                        longValue(baselineFailure, "evacuationFailurePauseCount"),
                        longValue(currentFailure, "evacuationFailurePauseCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "toSpaceExhaustedCount",
                        longValue(baselineFailure, "toSpaceExhaustedCount"),
                        longValue(currentFailure, "toSpaceExhaustedCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "fullCompactionAttemptCount",
                        longValue(baselineFailure, "fullCompactionAttemptCount"),
                        longValue(currentFailure, "fullCompactionAttemptCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "maxFullGcStreak",
                        longValue(baselinePressure, "maxFullGcStreak"),
                        longValue(currentPressure, "maxFullGcStreak")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "lowReclaimHighRetentionFullGcCount",
                        longValue(baselineG1, "lowReclaimHighRetentionFullGcCount"),
                        longValue(currentG1, "lowReclaimHighRetentionFullGcCount")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "averageFullGcReclaimedMb",
                        doubleValue(baselineG1, "averageFullGcReclaimedMb"),
                        doubleValue(currentG1, "averageFullGcReclaimedMb")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "peakHumongousAfterRegions",
                        longValue(baselineSummary, "peakHumongousAfterRegions"),
                        longValue(currentSummary, "peakHumongousAfterRegions")
                    );
                }
                case "CMS" -> {
                    putLongComparison(
                        collectorSpecificDelta,
                        "concurrentModeFailureCount",
                        longValue(baselineFailure, "concurrentModeFailureCount"),
                        longValue(currentFailure, "concurrentModeFailureCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "promotionFailedCount",
                        longValue(baselineFailure, "promotionFailedCount"),
                        longValue(currentFailure, "promotionFailedCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "maxConcurrentModeFailureStreak",
                        longValue(baselinePressure, "maxConcurrentModeFailureStreak"),
                        longValue(currentPressure, "maxConcurrentModeFailureStreak")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "concurrentPhaseCount",
                        longValue(baselineConcurrent, "concurrentPhaseCount"),
                        longValue(currentConcurrent, "concurrentPhaseCount")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "longestConcurrentPhaseMs",
                        doubleValue(baselineConcurrent, "longestConcurrentPhaseMs"),
                        doubleValue(currentConcurrent, "longestConcurrentPhaseMs")
                    );
                }
                case "SERIAL", "PARALLEL" -> {
                    putLongComparison(
                        collectorSpecificDelta,
                        "maxFullGcStreak",
                        longValue(baselinePressure, "maxFullGcStreak"),
                        longValue(currentPressure, "maxFullGcStreak")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "averageFullPostGcOccupancyRatio",
                        doubleValue(baselineRecovery, "averageFullPostGcOccupancyRatio"),
                        doubleValue(currentRecovery, "averageFullPostGcOccupancyRatio")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "averageReclaimedMb",
                        firstPositiveDouble(baselinePressure, "averageReclaimedMb", baselineRecovery, "averageReclaimedMb"),
                        firstPositiveDouble(currentPressure, "averageReclaimedMb", currentRecovery, "averageReclaimedMb")
                    );
                }
                case "ZGC" -> {
                    putLongComparison(
                        collectorSpecificDelta,
                        "allocationStallCount",
                        firstPositiveLong(baselinePressure, "allocationStallCount", baselineSummary, "allocationStallCount"),
                        firstPositiveLong(currentPressure, "allocationStallCount", currentSummary, "allocationStallCount")
                    );
                    putLongComparison(
                        collectorSpecificDelta,
                        "maxAllocationStallStreak",
                        longValue(baselinePressure, "maxAllocationStallStreak"),
                        longValue(currentPressure, "maxAllocationStallStreak")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "maxAllocationStallMs",
                        firstPositiveDouble(baselinePressure, "maxAllocationStallMs", baselineSummary, "maxAllocationStallMs"),
                        firstPositiveDouble(currentPressure, "maxAllocationStallMs", currentSummary, "maxAllocationStallMs")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "totalAllocationStallMs",
                        firstPositiveDouble(baselinePressure, "totalAllocationStallMs", baselineSummary, "totalAllocationStallMs"),
                        firstPositiveDouble(currentPressure, "totalAllocationStallMs", currentSummary, "totalAllocationStallMs")
                    );
                    putDoubleComparison(
                        collectorSpecificDelta,
                        "longestConcurrentPhaseMs",
                        doubleValue(baselineConcurrent, "longestConcurrentPhaseMs"),
                        doubleValue(currentConcurrent, "longestConcurrentPhaseMs")
                    );
                }
                default -> {
                }
            }
        }
        if (!collectorSpecificDelta.isEmpty()) {
            summary.put("collectorSpecificDelta", Map.copyOf(collectorSpecificDelta));
        }

        Map<String, Object> dominantIncidentPair = gcDominantIncidentPair(baselineContext, currentContext);
        if (!dominantIncidentPair.isEmpty()) {
            summary.put("dominantIncidentPair", dominantIncidentPair);
        }

        return Map.copyOf(summary);
    }

    private Map<String, Object> jfrComparisonSummary(
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        ParsedArtifact baselineParsed = baselineContext != null ? baselineContext.parsedArtifact() : null;
        ParsedArtifact currentParsed = currentContext != null ? currentContext.parsedArtifact() : null;
        if (baselineParsed == null || currentParsed == null) {
            return Map.of();
        }

        Map<String, Object> baselineSummary = mapValue(baselineParsed.extractedData().get("summary"));
        Map<String, Object> currentSummary = mapValue(currentParsed.extractedData().get("summary"));
        if (baselineSummary.isEmpty() && currentSummary.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> baselineLockSummary = mapValue(baselineParsed.extractedData().get("lockSummary"));
        Map<String, Object> currentLockSummary = mapValue(currentParsed.extractedData().get("lockSummary"));
        Map<String, Object> baselineGcSummary = mapValue(baselineParsed.extractedData().get("gcSummary"));
        Map<String, Object> currentGcSummary = mapValue(currentParsed.extractedData().get("gcSummary"));
        Map<String, Object> baselineThreadParkSummary = mapValue(baselineParsed.extractedData().get("threadParkSummary"));
        Map<String, Object> currentThreadParkSummary = mapValue(currentParsed.extractedData().get("threadParkSummary"));
        Map<String, Object> baselineIoSummary = mapValue(baselineParsed.extractedData().get("ioSummary"));
        Map<String, Object> currentIoSummary = mapValue(currentParsed.extractedData().get("ioSummary"));
        Map<String, Object> baselineExceptionSummary = mapValue(baselineParsed.extractedData().get("exceptionSummary"));
        Map<String, Object> currentExceptionSummary = mapValue(currentParsed.extractedData().get("exceptionSummary"));
        Map<String, Object> baselineSafepointSummary = mapValue(baselineParsed.extractedData().get("safepointSummary"));
        Map<String, Object> currentSafepointSummary = mapValue(currentParsed.extractedData().get("safepointSummary"));
        Map<String, Object> baselineAllocationFieldSummary = mapValue(baselineParsed.extractedData().get("allocationFieldSummary"));
        Map<String, Object> currentAllocationFieldSummary = mapValue(currentParsed.extractedData().get("allocationFieldSummary"));
        Map<String, Object> baselineAllocationHotspotSummary = mapValue(baselineParsed.extractedData().get("allocationHotspotSummary"));
        Map<String, Object> currentAllocationHotspotSummary = mapValue(currentParsed.extractedData().get("allocationHotspotSummary"));
        Map<String, Object> baselineOldObjectFieldSummary = mapValue(baselineParsed.extractedData().get("oldObjectFieldSummary"));
        Map<String, Object> currentOldObjectFieldSummary = mapValue(currentParsed.extractedData().get("oldObjectFieldSummary"));
        Map<String, Object> baselineExecutionHotspotSummary = mapValue(baselineParsed.extractedData().get("executionHotspotSummary"));
        Map<String, Object> currentExecutionHotspotSummary = mapValue(currentParsed.extractedData().get("executionHotspotSummary"));
        Map<String, Object> baselineRuntimeHotspotSummary = mapValue(baselineParsed.extractedData().get("runtimeHotspotSummary"));
        Map<String, Object> currentRuntimeHotspotSummary = mapValue(currentParsed.extractedData().get("runtimeHotspotSummary"));
        Map<String, Object> baselineIncidentWindowSummary = mapValue(baselineParsed.extractedData().get("incidentWindowSummary"));
        Map<String, Object> currentIncidentWindowSummary = mapValue(currentParsed.extractedData().get("incidentWindowSummary"));
        List<Map<String, Object>> baselineIncidentWindows = listOfMapsValue(baselineParsed.extractedData().get("incidentWindows"));
        List<Map<String, Object>> currentIncidentWindows = listOfMapsValue(currentParsed.extractedData().get("incidentWindows"));
        List<Map<String, Object>> baselineTimelineEvents = listOfMapsValue(baselineParsed.extractedData().get("timelineEvents"));
        List<Map<String, Object>> currentTimelineEvents = listOfMapsValue(currentParsed.extractedData().get("timelineEvents"));

        Map<String, Object> recordingComparison = jfrRecordingComparison(baselineSummary, currentSummary);
        Map<String, Object> dominantIncidentPair = jfrDominantIncidentPair(
            baselineIncidentWindowSummary,
            baselineIncidentWindows,
            currentIncidentWindowSummary,
            currentIncidentWindows
        );
        Map<String, Object> dominantHotspotPair = jfrDominantHotspotPair(
            baselineExecutionHotspotSummary,
            baselineRuntimeHotspotSummary,
            baselineAllocationHotspotSummary,
            currentExecutionHotspotSummary,
            currentRuntimeHotspotSummary,
            currentAllocationHotspotSummary
        );
        Map<String, Object> eventFamilyRegression = jfrEventFamilyRegression(
            baselineTimelineEvents,
            longValue(baselineSummary, "durationMs"),
            currentTimelineEvents,
            longValue(currentSummary, "durationMs")
        );
        Map<String, Object> threadRegression = jfrThreadRegression(
            baselineTimelineEvents,
            longValue(baselineSummary, "durationMs"),
            currentTimelineEvents,
            longValue(currentSummary, "durationMs")
        );
        Map<String, Object> runtimePressureDelta = jfrRuntimePressureDelta(
            baselineLockSummary,
            currentLockSummary,
            baselineGcSummary,
            currentGcSummary,
            baselineThreadParkSummary,
            currentThreadParkSummary,
            baselineIoSummary,
            currentIoSummary,
            baselineExceptionSummary,
            currentExceptionSummary,
            baselineSafepointSummary,
            currentSafepointSummary
        );
        Map<String, Object> allocationDelta = jfrAllocationDelta(
            baselineAllocationFieldSummary,
            baselineAllocationHotspotSummary,
            currentAllocationFieldSummary,
            currentAllocationHotspotSummary
        );
        Map<String, Object> retentionDelta = jfrRetentionDelta(
            baselineOldObjectFieldSummary,
            currentOldObjectFieldSummary
        );
        Map<String, Object> regressionSynopsis = jfrComparisonSynopsis(
            recordingComparison,
            dominantIncidentPair,
            dominantHotspotPair,
            eventFamilyRegression,
            threadRegression,
            runtimePressureDelta,
            allocationDelta,
            retentionDelta
        );

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        if (!regressionSynopsis.isEmpty()) {
            summary.put("regressionSynopsis", regressionSynopsis);
        }
        if (!recordingComparison.isEmpty()) {
            summary.put("recordingComparison", recordingComparison);
        }
        if (!dominantIncidentPair.isEmpty()) {
            summary.put("dominantIncidentPair", dominantIncidentPair);
        }
        if (!dominantHotspotPair.isEmpty()) {
            summary.put("dominantHotspotPair", dominantHotspotPair);
        }
        if (!eventFamilyRegression.isEmpty()) {
            summary.put("eventFamilyRegression", eventFamilyRegression);
        }
        if (!threadRegression.isEmpty()) {
            summary.put("threadRegression", threadRegression);
        }
        if (!runtimePressureDelta.isEmpty()) {
            summary.put("runtimePressureDelta", runtimePressureDelta);
        }
        if (!allocationDelta.isEmpty()) {
            summary.put("allocationDelta", allocationDelta);
        }
        if (!retentionDelta.isEmpty()) {
            summary.put("retentionDelta", retentionDelta);
        }
        return summary.isEmpty() ? Map.of() : Map.copyOf(summary);
    }

    private Map<String, Object> jfrRecordingComparison(
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary
    ) {
        LinkedHashMap<String, Object> comparison = new LinkedHashMap<>();
        putLongComparison(
            comparison,
            "durationMs",
            longValue(baselineSummary, "durationMs"),
            longValue(currentSummary, "durationMs")
        );
        putLongComparison(
            comparison,
            "eventCount",
            longValue(baselineSummary, "eventCount"),
            longValue(currentSummary, "eventCount")
        );
        putLongComparison(
            comparison,
            "observedEventTypeCount",
            longValue(baselineSummary, "observedEventTypeCount"),
            longValue(currentSummary, "observedEventTypeCount")
        );
        return comparison.isEmpty() ? Map.of() : Map.copyOf(comparison);
    }

    private Map<String, Object> jfrDominantIncidentPair(
        Map<String, Object> baselineIncidentWindowSummary,
        List<Map<String, Object>> baselineIncidentWindows,
        Map<String, Object> currentIncidentWindowSummary,
        List<Map<String, Object>> currentIncidentWindows
    ) {
        Map<String, Object> baselineIncident = jfrComparisonIncident(
            mapValue(baselineIncidentWindowSummary.get("primaryIncident")),
            baselineIncidentWindows
        );
        Map<String, Object> currentIncident = jfrComparisonIncident(
            mapValue(currentIncidentWindowSummary.get("primaryIncident")),
            currentIncidentWindows
        );
        if (baselineIncident.isEmpty() && currentIncident.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
        if (!baselineIncident.isEmpty()) {
            pair.put("baselineIncident", baselineIncident);
        }
        if (!currentIncident.isEmpty()) {
            pair.put("currentIncident", currentIncident);
        }

        LinkedHashMap<String, Object> windowDelta = new LinkedHashMap<>();
        String baselineFocus = stringValue(baselineIncident.get("focus"));
        String currentFocus = stringValue(currentIncident.get("focus"));
        if (hasText(baselineFocus)) {
            windowDelta.put("baselineFocus", baselineFocus);
        }
        if (hasText(currentFocus)) {
            windowDelta.put("currentFocus", currentFocus);
        }
        if (hasText(baselineFocus) && hasText(currentFocus)) {
            windowDelta.put("focusChanged", !baselineFocus.equals(currentFocus));
        }
        putLongComparison(windowDelta, "relativeStartMs", longValue(baselineIncident, "relativeStartMs"), longValue(currentIncident, "relativeStartMs"));
        putLongComparison(windowDelta, "relativeEndMs", longValue(baselineIncident, "relativeEndMs"), longValue(currentIncident, "relativeEndMs"));
        putLongComparison(windowDelta, "durationMs", longValue(baselineIncident, "durationMs"), longValue(currentIncident, "durationMs"));
        putLongComparison(windowDelta, "eventCount", longValue(baselineIncident, "eventCount"), longValue(currentIncident, "eventCount"));
        putLongComparison(windowDelta, "totalDurationMs", longValue(baselineIncident, "totalDurationMs"), longValue(currentIncident, "totalDurationMs"));
        putLongComparison(windowDelta, "totalAllocatedBytes", longValue(baselineIncident, "totalAllocatedBytes"), longValue(currentIncident, "totalAllocatedBytes"));
        putLongComparison(
            windowDelta,
            "totalSampledObjectBytes",
            longValue(baselineIncident, "totalSampledObjectBytes"),
            longValue(currentIncident, "totalSampledObjectBytes")
        );
        putLongComparison(windowDelta, "maxReferenceDepth", longValue(baselineIncident, "maxReferenceDepth"), longValue(currentIncident, "maxReferenceDepth"));
        if (!windowDelta.isEmpty()) {
            pair.put("windowDelta", Map.copyOf(windowDelta));
        }
        return Map.copyOf(pair);
    }

    private Map<String, Object> jfrComparisonIncident(
        Map<String, Object> primaryIncident,
        List<Map<String, Object>> incidentWindows
    ) {
        Map<String, Object> fallbackIncident = primaryIncident;
        if (fallbackIncident.isEmpty() && incidentWindows != null && !incidentWindows.isEmpty()) {
            fallbackIncident = incidentWindows.getFirst();
        }
        if (fallbackIncident.isEmpty()) {
            return Map.of();
        }

        String windowId = stringValue(fallbackIncident.get("windowId"));
        String focus = stringValue(fallbackIncident.get("focus"));
        Map<String, Object> fullWindow = jfrFindIncidentWindow(windowId, focus, incidentWindows);
        Map<String, Object> source = fullWindow.isEmpty() ? fallbackIncident : fullWindow;
        windowId = firstNonBlank(windowId, stringValue(source.get("windowId")));
        focus = firstNonBlank(focus, stringValue(source.get("focus")));

        LinkedHashMap<String, Object> incident = new LinkedHashMap<>();
        if (hasText(windowId)) {
            incident.put("windowId", windowId);
        }
        if (hasText(focus)) {
            incident.put("focus", focus);
        }
        String label = firstNonBlank(stringValue(source.get("label")), stringValue(fallbackIncident.get("label")));
        if (hasText(label)) {
            incident.put("label", label);
        }
        if (source.containsKey("relativeStartMs")) {
            incident.put("relativeStartMs", longValue(source, "relativeStartMs"));
        }
        if (source.containsKey("relativeEndMs")) {
            incident.put("relativeEndMs", longValue(source, "relativeEndMs"));
        }
        if (source.containsKey("durationMs")) {
            incident.put("durationMs", longValue(source, "durationMs"));
        }
        if (source.containsKey("eventCount")) {
            incident.put("eventCount", longValue(source, "eventCount"));
        }
        putIfPositiveLong(incident, "totalDurationMs", longValue(source, "totalDurationMs"));
        putIfPositiveLong(incident, "totalAllocatedBytes", longValue(source, "totalAllocatedBytes"));
        putIfPositiveLong(incident, "totalSampledObjectBytes", longValue(source, "totalSampledObjectBytes"));
        putIfPositiveLong(incident, "maxReferenceDepth", longValue(source, "maxReferenceDepth"));

        String topMethod = firstNonBlank(
            stringValue(fallbackIncident.get("topMethod")),
            jfrTopRankedValue(listOfMapsValue(source.get("topMethods")), "method")
        );
        String topThread = jfrTopRankedValue(listOfMapsValue(source.get("topThreads")), "thread");
        String topClass = firstNonBlank(
            stringValue(fallbackIncident.get("topClass")),
            jfrTopRankedValue(listOfMapsValue(source.get("topClasses")), "className")
        );
        String topRoot = firstNonBlank(
            stringValue(fallbackIncident.get("topRoot")),
            jfrTopRankedValue(listOfMapsValue(source.get("topRoots")), "root")
        );
        if (hasText(topMethod)) {
            incident.put("topMethod", topMethod);
        }
        if (hasText(topThread)) {
            incident.put("topThread", topThread);
        }
        if (hasText(topClass)) {
            incident.put("topClass", topClass);
        }
        if (hasText(topRoot)) {
            incident.put("topRoot", topRoot);
        }

        List<Map<String, Object>> dominantSignals = listOfMapsValue(source.get("dominantSignals"));
        if (!dominantSignals.isEmpty()) {
            incident.put("dominantSignals", List.copyOf(dominantSignals.stream().limit(3).toList()));
        }

        String summaryLine = firstNonBlank(stringValue(fallbackIncident.get("summaryLine")), stringValue(source.get("summaryLine")));
        if (hasText(summaryLine)) {
            incident.put("summaryLine", summaryLine);
        }
        return incident.isEmpty() ? Map.of() : Map.copyOf(incident);
    }

    private Map<String, Object> jfrFindIncidentWindow(
        String windowId,
        String focus,
        List<Map<String, Object>> incidentWindows
    ) {
        if (incidentWindows == null || incidentWindows.isEmpty()) {
            return Map.of();
        }
        if (hasText(windowId)) {
            for (Map<String, Object> window : incidentWindows) {
                if (windowId.equals(stringValue(window.get("windowId")))) {
                    return window;
                }
            }
        }
        if (hasText(focus)) {
            for (Map<String, Object> window : incidentWindows) {
                if (focus.equals(stringValue(window.get("focus")))) {
                    return window;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> jfrEventFamilyRegression(
        List<Map<String, Object>> baselineTimelineEvents,
        long baselineRecordingDurationMs,
        List<Map<String, Object>> currentTimelineEvents,
        long currentRecordingDurationMs
    ) {
        Map<String, JfrTimelineTrendAccumulator> baselineFamilies = aggregateJfrTimeline(
            baselineTimelineEvents,
            event -> stringValue(event.get("signalFamily")),
            this::jfrSignalFamilyLabel
        );
        Map<String, JfrTimelineTrendAccumulator> currentFamilies = aggregateJfrTimeline(
            currentTimelineEvents,
            event -> stringValue(event.get("signalFamily")),
            this::jfrSignalFamilyLabel
        );
        List<Map<String, Object>> topFamilies = jfrTrendComparisons(
            "signalFamily",
            baselineFamilies,
            currentFamilies,
            baselineRecordingDurationMs,
            currentRecordingDurationMs,
            4
        );
        if (topFamilies.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> regression = new LinkedHashMap<>();
        regression.put("topFamilies", topFamilies);
        return Map.copyOf(regression);
    }

    private Map<String, Object> jfrThreadRegression(
        List<Map<String, Object>> baselineTimelineEvents,
        long baselineRecordingDurationMs,
        List<Map<String, Object>> currentTimelineEvents,
        long currentRecordingDurationMs
    ) {
        Map<String, JfrTimelineTrendAccumulator> baselineThreads = aggregateJfrTimeline(
            baselineTimelineEvents,
            event -> stringValue(event.get("eventThread")),
            Function.identity()
        );
        Map<String, JfrTimelineTrendAccumulator> currentThreads = aggregateJfrTimeline(
            currentTimelineEvents,
            event -> stringValue(event.get("eventThread")),
            Function.identity()
        );
        List<Map<String, Object>> topThreads = jfrTrendComparisons(
            "thread",
            baselineThreads,
            currentThreads,
            baselineRecordingDurationMs,
            currentRecordingDurationMs,
            3
        );
        if (topThreads.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> regression = new LinkedHashMap<>();
        regression.put("topThreads", topThreads);
        return Map.copyOf(regression);
    }

    private Map<String, JfrTimelineTrendAccumulator> aggregateJfrTimeline(
        List<Map<String, Object>> timelineEvents,
        Function<Map<String, Object>, String> keyExtractor,
        Function<String, String> labelResolver
    ) {
        if (timelineEvents == null || timelineEvents.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, JfrTimelineTrendAccumulator> aggregates = new LinkedHashMap<>();
        for (Map<String, Object> event : timelineEvents) {
            String key = keyExtractor.apply(event);
            if (!hasText(key)) {
                continue;
            }
            String normalizedKey = key.strip();
            aggregates.computeIfAbsent(
                normalizedKey,
                ignored -> new JfrTimelineTrendAccumulator(labelResolver.apply(normalizedKey))
            ).record(event);
        }
        return aggregates.isEmpty() ? Map.of() : Map.copyOf(aggregates);
    }

    private List<Map<String, Object>> jfrTrendComparisons(
        String keyName,
        Map<String, JfrTimelineTrendAccumulator> baselineAggregates,
        Map<String, JfrTimelineTrendAccumulator> currentAggregates,
        long baselineRecordingDurationMs,
        long currentRecordingDurationMs,
        int limit
    ) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(baselineAggregates.keySet());
        keys.addAll(currentAggregates.keySet());
        if (keys.isEmpty()) {
            return List.of();
        }

        List<JfrTimelineTrendComparison> comparisons = new ArrayList<>();
        for (String key : keys) {
            JfrTimelineTrendAccumulator baseline = baselineAggregates.get(key);
            JfrTimelineTrendAccumulator current = currentAggregates.get(key);
            JfrTimelineTrendComparison comparison = jfrTrendComparisonEntry(
                keyName,
                key,
                baseline,
                current,
                baselineRecordingDurationMs,
                currentRecordingDurationMs
            );
            if (comparison != null) {
                comparisons.add(comparison);
            }
        }

        return comparisons.stream()
            .sorted(Comparator.comparingDouble(JfrTimelineTrendComparison::score).reversed()
                .thenComparing(Comparator.comparingDouble(JfrTimelineTrendComparison::currentProminence).reversed())
                .thenComparing(JfrTimelineTrendComparison::key))
            .limit(limit)
            .map(JfrTimelineTrendComparison::entry)
            .toList();
    }

    private JfrTimelineTrendComparison jfrTrendComparisonEntry(
        String keyName,
        String key,
        JfrTimelineTrendAccumulator baselineAggregate,
        JfrTimelineTrendAccumulator currentAggregate,
        long baselineRecordingDurationMs,
        long currentRecordingDurationMs
    ) {
        JfrTimelineTrendAccumulator baseline = baselineAggregate != null ? baselineAggregate : new JfrTimelineTrendAccumulator("");
        JfrTimelineTrendAccumulator current = currentAggregate != null ? currentAggregate : new JfrTimelineTrendAccumulator("");
        if (baseline.eventCount() == 0L && current.eventCount() == 0L) {
            return null;
        }

        double baselineEventRatePerMinute = ratePerMinute(baseline.eventCount(), baselineRecordingDurationMs);
        double currentEventRatePerMinute = ratePerMinute(current.eventCount(), currentRecordingDurationMs);
        double baselineDurationSharePct = sharePercent(baseline.totalDurationMs(), baselineRecordingDurationMs);
        double currentDurationSharePct = sharePercent(current.totalDurationMs(), currentRecordingDurationMs);
        double baselineByteRatePerSecond = byteRatePerSecond(baseline.totalBytes(), baselineRecordingDurationMs);
        double currentByteRatePerSecond = byteRatePerSecond(current.totalBytes(), currentRecordingDurationMs);
        double score = jfrRegressionScore(
            baselineEventRatePerMinute,
            currentEventRatePerMinute,
            baselineDurationSharePct,
            currentDurationSharePct,
            baselineByteRatePerSecond,
            currentByteRatePerSecond
        );
        boolean materiallyChanged = score > 0.0d
            || !firstNonBlank(baseline.topMethod(), "").equals(firstNonBlank(current.topMethod(), ""))
            || !firstNonBlank(baseline.topEventType(), "").equals(firstNonBlank(current.topEventType(), ""))
            || !firstNonBlank(baseline.topClass(), "").equals(firstNonBlank(current.topClass(), ""))
            || !firstNonBlank(baseline.topSignalFamily(), "").equals(firstNonBlank(current.topSignalFamily(), ""));
        if (!materiallyChanged) {
            return null;
        }

        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put(keyName, key);
        String label = firstNonBlank(current.label(), baseline.label());
        if (hasText(label) && !label.equals(key)) {
            entry.put("label", label);
        }
        putLongComparison(entry, "eventCount", baseline.eventCount(), current.eventCount());
        putDoubleComparison(entry, "eventRatePerMinute", baselineEventRatePerMinute, currentEventRatePerMinute);
        putLongComparison(entry, "totalDurationMs", baseline.totalDurationMs(), current.totalDurationMs());
        putDoubleComparison(entry, "durationSharePct", baselineDurationSharePct, currentDurationSharePct);
        putLongComparison(entry, "totalAllocatedBytes", baseline.totalAllocatedBytes(), current.totalAllocatedBytes());
        putLongComparison(entry, "totalSampledObjectBytes", baseline.totalSampledObjectBytes(), current.totalSampledObjectBytes());
        putDoubleComparison(entry, "byteRatePerSecond", baselineByteRatePerSecond, currentByteRatePerSecond);
        putLongComparison(entry, "maxReferenceDepth", baseline.maxReferenceDepth(), current.maxReferenceDepth());
        putLongComparison(entry, "maxObjectAgeMs", baseline.maxObjectAgeMs(), current.maxObjectAgeMs());

        putIfText(entry, "baselineTopEventType", baseline.topEventType());
        putIfText(entry, "currentTopEventType", current.topEventType());
        putIfText(entry, "baselineTopMethod", baseline.topMethod());
        putIfText(entry, "currentTopMethod", current.topMethod());
        putIfText(entry, "baselineTopClass", baseline.topClass());
        putIfText(entry, "currentTopClass", current.topClass());
        if (!"thread".equals(keyName)) {
            putIfText(entry, "baselineTopThread", baseline.topThread());
            putIfText(entry, "currentTopThread", current.topThread());
        }
        if (!"signalFamily".equals(keyName)) {
            putIfText(entry, "baselineTopSignalFamily", jfrSignalFamilyLabel(baseline.topSignalFamily()));
            putIfText(entry, "currentTopSignalFamily", jfrSignalFamilyLabel(current.topSignalFamily()));
        }
        entry.put("regressionScore", round(score));

        double currentProminence = currentEventRatePerMinute + (currentDurationSharePct * 2.0d) + (currentByteRatePerSecond / 1_000_000.0d);
        return new JfrTimelineTrendComparison(Map.copyOf(entry), score, currentProminence, key);
    }

    private String jfrTopRankedValue(List<Map<String, Object>> rankedItems, String key) {
        if (rankedItems == null || rankedItems.isEmpty()) {
            return "";
        }
        return stringValue(rankedItems.getFirst().get(key));
    }

    private String jfrSignalFamilyLabel(String signalFamily) {
        return switch (signalFamily == null ? "" : signalFamily) {
            case "gcPause" -> "GC pauses";
            case "lockContention" -> "monitor blocks";
            case "threadPark" -> "thread parks";
            case "ioLatency" -> "I/O latency";
            case "exceptionBurst" -> "exceptions";
            case "safepointPause" -> "safepoints";
            case "allocation" -> "allocations";
            case "oldObject" -> "retained objects";
            case "executionSample" -> "execution samples";
            default -> signalFamily;
        };
    }

    private double jfrRegressionScore(
        double baselineEventRatePerMinute,
        double currentEventRatePerMinute,
        double baselineDurationSharePct,
        double currentDurationSharePct,
        double baselineByteRatePerSecond,
        double currentByteRatePerSecond
    ) {
        return Math.max(0.0d, currentEventRatePerMinute - baselineEventRatePerMinute)
            + (Math.max(0.0d, currentDurationSharePct - baselineDurationSharePct) * 2.0d)
            + (Math.max(0.0d, currentByteRatePerSecond - baselineByteRatePerSecond) / 1_000_000.0d);
    }

    private Map<String, Object> jfrDominantHotspotPair(
        Map<String, Object> baselineExecutionHotspotSummary,
        Map<String, Object> baselineRuntimeHotspotSummary,
        Map<String, Object> baselineAllocationHotspotSummary,
        Map<String, Object> currentExecutionHotspotSummary,
        Map<String, Object> currentRuntimeHotspotSummary,
        Map<String, Object> currentAllocationHotspotSummary
    ) {
        Map<String, Object> baselineHotspot = jfrDominantHotspot(
            baselineExecutionHotspotSummary,
            baselineRuntimeHotspotSummary,
            baselineAllocationHotspotSummary
        );
        Map<String, Object> currentHotspot = jfrDominantHotspot(
            currentExecutionHotspotSummary,
            currentRuntimeHotspotSummary,
            currentAllocationHotspotSummary
        );
        if (baselineHotspot.isEmpty() && currentHotspot.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
        if (!baselineHotspot.isEmpty()) {
            pair.put("baselineHotspot", baselineHotspot);
        }
        if (!currentHotspot.isEmpty()) {
            pair.put("currentHotspot", currentHotspot);
        }

        String baselineKind = stringValue(baselineHotspot.get("kind"));
        String currentKind = stringValue(currentHotspot.get("kind"));
        String baselineMethod = stringValue(baselineHotspot.get("topMethod"));
        String currentMethod = stringValue(currentHotspot.get("topMethod"));
        double baselineSharePct = doubleValue(baselineHotspot, "sharePct");
        double currentSharePct = doubleValue(currentHotspot, "sharePct");
        if (hasText(baselineKind)
            || hasText(currentKind)
            || hasText(baselineMethod)
            || hasText(currentMethod)
            || hasPositiveMetric(baselineSharePct, currentSharePct)) {
            LinkedHashMap<String, Object> shift = new LinkedHashMap<>();
            if (hasText(baselineKind)) {
                shift.put("baselineKind", baselineKind);
            }
            if (hasText(currentKind)) {
                shift.put("currentKind", currentKind);
            }
            if (hasText(baselineMethod)) {
                shift.put("baselineMethod", baselineMethod);
            }
            if (hasText(currentMethod)) {
                shift.put("currentMethod", currentMethod);
            }
            if (hasText(baselineMethod) && hasText(currentMethod)) {
                shift.put("methodChanged", !baselineMethod.equals(currentMethod));
            }
            if (hasText(baselineKind) && hasText(currentKind)) {
                shift.put("kindChanged", !baselineKind.equals(currentKind));
            }
            if (hasPositiveMetric(baselineSharePct, currentSharePct)) {
                shift.put("baselineSharePct", round(baselineSharePct));
                shift.put("currentSharePct", round(currentSharePct));
                shift.put("sharePctDelta", round(currentSharePct - baselineSharePct));
            }
            pair.put("hotspotShift", Map.copyOf(shift));
        }

        return Map.copyOf(pair);
    }

    private Map<String, Object> jfrRuntimePressureDelta(
        Map<String, Object> baselineLockSummary,
        Map<String, Object> currentLockSummary,
        Map<String, Object> baselineGcSummary,
        Map<String, Object> currentGcSummary,
        Map<String, Object> baselineThreadParkSummary,
        Map<String, Object> currentThreadParkSummary,
        Map<String, Object> baselineIoSummary,
        Map<String, Object> currentIoSummary,
        Map<String, Object> baselineExceptionSummary,
        Map<String, Object> currentExceptionSummary,
        Map<String, Object> baselineSafepointSummary,
        Map<String, Object> currentSafepointSummary
    ) {
        LinkedHashMap<String, Object> delta = new LinkedHashMap<>();
        putJfrSignalComparison(delta, "gcPause", baselineGcSummary, currentGcSummary);
        putJfrSignalComparison(delta, "lockContention", baselineLockSummary, currentLockSummary);
        putJfrSignalComparison(delta, "threadPark", baselineThreadParkSummary, currentThreadParkSummary);
        putJfrSignalComparison(delta, "ioLatency", baselineIoSummary, currentIoSummary);
        putJfrSignalComparison(delta, "exceptionBurst", baselineExceptionSummary, currentExceptionSummary);
        putJfrSignalComparison(delta, "safepointPause", baselineSafepointSummary, currentSafepointSummary);
        return delta.isEmpty() ? Map.of() : Map.copyOf(delta);
    }

    private void putJfrSignalComparison(
        Map<String, Object> target,
        String key,
        Map<String, Object> baselineSignalSummary,
        Map<String, Object> currentSignalSummary
    ) {
        long baselineEventCount = longValue(baselineSignalSummary, "eventCount");
        long currentEventCount = longValue(currentSignalSummary, "eventCount");
        long baselineTotalDurationMs = longValue(baselineSignalSummary, "totalDurationMs");
        long currentTotalDurationMs = longValue(currentSignalSummary, "totalDurationMs");
        long baselineMaxDurationMs = longValue(baselineSignalSummary, "maxDurationMs");
        long currentMaxDurationMs = longValue(currentSignalSummary, "maxDurationMs");
        if (!hasPositiveMetric(
            baselineEventCount,
            currentEventCount,
            baselineTotalDurationMs,
            currentTotalDurationMs,
            baselineMaxDurationMs,
            currentMaxDurationMs
        )) {
            return;
        }

        LinkedHashMap<String, Object> comparison = new LinkedHashMap<>();
        putLongComparison(comparison, "eventCount", baselineEventCount, currentEventCount);
        putLongComparison(comparison, "totalDurationMs", baselineTotalDurationMs, currentTotalDurationMs);
        putLongComparison(comparison, "maxDurationMs", baselineMaxDurationMs, currentMaxDurationMs);
        target.put(key, Map.copyOf(comparison));
    }

    private Map<String, Object> jfrAllocationDelta(
        Map<String, Object> baselineAllocationFieldSummary,
        Map<String, Object> baselineAllocationHotspotSummary,
        Map<String, Object> currentAllocationFieldSummary,
        Map<String, Object> currentAllocationHotspotSummary
    ) {
        LinkedHashMap<String, Object> delta = new LinkedHashMap<>();
        putLongComparison(
            delta,
            "eventCount",
            longValue(baselineAllocationFieldSummary, "eventCount"),
            longValue(currentAllocationFieldSummary, "eventCount")
        );
        putLongComparison(
            delta,
            "totalAllocatedBytes",
            longValue(baselineAllocationFieldSummary, "totalAllocatedBytes"),
            longValue(currentAllocationFieldSummary, "totalAllocatedBytes")
        );
        putLongComparison(
            delta,
            "maxAllocatedBytes",
            longValue(baselineAllocationFieldSummary, "maxAllocatedBytes"),
            longValue(currentAllocationFieldSummary, "maxAllocatedBytes")
        );

        double baselineTopClassSharePct = topSharePercent(baselineAllocationFieldSummary, "topClassAllocatedByteShare", "topClassEventShare");
        double currentTopClassSharePct = topSharePercent(currentAllocationFieldSummary, "topClassAllocatedByteShare", "topClassEventShare");
        if (hasPositiveMetric(baselineTopClassSharePct, currentTopClassSharePct)) {
            putDoubleComparison(delta, "topClassSharePct", baselineTopClassSharePct, currentTopClassSharePct);
        }

        String baselineTopClass = stringValue(baselineAllocationFieldSummary.get("topClass"));
        String currentTopClass = stringValue(currentAllocationFieldSummary.get("topClass"));
        if (hasText(baselineTopClass)) {
            delta.put("baselineTopClass", baselineTopClass);
        }
        if (hasText(currentTopClass)) {
            delta.put("currentTopClass", currentTopClass);
        }
        if (hasText(baselineTopClass) && hasText(currentTopClass)) {
            delta.put("topClassChanged", !baselineTopClass.equals(currentTopClass));
        }

        String baselineTopMethod = firstNonBlank(
            stringValue(baselineAllocationHotspotSummary.get("topMethod")),
            stringValue(baselineAllocationHotspotSummary.get("topMethodByBytes"))
        );
        String currentTopMethod = firstNonBlank(
            stringValue(currentAllocationHotspotSummary.get("topMethod")),
            stringValue(currentAllocationHotspotSummary.get("topMethodByBytes"))
        );
        if (hasText(baselineTopMethod)) {
            delta.put("baselineTopMethod", baselineTopMethod);
        }
        if (hasText(currentTopMethod)) {
            delta.put("currentTopMethod", currentTopMethod);
        }
        if (hasText(baselineTopMethod) && hasText(currentTopMethod)) {
            delta.put("topMethodChanged", !baselineTopMethod.equals(currentTopMethod));
        }

        return delta.isEmpty() ? Map.of() : Map.copyOf(delta);
    }

    private Map<String, Object> jfrRetentionDelta(
        Map<String, Object> baselineOldObjectFieldSummary,
        Map<String, Object> currentOldObjectFieldSummary
    ) {
        LinkedHashMap<String, Object> delta = new LinkedHashMap<>();
        putLongComparison(
            delta,
            "eventCount",
            longValue(baselineOldObjectFieldSummary, "eventCount"),
            longValue(currentOldObjectFieldSummary, "eventCount")
        );
        putLongComparison(
            delta,
            "totalSampledObjectBytes",
            longValue(baselineOldObjectFieldSummary, "totalSampledObjectBytes"),
            longValue(currentOldObjectFieldSummary, "totalSampledObjectBytes")
        );
        putLongComparison(
            delta,
            "maxObjectAgeMs",
            longValue(baselineOldObjectFieldSummary, "maxObjectAgeMs"),
            longValue(currentOldObjectFieldSummary, "maxObjectAgeMs")
        );
        putLongComparison(
            delta,
            "maxReferenceDepth",
            longValue(baselineOldObjectFieldSummary, "maxReferenceDepth"),
            longValue(currentOldObjectFieldSummary, "maxReferenceDepth")
        );
        putDoubleComparison(
            delta,
            "averageReferenceDepth",
            doubleValue(baselineOldObjectFieldSummary, "averageReferenceDepth"),
            doubleValue(currentOldObjectFieldSummary, "averageReferenceDepth")
        );

        String baselineTopClass = stringValue(baselineOldObjectFieldSummary.get("topClass"));
        String currentTopClass = stringValue(currentOldObjectFieldSummary.get("topClass"));
        if (hasText(baselineTopClass)) {
            delta.put("baselineTopClass", baselineTopClass);
        }
        if (hasText(currentTopClass)) {
            delta.put("currentTopClass", currentTopClass);
        }
        if (hasText(baselineTopClass) && hasText(currentTopClass)) {
            delta.put("topClassChanged", !baselineTopClass.equals(currentTopClass));
        }

        String baselineRootType = stringValue(baselineOldObjectFieldSummary.get("topRootType"));
        String currentRootType = stringValue(currentOldObjectFieldSummary.get("topRootType"));
        String baselineRootSystem = stringValue(baselineOldObjectFieldSummary.get("topRootSystem"));
        String currentRootSystem = stringValue(currentOldObjectFieldSummary.get("topRootSystem"));
        if (hasText(baselineRootType)) {
            delta.put("baselineTopRootType", baselineRootType);
        }
        if (hasText(currentRootType)) {
            delta.put("currentTopRootType", currentRootType);
        }
        if (hasText(baselineRootSystem)) {
            delta.put("baselineTopRootSystem", baselineRootSystem);
        }
        if (hasText(currentRootSystem)) {
            delta.put("currentTopRootSystem", currentRootSystem);
        }
        if (hasText(baselineRootType) && hasText(currentRootType)) {
            delta.put("topRootTypeChanged", !baselineRootType.equals(currentRootType));
        }
        if (hasText(baselineRootSystem) && hasText(currentRootSystem)) {
            delta.put("topRootSystemChanged", !baselineRootSystem.equals(currentRootSystem));
        }

        return delta.isEmpty() ? Map.of() : Map.copyOf(delta);
    }

    private Map<String, Object> jfrComparisonSynopsis(
        Map<String, Object> recordingComparison,
        Map<String, Object> dominantIncidentPair,
        Map<String, Object> dominantHotspotPair,
        Map<String, Object> eventFamilyRegression,
        Map<String, Object> threadRegression,
        Map<String, Object> runtimePressureDelta,
        Map<String, Object> allocationDelta,
        Map<String, Object> retentionDelta
    ) {
        List<String> summaryLines = new ArrayList<>();
        appendJfrComparisonRecordingLine(summaryLines, recordingComparison);
        appendJfrComparisonIncidentLine(summaryLines, dominantIncidentPair);
        appendJfrComparisonHotspotLine(summaryLines, dominantHotspotPair);
        appendJfrComparisonEventFamilyLine(summaryLines, eventFamilyRegression);
        appendJfrComparisonThreadLine(summaryLines, threadRegression);
        appendJfrComparisonRuntimePressureLine(summaryLines, runtimePressureDelta);
        appendJfrComparisonAllocationLine(summaryLines, allocationDelta);
        appendJfrComparisonRetentionLine(summaryLines, retentionDelta);
        if (summaryLines.isEmpty()) {
            return Map.of();
        }

        return Map.of("summaryLines", List.copyOf(summaryLines.stream().limit(8).toList()));
    }

    private void appendJfrComparisonRecordingLine(List<String> summaryLines, Map<String, Object> recordingComparison) {
        long baselineDurationMs = longValue(recordingComparison, "baselineDurationMs");
        long currentDurationMs = longValue(recordingComparison, "currentDurationMs");
        long baselineEventCount = longValue(recordingComparison, "baselineEventCount");
        long currentEventCount = longValue(recordingComparison, "currentEventCount");
        long baselineObservedEventTypeCount = longValue(recordingComparison, "baselineObservedEventTypeCount");
        long currentObservedEventTypeCount = longValue(recordingComparison, "currentObservedEventTypeCount");
        if (!hasPositiveMetric(
            baselineDurationMs,
            currentDurationMs,
            baselineEventCount,
            currentEventCount,
            baselineObservedEventTypeCount,
            currentObservedEventTypeCount
        )) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Recording window: baseline %s with %d event(s)/%d type(s); current %s with %d event(s)/%d type(s).",
            baselineDurationMs > 0L ? humanDuration(baselineDurationMs) : "unknown duration",
            baselineEventCount,
            baselineObservedEventTypeCount,
            currentDurationMs > 0L ? humanDuration(currentDurationMs) : "unknown duration",
            currentEventCount,
            currentObservedEventTypeCount
        ));
    }

    private void appendJfrComparisonIncidentLine(List<String> summaryLines, Map<String, Object> dominantIncidentPair) {
        Map<String, Object> baselineIncident = mapValue(dominantIncidentPair.get("baselineIncident"));
        Map<String, Object> currentIncident = mapValue(dominantIncidentPair.get("currentIncident"));
        if (baselineIncident.isEmpty() && currentIncident.isEmpty()) {
            return;
        }

        String baselineFocus = stringValue(baselineIncident.get("focus"));
        String currentFocus = stringValue(currentIncident.get("focus"));
        long baselineStartMs = longValue(baselineIncident, "relativeStartMs");
        long baselineEndMs = longValue(baselineIncident, "relativeEndMs");
        long currentStartMs = longValue(currentIncident, "relativeStartMs");
        long currentEndMs = longValue(currentIncident, "relativeEndMs");
        long baselineEventCount = longValue(baselineIncident, "eventCount");
        long currentEventCount = longValue(currentIncident, "eventCount");
        if (!hasPositiveMetric(baselineStartMs, baselineEndMs, currentStartMs, currentEndMs, baselineEventCount, currentEventCount)
            && !hasText(baselineFocus)
            && !hasText(currentFocus)) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Incident window: baseline %s +%.3fs to +%.3fs (%d event(s)); current %s +%.3fs to +%.3fs (%d event(s)).",
            humanTextOrNone(jfrSignalFamilyLabelOrSelf(baselineFocus)),
            baselineStartMs / 1000.0d,
            baselineEndMs / 1000.0d,
            baselineEventCount,
            humanTextOrNone(jfrSignalFamilyLabelOrSelf(currentFocus)),
            currentStartMs / 1000.0d,
            currentEndMs / 1000.0d,
            currentEventCount
        ));
    }

    private void appendJfrComparisonHotspotLine(List<String> summaryLines, Map<String, Object> dominantHotspotPair) {
        Map<String, Object> baselineHotspot = mapValue(dominantHotspotPair.get("baselineHotspot"));
        Map<String, Object> currentHotspot = mapValue(dominantHotspotPair.get("currentHotspot"));
        String baselineKind = stringValue(baselineHotspot.get("kind"));
        String currentKind = stringValue(currentHotspot.get("kind"));
        String baselineMethod = stringValue(baselineHotspot.get("topMethod"));
        String currentMethod = stringValue(currentHotspot.get("topMethod"));
        double baselineSharePct = doubleValue(baselineHotspot, "sharePct");
        double currentSharePct = doubleValue(currentHotspot, "sharePct");
        if (!hasText(currentMethod) && !hasText(baselineMethod)) {
            return;
        }

        if (hasText(baselineMethod) && hasText(currentMethod) && !baselineMethod.equals(currentMethod)) {
            summaryLines.add(String.format(
                Locale.ROOT,
                "Hotspot shift: dominant hotspot moved from %s (%s, %d event(s)) to %s (%s, %d event(s)).",
                jfrHotspotLabel(baselineKind, baselineMethod),
                humanPercent(baselineSharePct),
                longValue(baselineHotspot, "eventCount"),
                jfrHotspotLabel(currentKind, currentMethod),
                humanPercent(currentSharePct),
                longValue(currentHotspot, "eventCount")
            ));
            return;
        }

        String activeKind = hasText(currentKind) ? currentKind : baselineKind;
        String activeMethod = hasText(currentMethod) ? currentMethod : baselineMethod;
        summaryLines.add(String.format(
            Locale.ROOT,
            "Hotspot shift: %s concentrates more heavily in current (%s -> %s).",
            jfrHotspotLabel(activeKind, activeMethod),
            humanPercent(baselineSharePct),
            humanPercent(currentSharePct)
        ));
    }

    private void appendJfrComparisonEventFamilyLine(List<String> summaryLines, Map<String, Object> eventFamilyRegression) {
        List<Map<String, Object>> topFamilies = listOfMapsValue(eventFamilyRegression.get("topFamilies"));
        if (topFamilies.isEmpty()) {
            return;
        }

        List<String> parts = new ArrayList<>();
        for (Map<String, Object> family : topFamilies.stream().limit(2).toList()) {
            String label = firstNonBlank(stringValue(family.get("label")), jfrSignalFamilyLabelOrSelf(stringValue(family.get("signalFamily"))));
            long baselineEventCount = longValue(family, "baselineEventCount");
            long currentEventCount = longValue(family, "currentEventCount");
            double baselineDurationSharePct = doubleValue(family, "baselineDurationSharePct");
            double currentDurationSharePct = doubleValue(family, "currentDurationSharePct");
            double baselineByteRatePerSecond = doubleValue(family, "baselineByteRatePerSecond");
            double currentByteRatePerSecond = doubleValue(family, "currentByteRatePerSecond");

            StringBuilder part = new StringBuilder(label)
                .append(' ')
                .append(baselineEventCount)
                .append(" -> ")
                .append(currentEventCount);
            if (hasPositiveMetric(baselineDurationSharePct, currentDurationSharePct)) {
                part.append(" (share ").append(humanPercent(baselineDurationSharePct)).append(" -> ").append(humanPercent(currentDurationSharePct)).append(')');
            }
            if (hasPositiveMetric(baselineByteRatePerSecond, currentByteRatePerSecond)) {
                part.append(" (bytes/s ").append(humanBytesPerSecond(baselineByteRatePerSecond)).append(" -> ").append(humanBytesPerSecond(currentByteRatePerSecond)).append(')');
            }
            parts.add(part.toString());
        }
        if (!parts.isEmpty()) {
            summaryLines.add("Event-family trend: " + String.join("; ", parts) + ".");
        }
    }

    private void appendJfrComparisonThreadLine(List<String> summaryLines, Map<String, Object> threadRegression) {
        List<Map<String, Object>> topThreads = listOfMapsValue(threadRegression.get("topThreads"));
        if (topThreads.isEmpty()) {
            return;
        }

        Map<String, Object> topThread = topThreads.getFirst();
        String thread = stringValue(topThread.get("thread"));
        long baselineEventCount = longValue(topThread, "baselineEventCount");
        long currentEventCount = longValue(topThread, "currentEventCount");
        String currentTopSignalFamily = stringValue(topThread.get("currentTopSignalFamily"));
        String currentTopMethod = stringValue(topThread.get("currentTopMethod"));
        if (!hasText(thread) && !hasPositiveMetric(baselineEventCount, currentEventCount)) {
            return;
        }

        StringBuilder line = new StringBuilder(String.format(
            Locale.ROOT,
            "Thread trend: %s moved from %d to %d event(s)",
            hasText(thread) ? thread : "dominant thread",
            baselineEventCount,
            currentEventCount
        ));
        if (hasText(currentTopSignalFamily)) {
            line.append("; current focus ").append(currentTopSignalFamily);
        }
        if (hasText(currentTopMethod)) {
            line.append("; current path ").append(currentTopMethod);
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrComparisonRuntimePressureLine(List<String> summaryLines, Map<String, Object> runtimePressureDelta) {
        List<String> parts = new ArrayList<>();
        appendJfrComparisonSignalPart(parts, "GC pauses", mapValue(runtimePressureDelta.get("gcPause")));
        appendJfrComparisonSignalPart(parts, "monitor blocks", mapValue(runtimePressureDelta.get("lockContention")));
        appendJfrComparisonSignalPart(parts, "thread parks", mapValue(runtimePressureDelta.get("threadPark")));
        appendJfrComparisonSignalPart(parts, "I/O latency", mapValue(runtimePressureDelta.get("ioLatency")));
        appendJfrComparisonSignalPart(parts, "exceptions", mapValue(runtimePressureDelta.get("exceptionBurst")));
        appendJfrComparisonSignalPart(parts, "safepoints", mapValue(runtimePressureDelta.get("safepointPause")));
        if (parts.isEmpty()) {
            return;
        }

        summaryLines.add("Runtime pressure: " + String.join("; ", parts.stream().limit(4).toList()) + ".");
    }

    private void appendJfrComparisonSignalPart(List<String> parts, String label, Map<String, Object> signalDelta) {
        long baselineEventCount = longValue(signalDelta, "baselineEventCount");
        long currentEventCount = longValue(signalDelta, "currentEventCount");
        long baselineTotalDurationMs = longValue(signalDelta, "baselineTotalDurationMs");
        long currentTotalDurationMs = longValue(signalDelta, "currentTotalDurationMs");
        if (!hasPositiveMetric(baselineEventCount, currentEventCount, baselineTotalDurationMs, currentTotalDurationMs)) {
            return;
        }

        if (hasPositiveMetric(baselineTotalDurationMs, currentTotalDurationMs)) {
            parts.add(String.format(
                Locale.ROOT,
                "%s %d -> %d (total %s -> %s)",
                label,
                baselineEventCount,
                currentEventCount,
                humanDuration(baselineTotalDurationMs),
                humanDuration(currentTotalDurationMs)
            ));
            return;
        }

        parts.add(String.format(Locale.ROOT, "%s %d -> %d", label, baselineEventCount, currentEventCount));
    }

    private void appendJfrComparisonAllocationLine(List<String> summaryLines, Map<String, Object> allocationDelta) {
        long baselineTotalAllocatedBytes = longValue(allocationDelta, "baselineTotalAllocatedBytes");
        long currentTotalAllocatedBytes = longValue(allocationDelta, "currentTotalAllocatedBytes");
        long baselineEventCount = longValue(allocationDelta, "baselineEventCount");
        long currentEventCount = longValue(allocationDelta, "currentEventCount");
        String baselineTopClass = stringValue(allocationDelta.get("baselineTopClass"));
        String currentTopClass = stringValue(allocationDelta.get("currentTopClass"));
        String baselineTopMethod = stringValue(allocationDelta.get("baselineTopMethod"));
        String currentTopMethod = stringValue(allocationDelta.get("currentTopMethod"));
        if (!hasPositiveMetric(baselineTotalAllocatedBytes, currentTotalAllocatedBytes, baselineEventCount, currentEventCount)
            && !hasText(baselineTopClass)
            && !hasText(currentTopClass)
            && !hasText(baselineTopMethod)
            && !hasText(currentTopMethod)) {
            return;
        }

        StringBuilder line = new StringBuilder(String.format(
            Locale.ROOT,
            "Allocation pressure: %s -> %s",
            humanBytes(baselineTotalAllocatedBytes),
            humanBytes(currentTotalAllocatedBytes)
        ));
        if (hasPositiveMetric(baselineEventCount, currentEventCount)) {
            line.append("; events ").append(baselineEventCount).append(" -> ").append(currentEventCount);
        }
        if (hasText(baselineTopClass) && hasText(currentTopClass)) {
            line.append("; top class ").append(baselineTopClass).append(" -> ").append(currentTopClass);
        } else if (hasText(currentTopClass)) {
            line.append("; current top class ").append(currentTopClass);
        }
        if (hasText(baselineTopMethod) && hasText(currentTopMethod) && !baselineTopMethod.equals(currentTopMethod)) {
            line.append("; dominant path ").append(baselineTopMethod).append(" -> ").append(currentTopMethod);
        } else if (hasText(currentTopMethod)) {
            line.append("; dominant path ").append(currentTopMethod);
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrComparisonRetentionLine(List<String> summaryLines, Map<String, Object> retentionDelta) {
        long baselineTotalSampledObjectBytes = longValue(retentionDelta, "baselineTotalSampledObjectBytes");
        long currentTotalSampledObjectBytes = longValue(retentionDelta, "currentTotalSampledObjectBytes");
        long baselineMaxObjectAgeMs = longValue(retentionDelta, "baselineMaxObjectAgeMs");
        long currentMaxObjectAgeMs = longValue(retentionDelta, "currentMaxObjectAgeMs");
        long baselineMaxReferenceDepth = longValue(retentionDelta, "baselineMaxReferenceDepth");
        long currentMaxReferenceDepth = longValue(retentionDelta, "currentMaxReferenceDepth");
        String baselineTopClass = stringValue(retentionDelta.get("baselineTopClass"));
        String currentTopClass = stringValue(retentionDelta.get("currentTopClass"));
        if (!hasPositiveMetric(
            baselineTotalSampledObjectBytes,
            currentTotalSampledObjectBytes,
            baselineMaxObjectAgeMs,
            currentMaxObjectAgeMs,
            baselineMaxReferenceDepth,
            currentMaxReferenceDepth
        ) && !hasText(baselineTopClass) && !hasText(currentTopClass)) {
            return;
        }

        StringBuilder line = new StringBuilder(String.format(
            Locale.ROOT,
            "Retained-object signals: %s -> %s",
            humanBytes(baselineTotalSampledObjectBytes),
            humanBytes(currentTotalSampledObjectBytes)
        ));
        if (hasText(baselineTopClass) && hasText(currentTopClass)) {
            line.append("; top class ").append(baselineTopClass).append(" -> ").append(currentTopClass);
        } else if (hasText(currentTopClass)) {
            line.append("; current top class ").append(currentTopClass);
        }
        if (hasPositiveMetric(baselineMaxObjectAgeMs, currentMaxObjectAgeMs)) {
            line.append("; max age ").append(humanDuration(baselineMaxObjectAgeMs)).append(" -> ").append(humanDuration(currentMaxObjectAgeMs));
        }
        if (hasPositiveMetric(baselineMaxReferenceDepth, currentMaxReferenceDepth)) {
            line.append("; max depth ").append(baselineMaxReferenceDepth).append(" -> ").append(currentMaxReferenceDepth);
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private String jfrHotspotLabel(String kind, String method) {
        if (!hasText(kind)) {
            return method;
        }
        if (!hasText(method)) {
            return kind;
        }
        return kind + ":" + method;
    }

    private double topSharePercent(Map<String, Object> summary, String byteShareKey, String eventShareKey) {
        double share = firstPositiveDouble(summary, byteShareKey, summary, eventShareKey);
        return share > 0.0d ? share * 100.0d : 0.0d;
    }

    private Map<String, Object> jfrSingleArtifactSummary(IndexedArtifactDiagnosticContext indexedContext) {
        ParsedArtifact parsedArtifact = indexedContext != null ? indexedContext.parsedArtifact() : null;
        if (parsedArtifact == null) {
            return Map.of();
        }

        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        Map<String, Object> lockSummary = mapValue(parsedArtifact.extractedData().get("lockSummary"));
        Map<String, Object> gcSummary = mapValue(parsedArtifact.extractedData().get("gcSummary"));
        Map<String, Object> threadParkSummary = mapValue(parsedArtifact.extractedData().get("threadParkSummary"));
        Map<String, Object> ioSummary = mapValue(parsedArtifact.extractedData().get("ioSummary"));
        Map<String, Object> exceptionSummary = mapValue(parsedArtifact.extractedData().get("exceptionSummary"));
        Map<String, Object> safepointSummary = mapValue(parsedArtifact.extractedData().get("safepointSummary"));
        Map<String, Object> classLoadingSummary = mapValue(parsedArtifact.extractedData().get("classLoadingSummary"));
        Map<String, Object> codeCacheSummary = mapValue(parsedArtifact.extractedData().get("codeCacheSummary"));
        Map<String, Object> allocationFieldSummary = mapValue(parsedArtifact.extractedData().get("allocationFieldSummary"));
        Map<String, Object> allocationHotspotSummary = mapValue(parsedArtifact.extractedData().get("allocationHotspotSummary"));
        Map<String, Object> oldObjectFieldSummary = mapValue(parsedArtifact.extractedData().get("oldObjectFieldSummary"));
        Map<String, Object> executionHotspotSummary = mapValue(parsedArtifact.extractedData().get("executionHotspotSummary"));
        Map<String, Object> runtimeHotspotSummary = mapValue(parsedArtifact.extractedData().get("runtimeHotspotSummary"));
        Map<String, Object> incidentWindowSummary = mapValue(parsedArtifact.extractedData().get("incidentWindowSummary"));

        List<String> summaryLines = new ArrayList<>();
        appendJfrRecordingWindowLine(summaryLines, summary);
        appendJfrExecutionHotspotLine(summaryLines, executionHotspotSummary);
        appendJfrRuntimeHotspotLine(summaryLines, runtimeHotspotSummary);
        appendJfrRuntimePressureLine(summaryLines, lockSummary, gcSummary, threadParkSummary, ioSummary, exceptionSummary, safepointSummary);
        appendJfrClassLoadingLine(summaryLines, classLoadingSummary);
        appendJfrCodeCacheLine(summaryLines, codeCacheSummary);
        appendJfrAllocationSynopsisLine(summaryLines, allocationFieldSummary, allocationHotspotSummary);
        appendJfrOldObjectSynopsisLine(summaryLines, oldObjectFieldSummary);
        appendJfrIncidentWindowLine(summaryLines, incidentWindowSummary);

        LinkedHashMap<String, Object> synopsis = new LinkedHashMap<>();
        if (!summaryLines.isEmpty()) {
            synopsis.put("summaryLines", List.copyOf(summaryLines.stream().limit(8).toList()));
        }

        Map<String, Object> dominantHotspot = jfrDominantHotspot(
            executionHotspotSummary,
            runtimeHotspotSummary,
            allocationHotspotSummary
        );
        if (!dominantHotspot.isEmpty()) {
            synopsis.put("dominantHotspot", dominantHotspot);
        }

        Map<String, Object> runtimeSignals = jfrRuntimeSignalsSnapshot(
            lockSummary,
            gcSummary,
            threadParkSummary,
            ioSummary,
            exceptionSummary,
            safepointSummary
        );
        if (!runtimeSignals.isEmpty()) {
            synopsis.put("runtimeSignals", runtimeSignals);
        }

        Map<String, Object> memorySignals = jfrMemorySignalsSnapshot(
            classLoadingSummary,
            codeCacheSummary,
            allocationFieldSummary,
            allocationHotspotSummary,
            oldObjectFieldSummary
        );
        if (!memorySignals.isEmpty()) {
            synopsis.put("memorySignals", memorySignals);
        }

        Map<String, Object> primaryIncident = mapValue(incidentWindowSummary.get("primaryIncident"));
        if (!primaryIncident.isEmpty()) {
            synopsis.put("primaryIncidentWindow", primaryIncident);
        }

        return synopsis.isEmpty() ? Map.of() : Map.copyOf(synopsis);
    }

    private void appendJfrRecordingWindowLine(List<String> summaryLines, Map<String, Object> summary) {
        long durationMs = longValue(summary, "durationMs");
        long eventCount = longValue(summary, "eventCount");
        long observedEventTypeCount = longValue(summary, "observedEventTypeCount");
        if (!hasPositiveMetric(durationMs, eventCount, observedEventTypeCount)) {
            return;
        }
        summaryLines.add(String.format(
            Locale.ROOT,
            "Recording window: %s; %d event(s) across %d observed event type(s).",
            durationMs > 0L ? humanDuration(durationMs) : "unknown duration",
            eventCount,
            observedEventTypeCount
        ));
    }

    private void appendJfrExecutionHotspotLine(List<String> summaryLines, Map<String, Object> executionHotspotSummary) {
        String topMethod = stringValue(executionHotspotSummary.get("topMethod"));
        long stackEventCount = longValue(executionHotspotSummary, "stackEventCount");
        long topMethodCount = longValue(executionHotspotSummary, "topMethodCount");
        double topMethodShare = doubleValue(executionHotspotSummary, "topMethodShare");
        if (!hasText(topMethod) || !hasPositiveMetric(stackEventCount, topMethodCount, topMethodShare)) {
            return;
        }
        summaryLines.add(String.format(
            Locale.ROOT,
            "Execution hotspot: %s dominates %d of %d sampled stack(s) (%s).",
            topMethod,
            topMethodCount,
            stackEventCount,
            humanRatioPercent(topMethodShare)
        ));
    }

    private void appendJfrRuntimeHotspotLine(List<String> summaryLines, Map<String, Object> runtimeHotspotSummary) {
        String topMethod = stringValue(runtimeHotspotSummary.get("topMethod"));
        long stackEventCount = longValue(runtimeHotspotSummary, "stackEventCount");
        long topMethodCount = longValue(runtimeHotspotSummary, "topMethodCount");
        double topMethodShare = doubleValue(runtimeHotspotSummary, "topMethodShare");
        if (!hasText(topMethod) || !hasPositiveMetric(stackEventCount, topMethodCount, topMethodShare)) {
            return;
        }
        summaryLines.add(String.format(
            Locale.ROOT,
            "Runtime hotspot: %s dominates %d of %d stack-bearing wait/latency event(s) (%s).",
            topMethod,
            topMethodCount,
            stackEventCount,
            humanRatioPercent(topMethodShare)
        ));
    }

    private void appendJfrRuntimePressureLine(
        List<String> summaryLines,
        Map<String, Object> lockSummary,
        Map<String, Object> gcSummary,
        Map<String, Object> threadParkSummary,
        Map<String, Object> ioSummary,
        Map<String, Object> exceptionSummary,
        Map<String, Object> safepointSummary
    ) {
        List<String> parts = new ArrayList<>();
        appendJfrSignalPart(parts, "GC pauses", gcSummary);
        appendJfrSignalPart(parts, "monitor blocks", lockSummary);
        appendJfrSignalPart(parts, "thread parks", threadParkSummary);
        appendJfrSignalPart(parts, "I/O latency", ioSummary);

        long exceptionEventCount = longValue(exceptionSummary, "eventCount");
        if (exceptionEventCount > 0L) {
            parts.add(String.format(Locale.ROOT, "exceptions %d", exceptionEventCount));
        }

        appendJfrSignalPart(parts, "safepoints", safepointSummary);
        if (parts.isEmpty()) {
            return;
        }

        summaryLines.add("Runtime pressure: " + String.join("; ", parts) + ".");
    }

    private void appendJfrClassLoadingLine(List<String> summaryLines, Map<String, Object> classLoadingSummary) {
        long eventCount = longValue(classLoadingSummary, "eventCount");
        long definedClassCount = longValue(classLoadingSummary, "definedClassCount");
        long totalMetadataBytes = longValue(classLoadingSummary, "totalMetadataBytes");
        String topLoader = stringValue(classLoadingSummary.get("topLoader"));
        String topPackage = stringValue(classLoadingSummary.get("topPackage"));
        if (!hasPositiveMetric(eventCount, definedClassCount, totalMetadataBytes) && !hasText(topLoader) && !hasText(topPackage)) {
            return;
        }

        StringBuilder line = new StringBuilder("Class loading: ");
        if (eventCount > 0L) {
            line.append(eventCount).append(" event(s)");
        } else {
            line.append("class-definition activity");
        }
        if (definedClassCount > 0L) {
            line.append("; ").append(definedClassCount).append(" defined class(es)");
        }
        if (totalMetadataBytes > 0L) {
            line.append("; about ").append(humanBytes(totalMetadataBytes)).append(" attributed metadata");
        }
        if (hasText(topLoader)) {
            line.append("; top loader ").append(topLoader);
        }
        if (hasText(topPackage)) {
            line.append("; top package ").append(topPackage);
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrCodeCacheLine(List<String> summaryLines, Map<String, Object> codeCacheSummary) {
        long eventCount = longValue(codeCacheSummary, "eventCount");
        long codeCacheFullEventCount = longValue(codeCacheSummary, "codeCacheFullEventCount");
        long peakCodeCacheUsedBytes = longValue(codeCacheSummary, "peakCodeCacheUsedBytes");
        long peakCodeCacheCapacityBytes = longValue(codeCacheSummary, "peakCodeCacheCapacityBytes");
        long minCodeCacheFreeBytes = longValue(codeCacheSummary, "minCodeCacheFreeBytes");
        long maxCompilationQueueSize = longValue(codeCacheSummary, "maxCompilationQueueSize");
        String topCompiler = stringValue(codeCacheSummary.get("topCompiler"));
        String topCompilationMethod = stringValue(codeCacheSummary.get("topCompilationMethod"));
        if (!hasPositiveMetric(eventCount, codeCacheFullEventCount, peakCodeCacheUsedBytes, peakCodeCacheCapacityBytes, minCodeCacheFreeBytes, maxCompilationQueueSize)
            && !hasText(topCompiler)
            && !hasText(topCompilationMethod)
            && !Boolean.TRUE.equals(codeCacheSummary.get("compilerDisabled"))) {
            return;
        }

        StringBuilder line = new StringBuilder("Code cache: ");
        if (peakCodeCacheUsedBytes > 0L && peakCodeCacheCapacityBytes > 0L) {
            line.append(humanBytes(peakCodeCacheUsedBytes)).append(" used of ").append(humanBytes(peakCodeCacheCapacityBytes));
        } else if (eventCount > 0L) {
            line.append(eventCount).append(" compilation/code-cache event(s)");
        } else {
            line.append("code-cache activity");
        }
        if (codeCacheFullEventCount > 0L) {
            line.append("; ").append(codeCacheFullEventCount).append(" code-cache-full event(s)");
        }
        if (minCodeCacheFreeBytes > 0L) {
            line.append("; min free ").append(humanBytes(minCodeCacheFreeBytes));
        }
        if (maxCompilationQueueSize > 0L) {
            line.append("; queue peak ").append(maxCompilationQueueSize);
        }
        if (hasText(topCompiler)) {
            line.append("; compiler ").append(topCompiler);
        }
        if (hasText(topCompilationMethod)) {
            line.append("; top method ").append(topCompilationMethod);
        }
        if (Boolean.TRUE.equals(codeCacheSummary.get("compilerDisabled"))) {
            line.append("; compiler disabled");
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrSignalPart(List<String> parts, String label, Map<String, Object> signalSummary) {
        long eventCount = longValue(signalSummary, "eventCount");
        long maxDurationMs = longValue(signalSummary, "maxDurationMs");
        long totalDurationMs = longValue(signalSummary, "totalDurationMs");
        if (!hasPositiveMetric(eventCount, maxDurationMs, totalDurationMs)) {
            return;
        }

        if (hasPositiveMetric(maxDurationMs, totalDurationMs)) {
            parts.add(String.format(
                Locale.ROOT,
                "%s %d (max %s, total %s)",
                label,
                eventCount,
                humanDuration(maxDurationMs),
                humanDuration(totalDurationMs)
            ));
            return;
        }

        parts.add(String.format(Locale.ROOT, "%s %d", label, eventCount));
    }

    private void appendJfrAllocationSynopsisLine(
        List<String> summaryLines,
        Map<String, Object> allocationFieldSummary,
        Map<String, Object> allocationHotspotSummary
    ) {
        long eventCount = longValue(allocationFieldSummary, "eventCount");
        long totalAllocatedBytes = longValue(allocationFieldSummary, "totalAllocatedBytes");
        String topClass = stringValue(allocationFieldSummary.get("topClass"));
        String topMethod = firstNonBlank(
            stringValue(allocationHotspotSummary.get("topMethod")),
            stringValue(allocationHotspotSummary.get("topMethodByBytes"))
        );
        if (eventCount == 0L && totalAllocatedBytes == 0L && !hasText(topClass) && !hasText(topMethod)) {
            return;
        }

        StringBuilder line = new StringBuilder("Allocation pressure: ");
        if (totalAllocatedBytes > 0L) {
            line.append("about ").append(humanBytes(totalAllocatedBytes));
        } else {
            line.append("sampled allocation activity");
        }
        if (eventCount > 0L) {
            line.append(" across ").append(eventCount).append(" event(s)");
        }
        if (hasText(topClass)) {
            line.append("; top class ").append(topClass);
        }
        if (hasText(topMethod)) {
            line.append("; dominant allocation path ").append(topMethod);
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrOldObjectSynopsisLine(List<String> summaryLines, Map<String, Object> oldObjectFieldSummary) {
        long eventCount = longValue(oldObjectFieldSummary, "eventCount");
        long totalSampledObjectBytes = longValue(oldObjectFieldSummary, "totalSampledObjectBytes");
        String topClass = stringValue(oldObjectFieldSummary.get("topClass"));
        long maxObjectAgeMs = longValue(oldObjectFieldSummary, "maxObjectAgeMs");
        long maxReferenceDepth = longValue(oldObjectFieldSummary, "maxReferenceDepth");
        String topRootType = stringValue(oldObjectFieldSummary.get("topRootType"));
        String topRootSystem = stringValue(oldObjectFieldSummary.get("topRootSystem"));
        if (!hasPositiveMetric(eventCount, totalSampledObjectBytes, maxObjectAgeMs, maxReferenceDepth)
            && !hasText(topClass)
            && !hasText(topRootType)
            && !hasText(topRootSystem)) {
            return;
        }

        StringBuilder line = new StringBuilder("Retained-object signals: ");
        if (totalSampledObjectBytes > 0L) {
            line.append("about ").append(humanBytes(totalSampledObjectBytes));
        } else {
            line.append("retained-object coverage");
        }
        if (eventCount > 0L) {
            line.append(" across ").append(eventCount).append(" old-object sample(s)");
        }
        if (hasText(topClass)) {
            line.append("; top class ").append(topClass);
        }
        if (maxObjectAgeMs > 0L) {
            line.append("; max age ").append(humanDuration(maxObjectAgeMs));
        }
        if (maxReferenceDepth > 0L) {
            line.append("; max reference depth ").append(maxReferenceDepth);
        }
        if (hasText(topRootType) || hasText(topRootSystem)) {
            line.append("; root ");
            line.append(hasText(topRootType) ? topRootType : "unknown");
            if (hasText(topRootSystem)) {
                line.append('/').append(topRootSystem);
            }
        }
        line.append('.');
        summaryLines.add(line.toString());
    }

    private void appendJfrIncidentWindowLine(List<String> summaryLines, Map<String, Object> incidentWindowSummary) {
        Map<String, Object> primaryIncident = mapValue(incidentWindowSummary.get("primaryIncident"));
        String summaryLine = stringValue(primaryIncident.get("summaryLine"));
        if (summaryLine.isBlank()) {
            List<?> summaryLinesList = incidentWindowSummary.get("summaryLines") instanceof List<?> list ? list : List.of();
            if (!summaryLinesList.isEmpty()) {
                summaryLine = stringValue(summaryLinesList.getFirst());
            }
        }
        if (!summaryLine.isBlank()) {
            summaryLines.add("Incident window: " + summaryLine);
        }
    }

    private Map<String, Object> jfrDominantHotspot(
        Map<String, Object> executionHotspotSummary,
        Map<String, Object> runtimeHotspotSummary,
        Map<String, Object> allocationHotspotSummary
    ) {
        JfrHotspotCandidate best = null;
        best = chooseHotspot(best, jfrHotspotCandidate("execution", executionHotspotSummary, 1));
        best = chooseHotspot(best, jfrHotspotCandidate("runtime", runtimeHotspotSummary, 2));
        best = chooseHotspot(best, jfrHotspotCandidate("allocation", allocationHotspotSummary, 3));
        if (best == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> hotspot = new LinkedHashMap<>();
        hotspot.put("kind", best.kind());
        hotspot.put("topMethod", best.topMethod());
        hotspot.put("topMethodCount", best.topMethodCount());
        hotspot.put("eventCount", best.eventCount());
        hotspot.put("sharePct", round(best.share() * 100.0d));
        if (best.allocatedBytes() > 0L) {
            hotspot.put("allocatedBytes", best.allocatedBytes());
        }
        if (hasText(best.topStack())) {
            hotspot.put("topStack", best.topStack());
        }
        return Map.copyOf(hotspot);
    }

    private JfrHotspotCandidate chooseHotspot(JfrHotspotCandidate current, JfrHotspotCandidate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        int shareCompare = Double.compare(candidate.share(), current.share());
        if (shareCompare > 0) {
            return candidate;
        }
        if (shareCompare < 0) {
            return current;
        }
        int countCompare = Long.compare(candidate.eventCount(), current.eventCount());
        if (countCompare > 0) {
            return candidate;
        }
        if (countCompare < 0) {
            return current;
        }
        return candidate.priority() < current.priority() ? candidate : current;
    }

    private JfrHotspotCandidate jfrHotspotCandidate(String kind, Map<String, Object> hotspotSummary, int priority) {
        String topMethod = stringValue(hotspotSummary.get("topMethod"));
        long topMethodCount = longValue(hotspotSummary, "topMethodCount");
        long eventCount = longValue(hotspotSummary, "stackEventCount");
        double share = doubleValue(hotspotSummary, "topMethodShare");
        long allocatedBytes = 0L;
        if ("allocation".equals(kind)) {
            share = Math.max(share, doubleValue(hotspotSummary, "topMethodAllocatedByteShare"));
            allocatedBytes = longValue(hotspotSummary, "topMethodAllocatedBytes");
        }
        if (!hasText(topMethod) || !hasPositiveMetric(topMethodCount, eventCount, share, allocatedBytes)) {
            return null;
        }
        return new JfrHotspotCandidate(
            kind,
            topMethod,
            topMethodCount,
            eventCount,
            share,
            stringValue(hotspotSummary.get("topStack")),
            allocatedBytes,
            priority
        );
    }

    private Map<String, Object> jfrRuntimeSignalsSnapshot(
        Map<String, Object> lockSummary,
        Map<String, Object> gcSummary,
        Map<String, Object> threadParkSummary,
        Map<String, Object> ioSummary,
        Map<String, Object> exceptionSummary,
        Map<String, Object> safepointSummary
    ) {
        LinkedHashMap<String, Object> runtimeSignals = new LinkedHashMap<>();
        putJfrSignalSnapshot(runtimeSignals, "gcPause", gcSummary);
        putJfrSignalSnapshot(runtimeSignals, "lockContention", lockSummary);
        putJfrSignalSnapshot(runtimeSignals, "threadPark", threadParkSummary);
        putJfrSignalSnapshot(runtimeSignals, "ioLatency", ioSummary);
        putJfrSignalSnapshot(runtimeSignals, "exceptionBurst", exceptionSummary);
        putJfrSignalSnapshot(runtimeSignals, "safepointPause", safepointSummary);
        return runtimeSignals.isEmpty() ? Map.of() : Map.copyOf(runtimeSignals);
    }

    private void putJfrSignalSnapshot(Map<String, Object> target, String key, Map<String, Object> signalSummary) {
        long eventCount = longValue(signalSummary, "eventCount");
        long maxDurationMs = longValue(signalSummary, "maxDurationMs");
        long totalDurationMs = longValue(signalSummary, "totalDurationMs");
        if (!hasPositiveMetric(eventCount, maxDurationMs, totalDurationMs)) {
            return;
        }

        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("eventCount", eventCount);
        putIfPositiveLong(snapshot, "maxDurationMs", maxDurationMs);
        putIfPositiveLong(snapshot, "totalDurationMs", totalDurationMs);
        target.put(key, Map.copyOf(snapshot));
    }

    private Map<String, Object> jfrMemorySignalsSnapshot(
        Map<String, Object> classLoadingSummary,
        Map<String, Object> codeCacheSummary,
        Map<String, Object> allocationFieldSummary,
        Map<String, Object> allocationHotspotSummary,
        Map<String, Object> oldObjectFieldSummary
    ) {
        LinkedHashMap<String, Object> memorySignals = new LinkedHashMap<>();

        long classLoadingEventCount = longValue(classLoadingSummary, "eventCount");
        long definedClassCount = longValue(classLoadingSummary, "definedClassCount");
        long totalMetadataBytes = longValue(classLoadingSummary, "totalMetadataBytes");
        String topLoader = stringValue(classLoadingSummary.get("topLoader"));
        String topPackage = stringValue(classLoadingSummary.get("topPackage"));
        if (hasPositiveMetric(classLoadingEventCount, definedClassCount, totalMetadataBytes) || hasText(topLoader) || hasText(topPackage)) {
            LinkedHashMap<String, Object> classLoading = new LinkedHashMap<>();
            putIfPositiveLong(classLoading, "eventCount", classLoadingEventCount);
            putIfPositiveLong(classLoading, "definedClassCount", definedClassCount);
            putIfPositiveLong(classLoading, "totalMetadataBytes", totalMetadataBytes);
            if (hasText(topLoader)) {
                classLoading.put("topLoader", topLoader);
            }
            if (hasText(topPackage)) {
                classLoading.put("topPackage", topPackage);
            }
            memorySignals.put("classLoading", Map.copyOf(classLoading));
        }

        long codeCacheEventCount = longValue(codeCacheSummary, "eventCount");
        long peakCodeCacheUsedBytes = longValue(codeCacheSummary, "peakCodeCacheUsedBytes");
        long peakCodeCacheCapacityBytes = longValue(codeCacheSummary, "peakCodeCacheCapacityBytes");
        long minCodeCacheFreeBytes = longValue(codeCacheSummary, "minCodeCacheFreeBytes");
        long maxCompilationQueueSize = longValue(codeCacheSummary, "maxCompilationQueueSize");
        String topCompiler = stringValue(codeCacheSummary.get("topCompiler"));
        String topCompilationMethod = stringValue(codeCacheSummary.get("topCompilationMethod"));
        if (hasPositiveMetric(codeCacheEventCount, peakCodeCacheUsedBytes, peakCodeCacheCapacityBytes, minCodeCacheFreeBytes, maxCompilationQueueSize)
            || hasText(topCompiler)
            || hasText(topCompilationMethod)
            || Boolean.TRUE.equals(codeCacheSummary.get("compilerDisabled"))) {
            LinkedHashMap<String, Object> codeCache = new LinkedHashMap<>();
            putIfPositiveLong(codeCache, "eventCount", codeCacheEventCount);
            putIfPositiveLong(codeCache, "peakCodeCacheUsedBytes", peakCodeCacheUsedBytes);
            putIfPositiveLong(codeCache, "peakCodeCacheCapacityBytes", peakCodeCacheCapacityBytes);
            putIfPositiveLong(codeCache, "minCodeCacheFreeBytes", minCodeCacheFreeBytes);
            putIfPositiveLong(codeCache, "maxCompilationQueueSize", maxCompilationQueueSize);
            if (hasText(topCompiler)) {
                codeCache.put("topCompiler", topCompiler);
            }
            if (hasText(topCompilationMethod)) {
                codeCache.put("topCompilationMethod", topCompilationMethod);
            }
            if (Boolean.TRUE.equals(codeCacheSummary.get("compilerDisabled"))) {
                codeCache.put("compilerDisabled", true);
            }
            memorySignals.put("codeCache", Map.copyOf(codeCache));
        }

        long allocationEventCount = longValue(allocationFieldSummary, "eventCount");
        long totalAllocatedBytes = longValue(allocationFieldSummary, "totalAllocatedBytes");
        String allocationTopClass = stringValue(allocationFieldSummary.get("topClass"));
        String allocationTopMethod = firstNonBlank(
            stringValue(allocationHotspotSummary.get("topMethod")),
            stringValue(allocationHotspotSummary.get("topMethodByBytes"))
        );
        if (hasPositiveMetric(allocationEventCount, totalAllocatedBytes) || hasText(allocationTopClass) || hasText(allocationTopMethod)) {
            LinkedHashMap<String, Object> allocation = new LinkedHashMap<>();
            putIfPositiveLong(allocation, "eventCount", allocationEventCount);
            putIfPositiveLong(allocation, "totalAllocatedBytes", totalAllocatedBytes);
            if (hasText(allocationTopClass)) {
                allocation.put("topClass", allocationTopClass);
            }
            if (hasText(allocationTopMethod)) {
                allocation.put("topMethod", allocationTopMethod);
            }
            memorySignals.put("allocation", Map.copyOf(allocation));
        }

        long oldObjectEventCount = longValue(oldObjectFieldSummary, "eventCount");
        long totalSampledObjectBytes = longValue(oldObjectFieldSummary, "totalSampledObjectBytes");
        String oldObjectTopClass = stringValue(oldObjectFieldSummary.get("topClass"));
        long maxObjectAgeMs = longValue(oldObjectFieldSummary, "maxObjectAgeMs");
        long maxReferenceDepth = longValue(oldObjectFieldSummary, "maxReferenceDepth");
        String topRootType = stringValue(oldObjectFieldSummary.get("topRootType"));
        String topRootSystem = stringValue(oldObjectFieldSummary.get("topRootSystem"));
        if (hasPositiveMetric(oldObjectEventCount, totalSampledObjectBytes, maxObjectAgeMs, maxReferenceDepth)
            || hasText(oldObjectTopClass)
            || hasText(topRootType)
            || hasText(topRootSystem)) {
            LinkedHashMap<String, Object> oldObjects = new LinkedHashMap<>();
            putIfPositiveLong(oldObjects, "eventCount", oldObjectEventCount);
            putIfPositiveLong(oldObjects, "totalSampledObjectBytes", totalSampledObjectBytes);
            if (hasText(oldObjectTopClass)) {
                oldObjects.put("topClass", oldObjectTopClass);
            }
            putIfPositiveLong(oldObjects, "maxObjectAgeMs", maxObjectAgeMs);
            putIfPositiveLong(oldObjects, "maxReferenceDepth", maxReferenceDepth);
            if (hasText(topRootType)) {
                oldObjects.put("topRootType", topRootType);
            }
            if (hasText(topRootSystem)) {
                oldObjects.put("topRootSystem", topRootSystem);
            }
            memorySignals.put("oldObjects", Map.copyOf(oldObjects));
        }

        return memorySignals.isEmpty() ? Map.of() : Map.copyOf(memorySignals);
    }

    private Map<String, Object> gcDominantPauseCauseShift(String baselineCause, String currentCause) {
        if (!hasText(baselineCause) && !hasText(currentCause)) {
            return Map.of();
        }

        LinkedHashMap<String, Object> shift = new LinkedHashMap<>();
        if (hasText(baselineCause)) {
            shift.put("baselineDominantPauseCause", baselineCause);
        }
        if (hasText(currentCause)) {
            shift.put("currentDominantPauseCause", currentCause);
        }
        shift.put("changed", !firstNonBlank(baselineCause, "").equals(firstNonBlank(currentCause, "")));
        return Map.copyOf(shift);
    }

    private Map<String, Object> gcSingleArtifactSummary(IndexedArtifactDiagnosticContext indexedContext) {
        ParsedArtifact parsedArtifact = indexedContext != null ? indexedContext.parsedArtifact() : null;
        if (parsedArtifact == null || parsedArtifact.type() != ArtifactType.GC_LOG) {
            return Map.of();
        }

        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        Map<String, Object> pressure = mapValue(parsedArtifact.extractedData().get("collectorPressureSummary"));
        Map<String, Object> recovery = mapValue(parsedArtifact.extractedData().get("recoverySummary"));
        Map<String, Object> failure = mapValue(parsedArtifact.extractedData().get("failureSummary"));
        Map<String, Object> g1 = mapValue(parsedArtifact.extractedData().get("g1CycleProgressionSummary"));
        Map<String, Object> concurrent = mapValue(parsedArtifact.extractedData().get("concurrentSummary"));
        Map<String, Object> pauseBreakdown = mapValue(parsedArtifact.extractedData().get("pauseBreakdown"));

        if (summary.isEmpty() && pressure.isEmpty() && recovery.isEmpty()) {
            return Map.of();
        }

        String collector = collectorName(parsedArtifact, pressure);
        String dominantPauseCause = firstNonBlank(
            stringValue(pressure.get("dominantPauseCauseByTotalPauseMs")),
            stringValue(pauseBreakdown.get("dominantPauseCauseByTotalPauseMs"))
        );
        String dominantPauseCauseByCount = stringValue(pauseBreakdown.get("dominantPauseCauseByCount"));

        List<String> summaryLines = new ArrayList<>();

        double p95PauseMs = firstPositiveDouble(pressure, "p95PauseMs", summary, "p95PauseMs");
        double maxPauseMs = firstPositiveDouble(pressure, "maxPauseMs", summary, "maxPauseMs");
        double stopTheWorldOverheadPct = doubleValue(summary, "stopTheWorldOverheadPct");
        if (hasPositiveMetric(p95PauseMs, maxPauseMs, stopTheWorldOverheadPct)) {
            summaryLines.add(String.format(
                Locale.ROOT,
                "Pause profile: p95 %s; max %s; stop-the-world overhead %s.",
                humanMs(p95PauseMs),
                humanMs(maxPauseMs),
                humanPercent(stopTheWorldOverheadPct)
            ));
        }

        String normalizedCollector = hasText(collector) ? collector.toUpperCase(Locale.ROOT) : "";
        if ("ZGC".equals(normalizedCollector)) {
            appendSingleArtifactZgcSynopsisLine(summaryLines, pressure, summary, concurrent);
        } else {
            appendSingleArtifactFullGcSynopsisLine(summaryLines, pressure, summary);
        }

        appendSingleArtifactRecoverySynopsisLine(summaryLines, pressure, recovery);

        if (hasText(dominantPauseCause) || hasText(dominantPauseCauseByCount)) {
            summaryLines.add(String.format(
                Locale.ROOT,
                "Pause mix: dominant total-pause cause %s; dominant count cause %s.",
                humanTextOrNone(dominantPauseCause),
                humanTextOrNone(dominantPauseCauseByCount)
            ));
        }

        appendSingleArtifactCollectorSpecificSynopsisLine(
            summaryLines,
            normalizedCollector,
            pressure,
            summary,
            failure,
            g1,
            concurrent
        );

        LinkedHashMap<String, Object> synopsis = new LinkedHashMap<>();
        if (hasText(collector)) {
            synopsis.put("collector", collector);
        }
        if (!summaryLines.isEmpty()) {
            synopsis.put("summaryLines", List.copyOf(summaryLines.stream().limit(5).toList()));
        }

        Map<String, Object> dominantIncident = gcIncidentSnapshot(selectDominantGcIncidentSlice(indexedContext));
        if (!dominantIncident.isEmpty()) {
            synopsis.put("dominantIncident", dominantIncident);
        }
        return synopsis.isEmpty() ? Map.of() : Map.copyOf(synopsis);
    }

    private void appendSingleArtifactFullGcSynopsisLine(
        List<String> summaryLines,
        Map<String, Object> pressure,
        Map<String, Object> summary
    ) {
        long fullGcCount = firstPositiveLong(pressure, "fullGcCount", summary, "fullGcCount");
        double maxFullGcPauseMs = firstPositiveDouble(summary, "maxFullGcPauseMs", pressure, "maxPauseMs");
        long metaspaceTriggeredFullGcCount = firstPositiveLong(
            pressure,
            "metaspaceTriggeredFullGcCount",
            summary,
            "metaspaceTriggeredFullGcCount"
        );
        if (!hasPositiveMetric(fullGcCount, maxFullGcPauseMs, metaspaceTriggeredFullGcCount)) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Full-GC profile: count %d; longest full GC %s; metaspace-triggered full GCs %d.",
            fullGcCount,
            humanMs(maxFullGcPauseMs),
            metaspaceTriggeredFullGcCount
        ));
    }

    private void appendSingleArtifactZgcSynopsisLine(
        List<String> summaryLines,
        Map<String, Object> pressure,
        Map<String, Object> summary,
        Map<String, Object> concurrent
    ) {
        long allocationStallCount = firstPositiveLong(pressure, "allocationStallCount", summary, "allocationStallCount");
        double maxAllocationStallMs = firstPositiveDouble(pressure, "maxAllocationStallMs", summary, "maxAllocationStallMs");
        double totalAllocationStallMs = firstPositiveDouble(pressure, "totalAllocationStallMs", summary, "totalAllocationStallMs");
        double longestConcurrentPhaseMs = doubleValue(concurrent, "longestConcurrentPhaseMs");
        if (!hasPositiveMetric(allocationStallCount, maxAllocationStallMs, totalAllocationStallMs, longestConcurrentPhaseMs)) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Allocation-stall profile: count %d; max stall %s; total stall %s; longest concurrent phase %s.",
            allocationStallCount,
            humanMs(maxAllocationStallMs),
            humanMs(totalAllocationStallMs),
            humanMs(longestConcurrentPhaseMs)
        ));
    }

    private void appendSingleArtifactRecoverySynopsisLine(
        List<String> summaryLines,
        Map<String, Object> pressure,
        Map<String, Object> recovery
    ) {
        double peakPostGcOccupancyRatio = firstPositiveDouble(pressure, "peakPostGcOccupancyRatio", recovery, "peakPostGcOccupancyRatio");
        double averagePostGcOccupancyRatio = firstPositiveDouble(pressure, "averagePostGcOccupancyRatio", recovery, "averagePostGcOccupancyRatio");
        long nearCapacityAfterGcCount = firstPositiveLong(pressure, "nearCapacityAfterGcCount", recovery, "nearCapacityAfterGcCount");
        if (!hasPositiveMetric(peakPostGcOccupancyRatio, averagePostGcOccupancyRatio, nearCapacityAfterGcCount)) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Recovery shape: peak post-GC occupancy %s; average post-GC occupancy %s; near-capacity events %d.",
            humanRatioPercent(peakPostGcOccupancyRatio),
            humanRatioPercent(averagePostGcOccupancyRatio),
            nearCapacityAfterGcCount
        ));
    }

    private void appendSingleArtifactCollectorSpecificSynopsisLine(
        List<String> summaryLines,
        String normalizedCollector,
        Map<String, Object> pressure,
        Map<String, Object> summary,
        Map<String, Object> failure,
        Map<String, Object> g1,
        Map<String, Object> concurrent
    ) {
        switch (normalizedCollector) {
            case "G1" -> {
                long toSpaceExhaustedCount = longValue(failure, "toSpaceExhaustedCount");
                long fullCompactionAttemptCount = longValue(failure, "fullCompactionAttemptCount");
                long lowReclaimHighRetentionFullGcCount = longValue(g1, "lowReclaimHighRetentionFullGcCount");
                if (hasPositiveMetric(toSpaceExhaustedCount, fullCompactionAttemptCount, lowReclaimHighRetentionFullGcCount)) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "G1 distress signals: to-space exhausted %d; full compaction attempts %d; low-reclaim high-retention full GCs %d.",
                        toSpaceExhaustedCount,
                        fullCompactionAttemptCount,
                        lowReclaimHighRetentionFullGcCount
                    ));
                }
            }
            case "CMS" -> {
                long concurrentModeFailureCount = longValue(failure, "concurrentModeFailureCount");
                long promotionFailedCount = longValue(failure, "promotionFailedCount");
                double longestConcurrentPhaseMs = doubleValue(concurrent, "longestConcurrentPhaseMs");
                if (hasPositiveMetric(concurrentModeFailureCount, promotionFailedCount, longestConcurrentPhaseMs)) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "CMS fallback signals: concurrent mode failure %d; promotion failed %d; longest concurrent phase %s.",
                        concurrentModeFailureCount,
                        promotionFailedCount,
                        humanMs(longestConcurrentPhaseMs)
                    ));
                }
            }
            case "SERIAL", "PARALLEL" -> {
                long maxFullGcStreak = longValue(pressure, "maxFullGcStreak");
                double averageReclaimedMb = firstPositiveDouble(pressure, "averageReclaimedMb", Map.of(), "");
                if (hasPositiveMetric(maxFullGcStreak, averageReclaimedMb)) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "Stop-the-world recovery: max full-GC streak %d; average reclaimed heap %s.",
                        maxFullGcStreak,
                        humanMb(averageReclaimedMb)
                    ));
                }
            }
            case "ZGC" -> {
                long allocationStallCount = firstPositiveLong(pressure, "allocationStallCount", summary, "allocationStallCount");
                long maxAllocationStallStreak = longValue(pressure, "maxAllocationStallStreak");
                if (hasPositiveMetric(allocationStallCount, maxAllocationStallStreak)) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "ZGC stall signals: allocation stalls %d; max allocation-stall streak %d.",
                        allocationStallCount,
                        maxAllocationStallStreak
                    ));
                }
            }
            default -> {
            }
        }
    }

    private Map<String, Object> gcCauseMixPair(
        Map<String, Object> baselinePauseBreakdown,
        Map<String, Object> currentPauseBreakdown
    ) {
        Map<String, Object> baselineCauseMix = gcCauseMixSnapshot(baselinePauseBreakdown);
        Map<String, Object> currentCauseMix = gcCauseMixSnapshot(currentPauseBreakdown);
        if (baselineCauseMix.isEmpty() && currentCauseMix.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
        if (!baselineCauseMix.isEmpty()) {
            pair.put("baselineCauseMix", baselineCauseMix);
        }
        if (!currentCauseMix.isEmpty()) {
            pair.put("currentCauseMix", currentCauseMix);
        }
        return Map.copyOf(pair);
    }

    private Map<String, Object> gcCauseMixSnapshot(Map<String, Object> pauseBreakdown) {
        if (pauseBreakdown == null || pauseBreakdown.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        String dominantByTotalPause = stringValue(pauseBreakdown.get("dominantPauseCauseByTotalPauseMs"));
        String dominantByCount = stringValue(pauseBreakdown.get("dominantPauseCauseByCount"));
        if (hasText(dominantByTotalPause)) {
            snapshot.put("dominantPauseCauseByTotalPauseMs", dominantByTotalPause);
        }
        if (hasText(dominantByCount)) {
            snapshot.put("dominantPauseCauseByCount", dominantByCount);
        }

        List<Map<String, Object>> topCauses = topPauseCauses(pauseBreakdown, 3);
        if (!topCauses.isEmpty()) {
            snapshot.put("topPauseCausesByTotalPauseMs", topCauses);
        }

        Object observedPauseCauses = pauseBreakdown.get("observedPauseCauses");
        if (observedPauseCauses instanceof List<?> causes && !causes.isEmpty()) {
            snapshot.put("observedPauseCauseCount", causes.size());
        }
        return Map.copyOf(snapshot);
    }

    private Map<String, Object> gcRegressionSynopsis(
        String baselineCollector,
        String currentCollector,
        boolean sameCollector,
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineRecovery,
        Map<String, Object> currentRecovery,
        Map<String, Object> baselineFailure,
        Map<String, Object> currentFailure,
        Map<String, Object> baselineG1,
        Map<String, Object> currentG1,
        Map<String, Object> baselineConcurrent,
        Map<String, Object> currentConcurrent,
        String baselineDominantPauseCause,
        String currentDominantPauseCause
    ) {
        List<String> summaryLines = new ArrayList<>();

        double baselineP95PauseMs = firstPositiveDouble(baselinePressure, "p95PauseMs", baselineSummary, "p95PauseMs");
        double currentP95PauseMs = firstPositiveDouble(currentPressure, "p95PauseMs", currentSummary, "p95PauseMs");
        double baselineMaxPauseMs = firstPositiveDouble(baselinePressure, "maxPauseMs", baselineSummary, "maxPauseMs");
        double currentMaxPauseMs = firstPositiveDouble(currentPressure, "maxPauseMs", currentSummary, "maxPauseMs");
        double baselineStopTheWorldPct = doubleValue(baselineSummary, "stopTheWorldOverheadPct");
        double currentStopTheWorldPct = doubleValue(currentSummary, "stopTheWorldOverheadPct");
        if (hasPositiveMetric(baselineP95PauseMs, currentP95PauseMs, baselineMaxPauseMs, currentMaxPauseMs, baselineStopTheWorldPct, currentStopTheWorldPct)) {
            summaryLines.add(String.format(
                Locale.ROOT,
                "Pause profile: p95 %s -> %s; max %s -> %s; stop-the-world overhead %s -> %s.",
                humanMs(baselineP95PauseMs),
                humanMs(currentP95PauseMs),
                humanMs(baselineMaxPauseMs),
                humanMs(currentMaxPauseMs),
                humanPercent(baselineStopTheWorldPct),
                humanPercent(currentStopTheWorldPct)
            ));
        }

        String normalizedCollector = hasText(currentCollector)
            ? currentCollector.toUpperCase(Locale.ROOT)
            : hasText(baselineCollector) ? baselineCollector.toUpperCase(Locale.ROOT) : "";
        switch (normalizedCollector) {
            case "ZGC" -> appendZgcSynopsisLine(summaryLines, baselinePressure, currentPressure, baselineSummary, currentSummary);
            default -> appendFullGcSynopsisLine(summaryLines, baselinePressure, currentPressure, baselineSummary, currentSummary);
        }

        appendRecoverySynopsisLine(summaryLines, baselinePressure, currentPressure, baselineRecovery, currentRecovery);

        if (hasText(baselineDominantPauseCause) || hasText(currentDominantPauseCause)) {
            summaryLines.add(String.format(
                Locale.ROOT,
                "Pause mix: dominant total-pause cause %s -> %s.",
                humanTextOrNone(baselineDominantPauseCause),
                humanTextOrNone(currentDominantPauseCause)
            ));
        }

        if (sameCollector) {
            appendCollectorSpecificSynopsisLine(
                summaryLines,
                normalizedCollector,
                baselinePressure,
                currentPressure,
                baselineFailure,
                currentFailure,
                baselineG1,
                currentG1,
                baselineConcurrent,
                currentConcurrent
            );
        }

        if (summaryLines.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> synopsis = new LinkedHashMap<>();
        synopsis.put("summaryLines", List.copyOf(summaryLines.stream().limit(5).toList()));
        return Map.copyOf(synopsis);
    }

    private void appendFullGcSynopsisLine(
        List<String> summaryLines,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary
    ) {
        long baselineFullGcCount = firstPositiveLong(baselinePressure, "fullGcCount", baselineSummary, "fullGcCount");
        long currentFullGcCount = firstPositiveLong(currentPressure, "fullGcCount", currentSummary, "fullGcCount");
        double baselineMaxFullGcPauseMs = firstPositiveDouble(baselineSummary, "maxFullGcPauseMs", baselinePressure, "maxPauseMs");
        double currentMaxFullGcPauseMs = firstPositiveDouble(currentSummary, "maxFullGcPauseMs", currentPressure, "maxPauseMs");
        if (!hasPositiveMetric(baselineFullGcCount, currentFullGcCount, baselineMaxFullGcPauseMs, currentMaxFullGcPauseMs)) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Full-GC profile: count %d -> %d; longest full GC %s -> %s.",
            baselineFullGcCount,
            currentFullGcCount,
            humanMs(baselineMaxFullGcPauseMs),
            humanMs(currentMaxFullGcPauseMs)
        ));
    }

    private void appendZgcSynopsisLine(
        List<String> summaryLines,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineSummary,
        Map<String, Object> currentSummary
    ) {
        long baselineAllocationStallCount = firstPositiveLong(baselinePressure, "allocationStallCount", baselineSummary, "allocationStallCount");
        long currentAllocationStallCount = firstPositiveLong(currentPressure, "allocationStallCount", currentSummary, "allocationStallCount");
        double baselineMaxAllocationStallMs = firstPositiveDouble(baselinePressure, "maxAllocationStallMs", baselineSummary, "maxAllocationStallMs");
        double currentMaxAllocationStallMs = firstPositiveDouble(currentPressure, "maxAllocationStallMs", currentSummary, "maxAllocationStallMs");
        double baselineTotalAllocationStallMs = firstPositiveDouble(baselinePressure, "totalAllocationStallMs", baselineSummary, "totalAllocationStallMs");
        double currentTotalAllocationStallMs = firstPositiveDouble(currentPressure, "totalAllocationStallMs", currentSummary, "totalAllocationStallMs");
        if (!hasPositiveMetric(
            baselineAllocationStallCount,
            currentAllocationStallCount,
            baselineMaxAllocationStallMs,
            currentMaxAllocationStallMs,
            baselineTotalAllocationStallMs,
            currentTotalAllocationStallMs
        )) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Allocation-stall profile: count %d -> %d; max stall %s -> %s; total stall %s -> %s.",
            baselineAllocationStallCount,
            currentAllocationStallCount,
            humanMs(baselineMaxAllocationStallMs),
            humanMs(currentMaxAllocationStallMs),
            humanMs(baselineTotalAllocationStallMs),
            humanMs(currentTotalAllocationStallMs)
        ));
    }

    private void appendRecoverySynopsisLine(
        List<String> summaryLines,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineRecovery,
        Map<String, Object> currentRecovery
    ) {
        double baselinePeakPostGcOccupancyRatio = firstPositiveDouble(
            baselinePressure,
            "peakPostGcOccupancyRatio",
            baselineRecovery,
            "peakPostGcOccupancyRatio"
        );
        double currentPeakPostGcOccupancyRatio = firstPositiveDouble(
            currentPressure,
            "peakPostGcOccupancyRatio",
            currentRecovery,
            "peakPostGcOccupancyRatio"
        );
        double baselineAveragePostGcOccupancyRatio = firstPositiveDouble(
            baselinePressure,
            "averagePostGcOccupancyRatio",
            baselineRecovery,
            "averagePostGcOccupancyRatio"
        );
        double currentAveragePostGcOccupancyRatio = firstPositiveDouble(
            currentPressure,
            "averagePostGcOccupancyRatio",
            currentRecovery,
            "averagePostGcOccupancyRatio"
        );
        long baselineNearCapacityAfterGcCount = firstPositiveLong(
            baselinePressure,
            "nearCapacityAfterGcCount",
            baselineRecovery,
            "nearCapacityAfterGcCount"
        );
        long currentNearCapacityAfterGcCount = firstPositiveLong(
            currentPressure,
            "nearCapacityAfterGcCount",
            currentRecovery,
            "nearCapacityAfterGcCount"
        );
        if (!hasPositiveMetric(
            baselinePeakPostGcOccupancyRatio,
            currentPeakPostGcOccupancyRatio,
            baselineAveragePostGcOccupancyRatio,
            currentAveragePostGcOccupancyRatio,
            baselineNearCapacityAfterGcCount,
            currentNearCapacityAfterGcCount
        )) {
            return;
        }

        summaryLines.add(String.format(
            Locale.ROOT,
            "Recovery shape: peak post-GC occupancy %s -> %s; average post-GC occupancy %s -> %s; near-capacity events %d -> %d.",
            humanRatioPercent(baselinePeakPostGcOccupancyRatio),
            humanRatioPercent(currentPeakPostGcOccupancyRatio),
            humanRatioPercent(baselineAveragePostGcOccupancyRatio),
            humanRatioPercent(currentAveragePostGcOccupancyRatio),
            baselineNearCapacityAfterGcCount,
            currentNearCapacityAfterGcCount
        ));
    }

    private void appendCollectorSpecificSynopsisLine(
        List<String> summaryLines,
        String normalizedCollector,
        Map<String, Object> baselinePressure,
        Map<String, Object> currentPressure,
        Map<String, Object> baselineFailure,
        Map<String, Object> currentFailure,
        Map<String, Object> baselineG1,
        Map<String, Object> currentG1,
        Map<String, Object> baselineConcurrent,
        Map<String, Object> currentConcurrent
    ) {
        switch (normalizedCollector) {
            case "G1" -> {
                long baselineToSpaceExhaustedCount = longValue(baselineFailure, "toSpaceExhaustedCount");
                long currentToSpaceExhaustedCount = longValue(currentFailure, "toSpaceExhaustedCount");
                long baselineFullCompactionAttemptCount = longValue(baselineFailure, "fullCompactionAttemptCount");
                long currentFullCompactionAttemptCount = longValue(currentFailure, "fullCompactionAttemptCount");
                long baselineLowReclaimHighRetentionFullGcCount = longValue(baselineG1, "lowReclaimHighRetentionFullGcCount");
                long currentLowReclaimHighRetentionFullGcCount = longValue(currentG1, "lowReclaimHighRetentionFullGcCount");
                if (hasPositiveMetric(
                    baselineToSpaceExhaustedCount,
                    currentToSpaceExhaustedCount,
                    baselineFullCompactionAttemptCount,
                    currentFullCompactionAttemptCount,
                    baselineLowReclaimHighRetentionFullGcCount,
                    currentLowReclaimHighRetentionFullGcCount
                )) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "G1 distress signals: to-space exhausted %d -> %d; full compaction attempts %d -> %d; low-reclaim high-retention full GCs %d -> %d.",
                        baselineToSpaceExhaustedCount,
                        currentToSpaceExhaustedCount,
                        baselineFullCompactionAttemptCount,
                        currentFullCompactionAttemptCount,
                        baselineLowReclaimHighRetentionFullGcCount,
                        currentLowReclaimHighRetentionFullGcCount
                    ));
                }
            }
            case "CMS" -> {
                long baselineConcurrentModeFailureCount = longValue(baselineFailure, "concurrentModeFailureCount");
                long currentConcurrentModeFailureCount = longValue(currentFailure, "concurrentModeFailureCount");
                long baselinePromotionFailedCount = longValue(baselineFailure, "promotionFailedCount");
                long currentPromotionFailedCount = longValue(currentFailure, "promotionFailedCount");
                double baselineLongestConcurrentPhaseMs = doubleValue(baselineConcurrent, "longestConcurrentPhaseMs");
                double currentLongestConcurrentPhaseMs = doubleValue(currentConcurrent, "longestConcurrentPhaseMs");
                if (hasPositiveMetric(
                    baselineConcurrentModeFailureCount,
                    currentConcurrentModeFailureCount,
                    baselinePromotionFailedCount,
                    currentPromotionFailedCount,
                    baselineLongestConcurrentPhaseMs,
                    currentLongestConcurrentPhaseMs
                )) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "CMS fallback signals: concurrent mode failure %d -> %d; promotion failed %d -> %d; longest concurrent phase %s -> %s.",
                        baselineConcurrentModeFailureCount,
                        currentConcurrentModeFailureCount,
                        baselinePromotionFailedCount,
                        currentPromotionFailedCount,
                        humanMs(baselineLongestConcurrentPhaseMs),
                        humanMs(currentLongestConcurrentPhaseMs)
                    ));
                }
            }
            case "SERIAL", "PARALLEL" -> {
                long baselineMaxFullGcStreak = longValue(baselinePressure, "maxFullGcStreak");
                long currentMaxFullGcStreak = longValue(currentPressure, "maxFullGcStreak");
                double baselineAverageReclaimedMb = firstPositiveDouble(baselinePressure, "averageReclaimedMb", Map.of(), "");
                double currentAverageReclaimedMb = firstPositiveDouble(currentPressure, "averageReclaimedMb", Map.of(), "");
                if (hasPositiveMetric(
                    baselineMaxFullGcStreak,
                    currentMaxFullGcStreak,
                    baselineAverageReclaimedMb,
                    currentAverageReclaimedMb
                )) {
                    summaryLines.add(String.format(
                        Locale.ROOT,
                        "Stop-the-world recovery: max full-GC streak %d -> %d; average reclaimed heap %s -> %s.",
                        baselineMaxFullGcStreak,
                        currentMaxFullGcStreak,
                        humanMb(baselineAverageReclaimedMb),
                        humanMb(currentAverageReclaimedMb)
                    ));
                }
            }
            default -> {
            }
        }
    }

    private List<Map<String, Object>> topPauseCauses(Map<String, Object> pauseBreakdown, int limit) {
        Map<String, Double> causeTotalPauseMs = doubleMetricMap(pauseBreakdown.get("causeTotalPauseMs"));
        Map<String, Long> causeCounts = longMetricMap(pauseBreakdown.get("causeCounts"));
        Map<String, Double> causeMaxPauseMs = doubleMetricMap(pauseBreakdown.get("causeMaxPauseMs"));
        if (causeTotalPauseMs.isEmpty() && causeCounts.isEmpty() && causeMaxPauseMs.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Boolean> causeKeys = new LinkedHashMap<>();
        causeTotalPauseMs.keySet().forEach(key -> causeKeys.put(key, Boolean.TRUE));
        causeCounts.keySet().forEach(key -> causeKeys.put(key, Boolean.TRUE));
        causeMaxPauseMs.keySet().forEach(key -> causeKeys.put(key, Boolean.TRUE));

        List<String> orderedCauses = new ArrayList<>(causeKeys.keySet());
        orderedCauses.sort(Comparator
            .comparingDouble((String cause) -> causeTotalPauseMs.getOrDefault(cause, 0.0d))
            .thenComparingLong(cause -> causeCounts.getOrDefault(cause, 0L))
            .thenComparingDouble(cause -> causeMaxPauseMs.getOrDefault(cause, 0.0d))
            .reversed()
            .thenComparing(cause -> cause));

        List<Map<String, Object>> topCauses = new ArrayList<>();
        for (String cause : orderedCauses.stream().limit(limit).toList()) {
            LinkedHashMap<String, Object> causeSummary = new LinkedHashMap<>();
            causeSummary.put("cause", cause);
            if (causeTotalPauseMs.containsKey(cause)) {
                causeSummary.put("totalPauseMs", round(causeTotalPauseMs.get(cause)));
            }
            if (causeCounts.containsKey(cause)) {
                causeSummary.put("pauseCount", causeCounts.get(cause));
            }
            if (causeMaxPauseMs.containsKey(cause)) {
                causeSummary.put("maxPauseMs", round(causeMaxPauseMs.get(cause)));
            }
            topCauses.add(Map.copyOf(causeSummary));
        }
        return List.copyOf(topCauses);
    }

    private Map<String, Object> gcRecoveryShapePair(
        String baselineCollector,
        Map<String, Object> baselinePressure,
        Map<String, Object> baselineRecovery,
        Map<String, Object> baselineG1,
        String currentCollector,
        Map<String, Object> currentPressure,
        Map<String, Object> currentRecovery,
        Map<String, Object> currentG1
    ) {
        Map<String, Object> baselineRecoveryShape = gcRecoveryShapeSnapshot(
            baselineCollector,
            baselinePressure,
            baselineRecovery,
            baselineG1
        );
        Map<String, Object> currentRecoveryShape = gcRecoveryShapeSnapshot(
            currentCollector,
            currentPressure,
            currentRecovery,
            currentG1
        );
        if (baselineRecoveryShape.isEmpty() && currentRecoveryShape.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
        if (!baselineRecoveryShape.isEmpty()) {
            pair.put("baselineRecoveryShape", baselineRecoveryShape);
        }
        if (!currentRecoveryShape.isEmpty()) {
            pair.put("currentRecoveryShape", currentRecoveryShape);
        }
        return Map.copyOf(pair);
    }

    private Map<String, Object> gcRecoveryShapeSnapshot(
        String collector,
        Map<String, Object> pressure,
        Map<String, Object> recovery,
        Map<String, Object> g1
    ) {
        if ((pressure == null || pressure.isEmpty()) && (recovery == null || recovery.isEmpty()) && (g1 == null || g1.isEmpty())) {
            return Map.of();
        }

        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        putIfPositiveLong(
            snapshot,
            "fullGcCount",
            firstPositiveLong(pressure, "fullGcCount", Map.of(), "")
        );
        putIfPositiveDouble(
            snapshot,
            "peakPostGcOccupancyRatio",
            firstPositiveDouble(pressure, "peakPostGcOccupancyRatio", recovery, "peakPostGcOccupancyRatio")
        );
        putIfPositiveDouble(
            snapshot,
            "averagePostGcOccupancyRatio",
            firstPositiveDouble(pressure, "averagePostGcOccupancyRatio", recovery, "averagePostGcOccupancyRatio")
        );
        putIfPositiveLong(
            snapshot,
            "nearCapacityAfterGcCount",
            firstPositiveLong(pressure, "nearCapacityAfterGcCount", recovery, "nearCapacityAfterGcCount")
        );
        putIfPositiveLong(
            snapshot,
            "maxNearCapacityPauseStreak",
            firstPositiveLong(pressure, "maxNearCapacityPauseStreak", recovery, "maxNearCapacityPauseStreak")
        );

        String normalizedCollector = hasText(collector) ? collector.toUpperCase(Locale.ROOT) : "";
        switch (normalizedCollector) {
            case "G1" -> {
                putIfPositiveLong(snapshot, "maxFullGcStreak", longValue(pressure, "maxFullGcStreak"));
                putIfPositiveLong(
                    snapshot,
                    "lowReclaimHighRetentionFullGcCount",
                    firstPositiveLong(pressure, "lowReclaimHighRetentionFullGcCount", g1, "lowReclaimHighRetentionFullGcCount")
                );
                putIfPositiveDouble(
                    snapshot,
                    "averageFullGcReclaimedMb",
                    firstPositiveDouble(pressure, "averageFullGcReclaimedMb", g1, "averageFullGcReclaimedMb")
                );
            }
            case "CMS" -> putIfPositiveDouble(
                snapshot,
                "averageFullPostGcOccupancyRatio",
                firstPositiveDouble(pressure, "averageFullPostGcOccupancyRatio", recovery, "averageFullPostGcOccupancyRatio")
            );
            case "SERIAL", "PARALLEL" -> {
                putIfPositiveLong(snapshot, "maxFullGcStreak", longValue(pressure, "maxFullGcStreak"));
                putIfPositiveDouble(
                    snapshot,
                    "averageFullPostGcOccupancyRatio",
                    firstPositiveDouble(pressure, "averageFullPostGcOccupancyRatio", recovery, "averageFullPostGcOccupancyRatio")
                );
                putIfPositiveDouble(
                    snapshot,
                    "averageReclaimedMb",
                    firstPositiveDouble(pressure, "averageReclaimedMb", recovery, "averageReclaimedMb")
                );
            }
            default -> {
            }
        }
        return Map.copyOf(snapshot);
    }

    private Map<String, Object> gcDominantIncidentPair(
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        Map<String, Object> baselineIncident = gcIncidentSnapshot(selectDominantGcIncidentSlice(baselineContext));
        Map<String, Object> currentIncident = gcIncidentSnapshot(selectDominantGcIncidentSlice(currentContext));
        if (baselineIncident.isEmpty() && currentIncident.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
        if (!baselineIncident.isEmpty()) {
            pair.put("baselineIncident", baselineIncident);
        }
        if (!currentIncident.isEmpty()) {
            pair.put("currentIncident", currentIncident);
        }
        return Map.copyOf(pair);
    }

    private ContextSlice selectDominantGcIncidentSlice(IndexedArtifactDiagnosticContext indexedContext) {
        if (indexedContext == null || indexedContext.diagnosticContext() == null) {
            return null;
        }
        List<ContextSlice> representativeSlices = indexedContext.diagnosticContext().representativeSlices();
        if (representativeSlices == null || representativeSlices.isEmpty()) {
            return null;
        }

        for (ContextSlice slice : representativeSlices) {
            if (slice.sliceId() != null && slice.sliceId().startsWith("gc-incident-dominant-")) {
                return slice;
            }
        }
        for (ContextSlice slice : representativeSlices) {
            if (slice.sliceId() != null && slice.sliceId().startsWith("gc-incident-")) {
                return slice;
            }
        }
        return representativeSlices.getFirst();
    }

    private Map<String, Object> gcIncidentSnapshot(ContextSlice slice) {
        if (slice == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sliceId", slice.sliceId());
        snapshot.put("label", slice.label());
        if (slice.traceability() != null && !slice.traceability().isBlank()) {
            snapshot.put("source", renderSource(slice.traceability()));
        }
        if (slice.content() != null && !slice.content().isBlank()) {
            snapshot.put("excerpt", compactSliceExcerpt(slice.content()));
        }
        return Map.copyOf(snapshot);
    }

    private String compactSliceExcerpt(String content) {
        List<String> lines = DiagnosticContextRenderSupport.lines(content);
        String excerpt = String.join("\n", lines.stream().limit(6).toList());
        return DiagnosticContextRenderSupport.truncateBlock(excerpt, 700);
    }

    private String collectorName(ParsedArtifact parsedArtifact, Map<String, Object> pressure) {
        String collector = stringValue(pressure.get("collector"));
        if (hasText(collector)) {
            return collector;
        }
        return parsedArtifact != null ? stringValue(parsedArtifact.extractedData().get("collector")) : "";
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private void putLongComparison(Map<String, Object> target, String key, long baseline, long current) {
        if (baseline == 0L && current == 0L) {
            return;
        }
        target.put("baseline" + capitalize(key), baseline);
        target.put("current" + capitalize(key), current);
        target.put(key + "Delta", current - baseline);
    }

    private void putDoubleComparison(Map<String, Object> target, String key, double baseline, double current) {
        if (baseline == 0.0d && current == 0.0d) {
            return;
        }
        target.put("baseline" + capitalize(key), round(baseline));
        target.put("current" + capitalize(key), round(current));
        target.put(key + "Delta", round(current - baseline));
    }

    private void putIfPositiveLong(Map<String, Object> target, String key, long value) {
        if (value > 0L) {
            target.put(key, value);
        }
    }

    private void putIfPositiveDouble(Map<String, Object> target, String key, double value) {
        if (value > 0.0d) {
            target.put(key, round(value));
        }
    }

    private boolean hasPositiveMetric(Number... values) {
        if (values == null) {
            return false;
        }
        for (Number value : values) {
            if (value != null && value.doubleValue() > 0.0d) {
                return true;
            }
        }
        return false;
    }

    private String humanMs(double value) {
        return value > 0.0d ? round(value) + " ms" : "none";
    }

    private String humanDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "none";
        }
        if (durationMs >= 60_000L) {
            return round(durationMs / 60_000.0d) + " min";
        }
        if (durationMs >= 1_000L) {
            return round(durationMs / 1_000.0d) + " s";
        }
        return durationMs + " ms";
    }

    private String humanBytes(long bytes) {
        if (bytes <= 0L) {
            return "none";
        }
        double value = bytes;
        String[] units = { "B", "KB", "MB", "GB", "TB" };
        int unitIndex = 0;
        while (value >= 1024.0d && unitIndex < units.length - 1) {
            value /= 1024.0d;
            unitIndex++;
        }
        return round(value) + " " + units[unitIndex];
    }

    private String humanPercent(double value) {
        return value > 0.0d ? round(value) + "%" : "none";
    }

    private String humanBytesPerSecond(double bytesPerSecond) {
        return bytesPerSecond > 0.0d ? humanBytes(Math.round(bytesPerSecond)) + "/s" : "none";
    }

    private String humanRatioPercent(double value) {
        return value > 0.0d ? round(value * 100.0d) + "%" : "none";
    }

    private String humanMb(double value) {
        return value > 0.0d ? round(value) + " MB" : "none";
    }

    private String humanTextOrNone(String value) {
        return hasText(value) ? value : "none";
    }

    private String jfrSignalFamilyLabelOrSelf(String value) {
        return switch (value == null ? "" : value) {
            case "runtime" -> "runtime";
            case "allocation" -> "allocation";
            case "retention" -> "retention";
            default -> hasText(jfrSignalFamilyLabel(value)) ? jfrSignalFamilyLabel(value) : value;
        };
    }

    private double ratePerMinute(long count, long durationMs) {
        return durationMs > 0L ? (count * 60_000.0d) / durationMs : 0.0d;
    }

    private double sharePercent(long part, long total) {
        return total > 0L ? (part * 100.0d) / total : 0.0d;
    }

    private double byteRatePerSecond(long bytes, long durationMs) {
        return durationMs > 0L ? (bytes * 1000.0d) / durationMs : 0.0d;
    }

    private long firstPositiveLong(Map<String, Object> firstSource, String firstKey, Map<String, Object> secondSource, String secondKey) {
        long value = longValue(firstSource, firstKey);
        if (value > 0L) {
            return value;
        }
        return longValue(secondSource, secondKey);
    }

    private double firstPositiveDouble(Map<String, Object> firstSource, String firstKey, Map<String, Object> secondSource, String secondKey) {
        double value = doubleValue(firstSource, firstKey);
        if (value > 0.0d) {
            return value;
        }
        return doubleValue(secondSource, secondKey);
    }

    private double firstPositiveDouble(
        Map<String, Object> firstSource,
        String firstKey,
        Map<String, Object> secondSource,
        String secondKey,
        Map<String, Object> thirdSource,
        String thirdKey
    ) {
        double value = firstPositiveDouble(firstSource, firstKey, secondSource, secondKey);
        if (value > 0.0d) {
            return value;
        }
        return doubleValue(thirdSource, thirdKey);
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double doubleValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private Map<String, Long> longMetricMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                metrics.put(String.valueOf(entry.getKey()), number.longValue());
            }
        }
        return Map.copyOf(metrics);
    }

    private Map<String, Double> doubleMetricMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, Double> metrics = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                metrics.put(String.valueOf(entry.getKey()), number.doubleValue());
            }
        }
        return Map.copyOf(metrics);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : "";
    }

    private String capitalize(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private final class JfrTimelineTrendAccumulator {
        private final String label;
        private long eventCount;
        private long totalDurationMs;
        private long totalAllocatedBytes;
        private long totalSampledObjectBytes;
        private long maxReferenceDepth;
        private long maxObjectAgeMs;
        private final LinkedHashMap<String, Long> eventTypes = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> methods = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> threads = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> classes = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> signalFamilies = new LinkedHashMap<>();

        private JfrTimelineTrendAccumulator(String label) {
            this.label = label;
        }

        private void record(Map<String, Object> event) {
            eventCount++;
            totalDurationMs += longValue(event, "durationMs");
            totalAllocatedBytes += longValue(event, "allocatedBytes");
            totalSampledObjectBytes += longValue(event, "sampledObjectBytes");
            maxReferenceDepth = Math.max(maxReferenceDepth, longValue(event, "referenceDepth"));
            maxObjectAgeMs = Math.max(maxObjectAgeMs, longValue(event, "objectAgeMs"));
            increment(eventTypes, stringValue(event.get("eventType")));
            increment(methods, stringValue(event.get("topMethod")));
            increment(threads, stringValue(event.get("eventThread")));
            increment(classes, stringValue(event.get("className")));
            increment(signalFamilies, stringValue(event.get("signalFamily")));
        }

        private void increment(Map<String, Long> counts, String key) {
            if (!hasText(key)) {
                return;
            }
            counts.merge(key, 1L, Long::sum);
        }

        private long eventCount() {
            return eventCount;
        }

        private long totalDurationMs() {
            return totalDurationMs;
        }

        private long totalAllocatedBytes() {
            return totalAllocatedBytes;
        }

        private long totalSampledObjectBytes() {
            return totalSampledObjectBytes;
        }

        private long totalBytes() {
            return totalAllocatedBytes + totalSampledObjectBytes;
        }

        private long maxReferenceDepth() {
            return maxReferenceDepth;
        }

        private long maxObjectAgeMs() {
            return maxObjectAgeMs;
        }

        private String topEventType() {
            return topKey(eventTypes);
        }

        private String topMethod() {
            return topKey(methods);
        }

        private String topThread() {
            return topKey(threads);
        }

        private String topClass() {
            return topKey(classes);
        }

        private String topSignalFamily() {
            return topKey(signalFamilies);
        }

        private String label() {
            return label;
        }

        private String topKey(Map<String, Long> counts) {
            return counts.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("");
        }
    }

    private record JfrTimelineTrendComparison(
        Map<String, Object> entry,
        double score,
        double currentProminence,
        String key
    ) {
    }

    private record JfrHotspotCandidate(
        String kind,
        String topMethod,
        long topMethodCount,
        long eventCount,
        double share,
        String topStack,
        long allocatedBytes,
        int priority
    ) {
    }
}
