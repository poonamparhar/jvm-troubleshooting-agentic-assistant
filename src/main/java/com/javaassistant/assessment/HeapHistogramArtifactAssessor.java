package com.javaassistant.assessment;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class HeapHistogramArtifactAssessor implements ArtifactAssessor {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HEAP_HISTOGRAM;
    }

    @Override
    public AssessmentResult evaluate(ParsedArtifact parsedArtifact) {
        List<Finding> findings = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        List<Map<String, Object>> entries = AssessmentSupport.mapList(parsedArtifact.extractedData(), "entries");
        Map<String, Object> summary = AssessmentSupport.map(parsedArtifact.extractedData(), "summary");
        if (entries.isEmpty()) {
            missingData.add("No heap histogram entries were parsed.");
            return new AssessmentResult(findings, actions, missingData);
        }

        long totalBytes = AssessmentSupport.longValue(summary, "totalBytes");
        long visibleBytes = AssessmentSupport.longValue(summary, "visibleBytes");
        long topConsumerBytes = AssessmentSupport.longValue(summary, "topConsumerBytes");
        double topConsumerVisibleShare = AssessmentSupport.doubleValue(summary, "topConsumerVisibleShare");
        double topConsumerTotalShare = AssessmentSupport.doubleValue(summary, "topConsumerTotalShare");
        long payloadBytes = AssessmentSupport.longValue(summary, "payloadBytes");
        long payloadInstances = AssessmentSupport.longValue(summary, "payloadInstances");
        long collectionBytes = AssessmentSupport.longValue(summary, "collectionBytes");
        long collectionInstances = AssessmentSupport.longValue(summary, "collectionInstances");
        long cacheLikeBytes = AssessmentSupport.longValue(summary, "cacheLikeBytes");
        long cacheLikeInstances = AssessmentSupport.longValue(summary, "cacheLikeInstances");
        long entryLikeBytes = AssessmentSupport.longValue(summary, "entryLikeBytes");
        long entryLikeInstances = AssessmentSupport.longValue(summary, "entryLikeInstances");

        Map<String, Object> topConsumer = entries.getFirst();
        Map<String, Object> topCacheLikeEntry = topMatching(entries, entry -> boolValue(entry, "cacheLike"));
        Map<String, Object> topCollectionEntry = topMatching(entries, entry -> boolValue(entry, "collectionLike"));
        Map<String, Object> topPayloadEntry = topMatching(entries, entry -> boolValue(entry, "payloadLike"));

        if (topConsumerTotalShare >= 0.30d || (topConsumerVisibleShare >= 0.45d && topConsumerBytes >= 8_000_000L && visibleBytes >= 12_000_000L)) {
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                "histogram-top-heavy-consumer",
                "A single class dominates the surfaced heap histogram slice",
                String.format(
                    "%s accounts for %.1f%% of visible histogram bytes (%d of %d bytes).",
                    topConsumer.get("className"),
                    topConsumerVisibleShare * 100.0d,
                    topConsumerBytes,
                    visibleBytes
                ),
                "heap.distribution",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "histogram-top-consumer"),
                "A single dominant class in the visible histogram slice narrows the first place to inspect even when the full heap contains many smaller classes."
            ));
        }

        if (cacheLikeBytes >= 2_000_000L || cacheLikeInstances >= 20_000L) {
            String findingId = "histogram-cache-retention";
            SeverityLevel severity = cacheLikeBytes >= 8_000_000L || cacheLikeInstances >= 75_000L
                ? SeverityLevel.HIGH
                : SeverityLevel.MEDIUM;
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Heap histogram suggests cache or entry retention",
                String.format(
                    "%s leads %d bytes across %d cache-like or entry-like instances in the surfaced histogram slice.",
                    displayClassName(topCacheLikeEntry),
                    cacheLikeBytes,
                    cacheLikeInstances
                ),
                "heap.retention",
                severity,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "histogram-cache-like-entry", "histogram-collection-summary"),
                "Large entry-like and cache-like populations are a common retention pattern when application caches or maps continue growing."
            ));
            actions.add(AssessmentSupport.action(
                "action-histogram-cache-retention",
                "Review cache growth and retained map entries",
                "The histogram shows substantial memory retained in cache-like or entry-like structures.",
                ActionType.INVESTIGATION,
                ActionPriority.HIGH,
                List.of(
                    "Inspect the owners of the dominant cache-like classes and confirm whether retention matches expected business state.",
                    "Capture a later histogram to see whether the same entry-like classes continue growing.",
                    "Correlate the retained classes with cache eviction policy, map sizing, and recent traffic shifts."
                ),
                List.of(findingId)
            ));
        }

        if (collectionBytes >= 4_000_000L || collectionInstances >= 50_000L || entryLikeInstances >= 25_000L) {
            String findingId = "histogram-collection-retention";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Collection scaffolding is materially present in the heap",
                String.format(
                    "%s leads a surfaced collection footprint of %d bytes across %d collection-like instances.",
                    displayClassName(topCollectionEntry),
                    collectionBytes,
                    collectionInstances
                ),
                "heap.collections",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "histogram-collection-summary", "histogram-cache-like-entry"),
                "Large populations of map entries, lists, and collection scaffolding often point to retained application state rather than transient allocation noise."
            ));
            actions.add(AssessmentSupport.action(
                "action-histogram-collection-retention",
                "Inspect which owners retain the dominant collections",
                "The histogram shows a substantial amount of heap tied up in collection scaffolding.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Trace the owning caches, maps, or lists for the largest collection-like classes.",
                    "Check whether retained entries are expected for the current workload and deployment age.",
                    "Capture a heap dump if safe to do so when collection retention continues across later histograms."
                ),
                List.of(findingId)
            ));
        }

        if (payloadBytes >= 8_000_000L || payloadInstances >= 150_000L) {
            String findingId = "histogram-payload-retention";
            findings.add(AssessmentSupport.finding(
                parsedArtifact,
                findingId,
                "Strings and byte arrays make up a large surfaced heap payload",
                String.format(
                    "%s leads a surfaced payload of %d bytes across %d string and byte-array instances.",
                    displayClassName(topPayloadEntry),
                    payloadBytes,
                    payloadInstances
                ),
                "heap.payload",
                SeverityLevel.MEDIUM,
                ConfidenceLevel.MEDIUM,
                FindingStatus.LIKELY,
                evidenceIds(parsedArtifact, "histogram-payload-summary", "histogram-top-consumer"),
                "Large string and byte-array populations often reflect cached payloads, serialization buffers, duplicated request data, or retained text-heavy state."
            ));
            actions.add(AssessmentSupport.action(
                "action-histogram-payload-retention",
                "Inspect retained payloads, buffers, and duplicated strings",
                "The histogram shows substantial heap held in strings or byte arrays.",
                ActionType.INVESTIGATION,
                ActionPriority.MEDIUM,
                List.of(
                    "Review cache layers or queues that retain serialized payloads, request bodies, or text-heavy responses.",
                    "Look for duplicated strings, large byte buffers, or memoized payload objects in the owning code path.",
                    "Compare a later histogram to distinguish steady-state payload from continuing growth."
                ),
                List.of(findingId)
            ));
        }

        if (totalBytes == 0L) {
            missingData.add("Heap histogram totals could not be parsed.");
        }

        return new AssessmentResult(findings, actions, missingData);
    }

    private Map<String, Object> topMatching(List<Map<String, Object>> entries, Predicate<Map<String, Object>> predicate) {
        return entries.stream()
            .filter(predicate)
            .max(Comparator.comparingLong(entry -> AssessmentSupport.longValue(entry, "bytes")))
            .orElse(Map.of());
    }

    private boolean boolValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String displayClassName(Map<String, Object> entry) {
        Object value = entry.get("className");
        return value == null ? "an unknown class" : String.valueOf(value);
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
}
