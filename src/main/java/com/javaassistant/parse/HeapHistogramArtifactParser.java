package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeapHistogramArtifactParser implements ArtifactParser {

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
        "\\s*(\\d+):\\s*(\\d+)\\s*(\\d+)\\s+(.+)$",
        Pattern.MULTILINE
    );
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "^Total\\s+(\\d+)\\s+(\\d+)$",
        Pattern.MULTILINE
    );

    private static final String EVIDENCE_TOP_CONSUMER = "histogram-top-consumer";
    private static final String EVIDENCE_CACHE_ENTRY = "histogram-cache-like-entry";
    private static final String EVIDENCE_COLLECTION_SUMMARY = "histogram-collection-summary";
    private static final String EVIDENCE_PAYLOAD_SUMMARY = "histogram-payload-summary";

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HEAP_HISTOGRAM;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        List<Map<String, Object>> entries = parseEntries(artifact.content());
        Map<String, Object> totals = parseTotals(artifact.content(), entries);
        Map<String, Object> summary = summarize(entries, totals);
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (entries.isEmpty()) {
            warnings.add("Unable to parse heap histogram entries.");
        } else {
            Map<String, Object> topEntry = entries.get(0);
            evidence.add(ParserUtils.evidence(
                EVIDENCE_TOP_CONSUMER,
                artifact,
                "Top heap consumer",
                "Largest class entry in the heap histogram by bytes.",
                String.valueOf(topEntry.get("className")),
                Map.of(
                    "bytes", topEntry.get("bytes"),
                    "instances", topEntry.get("instances"),
                    "rank", topEntry.get("rank"),
                    "visibleShare", summary.get("topConsumerVisibleShare"),
                    "totalShare", summary.get("topConsumerTotalShare")
                )
            ));

            Map<String, Object> topCacheLikeEntry = topMatching(entries, entry -> boolValue(entry, "cacheLike"));
            if (!topCacheLikeEntry.isEmpty()) {
                evidence.add(ParserUtils.evidence(
                    EVIDENCE_CACHE_ENTRY,
                    artifact,
                    "Largest cache-like or entry-like class",
                    "Largest cache-like or map-entry class in the heap histogram by bytes.",
                    String.valueOf(topCacheLikeEntry.get("className")),
                    Map.of(
                        "bytes", topCacheLikeEntry.get("bytes"),
                        "instances", topCacheLikeEntry.get("instances"),
                        "cacheLikeBytes", summary.get("cacheLikeBytes"),
                        "cacheLikeInstances", summary.get("cacheLikeInstances")
                    )
                ));
            }

            Map<String, Object> topCollectionEntry = topMatching(entries, entry -> boolValue(entry, "collectionLike"));
            if (!topCollectionEntry.isEmpty()) {
                evidence.add(ParserUtils.evidence(
                    EVIDENCE_COLLECTION_SUMMARY,
                    artifact,
                    "Collection scaffolding summary",
                    "Largest collection-like class in the heap histogram plus aggregate collection scaffolding totals.",
                    String.valueOf(topCollectionEntry.get("className")),
                    Map.of(
                        "bytes", topCollectionEntry.get("bytes"),
                        "instances", topCollectionEntry.get("instances"),
                        "collectionBytes", summary.get("collectionBytes"),
                        "collectionInstances", summary.get("collectionInstances"),
                        "collectionVisibleShare", summary.get("collectionVisibleShare")
                    )
                ));
            }

            Map<String, Object> topPayloadEntry = topMatching(entries, entry -> boolValue(entry, "payloadLike"));
            if (!topPayloadEntry.isEmpty()) {
                evidence.add(ParserUtils.evidence(
                    EVIDENCE_PAYLOAD_SUMMARY,
                    artifact,
                    "String and byte payload summary",
                    "Largest string or byte-array class in the histogram plus aggregate payload totals.",
                    String.valueOf(topPayloadEntry.get("className")),
                    Map.of(
                        "bytes", topPayloadEntry.get("bytes"),
                        "instances", topPayloadEntry.get("instances"),
                        "payloadBytes", summary.get("payloadBytes"),
                        "payloadInstances", summary.get("payloadInstances"),
                        "byteArrayBytes", summary.get("byteArrayBytes"),
                        "stringBytes", summary.get("stringBytes")
                    )
                ));
            }
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("entries", entries);
        extractedData.put("totals", totals);
        extractedData.put("summary", summary);
        extractedData.put("topConsumers", entries.stream().limit(10).toList());

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "heap-histogram-v1", extractedData, evidence, warnings);
    }

    private List<Map<String, Object>> parseEntries(String content) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            String className = matcher.group(4).trim();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", Long.parseLong(matcher.group(1)));
            entry.put("instances", Long.parseLong(matcher.group(2)));
            entry.put("bytes", Long.parseLong(matcher.group(3)));
            entry.put("className", className);
            entry.put("byteArray", isByteArray(className));
            entry.put("stringLike", isStringLike(className));
            entry.put("payloadLike", isPayloadLike(className));
            entry.put("collectionLike", isCollectionLike(className));
            entry.put("cacheLike", isCacheLike(className));
            entry.put("entryLike", isEntryLike(className));
            entry.put("referenceLike", isReferenceLike(className));
            entry.put("threadLike", isThreadLike(className));
            entries.add(Map.copyOf(entry));
        }
        entries.sort(Comparator.comparingLong(entry -> ((Number) entry.get("rank")).longValue()));
        return entries;
    }

    private Map<String, Object> parseTotals(String content, List<Map<String, Object>> entries) {
        Matcher matcher = TOTAL_PATTERN.matcher(content);
        if (matcher.find()) {
            return Map.of(
                "instances", Long.parseLong(matcher.group(1)),
                "bytes", Long.parseLong(matcher.group(2))
            );
        }

        long totalInstances = 0L;
        long totalBytes = 0L;
        for (Map<String, Object> entry : entries) {
            totalInstances += longValue(entry, "instances");
            totalBytes += longValue(entry, "bytes");
        }
        return Map.of("instances", totalInstances, "bytes", totalBytes);
    }

    private Map<String, Object> summarize(List<Map<String, Object>> entries, Map<String, Object> totals) {
        long visibleInstances = sum(entries, "instances", entry -> true);
        long visibleBytes = sum(entries, "bytes", entry -> true);
        long totalInstances = longValue(totals, "instances");
        long totalBytes = longValue(totals, "bytes");
        Map<String, Object> topEntry = entries.isEmpty() ? Map.of() : entries.get(0);

        long byteArrayBytes = sum(entries, "bytes", entry -> boolValue(entry, "byteArray"));
        long byteArrayInstances = sum(entries, "instances", entry -> boolValue(entry, "byteArray"));
        long stringBytes = sum(entries, "bytes", entry -> boolValue(entry, "stringLike"));
        long stringInstances = sum(entries, "instances", entry -> boolValue(entry, "stringLike"));
        long payloadBytes = sum(entries, "bytes", entry -> boolValue(entry, "payloadLike"));
        long payloadInstances = sum(entries, "instances", entry -> boolValue(entry, "payloadLike"));
        long collectionBytes = sum(entries, "bytes", entry -> boolValue(entry, "collectionLike"));
        long collectionInstances = sum(entries, "instances", entry -> boolValue(entry, "collectionLike"));
        long cacheLikeBytes = sum(entries, "bytes", entry -> boolValue(entry, "cacheLike"));
        long cacheLikeInstances = sum(entries, "instances", entry -> boolValue(entry, "cacheLike"));
        long entryLikeBytes = sum(entries, "bytes", entry -> boolValue(entry, "entryLike"));
        long entryLikeInstances = sum(entries, "instances", entry -> boolValue(entry, "entryLike"));
        long referenceBytes = sum(entries, "bytes", entry -> boolValue(entry, "referenceLike"));
        long referenceInstances = sum(entries, "instances", entry -> boolValue(entry, "referenceLike"));
        long threadBytes = sum(entries, "bytes", entry -> boolValue(entry, "threadLike"));
        long threadInstances = sum(entries, "instances", entry -> boolValue(entry, "threadLike"));

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("visibleEntries", (long) entries.size());
        summary.put("visibleInstances", visibleInstances);
        summary.put("visibleBytes", visibleBytes);
        summary.put("totalInstances", totalInstances);
        summary.put("totalBytes", totalBytes);
        summary.put("visibleCoverageRatio", ratio(visibleBytes, totalBytes));
        summary.put("topConsumerClassName", topEntry.get("className"));
        summary.put("topConsumerBytes", longValue(topEntry, "bytes"));
        summary.put("topConsumerInstances", longValue(topEntry, "instances"));
        summary.put("topConsumerVisibleShare", ratio(longValue(topEntry, "bytes"), visibleBytes));
        summary.put("topConsumerTotalShare", ratio(longValue(topEntry, "bytes"), totalBytes));
        summary.put("byteArrayBytes", byteArrayBytes);
        summary.put("byteArrayInstances", byteArrayInstances);
        summary.put("stringBytes", stringBytes);
        summary.put("stringInstances", stringInstances);
        summary.put("payloadBytes", payloadBytes);
        summary.put("payloadInstances", payloadInstances);
        summary.put("payloadVisibleShare", ratio(payloadBytes, visibleBytes));
        summary.put("collectionBytes", collectionBytes);
        summary.put("collectionInstances", collectionInstances);
        summary.put("collectionVisibleShare", ratio(collectionBytes, visibleBytes));
        summary.put("cacheLikeBytes", cacheLikeBytes);
        summary.put("cacheLikeInstances", cacheLikeInstances);
        summary.put("cacheLikeVisibleShare", ratio(cacheLikeBytes, visibleBytes));
        summary.put("entryLikeBytes", entryLikeBytes);
        summary.put("entryLikeInstances", entryLikeInstances);
        summary.put("referenceBytes", referenceBytes);
        summary.put("referenceInstances", referenceInstances);
        summary.put("threadBytes", threadBytes);
        summary.put("threadInstances", threadInstances);
        return Map.copyOf(summary);
    }

    private Map<String, Object> topMatching(List<Map<String, Object>> entries, Predicate<Map<String, Object>> predicate) {
        return entries.stream()
            .filter(predicate)
            .max(Comparator.comparingLong(entry -> longValue(entry, "bytes")))
            .orElse(Map.of());
    }

    private long sum(List<Map<String, Object>> entries, String key, Predicate<Map<String, Object>> predicate) {
        long total = 0L;
        for (Map<String, Object> entry : entries) {
            if (predicate.test(entry)) {
                total += longValue(entry, key);
            }
        }
        return total;
    }

    private boolean isByteArray(String className) {
        return "[B".equals(className);
    }

    private boolean isStringLike(String className) {
        return "java.lang.String".equals(className) || "[C".equals(className);
    }

    private boolean isPayloadLike(String className) {
        return isByteArray(className) || isStringLike(className);
    }

    private boolean isCollectionLike(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("map")
            || lower.contains("list")
            || lower.contains("set")
            || lower.contains("queue")
            || lower.contains("deque")
            || lower.contains("vector")
            || lower.contains("properties")
            || lower.contains("collection")
            || lower.contains("$entry")
            || lower.contains("$node");
    }

    private boolean isCacheLike(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("cache")
            || lower.contains("$entry")
            || lower.contains("$node");
    }

    private boolean isEntryLike(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("$entry") || lower.contains("$node");
    }

    private boolean isReferenceLike(String className) {
        return className.toLowerCase(Locale.ROOT).contains("reference");
    }

    private boolean isThreadLike(String className) {
        return "java.lang.Thread".equals(className);
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean boolValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean bool && bool;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / denominator;
    }
}
