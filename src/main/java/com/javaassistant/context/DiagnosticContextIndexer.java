package com.javaassistant.context;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the agent's bounded starting context and the full internal index used by later tool calls.
 */
public class DiagnosticContextIndexer {

    public static final int MAX_STARTING_HIGHLIGHTS = 24;
    public static final int MAX_STARTING_STRUCTURED_SLICES = 24;
    public static final int MAX_STARTING_REPRESENTATIVE_SLICES = 24;

    private static final int RAW_WINDOW_RADIUS = 5;
    private static final int RAW_CHUNK_LINES = 24;
    private static final int BROAD_RAW_CHUNK_TARGET = 5;
    private static final String EXTERNAL_BINARY_JFR = "external-binary-jfr";
    private static final String ARTIFACT_ATTRIBUTES_KEY = "artifactAttributes";
    private static final List<String> COMMON_PRIORITY_KEYS = List.of("summary", "coverage", "totals");
    private static final List<String> JFR_PRIORITY_KEYS = List.of(
        "summary",
        "coverage",
        "observedEventTypes",
        "topEventTypes",
        "declaredEventTypes",
        "lockSummary",
        "gcSummary",
        "threadParkSummary",
        "ioSummary",
        "exceptionSummary",
        "safepointSummary",
        "allocationSummary",
        "allocationFieldSummary",
        "allocationHotspotSummary",
        "oldObjectSummary",
        "oldObjectFieldSummary",
        "executionSummary",
        "overallHotspotSummary",
        "executionHotspotSummary",
        "runtimeHotspotSummary"
    );

    private final StartingContextBudget budget;

    public DiagnosticContextIndexer() {
        this(StartingContextBudget.defaultBudget());
    }

    public DiagnosticContextIndexer(StartingContextBudget budget) {
        this.budget = budget != null ? budget : StartingContextBudget.defaultBudget();
    }

    public IndexedArtifactDiagnosticContext index(InputArtifact inputArtifact, ParsedArtifact parsedArtifact) {
        List<DiagnosticHighlight> highlights = diagnosticHighlights(parsedArtifact);
        Map<String, String> structuredBlocks = structuredBlocks(inputArtifact, parsedArtifact, highlights);
        List<String> rawLines = rawLines(inputArtifact);
        Map<String, IndexedArtifactDiagnosticContext.LineRange> rawSections = rawSections(inputArtifact, rawLines);
        List<ContextSlice> allSlices = allSlices(inputArtifact, parsedArtifact, rawLines);

        List<ContextSlice> structuredSlices = structuredBlocks.entrySet().stream()
            .map(entry -> buildStructuredSlice(entry.getKey(), entry.getValue()))
            .toList();

        List<DiagnosticHighlight> startingHighlights = highlights.stream()
            .limit(Math.min(MAX_STARTING_HIGHLIGHTS, budget.maxHighlights()))
            .toList();
        List<ContextSlice> boundedStructuredSlices = structuredSlices.stream()
            .limit(Math.min(MAX_STARTING_STRUCTURED_SLICES, budget.maxStructuredSlices()))
            .map(slice -> boundedSlice(slice, budget.maxStructuredSliceChars()))
            .toList();
        List<ContextSlice> boundedRepresentativeSlices = allSlices.stream()
            .limit(Math.min(MAX_STARTING_REPRESENTATIVE_SLICES, budget.maxRepresentativeSlices()))
            .map(slice -> boundedSlice(slice, budget.maxRepresentativeSliceChars()))
            .toList();

        ContextCoverage coverage = new ContextCoverage(
            sourcePath(inputArtifact),
            structuredSlices.stream().skip(boundedStructuredSlices.size()).map(ContextSlice::sliceId).toList(),
            allSlices.stream().skip(boundedRepresentativeSlices.size()).map(ContextSlice::sliceId).toList(),
            parsedArtifact != null ? parsedArtifact.warnings() : List.of(),
            truncationMarkers(boundedStructuredSlices, boundedRepresentativeSlices),
            structuredSlices.size() > boundedStructuredSlices.size()
                || allSlices.size() > boundedRepresentativeSlices.size()
                || (parsedArtifact != null && !parsedArtifact.warnings().isEmpty())
        );

        ArtifactDiagnosticContext context = new ArtifactDiagnosticContext(
            parsedArtifact != null ? parsedArtifact.type() : inputArtifact != null ? inputArtifact.type() : null,
            inputArtifact != null ? inputArtifact.metadata() : null,
            parsedArtifact != null ? parsedArtifact.parserVersion() : null,
            structuredFacts(parsedArtifact),
            startingHighlights,
            boundedStructuredSlices,
            boundedRepresentativeSlices,
            coverage
        );

        return new IndexedArtifactDiagnosticContext(
            inputArtifact,
            parsedArtifact,
            context,
            structuredBlocks,
            rawLines,
            rawSections,
            allSlices
        );
    }

    private Map<String, Object> structuredFacts(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> facts = new LinkedHashMap<>();
        addScalarFacts(facts, "summary", parsedArtifact.extractedData().get("summary"), 12);
        addScalarFacts(facts, "coverage", parsedArtifact.extractedData().get("coverage"), 10);
        addScalarFacts(facts, "totals", parsedArtifact.extractedData().get("totals"), 10);
        if (facts.isEmpty()) {
            for (Map.Entry<String, Object> entry : parsedArtifact.extractedData().entrySet()) {
                if (entry.getValue() != null && DiagnosticContextRenderSupport.isScalar(entry.getValue())) {
                    facts.put(entry.getKey(), entry.getValue());
                }
                if (facts.size() >= 16) {
                    break;
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    private void addScalarFacts(Map<String, Object> target, String prefix, Object value, int limit) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!DiagnosticContextRenderSupport.isScalar(entry.getValue())) {
                continue;
            }
            if (entry.getValue() != null) {
                target.put(prefix + "." + entry.getKey(), entry.getValue());
            }
            count++;
            if (count >= limit) {
                break;
            }
        }
    }

    private List<DiagnosticHighlight> diagnosticHighlights(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null || parsedArtifact.evidence().isEmpty()) {
            return List.of();
        }
        List<DiagnosticHighlight> highlights = new ArrayList<>();
        for (Evidence evidence : parsedArtifact.evidence()) {
            String traceability = evidence.artifactPath();
            if (!evidence.lineNumbers().isEmpty()) {
                traceability = traceability + " lines " + evidence.lineNumbers();
            }
            highlights.add(new DiagnosticHighlight(
                evidence.id(),
                evidence.label(),
                evidence.detail(),
                evidence.metrics(),
                traceability
            ));
        }
        return List.copyOf(highlights);
    }

    private Map<String, String> structuredBlocks(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        List<DiagnosticHighlight> highlights
    ) {
        LinkedHashMap<String, String> blocks = new LinkedHashMap<>();
        if (inputArtifact != null
            && inputArtifact.metadata() != null
            && inputArtifact.metadata().attributes() != null
            && !inputArtifact.metadata().attributes().isEmpty()) {
            blocks.put(ARTIFACT_ATTRIBUTES_KEY, DiagnosticContextRenderSupport.renderFullValue(inputArtifact.metadata().attributes()));
        }
        if (parsedArtifact != null) {
            Map<String, Object> extractedData = orderedExtractedData(parsedArtifact);
            for (Map.Entry<String, Object> entry : extractedData.entrySet()) {
                blocks.put(entry.getKey(), DiagnosticContextRenderSupport.renderFullValue(entry.getValue()));
            }
        }
        for (DiagnosticHighlight highlight : highlights) {
            blocks.put(highlightBlockId(highlight), DiagnosticContextRenderSupport.renderFullValue(highlightPayload(highlight)));
        }
        if (parsedArtifact != null && !parsedArtifact.warnings().isEmpty()) {
            blocks.put("parserWarnings", String.join("\n", parsedArtifact.warnings()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
    }

    private Map<String, Object> orderedExtractedData(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        appendPreferredKeys(ordered, parsedArtifact.extractedData(), COMMON_PRIORITY_KEYS);
        if (parsedArtifact.type() == ArtifactType.JFR) {
            appendPreferredKeys(ordered, parsedArtifact.extractedData(), JFR_PRIORITY_KEYS);
        }
        for (Map.Entry<String, Object> entry : parsedArtifact.extractedData().entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private void appendPreferredKeys(Map<String, Object> target, Map<String, Object> source, List<String> keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.putIfAbsent(key, source.get(key));
            }
        }
    }

    private Map<String, Object> highlightPayload(DiagnosticHighlight highlight) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("highlightId", highlight.highlightId());
        payload.put("label", highlight.label());
        payload.put("detail", highlight.detail());
        payload.put("traceability", highlight.traceability());
        payload.put("metrics", highlight.metrics());
        return Collections.unmodifiableMap(payload);
    }

    private String highlightBlockId(DiagnosticHighlight highlight) {
        return "highlight-" + sanitizeIdentifier(highlight.highlightId());
    }

    private String sanitizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.strip().replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private List<String> rawLines(InputArtifact inputArtifact) {
        if (inputArtifact == null || inputArtifact.content() == null || inputArtifact.content().isBlank()) {
            return List.of();
        }
        String representation = representation(inputArtifact.metadata());
        if (inputArtifact.type() == ArtifactType.JFR || EXTERNAL_BINARY_JFR.equals(representation)) {
            return List.of();
        }
        return DiagnosticContextRenderSupport.lines(inputArtifact.content());
    }

    private Map<String, IndexedArtifactDiagnosticContext.LineRange> rawSections(InputArtifact inputArtifact, List<String> rawLines) {
        if (rawLines.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, IndexedArtifactDiagnosticContext.LineRange> sections = new LinkedHashMap<>();
        if (inputArtifact != null && inputArtifact.type() == ArtifactType.CONTAINER_MEMORY) {
            String activeSection = null;
            int sectionStart = -1;
            for (int index = 0; index < rawLines.size(); index++) {
                String line = rawLines.get(index).trim();
                if (line.startsWith("[") && line.endsWith("]") && line.length() > 2) {
                    if (activeSection != null) {
                        sections.put(activeSection, new IndexedArtifactDiagnosticContext.LineRange(sectionStart + 1, index));
                    }
                    activeSection = line.substring(1, line.length() - 1);
                    sectionStart = index;
                }
            }
            if (activeSection != null) {
                sections.put(activeSection, new IndexedArtifactDiagnosticContext.LineRange(sectionStart + 1, rawLines.size()));
            }
        }

        if (inputArtifact != null && inputArtifact.type() == ArtifactType.THREAD_DUMP) {
            for (int index = 0; index < rawLines.size(); index++) {
                String line = rawLines.get(index);
                if (line.startsWith("\"")) {
                    int closingQuote = line.indexOf('"', 1);
                    if (closingQuote > 1) {
                        String threadName = line.substring(1, closingQuote);
                        sections.putIfAbsent(
                            "thread:" + threadName.toLowerCase(Locale.ROOT),
                            new IndexedArtifactDiagnosticContext.LineRange(index + 1, Math.min(rawLines.size(), index + 10))
                        );
                    }
                }
            }
        }
        return Map.copyOf(sections);
    }

    private List<ContextSlice> allSlices(InputArtifact inputArtifact, ParsedArtifact parsedArtifact, List<String> rawLines) {
        if (inputArtifact != null && inputArtifact.type() == ArtifactType.JFR) {
            return jfrSlices(parsedArtifact);
        }
        return rawSlices(inputArtifact, parsedArtifact, rawLines);
    }

    private List<ContextSlice> jfrSlices(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return List.of();
        }
        Map<String, Object> ordered = orderedExtractedData(parsedArtifact);
        List<ContextSlice> slices = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            slices.add(new ContextSlice(
                entry.getKey(),
                "derived",
                humanLabel(entry.getKey()),
                DiagnosticContextRenderSupport.renderFullValue(entry.getValue()),
                "extractedData." + entry.getKey(),
                false
            ));
        }
        return List.copyOf(slices);
    }

    private List<ContextSlice> rawSlices(InputArtifact inputArtifact, ParsedArtifact parsedArtifact, List<String> rawLines) {
        if (rawLines.isEmpty()) {
            return List.of();
        }
        List<ContextSlice> slices = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        List<ContextSlice> rawChunks = rawChunkSlices(inputArtifact, rawLines);
        addBroadRawChunkSlices(rawChunks, slices, added);
        addEvidenceWindowSlices(inputArtifact, parsedArtifact, rawLines, slices, added);
        for (ContextSlice chunk : rawChunks) {
            addSliceIfAbsent(slices, added, chunk);
        }
        return List.copyOf(slices);
    }

    private void addEvidenceWindowSlices(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        List<String> rawLines,
        List<ContextSlice> slices,
        Set<String> added
    ) {
        if (parsedArtifact == null) {
            return;
        }
        parsedArtifact.evidence().stream()
            .filter(evidence -> !evidence.lineNumbers().isEmpty())
            .sorted((left, right) -> Integer.compare(left.lineNumbers().getFirst(), right.lineNumbers().getFirst()))
            .forEach(evidence -> {
                int start = Math.max(1, evidence.lineNumbers().getFirst() - RAW_WINDOW_RADIUS);
                int end = Math.min(rawLines.size(), evidence.lineNumbers().getLast() + RAW_WINDOW_RADIUS);
                ContextSlice slice = buildRawLineSlice(
                    inputArtifact,
                    evidence.id(),
                    evidence.label(),
                    rawLines,
                    start,
                    end
                );
                addSliceIfAbsent(slices, added, slice);
            });
    }

    private List<ContextSlice> rawChunkSlices(InputArtifact inputArtifact, List<String> rawLines) {
        List<ContextSlice> slices = new ArrayList<>();
        int chunkIndex = 1;
        for (int startLine = 1; startLine <= rawLines.size(); startLine += RAW_CHUNK_LINES) {
            int endLine = Math.min(rawLines.size(), startLine + RAW_CHUNK_LINES - 1);
            slices.add(buildRawLineSlice(
                inputArtifact,
                "raw-chunk-%03d".formatted(chunkIndex),
                "Raw file chunk %d".formatted(chunkIndex),
                rawLines,
                startLine,
                endLine
            ));
            chunkIndex++;
        }
        return List.copyOf(slices);
    }

    private void addBroadRawChunkSlices(List<ContextSlice> rawChunks, List<ContextSlice> slices, Set<String> added) {
        if (rawChunks.isEmpty()) {
            return;
        }
        if (rawChunks.size() <= BROAD_RAW_CHUNK_TARGET) {
            for (ContextSlice rawChunk : rawChunks) {
                addSliceIfAbsent(slices, added, rawChunk);
            }
            return;
        }
        for (int anchorIndex : evenlySpacedIndexes(rawChunks.size(), BROAD_RAW_CHUNK_TARGET)) {
            addSliceIfAbsent(slices, added, rawChunks.get(anchorIndex));
        }
    }

    private List<Integer> evenlySpacedIndexes(int size, int targetCount) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        if (size <= 0 || targetCount <= 0) {
            return List.of();
        }
        if (targetCount == 1) {
            indexes.add(0);
        } else {
            for (int position = 0; position < targetCount; position++) {
                int index = (int) Math.round((double) position * (double) (size - 1) / (double) (targetCount - 1));
                indexes.add(Math.max(0, Math.min(size - 1, index)));
            }
        }
        return List.copyOf(indexes);
    }

    private void addSliceIfAbsent(List<ContextSlice> slices, Set<String> added, ContextSlice slice) {
        if (slice != null && added.add(slice.sliceId())) {
            slices.add(slice);
        }
    }

    private ContextSlice buildStructuredSlice(String key, String content) {
        String traceability = "extractedData." + key;
        if (key.equals(ARTIFACT_ATTRIBUTES_KEY)) {
            traceability = "artifact.metadata.attributes";
        } else if (key.startsWith("highlight-")) {
            traceability = "diagnosticHighlights." + key;
        } else if (key.equals("parserWarnings")) {
            traceability = "parsedArtifact.warnings";
        }
        return new ContextSlice(
            key,
            "structured",
            humanLabel(key),
            DiagnosticContextRenderSupport.normalizeTextBlock(content),
            traceability,
            false
        );
    }

    private ContextSlice buildRawLineSlice(
        InputArtifact inputArtifact,
        String sliceId,
        String label,
        List<String> rawLines,
        int startLineInclusive,
        int endLineInclusive
    ) {
        int normalizedStart = Math.max(1, startLineInclusive);
        int normalizedEnd = Math.min(rawLines.size(), Math.max(normalizedStart, endLineInclusive));
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = normalizedStart; lineNumber <= normalizedEnd; lineNumber++) {
            builder.append(lineNumber).append(": ").append(rawLines.get(lineNumber - 1)).append('\n');
        }
        String content = builder.toString().stripTrailing();
        return new ContextSlice(
            sliceId,
            "raw",
            label,
            content,
            sourcePath(inputArtifact) + " lines " + normalizedStart + "-" + normalizedEnd,
            false
        );
    }

    private ContextSlice boundedSlice(ContextSlice slice, int limit) {
        if (slice == null) {
            return null;
        }
        String content = DiagnosticContextRenderSupport.normalizeTextBlock(slice.content());
        boolean truncated = content.length() > limit;
        return new ContextSlice(
            slice.sliceId(),
            slice.kind(),
            slice.label(),
            truncated ? DiagnosticContextRenderSupport.truncateBlock(content, limit) : content,
            slice.traceability(),
            truncated
        );
    }

    private List<String> truncationMarkers(List<ContextSlice> structuredSlices, List<ContextSlice> representativeSlices) {
        List<String> markers = new ArrayList<>();
        structuredSlices.stream().filter(ContextSlice::truncated).forEach(slice -> markers.add("structured:" + slice.sliceId()));
        representativeSlices.stream().filter(ContextSlice::truncated).forEach(slice -> markers.add("slice:" + slice.sliceId()));
        return List.copyOf(markers);
    }

    private String humanLabel(String key) {
        String normalized = key.replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String sourcePath(InputArtifact inputArtifact) {
        return inputArtifact != null && inputArtifact.metadata() != null ? inputArtifact.metadata().sourcePath() : null;
    }

    private String representation(ArtifactMetadata metadata) {
        return metadata != null && metadata.attributes() != null
            ? metadata.attributes().get("contentRepresentation")
            : null;
    }

    public record StartingContextBudget(
        int maxHighlights,
        int maxStructuredSlices,
        int maxRepresentativeSlices,
        int maxStructuredSliceChars,
        int maxRepresentativeSliceChars
    ) {
        private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 8192;

        public static StartingContextBudget defaultBudget() {
            return forApproximateContextWindowTokens(null);
        }

        public static StartingContextBudget forApproximateContextWindowTokens(Integer contextWindowTokens) {
            int tokens = contextWindowTokens != null && contextWindowTokens > 0
                ? contextWindowTokens
                : DEFAULT_CONTEXT_WINDOW_TOKENS;
            if (tokens >= 65536) {
                return new StartingContextBudget(24, 24, 24, 9000, 7600);
            }
            if (tokens >= 32768) {
                return new StartingContextBudget(20, 20, 20, 7200, 5800);
            }
            if (tokens >= 16384) {
                return new StartingContextBudget(16, 18, 18, 5200, 4200);
            }
            return new StartingContextBudget(12, 12, 12, 2800, 2200);
        }
    }
}
