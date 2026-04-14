package com.javaassistant.compare;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.assessment.AssessmentResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HeapHistogramComparator implements ArtifactComparator {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HEAP_HISTOGRAM;
    }

    @Override
    public AssessmentResult compare(InputArtifact baseline, ParsedArtifact baselineParsed, InputArtifact current, ParsedArtifact currentParsed) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        List<Map<String, Object>> baselineEntries = entries(baselineParsed);
        List<Map<String, Object>> currentEntries = entries(currentParsed);
        if (baselineEntries.isEmpty() || currentEntries.isEmpty()) {
            missingData.add("Heap histogram comparison could not parse entries from one or both files.");
            return new AssessmentResult(findings, actions, missingData);
        }

        Map<String, Object> baselineSummary = summary(baselineParsed);
        Map<String, Object> currentSummary = summary(currentParsed);

        Map<String, Map<String, Object>> baselineByClass = indexByClassName(baselineEntries);
        List<Map<String, Object>> deltas = new ArrayList<>();
        for (Map<String, Object> currentEntry : currentEntries) {
            String className = String.valueOf(currentEntry.get("className"));
            Map<String, Object> baselineEntry = baselineByClass.get(className);
            long currentBytes = longValue(currentEntry, "bytes");
            long baselineBytes = baselineEntry != null ? longValue(baselineEntry, "bytes") : 0L;
            long currentInstances = longValue(currentEntry, "instances");
            long baselineInstances = baselineEntry != null ? longValue(baselineEntry, "instances") : 0L;
            Map<String, Object> delta = new HashMap<>();
            delta.put("className", className);
            delta.put("byteDelta", currentBytes - baselineBytes);
            delta.put("instanceDelta", currentInstances - baselineInstances);
            delta.put("cacheLike", boolValue(currentEntry, "cacheLike"));
            delta.put("collectionLike", boolValue(currentEntry, "collectionLike"));
            delta.put("payloadLike", boolValue(currentEntry, "payloadLike"));
            deltas.add(delta);
        }

        List<Map<String, Object>> positiveGrowth = deltas.stream()
            .filter(delta -> longValue(delta, "byteDelta") > 0L)
            .sorted(Comparator.comparingLong((Map<String, Object> delta) -> longValue(delta, "byteDelta")).reversed())
            .toList();

        Map<String, Object> topGrowth = positiveGrowth.stream().findFirst().orElse(Map.of());
        if (!topGrowth.isEmpty() && longValue(topGrowth, "byteDelta") > 500_000L) {
            String findingId = "compare-heap-growth";
            findings.add(new Finding(
                findingId,
                "Heap histogram shows large growth in one class",
                String.format(
                    "%s grew by %d bytes and %d instances between baseline and current histograms.",
                    topGrowth.get("className"),
                    topGrowth.get("byteDelta"),
                    topGrowth.get("instanceDelta")
                ),
                "compare.heap-growth",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "histogram-cache-like-entry", "histogram-top-consumer"),
                "A large positive delta between baseline and current histograms is a strong retention or growth signal."
            ));
            actions.add(new RecommendedAction(
                "action-compare-heap-growth",
                "Inspect the fastest-growing classes between snapshots",
                "The comparison shows meaningful byte growth in a specific class.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Review the code path or cache responsible for the growing class.",
                    "Capture a later histogram to verify whether the growth continues.",
                    "Correlate this growth with GC pressure and NMT if available."
                ),
                List.of(findingId)
            ));
        }

        long cacheLikeBytesDelta = longValue(currentSummary, "cacheLikeBytes") - longValue(baselineSummary, "cacheLikeBytes");
        long cacheLikeInstancesDelta = longValue(currentSummary, "cacheLikeInstances") - longValue(baselineSummary, "cacheLikeInstances");
        long collectionBytesDelta = longValue(currentSummary, "collectionBytes") - longValue(baselineSummary, "collectionBytes");
        long collectionInstancesDelta = longValue(currentSummary, "collectionInstances") - longValue(baselineSummary, "collectionInstances");
        long payloadBytesDelta = longValue(currentSummary, "payloadBytes") - longValue(baselineSummary, "payloadBytes");
        long payloadInstancesDelta = longValue(currentSummary, "payloadInstances") - longValue(baselineSummary, "payloadInstances");

        if (cacheLikeBytesDelta >= 2_000_000L || collectionBytesDelta >= 4_000_000L || cacheLikeInstancesDelta >= 20_000L) {
            List<Map<String, Object>> retentionGrowth = positiveGrowth.stream()
                .filter(delta -> boolValue(delta, "cacheLike") || boolValue(delta, "collectionLike"))
                .limit(3)
                .toList();
            String findingId = "compare-heap-retention-pattern";
            findings.add(new Finding(
                findingId,
                "Heap growth is concentrated in retained collections and cache-like classes",
                String.format(
                    "Cache-like classes grew by %d bytes and collection scaffolding grew by %d bytes, led by %s.",
                    cacheLikeBytesDelta,
                    collectionBytesDelta,
                    describeGrowth(retentionGrowth)
                ),
                "compare.heap-retention",
                SeverityLevel.HIGH,
                ConfidenceLevel.HIGH,
                FindingStatus.CONFIRMED,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "histogram-cache-like-entry", "histogram-collection-summary"),
                "Coordinated growth across cache-like and collection-like classes is a stronger retention signal than isolated payload movement."
            ));
            actions.add(new RecommendedAction(
                "action-compare-heap-retention-pattern",
                "Inspect the owners of the growing collections and caches",
                "The comparison shows growth concentrated in retained collection scaffolding.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect which application caches, maps, or lists own the fastest-growing classes.",
                    "Compare another later histogram to confirm whether the same retention pattern continues.",
                    "Correlate the growth with request volume, cache eviction behavior, and GC pressure from the same interval."
                ),
                List.of(findingId)
            ));
        }

        if (payloadBytesDelta >= 2_000_000L || payloadInstancesDelta >= 50_000L) {
            String findingId = "compare-heap-payload-growth";
            findings.add(new Finding(
                findingId,
                "Heap growth includes a large increase in strings or byte arrays",
                String.format(
                    "String and byte-array classes grew by %d bytes and %d instances between histograms.",
                    payloadBytesDelta,
                    payloadInstancesDelta
                ),
                "compare.heap-payload",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                List.of(path(baselineParsed), path(currentParsed)),
                evidenceIds(currentParsed, "histogram-payload-summary", "histogram-top-consumer"),
                "Payload growth without matching cache eviction is a common sign of retained request data, buffers, or duplicated text-heavy state."
            ));
            actions.add(new RecommendedAction(
                "action-compare-heap-payload-growth",
                "Inspect retained payloads and buffer growth between snapshots",
                "The comparison shows growing heap pressure in strings or byte arrays.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Review the code path retaining serialized payloads, byte buffers, or duplicated strings.",
                    "Check whether the payload growth matches expected traffic and cache behavior.",
                    "Capture another histogram after load stabilizes to see whether payload growth plateaus."
                ),
                List.of(findingId)
            ));
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("entries");
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(ParsedArtifact parsedArtifact) {
        Object value = parsedArtifact.extractedData().get("summary");
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Map<String, Map<String, Object>> indexByClassName(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> entry : entries) {
            indexed.put(String.valueOf(entry.get("className")), entry);
        }
        return indexed;
    }

    private String describeGrowth(List<Map<String, Object>> growth) {
        if (growth.isEmpty()) {
            return "no specific class";
        }
        return growth.stream()
            .map(delta -> String.format("%s (+%d bytes)", delta.get("className"), longValue(delta, "byteDelta")))
            .collect(Collectors.joining(", "));
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean boolValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private List<String> evidenceIds(ParsedArtifact parsedArtifact, String... candidateIds) {
        Set<String> availableEvidenceIds = parsedArtifact.evidence().stream()
            .map(evidence -> evidence.id())
            .collect(Collectors.toSet());
        List<String> selected = new ArrayList<>();
        for (String candidateId : candidateIds) {
            if (candidateId != null && availableEvidenceIds.contains(candidateId)) {
                selected.add(candidateId);
            }
        }
        return List.copyOf(selected);
    }

    private String path(ParsedArtifact artifact) {
        return artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }
}
