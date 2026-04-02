package com.javaassistant.orchestration;

import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.context.ArtifactDiagnosticContext;
import com.javaassistant.context.ContextCoverage;
import com.javaassistant.context.ContextSlice;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.context.DiagnosticContextRenderSupport;
import com.javaassistant.context.DiagnosticHighlight;
import com.javaassistant.context.IndexedArtifactDiagnosticContext;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds bounded starting diagnostic context inputs for agents from fully parsed artifacts and coverage metadata.
 */
public class AgentDiagnosticContextBuilder {

    private static final int MAX_COVERAGE_ITEMS_RENDERED = 12;

    private final DiagnosticContextIndexer contextIndexer;

    public AgentDiagnosticContextBuilder() {
        this(new DiagnosticContextIndexer());
    }

    public AgentDiagnosticContextBuilder(DiagnosticContextIndexer contextIndexer) {
        this.contextIndexer = contextIndexer;
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
        appendContext(builder, "ARTIFACT", grounding.inputArtifact(), indexedContext.diagnosticContext());
        return builder.toString().strip();
    }

    public String buildComparisonContext(
        ArtifactGrounding baseline,
        ArtifactGrounding current,
        AssessmentResult comparisonEvaluation
    ) {
        return buildComparisonContext(
            baseline,
            contextIndexer.index(baseline.inputArtifact(), baseline.parsedArtifact()),
            current,
            contextIndexer.index(current.inputArtifact(), current.parsedArtifact()),
            comparisonEvaluation
        );
    }

    public String buildComparisonContext(
        ArtifactGrounding baseline,
        IndexedArtifactDiagnosticContext baselineContext,
        ArtifactGrounding current,
        IndexedArtifactDiagnosticContext currentContext,
        AssessmentResult comparisonEvaluation
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("MODE: ARTIFACT_COMPARISON\n");
        builder.append("ARTIFACT_TYPE: ").append(current.inputArtifact().type()).append('\n');
        appendContext(builder, "BASELINE_ARTIFACT", baseline.inputArtifact(), baselineContext.diagnosticContext());
        appendContext(builder, "CURRENT_ARTIFACT", current.inputArtifact(), currentContext.diagnosticContext());
        return builder.toString().strip();
    }

    public String buildCorrelationContext(
        List<ArtifactGrounding> groundings,
        CorrelationResult correlationResult,
        List<SpecialistObservation> observations
    ) {
        List<IndexedArtifactDiagnosticContext> indexedContexts = groundings.stream()
            .map(grounding -> contextIndexer.index(grounding.inputArtifact(), grounding.parsedArtifact()))
            .toList();
        return buildCorrelationContext(groundings, indexedContexts, observations);
    }

    public String buildCorrelationContext(
        List<ArtifactGrounding> groundings,
        List<IndexedArtifactDiagnosticContext> indexedContexts,
        List<SpecialistObservation> observations
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("MODE: MULTI_ARTIFACT_CORRELATION\n");
        appendArtifactOverview(builder, groundings, indexedContexts);
        for (int index = 0; index < groundings.size(); index++) {
            ArtifactGrounding grounding = groundings.get(index);
            IndexedArtifactDiagnosticContext indexedContext = indexedContexts.get(index);
            appendContext(builder, "ARTIFACT_CONTEXT_" + (index + 1), grounding.inputArtifact(), indexedContext.diagnosticContext());
        }
        appendSpecialistObservations(builder, observations);
        return builder.toString().strip();
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
            builder.append("- Artifact ").append(index + 1).append(": ");
            builder.append(artifact.type());
            builder.append(" | source=").append(renderScalar(sourcePath(artifact)));
            builder.append(" | display=").append(renderScalar(displayName(artifact)));
            builder.append(" | additionalContextAvailable=").append(context.coverage().additionalContextAvailable());
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
        appendArtifactMetadata(builder, artifact, context);
        appendStructuredFacts(builder, context != null ? context.structuredFacts() : Map.of());
        appendHighlights(builder, context != null ? context.diagnosticHighlights() : List.of());
        appendSlices(builder, "STRUCTURED_CONTEXT_SLICES", context != null ? context.structuredSlices() : List.of());
        appendSlices(builder, "REPRESENTATIVE_CONTEXT_SLICES", context != null ? context.representativeSlices() : List.of());
        appendCoverage(builder, context != null ? context.coverage() : null);
    }

    private void appendArtifactMetadata(StringBuilder builder, InputArtifact artifact, ArtifactDiagnosticContext context) {
        ArtifactMetadata metadata = artifact != null ? artifact.metadata() : null;
        boolean attributesAlreadyPresentAsStructuredSlice = context != null
            && context.structuredSlices().stream().anyMatch(slice -> "artifactAttributes".equals(slice.sliceId()));
        builder.append("- Type: ").append(artifact != null ? artifact.type() : "(none)").append('\n');
        builder.append("- Source Path: ").append(renderScalar(sourcePath(artifact))).append('\n');
        builder.append("- Display Name: ").append(renderScalar(displayName(artifact))).append('\n');
        builder.append("- Content Length: ").append(contentLength(artifact)).append('\n');
        builder.append("- Parser Version: ").append(renderScalar(context != null ? context.parserVersion() : null)).append('\n');
        if (metadata != null && metadata.attributes() != null && !metadata.attributes().isEmpty()) {
            if (attributesAlreadyPresentAsStructuredSlice) {
                builder.append("- Attributes: included in the structured context slices below.\n");
            } else {
                builder.append("- Attributes:\n");
                builder.append(DiagnosticContextRenderSupport.indent(DiagnosticContextRenderSupport.renderFullValue(metadata.attributes()), "  ")).append('\n');
            }
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
                builder.append("   Traceability:\n");
                builder.append(DiagnosticContextRenderSupport.indent(
                    DiagnosticContextRenderSupport.renderFullValue(highlight.traceability()),
                    "     "
                )).append('\n');
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
            builder.append("  Kind: ").append(renderScalar(slice.kind())).append('\n');
            builder.append("  Traceability: ").append(renderScalar(slice.traceability())).append('\n');
            builder.append("  Truncated: ").append(slice.truncated()).append('\n');
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
        appendCoverageItems(builder, "Omitted Structured Blocks", coverage.omittedStructuredBlocks());
        appendCoverageItems(builder, "Omitted Context Slices", coverage.omittedRawSlices());
        appendCoverageItems(builder, "Parse Gaps", coverage.parseGaps());
        appendCoverageItems(builder, "Truncation Markers", coverage.truncationMarkers());
        if (coverage.additionalContextAvailable()) {
            builder.append("- Retrieval Hint: leave the selector blank to fetch the next omitted item. Use sliceId=<id> to reopen a specific slice, and page long slices with sliceId=<id>, offset=<charOffset>, chars=<charCount>.\n");
        }
    }

    private void appendCoverageItems(StringBuilder builder, String label, List<String> values) {
        builder.append("- ").append(label);
        if (values == null || values.isEmpty()) {
            builder.append(" (0): none\n");
            return;
        }
        builder.append(" (").append(values.size()).append("): ");
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
            builder.append("  Source Path: ").append(renderScalar(observation.sourcePath())).append('\n');
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

    private String displayName(InputArtifact artifact) {
        return artifact != null && artifact.metadata() != null ? artifact.metadata().displayName() : null;
    }

    private long contentLength(InputArtifact artifact) {
        if (artifact == null) {
            return 0L;
        }
        if (artifact.metadata() != null && artifact.metadata().contentLength() > 0L) {
            return artifact.metadata().contentLength();
        }
        return artifact.content() != null ? artifact.content().length() : 0L;
    }

    private String renderScalar(Object value) {
        return DiagnosticContextRenderSupport.renderScalar(value);
    }
}
