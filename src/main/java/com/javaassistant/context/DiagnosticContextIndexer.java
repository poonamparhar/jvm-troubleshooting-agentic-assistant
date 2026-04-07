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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the agent's bounded starting context and the full internal index used by later tool calls.
 */
public class DiagnosticContextIndexer {

    public static final int MAX_STARTING_HIGHLIGHTS = 24;
    public static final int MAX_STARTING_STRUCTURED_SLICES = 28;
    public static final int MAX_STARTING_REPRESENTATIVE_SLICES = 24;

    private static final int RAW_WINDOW_RADIUS = 5;
    private static final int GC_EVENT_SEARCH_RADIUS_LINES = 24;
    private static final int GC_EVENT_PADDING_LINES = 3;
    private static final int GC_FAILURE_CLUSTER_WINDOW_LINES = 48;
    private static final int GC_TAIL_SLICE_LINES = 24;
    private static final int GC_INCIDENT_FALLBACK_MIN_SLICES = 3;
    private static final int RAW_CHUNK_LINES = 24;
    private static final int BROAD_RAW_CHUNK_TARGET = 5;
    private static final int SMALL_TEXT_ARTIFACT_MAX_CHARS = 16000;
    private static final int SMALL_TEXT_ARTIFACT_MAX_LINES = 160;
    private static final int SMALL_TEXT_ARTIFACT_SLICE_CHAR_LIMIT = 16000;
    private static final String EXTERNAL_BINARY_JFR = "external-binary-jfr";
    private static final Set<String> INTERNAL_ONLY_EXTRACTED_DATA_KEYS = Set.of(
        "eventTypeDetails",
        "incidentWindows",
        "jvmRuntimeInfo",
        "timelineEvents"
    );
    private static final Pattern GC_ID_PATTERN = Pattern.compile("GC\\((\\d+)\\)");
    private static final List<String> COMMON_PRIORITY_KEYS = List.of("summary", "coverage", "totals");
    private static final List<String> GC_PRIORITY_KEYS = List.of(
        "summary",
        "collector",
        "collectorPressureSummary",
        "pauseBreakdown",
        "recoverySummary",
        "failureSummary",
        "concurrentSummary",
        "g1CycleProgressionSummary",
        "phaseSummary",
        "metaspace",
        "cpuSummary",
        "workerSummary",
        "humongousSummary",
        "pauses",
        "gcCycles",
        "allocationStalls",
        "mmuSamples",
        "phaseSamples",
        "cpuSamples",
        "workerSamples",
        "humongousRegionSamples",
        "failureSignals"
    );
    private static final List<String> JFR_PRIORITY_KEYS = List.of(
        "summary",
        "coverage",
        "incidentWindowSummary",
        "chronologyHighlights",
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
        Map<String, String> structuredBlocks = structuredBlocks(parsedArtifact, highlights);
        List<String> rawLines = rawLines(inputArtifact);
        Map<String, IndexedArtifactDiagnosticContext.LineRange> rawSections = rawSections(inputArtifact, rawLines);
        List<ContextSlice> allSlices = allSlices(inputArtifact, parsedArtifact, rawLines);

        List<ContextSlice> structuredSlices = structuredBlocks.entrySet().stream()
            .map(entry -> buildStructuredSlice(entry.getKey(), entry.getValue()))
            .toList();

        StartingContextBudget effectiveBudget = effectiveBudget(
            inputArtifact,
            highlights.size(),
            structuredSlices,
            allSlices,
            rawLines
        );

        List<DiagnosticHighlight> startingHighlights = highlights.stream()
            .limit(Math.min(MAX_STARTING_HIGHLIGHTS, effectiveBudget.maxHighlights()))
            .toList();
        List<ContextSlice> boundedStructuredSlices = structuredSlices.stream()
            .limit(Math.min(MAX_STARTING_STRUCTURED_SLICES, effectiveBudget.maxStructuredSlices()))
            .map(slice -> boundedSlice(slice, effectiveBudget.maxStructuredSliceChars()))
            .toList();
        List<ContextSlice> boundedRepresentativeSlices = allSlices.stream()
            .limit(Math.min(MAX_STARTING_REPRESENTATIVE_SLICES, effectiveBudget.maxRepresentativeSlices()))
            .map(slice -> boundedSlice(slice, effectiveBudget.maxRepresentativeSliceChars()))
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

    private StartingContextBudget effectiveBudget(
        InputArtifact inputArtifact,
        int highlightCount,
        List<ContextSlice> structuredSlices,
        List<ContextSlice> representativeSlices,
        List<String> rawLines
    ) {
        if (!shouldFrontLoadSmallTextArtifact(inputArtifact, structuredSlices, representativeSlices, rawLines)) {
            return budget;
        }

        return new StartingContextBudget(
            Math.max(budget.maxHighlights(), Math.min(MAX_STARTING_HIGHLIGHTS, highlightCount)),
            Math.max(budget.maxStructuredSlices(), Math.min(MAX_STARTING_STRUCTURED_SLICES, structuredSlices.size())),
            Math.max(budget.maxRepresentativeSlices(), Math.min(MAX_STARTING_REPRESENTATIVE_SLICES, representativeSlices.size())),
            Math.max(budget.maxStructuredSliceChars(), SMALL_TEXT_ARTIFACT_SLICE_CHAR_LIMIT),
            Math.max(budget.maxRepresentativeSliceChars(), SMALL_TEXT_ARTIFACT_SLICE_CHAR_LIMIT)
        );
    }

    private boolean shouldFrontLoadSmallTextArtifact(
        InputArtifact inputArtifact,
        List<ContextSlice> structuredSlices,
        List<ContextSlice> representativeSlices,
        List<String> rawLines
    ) {
        if (inputArtifact == null || rawLines.isEmpty()) {
            return false;
        }
        if (inputArtifact.type() != ArtifactType.THREAD_DUMP && inputArtifact.type() != ArtifactType.GC_LOG) {
            return false;
        }

        int contentLength = inputArtifact.content() != null ? inputArtifact.content().length() : 0;
        if (contentLength <= 0 || contentLength > SMALL_TEXT_ARTIFACT_MAX_CHARS) {
            return false;
        }
        if (rawLines.size() > SMALL_TEXT_ARTIFACT_MAX_LINES) {
            return false;
        }
        if (structuredSlices.size() > MAX_STARTING_STRUCTURED_SLICES || representativeSlices.size() > MAX_STARTING_REPRESENTATIVE_SLICES) {
            return false;
        }
        return true;
    }

    private Map<String, Object> structuredFacts(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> facts = new LinkedHashMap<>();
        addScalarFacts(facts, "summary", parsedArtifact.extractedData().get("summary"), 12);
        addScalarFacts(facts, "coverage", parsedArtifact.extractedData().get("coverage"), 10);
        addScalarFacts(facts, "totals", parsedArtifact.extractedData().get("totals"), 10);
        if (parsedArtifact.type() == ArtifactType.GC_LOG) {
            addGcStructuredFacts(facts, parsedArtifact.extractedData());
        }
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

    private void addGcStructuredFacts(Map<String, Object> target, Map<String, Object> extractedData) {
        if (extractedData == null || extractedData.isEmpty()) {
            return;
        }

        Object collectorValue = extractedData.get("collector");
        if (collectorValue == null) {
            return;
        }

        String collector = stringValue(collectorValue);
        if (collector.isBlank()) {
            return;
        }

        target.put("collector", collector);
        String focusAreas = gcCollectorFocusAreas(collector);
        if (!focusAreas.isBlank()) {
            target.put("collectorFocusAreas", focusAreas);
        }
        String interpretationHint = gcCollectorInterpretationHint(collector);
        if (!interpretationHint.isBlank()) {
            target.put("collectorInterpretationHint", interpretationHint);
        }
        addScalarFacts(target, "collectorSummary", extractedData.get("collectorPressureSummary"), 16, true);
        addScalarFacts(target, "recovery", extractedData.get("recoverySummary"), 10);
        addScalarFacts(target, "failure", extractedData.get("failureSummary"), 10, true);
        addScalarFacts(target, "concurrent", extractedData.get("concurrentSummary"), 8, true);
        if ("G1".equalsIgnoreCase(collector)) {
            addScalarFacts(target, "g1", extractedData.get("g1CycleProgressionSummary"), 8);
        }
    }

    private void addScalarFacts(Map<String, Object> target, String prefix, Object value, int limit) {
        addScalarFacts(target, prefix, value, limit, false);
    }

    private void addScalarFacts(
        Map<String, Object> target,
        String prefix,
        Object value,
        int limit,
        boolean omitLowSignalScalars
    ) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!DiagnosticContextRenderSupport.isScalar(entry.getValue())) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            if (omitLowSignalScalars && !hasMeaningfulContextValue(entry.getValue())) {
                continue;
            }
            target.put(prefix + "." + entry.getKey(), entry.getValue());
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

    private Map<String, String> structuredBlocks(ParsedArtifact parsedArtifact, List<DiagnosticHighlight> highlights) {
        LinkedHashMap<String, String> blocks = new LinkedHashMap<>();
        if (parsedArtifact != null) {
            Map<String, Object> extractedData = orderedExtractedData(parsedArtifact);
            for (Map.Entry<String, Object> entry : extractedData.entrySet()) {
                if (!hasMeaningfulContextValue(entry.getValue())) {
                    continue;
                }
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

    private boolean hasMeaningfulContextValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            return !Double.isNaN(numericValue) && numericValue != 0.0d;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return false;
            }
            for (Object childValue : map.values()) {
                if (hasMeaningfulContextValue(childValue)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return false;
            }
            for (Object childValue : list) {
                if (hasMeaningfulContextValue(childValue)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private Map<String, Object> orderedExtractedData(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        appendPreferredKeys(ordered, parsedArtifact.extractedData(), COMMON_PRIORITY_KEYS);
        if (parsedArtifact.type() == ArtifactType.GC_LOG) {
            appendPreferredKeys(ordered, parsedArtifact.extractedData(), GC_PRIORITY_KEYS);
        }
        if (parsedArtifact.type() == ArtifactType.JFR) {
            appendPreferredKeys(ordered, parsedArtifact.extractedData(), JFR_PRIORITY_KEYS);
        }
        for (Map.Entry<String, Object> entry : parsedArtifact.extractedData().entrySet()) {
            if (INTERNAL_ONLY_EXTRACTED_DATA_KEYS.contains(entry.getKey())) {
                continue;
            }
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private void appendPreferredKeys(Map<String, Object> target, Map<String, Object> source, List<String> keys) {
        for (String key : keys) {
            if (source.containsKey(key) && !INTERNAL_ONLY_EXTRACTED_DATA_KEYS.contains(key)) {
                target.putIfAbsent(key, source.get(key));
            }
        }
    }

    private Map<String, Object> highlightPayload(DiagnosticHighlight highlight) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", highlight.label());
        payload.put("detail", highlight.detail());
        payload.put("source", DiagnosticContextRenderSupport.renderSourceAnchor(highlight.traceability()));
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

    private String gcCollectorFocusAreas(String collector) {
        return switch (collector == null ? "" : collector.strip().toUpperCase(Locale.ROOT)) {
            case "G1" ->
                "Prioritize evacuation failures, humongous-region growth, mixed-versus-full collection behavior, and whether post-GC occupancy stays high.";
            case "ZGC" ->
                "Prioritize allocation stalls, concurrent-cycle progress, headroom after cycles, and any pauses or stalls that violate the expected low-pause profile.";
            case "PARALLEL" ->
                "Prioritize young/full stop-the-world pause frequency, old-generation saturation, throughput-versus-pause trade-offs, and metaspace-triggered full GCs.";
            case "SERIAL" ->
                "Prioritize stop-the-world pause impact, small-heap saturation, old-generation exhaustion, and metaspace-triggered full GCs.";
            case "CMS" ->
                "Prioritize old-generation saturation, fragmentation or promotion pressure, concurrent-cycle progress, and fallback full GCs that suggest CMS could not stay ahead.";
            case "UNKNOWN" -> "";
            default -> "";
        };
    }

    private String gcCollectorInterpretationHint(String collector) {
        return switch (collector == null ? "" : collector.strip().toUpperCase(Locale.ROOT)) {
            case "G1" ->
                "Interpret full GCs, evacuation failure, and humongous-region pressure as especially important for collector-fit analysis.";
            case "ZGC" ->
                "Interpret allocation stalls and retained occupancy after cycles as more important than raw pause counts alone.";
            case "PARALLEL", "SERIAL" ->
                "Interpret repeated full GCs and long stop-the-world pauses as direct signs of collector or heap pressure.";
            case "CMS" ->
                "Interpret fallback full GCs, remark pressure, and old-generation retention as strong signs of CMS distress.";
            case "UNKNOWN" ->
                "Limit collector-specific claims and describe the observed pause or occupancy behavior directly.";
            default -> "";
        };
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
        Set<String> addedIds = new LinkedHashSet<>();
        Set<String> addedLocations = new LinkedHashSet<>();
        List<ContextSlice> rawChunks = rawChunkSlices(inputArtifact, rawLines);
        if (inputArtifact != null && inputArtifact.type() == ArtifactType.GC_LOG) {
            addGcIncidentSlices(inputArtifact, parsedArtifact, rawLines, slices, addedIds, addedLocations);
            if (slices.size() < GC_INCIDENT_FALLBACK_MIN_SLICES) {
                addEvidenceWindowSlices(inputArtifact, parsedArtifact, rawLines, slices, addedIds, addedLocations);
                addBroadRawChunkSlices(rawChunks, slices, addedIds, addedLocations);
            }
        } else {
            addBroadRawChunkSlices(rawChunks, slices, addedIds, addedLocations);
            addEvidenceWindowSlices(inputArtifact, parsedArtifact, rawLines, slices, addedIds, addedLocations);
        }
        for (ContextSlice chunk : rawChunks) {
            addSliceIfAbsent(slices, addedIds, addedLocations, chunk);
        }
        return List.copyOf(slices);
    }

    private void addGcIncidentSlices(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        List<String> rawLines,
        List<ContextSlice> slices,
        Set<String> addedIds,
        Set<String> addedLocations
    ) {
        if (parsedArtifact == null) {
            return;
        }

        List<Map<String, Object>> pauses = extractedEventList(parsedArtifact, "pauses");
        List<Map<String, Object>> gcCycles = extractedEventList(parsedArtifact, "gcCycles");
        List<Map<String, Object>> allocationStalls = extractedEventList(parsedArtifact, "allocationStalls");
        List<Map<String, Object>> humongousSamples = extractedEventList(parsedArtifact, "humongousRegionSamples");
        List<Map<String, Object>> phaseSamples = extractedEventList(parsedArtifact, "phaseSamples");
        List<Map<String, Object>> failureSignals = extractedEventList(parsedArtifact, "failureSignals");
        String collector = gcCollector(parsedArtifact);

        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildCollectorPriorityGcWindowSlice(inputArtifact, collector, rawLines, pauses, allocationStalls, phaseSamples, failureSignals)
        );

        if ("G1".equalsIgnoreCase(collector)) {
            addSliceIfAbsent(
                slices,
                addedIds,
                addedLocations,
                buildGcEventWindowSlice(
                    inputArtifact,
                    "gc-incident-first-to-space-distress",
                    "First to-space-distress window",
                    rawLines,
                    firstEvent(failureSignals, this::isToSpaceDistressSignal)
                )
            );
        }

        if ("CMS".equalsIgnoreCase(collector)) {
            addSliceIfAbsent(
                slices,
                addedIds,
                addedLocations,
                buildGcEventWindowSlice(
                    inputArtifact,
                    "gc-incident-first-concurrent-mode-failure",
                    "First concurrent-mode-failure window",
                    rawLines,
                    firstEvent(failureSignals, this::isConcurrentModeFailureSignal)
                )
            );
            addSliceIfAbsent(
                slices,
                addedIds,
                addedLocations,
                buildGcEventWindowSlice(
                    inputArtifact,
                    "gc-incident-longest-concurrent-phase",
                    "Longest concurrent-phase window",
                    rawLines,
                    maxEventByDouble(
                        phaseSamples,
                        sample -> "CONCURRENT".equals(stringValue(sample.get("phaseKind"))),
                        "durationMs"
                    )
                )
            );
        }

        boolean skipFirstFullGcWindow =
            "G1".equalsIgnoreCase(collector)
                || "CMS".equalsIgnoreCase(collector)
                || "SERIAL".equalsIgnoreCase(collector)
                || "PARALLEL".equalsIgnoreCase(collector);

        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-first-evacuation-failure",
                "First evacuation-failure window",
                rawLines,
                firstEvent(pauses, this::isEvacuationFailurePause)
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            skipFirstFullGcWindow
                ? null
                : buildGcEventWindowSlice(
                    inputArtifact,
                    "gc-incident-first-full-gc",
                    "First full-GC window",
                    rawLines,
                    firstEvent(pauses, this::isFullGcEvent)
                )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-longest-full-gc",
                "Longest full-GC window",
                rawLines,
                maxEventByDouble(pauses, this::isFullGcEvent, "pauseMs")
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-first-concurrent-abort",
                "First concurrent-abort window",
                rawLines,
                firstEvent(failureSignals, signal -> "CONCURRENT_ABORT".equals(stringValue(signal.get("signalType"))))
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-first-full-compaction-attempt",
                "First full-compaction-attempt window",
                rawLines,
                firstEvent(failureSignals, signal -> "FULL_COMPACTION_ATTEMPT".equals(stringValue(signal.get("signalType"))))
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-peak-occupancy",
                "Peak post-GC-occupancy window",
                rawLines,
                peakOccupancyEvent(pauses, gcCycles)
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-longest-allocation-stall",
                "Longest allocation-stall window",
                rawLines,
                maxEventByDouble(allocationStalls, event -> true, "stallMs")
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcEventWindowSlice(
                inputArtifact,
                "gc-incident-peak-humongous-growth",
                "Peak humongous-region-growth window",
                rawLines,
                maxEventByLong(humongousSamples, sample -> longValue(sample.get("deltaRegions")) > 0L, "deltaRegions")
            )
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcFailureClusterSlice(inputArtifact, rawLines, pauses, failureSignals)
        );
        addSliceIfAbsent(
            slices,
            addedIds,
            addedLocations,
            buildGcTailSlice(inputArtifact, rawLines)
        );
    }

    private void addEvidenceWindowSlices(
        InputArtifact inputArtifact,
        ParsedArtifact parsedArtifact,
        List<String> rawLines,
        List<ContextSlice> slices,
        Set<String> addedIds,
        Set<String> addedLocations
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
                addSliceIfAbsent(slices, addedIds, addedLocations, slice);
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

    private void addBroadRawChunkSlices(
        List<ContextSlice> rawChunks,
        List<ContextSlice> slices,
        Set<String> addedIds,
        Set<String> addedLocations
    ) {
        if (rawChunks.isEmpty()) {
            return;
        }
        if (rawChunks.size() <= BROAD_RAW_CHUNK_TARGET) {
            for (ContextSlice rawChunk : rawChunks) {
                addSliceIfAbsent(slices, addedIds, addedLocations, rawChunk);
            }
            return;
        }
        LinkedHashSet<Integer> anchorIndexes = new LinkedHashSet<>();
        anchorIndexes.add(0);
        anchorIndexes.add(rawChunks.size() - 1);
        if (rawChunks.size() > 2) {
            anchorIndexes.add(rawChunks.size() / 2);
        }
        for (int anchorIndex : evenlySpacedIndexes(rawChunks.size(), BROAD_RAW_CHUNK_TARGET)) {
            anchorIndexes.add(anchorIndex);
        }
        for (int anchorIndex : anchorIndexes) {
            addSliceIfAbsent(slices, addedIds, addedLocations, rawChunks.get(anchorIndex));
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

    private void addSliceIfAbsent(List<ContextSlice> slices, Set<String> addedIds, Set<String> addedLocations, ContextSlice slice) {
        if (slice == null) {
            return;
        }
        String locationKey = slice.kind() + ":" + slice.traceability();
        if (addedIds.contains(slice.sliceId()) || addedLocations.contains(locationKey)) {
            return;
        }
        addedIds.add(slice.sliceId());
        addedLocations.add(locationKey);
        slices.add(slice);
    }

    private ContextSlice buildStructuredSlice(String key, String content) {
        String traceability = "extractedData." + key;
        if (key.startsWith("highlight-")) {
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
        return buildRawRangeSlice(inputArtifact, sliceId, label, rawLines, startLineInclusive, endLineInclusive);
    }

    private ContextSlice buildRawRangeSlice(
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

    private ContextSlice buildGcEventWindowSlice(
        InputArtifact inputArtifact,
        String sliceId,
        String label,
        List<String> rawLines,
        Map<String, Object> event
    ) {
        if (event == null || event.isEmpty()) {
            return null;
        }
        int lineNumber = lineNumber(event);
        if (lineNumber <= 0) {
            return null;
        }
        IndexedArtifactDiagnosticContext.LineRange range = gcEventRange(rawLines, event);
        return buildRawRangeSlice(
            inputArtifact,
            sliceId,
            label,
            rawLines,
            range.startLineInclusive(),
            range.endLineInclusive()
        );
    }

    private ContextSlice buildGcFailureClusterSlice(
        InputArtifact inputArtifact,
        List<String> rawLines,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> failureSignals
    ) {
        List<Integer> incidentLines = new ArrayList<>();
        for (Map<String, Object> pause : pauses) {
            if (isEvacuationFailurePause(pause) || isFullGcEvent(pause)) {
                addIncidentLine(incidentLines, lineNumber(pause));
            }
        }
        for (Map<String, Object> failureSignal : failureSignals) {
            addIncidentLine(incidentLines, lineNumber(failureSignal));
        }
        if (incidentLines.size() < 2) {
            return null;
        }

        incidentLines.sort(Integer::compareTo);
        int bestStartIndex = 0;
        int bestEndIndex = 0;
        int bestCount = 1;
        int bestSpan = Integer.MAX_VALUE;
        int right = 0;
        for (int left = 0; left < incidentLines.size(); left++) {
            while (right + 1 < incidentLines.size()
                && incidentLines.get(right + 1) - incidentLines.get(left) <= GC_FAILURE_CLUSTER_WINDOW_LINES) {
                right++;
            }
            int count = right - left + 1;
            int span = incidentLines.get(right) - incidentLines.get(left);
            if (count > bestCount || (count == bestCount && span < bestSpan)) {
                bestCount = count;
                bestSpan = span;
                bestStartIndex = left;
                bestEndIndex = right;
            }
        }
        if (bestCount < 2) {
            return null;
        }

        return buildRawRangeSlice(
            inputArtifact,
            "gc-incident-densest-failure-region",
            "Densest GC-failure region",
            rawLines,
            Math.max(1, incidentLines.get(bestStartIndex) - GC_EVENT_PADDING_LINES),
            Math.min(rawLines.size(), incidentLines.get(bestEndIndex) + GC_EVENT_PADDING_LINES)
        );
    }

    private ContextSlice buildCollectorPriorityGcWindowSlice(
        InputArtifact inputArtifact,
        String collector,
        List<String> rawLines,
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        String normalizedCollector = collector == null ? "" : collector.strip().toUpperCase(Locale.ROOT);
        return switch (normalizedCollector) {
            case "G1" -> buildGcDominantWindowSlice(
                inputArtifact,
                "gc-incident-dominant-g1-distress-window",
                "Dominant G1 distress window",
                rawLines,
                combineGcEvents(
                    pauses.stream().filter(pause -> isFullGcEvent(pause) || isEvacuationFailurePause(pause)).toList(),
                    failureSignals
                )
            );
            case "CMS" -> buildGcDominantWindowSlice(
                inputArtifact,
                "gc-incident-dominant-cms-fallback-window",
                "Dominant CMS fallback window",
                rawLines,
                combineGcEvents(
                    pauses.stream().filter(this::isFullGcEvent).toList(),
                    failureSignals.stream().filter(this::isConcurrentModeFailureSignal).toList(),
                    phaseSamples.stream().filter(sample -> "CONCURRENT".equals(stringValue(sample.get("phaseKind")))).toList()
                )
            );
            case "SERIAL", "PARALLEL" -> buildGcDominantWindowSlice(
                inputArtifact,
                "gc-incident-dominant-full-gc-window",
                "Dominant full-GC pressure window",
                rawLines,
                pauses.stream().filter(this::isFullGcEvent).toList()
            );
            case "ZGC" -> buildGcDominantWindowSlice(
                inputArtifact,
                "gc-incident-dominant-zgc-stall-window",
                "Dominant ZGC stall window",
                rawLines,
                allocationStalls
            );
            default -> null;
        };
    }

    @SafeVarargs
    private final List<Map<String, Object>> combineGcEvents(List<Map<String, Object>>... eventGroups) {
        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (List<Map<String, Object>> eventGroup : eventGroups) {
            if (eventGroup == null) {
                continue;
            }
            for (Map<String, Object> event : eventGroup) {
                if (event == null || event.isEmpty() || lineNumber(event) <= 0) {
                    continue;
                }
                String uniqueKey = lineNumber(event)
                    + ":"
                    + gcId(event)
                    + ":"
                    + stringValue(event.get("event"))
                    + ":"
                    + stringValue(event.get("signalType"))
                    + ":"
                    + stringValue(event.get("phase"));
                unique.putIfAbsent(uniqueKey, event);
            }
        }
        return List.copyOf(unique.values());
    }

    private ContextSlice buildGcDominantWindowSlice(
        InputArtifact inputArtifact,
        String sliceId,
        String label,
        List<String> rawLines,
        List<Map<String, Object>> events
    ) {
        List<Map<String, Object>> dominantWindowEvents = dominantGcWindowEvents(events);
        if (dominantWindowEvents.isEmpty()) {
            return null;
        }

        int startLine = Integer.MAX_VALUE;
        int endLine = 0;
        for (Map<String, Object> event : dominantWindowEvents) {
            IndexedArtifactDiagnosticContext.LineRange range = gcEventRange(rawLines, event);
            startLine = Math.min(startLine, range.startLineInclusive());
            endLine = Math.max(endLine, range.endLineInclusive());
        }
        if (startLine == Integer.MAX_VALUE || endLine <= 0) {
            return null;
        }

        return buildRawRangeSlice(inputArtifact, sliceId, label, rawLines, startLine, endLine);
    }

    private List<Map<String, Object>> dominantGcWindowEvents(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> ordered = new ArrayList<>(events.stream()
            .filter(event -> lineNumber(event) > 0)
            .toList());
        ordered.sort((left, right) -> Integer.compare(lineNumber(left), lineNumber(right)));
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> bestWindow = List.of();
        int bestCount = 0;
        double bestWeight = Double.NEGATIVE_INFINITY;
        int bestSpan = Integer.MAX_VALUE;
        for (int left = 0; left < ordered.size(); left++) {
            int startLine = lineNumber(ordered.get(left));
            List<Map<String, Object>> currentWindow = new ArrayList<>();
            double currentWeight = 0.0d;
            for (int right = left; right < ordered.size(); right++) {
                Map<String, Object> event = ordered.get(right);
                int lineNumber = lineNumber(event);
                if (lineNumber - startLine > GC_FAILURE_CLUSTER_WINDOW_LINES) {
                    break;
                }
                currentWindow.add(event);
                currentWeight += gcWindowEventWeight(event);
                int currentCount = currentWindow.size();
                int currentSpan = lineNumber - startLine;
                if (currentCount > bestCount
                    || (currentCount == bestCount && currentWeight > bestWeight)
                    || (currentCount == bestCount && Double.compare(currentWeight, bestWeight) == 0 && currentSpan < bestSpan)) {
                    bestWindow = List.copyOf(currentWindow);
                    bestCount = currentCount;
                    bestWeight = currentWeight;
                    bestSpan = currentSpan;
                }
            }
        }
        return bestWindow;
    }

    private double gcWindowEventWeight(Map<String, Object> event) {
        double weight = Math.max(1.0d, Math.max(doubleValue(event.get("pauseMs")), doubleValue(event.get("durationMs"))));
        if (!stringValue(event.get("signalType")).isBlank()) {
            weight += 25.0d;
        }
        if ("CONCURRENT".equals(stringValue(event.get("phaseKind")))) {
            weight += 10.0d;
        }
        return weight;
    }

    private void addIncidentLine(List<Integer> incidentLines, int lineNumber) {
        if (lineNumber > 0) {
            incidentLines.add(lineNumber);
        }
    }

    private ContextSlice buildGcTailSlice(InputArtifact inputArtifact, List<String> rawLines) {
        if (rawLines.isEmpty()) {
            return null;
        }
        return buildRawRangeSlice(
            inputArtifact,
            "gc-incident-tail",
            "Latest GC-log tail",
            rawLines,
            Math.max(1, rawLines.size() - GC_TAIL_SLICE_LINES + 1),
            rawLines.size()
        );
    }

    private IndexedArtifactDiagnosticContext.LineRange gcEventRange(List<String> rawLines, Map<String, Object> event) {
        int lineNumber = lineNumber(event);
        int start = Math.max(1, lineNumber - RAW_WINDOW_RADIUS);
        int end = Math.min(rawLines.size(), lineNumber + RAW_WINDOW_RADIUS);
        long gcId = gcId(event);
        if (gcId <= 0L) {
            return legacyGcEventRange(rawLines, lineNumber);
        }

        String gcIdToken = "GC(" + gcId + ")";
        int searchStart = Math.max(1, lineNumber - GC_EVENT_SEARCH_RADIUS_LINES);
        int searchEnd = Math.min(rawLines.size(), lineNumber + GC_EVENT_SEARCH_RADIUS_LINES);
        int firstMatch = lineNumber;
        int lastMatch = lineNumber;
        for (int index = searchStart; index <= searchEnd; index++) {
            if (rawLines.get(index - 1).contains(gcIdToken)) {
                firstMatch = Math.min(firstMatch, index);
                lastMatch = Math.max(lastMatch, index);
            }
        }

        return new IndexedArtifactDiagnosticContext.LineRange(
            Math.max(1, Math.min(start, firstMatch - GC_EVENT_PADDING_LINES)),
            Math.min(rawLines.size(), Math.max(end, lastMatch + GC_EVENT_PADDING_LINES))
        );
    }

    private IndexedArtifactDiagnosticContext.LineRange legacyGcEventRange(List<String> rawLines, int lineNumber) {
        if (lineNumber <= 0 || rawLines.isEmpty()) {
            return new IndexedArtifactDiagnosticContext.LineRange(1, Math.min(rawLines.size(), 1));
        }

        int start = Math.max(1, lineNumber - RAW_WINDOW_RADIUS);
        int end = Math.min(rawLines.size(), lineNumber + RAW_WINDOW_RADIUS);
        int blockEnd = lineNumber;
        while (blockEnd < rawLines.size() && isLegacyGcContinuationLine(rawLines.get(blockEnd))) {
            blockEnd++;
        }

        return new IndexedArtifactDiagnosticContext.LineRange(
            start,
            Math.min(rawLines.size(), Math.max(end, blockEnd))
        );
    }

    private boolean isLegacyGcContinuationLine(String line) {
        return line != null
            && !line.isBlank()
            && Character.isWhitespace(line.charAt(0))
            && line.stripLeading().startsWith("[");
    }

    private List<Map<String, Object>> extractedEventList(ParsedArtifact parsedArtifact, String key) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return List.of();
        }
        Object value = parsedArtifact.extractedData().get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                continue;
            }
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            events.add(Collections.unmodifiableMap(normalized));
        }
        return List.copyOf(events);
    }

    private Map<String, Object> firstEvent(List<Map<String, Object>> events, Predicate<Map<String, Object>> predicate) {
        Map<String, Object> selected = null;
        int bestLine = Integer.MAX_VALUE;
        int ordinal = 0;
        int bestOrdinal = Integer.MAX_VALUE;
        for (Map<String, Object> event : events) {
            if (!predicate.test(event)) {
                ordinal++;
                continue;
            }
            int lineNumber = lineNumber(event);
            if ((lineNumber > 0 && lineNumber < bestLine) || (lineNumber <= 0 && selected == null) || ordinal < bestOrdinal) {
                selected = event;
                if (lineNumber > 0) {
                    bestLine = lineNumber;
                }
                bestOrdinal = ordinal;
            }
            ordinal++;
        }
        return selected;
    }

    private Map<String, Object> maxEventByDouble(
        List<Map<String, Object>> events,
        Predicate<Map<String, Object>> predicate,
        String metricKey
    ) {
        Map<String, Object> selected = null;
        double bestMetric = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> event : events) {
            if (!predicate.test(event)) {
                continue;
            }
            double metric = doubleValue(event.get(metricKey));
            if (selected == null || metric > bestMetric) {
                selected = event;
                bestMetric = metric;
            }
        }
        return selected;
    }

    private Map<String, Object> maxEventByLong(
        List<Map<String, Object>> events,
        Predicate<Map<String, Object>> predicate,
        String metricKey
    ) {
        Map<String, Object> selected = null;
        long bestMetric = Long.MIN_VALUE;
        for (Map<String, Object> event : events) {
            if (!predicate.test(event)) {
                continue;
            }
            long metric = longValue(event.get(metricKey));
            if (selected == null || metric > bestMetric) {
                selected = event;
                bestMetric = metric;
            }
        }
        return selected;
    }

    private Map<String, Object> peakOccupancyEvent(List<Map<String, Object>> pauses, List<Map<String, Object>> gcCycles) {
        Map<String, Object> selected = null;
        double bestRatio = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> event : pauses) {
            double ratio = doubleValue(event.get("afterOccupancyRatio"));
            if (selected == null || ratio > bestRatio) {
                selected = event;
                bestRatio = ratio;
            }
        }
        for (Map<String, Object> event : gcCycles) {
            double ratio = doubleValue(event.get("afterOccupancyRatio"));
            if (selected == null || ratio > bestRatio) {
                selected = event;
                bestRatio = ratio;
            }
        }
        return selected;
    }

    private String gcCollector(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return "";
        }
        return stringValue(parsedArtifact.extractedData().get("collector"));
    }

    private boolean isFullGcEvent(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("full");
    }

    private boolean isEvacuationFailurePause(Map<String, Object> pause) {
        return stringValue(pause.get("event")).toLowerCase(Locale.ROOT).contains("evacuation failure");
    }

    private boolean isToSpaceDistressSignal(Map<String, Object> signal) {
        return "TO_SPACE_EXHAUSTED".equals(stringValue(signal.get("signalType")));
    }

    private boolean isConcurrentModeFailureSignal(Map<String, Object> signal) {
        return "CONCURRENT_MODE_FAILURE".equals(stringValue(signal.get("signalType")));
    }

    private int lineNumber(Map<String, Object> event) {
        long lineNumber = longValue(event.get("lineNumber"));
        return lineNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, lineNumber);
    }

    private long gcId(Map<String, Object> event) {
        long explicitGcId = longValue(event.get("gcId"));
        if (explicitGcId > 0L) {
            return explicitGcId;
        }
        Matcher matcher = GC_ID_PATTERN.matcher(stringValue(event.get("rawLine")));
        if (!matcher.find()) {
            return 0L;
        }
        return Long.parseLong(matcher.group(1));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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
        return DiagnosticContextRenderSupport.humanizeIdentifier(key);
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
        private static final Pattern PARAMETER_SIZE_PATTERN =
            Pattern.compile("(?:^|[:/_-])(\\d+(?:\\.\\d+)?)b(?:[^a-z0-9]|$)");

        public static StartingContextBudget defaultBudget() {
            return forApproximateContextWindowTokens(null);
        }

        public static StartingContextBudget forModel(
            String providerId,
            String modelName,
            String modelFamily,
            Integer contextWindowTokens
        ) {
            if (prefersCompactLocalBudget(providerId, modelName, modelFamily)) {
                return forCompactLocalModel(contextWindowTokens);
            }
            return forApproximateContextWindowTokens(contextWindowTokens);
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

        private static StartingContextBudget forCompactLocalModel(Integer contextWindowTokens) {
            int tokens = contextWindowTokens != null && contextWindowTokens > 0
                ? contextWindowTokens
                : DEFAULT_CONTEXT_WINDOW_TOKENS;
            if (tokens >= 65536) {
                return new StartingContextBudget(16, 18, 16, 5200, 3800);
            }
            if (tokens >= 32768) {
                return new StartingContextBudget(12, 14, 12, 3600, 2600);
            }
            if (tokens >= 16384) {
                return new StartingContextBudget(10, 10, 8, 2400, 1600);
            }
            return new StartingContextBudget(8, 8, 6, 1800, 1200);
        }

        private static boolean prefersCompactLocalBudget(String providerId, String modelName, String modelFamily) {
            if (providerId == null || !"OLLAMA".equalsIgnoreCase(providerId.strip())) {
                return false;
            }

            Double parameterSizeBillions = approximateParameterSizeBillions(modelName);
            if (parameterSizeBillions != null) {
                return parameterSizeBillions <= 8.0d;
            }

            String normalizedModelName = normalizeModelIdentifier(modelName);
            if (normalizedModelName.startsWith("llama3.2")) {
                return true;
            }

            String normalizedModelFamily = normalizeModelIdentifier(modelFamily);
            return normalizedModelFamily.startsWith("llama3.2");
        }

        private static Double approximateParameterSizeBillions(String modelName) {
            String normalizedModelName = normalizeModelIdentifier(modelName);
            if (normalizedModelName.isBlank()) {
                return null;
            }

            Matcher matcher = PARAMETER_SIZE_PATTERN.matcher(normalizedModelName);
            if (!matcher.find()) {
                return null;
            }

            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static String normalizeModelIdentifier(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }

            String normalized = value.strip().toLowerCase(Locale.ROOT);
            int slash = normalized.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < normalized.length()) {
                normalized = normalized.substring(slash + 1);
            }
            return normalized;
        }
    }
}
