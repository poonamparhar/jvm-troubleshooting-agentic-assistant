package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PmapArtifactParser implements ArtifactParser {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(\\d+):\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "^total\\s+kB\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HEADERLESS_TOTAL_PATTERN = Pattern.compile(
        "^\\s*total\\s+(\\d+)K\\s*$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HEADERLESS_ENTRY_PATTERN = Pattern.compile(
        "^\\s*([0-9a-fA-F]+)\\s+(\\d+)K\\s+([rwxsp-]{5})\\s+(.*)$"
    );
    private static final Pattern HEADER_ENTRY_PATTERN = Pattern.compile(
        "^\\s*([0-9a-fA-F]+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([rwxsp-]{5})\\s+(.*)$"
    );

    private static final String EVIDENCE_LARGEST_MAPPING = "pmap-largest-mapping";
    private static final String EVIDENCE_LARGEST_RESIDENT_MAPPING = "pmap-largest-resident-mapping";
    private static final String EVIDENCE_RESIDENT_GAP = "pmap-resident-gap";

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PMAP;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, Object> header = parseHeader(artifact.content());
        List<Map<String, Object>> mappings = parseMappings(artifact.content());
        boolean rssAvailable = hasMetric(mappings, "rssKb");
        boolean dirtyAvailable = hasMetric(mappings, "dirtyKb");
        Map<String, Long> totals = parseTotals(artifact.content(), mappings);
        Map<String, Map<String, Object>> categorySummaries = summarizeCategories(mappings, rssAvailable, dirtyAvailable);
        Map<String, Long> sizeCategoryBreakdown = categoryBreakdown(categorySummaries, "sizeKb");
        Map<String, Long> rssCategoryBreakdown = categoryBreakdown(categorySummaries, "rssKb");
        Map<String, Long> dirtyCategoryBreakdown = categoryBreakdown(categorySummaries, "dirtyKb");
        Map<String, Object> summary = summarize(mappings, totals, categorySummaries, rssAvailable, dirtyAvailable);
        List<Map<String, Object>> largestMappings = sortMappings(mappings, "sizeKb", false, 10);
        List<Map<String, Object>> largestResidentMappings = sortMappings(mappings, "rssKb", true, 10);
        List<Map<String, Object>> largestDirtyMappings = sortMappings(mappings, "dirtyKb", true, 10);
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (mappings.isEmpty()) {
            warnings.add("Unable to parse pmap mappings.");
        } else {
            Map<String, Object> largestMapping = largestMappings.getFirst();
            evidence.add(ParserUtils.evidence(
                EVIDENCE_LARGEST_MAPPING,
                artifact,
                "Largest virtual mapping",
                "Largest parsed pmap region by virtual size.",
                String.valueOf(largestMapping.get("line")),
                mappingMetrics(largestMapping)
            ));

            if (!largestResidentMappings.isEmpty()) {
                Map<String, Object> largestResidentMapping = largestResidentMappings.getFirst();
                evidence.add(ParserUtils.evidence(
                    EVIDENCE_LARGEST_RESIDENT_MAPPING,
                    artifact,
                    "Largest resident mapping",
                    "Mapping with the highest resident set size in the parsed pmap output.",
                    String.valueOf(largestResidentMapping.get("line")),
                    mappingMetrics(largestResidentMapping)
                ));
            }

            if (rssAvailable) {
                String totalLine = findTotalLine(artifact.content());
                if (totalLine != null) {
                    evidence.add(ParserUtils.evidence(
                        EVIDENCE_RESIDENT_GAP,
                        artifact,
                        "Reserved versus resident gap",
                        "Total mapped size compared with total resident memory from pmap.",
                        totalLine,
                        residentGapMetrics(summary)
                    ));
                }
            }
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("header", header);
        extractedData.put("totals", totals);
        extractedData.put("summary", summary);
        extractedData.put("categoryBreakdown", sizeCategoryBreakdown);
        extractedData.put("sizeCategoryBreakdown", sizeCategoryBreakdown);
        extractedData.put("rssCategoryBreakdown", rssCategoryBreakdown);
        extractedData.put("dirtyCategoryBreakdown", dirtyCategoryBreakdown);
        extractedData.put("categorySummaries", categorySummaries);
        extractedData.put("largestMappings", largestMappings);
        extractedData.put("largestResidentMappings", largestResidentMappings);
        extractedData.put("largestDirtyMappings", largestDirtyMappings);

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "pmap-v1", extractedData, evidence, warnings);
    }

    private Map<String, Object> parseHeader(String content) {
        Matcher matcher = HEADER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }
        return Map.of(
            "pid", Long.parseLong(matcher.group(1)),
            "command", matcher.group(2).trim()
        );
    }

    private Map<String, Long> parseTotals(String content, List<Map<String, Object>> mappings) {
        Matcher matcher = TOTAL_PATTERN.matcher(content);
        if (matcher.find()) {
            return Map.of(
                "sizeKb", Long.parseLong(matcher.group(1)),
                "rssKb", Long.parseLong(matcher.group(2)),
                "dirtyKb", Long.parseLong(matcher.group(3))
            );
        }

        Matcher headerlessMatcher = HEADERLESS_TOTAL_PATTERN.matcher(content);
        if (headerlessMatcher.find()) {
            return Map.of("sizeKb", Long.parseLong(headerlessMatcher.group(1)));
        }

        LinkedHashMap<String, Long> totals = new LinkedHashMap<>();
        long sizeKb = mappings.stream().mapToLong(entry -> longValue(entry, "sizeKb")).sum();
        if (sizeKb > 0L || !mappings.isEmpty()) {
            totals.put("sizeKb", sizeKb);
        }
        if (hasMetric(mappings, "rssKb")) {
            totals.put("rssKb", mappings.stream().mapToLong(entry -> longValue(entry, "rssKb")).sum());
        }
        if (hasMetric(mappings, "dirtyKb")) {
            totals.put("dirtyKb", mappings.stream().mapToLong(entry -> longValue(entry, "dirtyKb")).sum());
        }
        return totals.isEmpty() ? Map.of() : Map.copyOf(totals);
    }

    private List<Map<String, Object>> parseMappings(String content) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        List<String> lines = ParserUtils.lines(content);

        for (String line : lines) {
            Matcher matcher = HEADER_ENTRY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            mappings.add(buildHeaderedMapping(line, matcher));
        }

        if (!mappings.isEmpty()) {
            return List.copyOf(mappings);
        }

        for (String line : lines) {
            Matcher matcher = HEADERLESS_ENTRY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            mappings.add(buildHeaderlessMapping(line, matcher));
        }
        return List.copyOf(mappings);
    }

    private Map<String, Object> buildHeaderedMapping(String line, Matcher matcher) {
        String mappingName = matcher.group(6).trim();
        long sizeKb = Long.parseLong(matcher.group(2));
        long rssKb = Long.parseLong(matcher.group(3));
        long dirtyKb = Long.parseLong(matcher.group(4));

        LinkedHashMap<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("address", matcher.group(1));
        mapping.put("sizeKb", sizeKb);
        mapping.put("rssKb", rssKb);
        mapping.put("dirtyKb", dirtyKb);
        mapping.put("mode", matcher.group(5));
        mapping.put("mapping", mappingName);
        mapping.put("category", categorizeMapping(mappingName));
        mapping.put("line", line.trim());
        mapping.put("residentRatio", ratio(rssKb, sizeKb));
        mapping.put("reservedGapKb", Math.max(0L, sizeKb - rssKb));
        mapping.put("dirtyRatio", ratio(dirtyKb, sizeKb));
        return Map.copyOf(mapping);
    }

    private Map<String, Object> buildHeaderlessMapping(String line, Matcher matcher) {
        String mappingName = matcher.group(4).trim();

        LinkedHashMap<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("address", matcher.group(1));
        mapping.put("sizeKb", Long.parseLong(matcher.group(2)));
        mapping.put("mode", matcher.group(3));
        mapping.put("mapping", mappingName);
        mapping.put("category", categorizeMapping(mappingName));
        mapping.put("line", line.trim());
        return Map.copyOf(mapping);
    }

    private Map<String, Map<String, Object>> summarizeCategories(
        List<Map<String, Object>> mappings,
        boolean rssAvailable,
        boolean dirtyAvailable
    ) {
        Map<String, CategoryAccumulator> accumulators = new LinkedHashMap<>();
        for (Map<String, Object> mapping : mappings) {
            String category = String.valueOf(mapping.getOrDefault("category", "other"));
            CategoryAccumulator accumulator = accumulators.computeIfAbsent(category, ignored -> new CategoryAccumulator());
            accumulator.count++;
            accumulator.sizeKb += longValue(mapping, "sizeKb");
            if (mapping.containsKey("rssKb")) {
                accumulator.rssKb += longValue(mapping, "rssKb");
            }
            if (mapping.containsKey("dirtyKb")) {
                accumulator.dirtyKb += longValue(mapping, "dirtyKb");
            }
        }

        LinkedHashMap<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, CategoryAccumulator> entry : accumulators.entrySet()) {
            CategoryAccumulator accumulator = entry.getValue();
            LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
            summary.put("count", accumulator.count);
            summary.put("sizeKb", accumulator.sizeKb);
            if (rssAvailable) {
                summary.put("rssKb", accumulator.rssKb);
                summary.put("residentRatio", ratio(accumulator.rssKb, accumulator.sizeKb));
                summary.put("reservedGapKb", Math.max(0L, accumulator.sizeKb - accumulator.rssKb));
            }
            if (dirtyAvailable) {
                summary.put("dirtyKb", accumulator.dirtyKb);
            }
            summaries.put(entry.getKey(), Map.copyOf(summary));
        }
        return summaries.isEmpty() ? Map.of() : Map.copyOf(summaries);
    }

    private Map<String, Long> categoryBreakdown(Map<String, Map<String, Object>> categorySummaries, String metricKey) {
        LinkedHashMap<String, Long> breakdown = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : categorySummaries.entrySet()) {
            if (entry.getValue().containsKey(metricKey)) {
                breakdown.put(entry.getKey(), longValue(entry.getValue(), metricKey));
            }
        }
        return breakdown.isEmpty() ? Map.of() : Map.copyOf(breakdown);
    }

    private Map<String, Object> summarize(
        List<Map<String, Object>> mappings,
        Map<String, Long> totals,
        Map<String, Map<String, Object>> categorySummaries,
        boolean rssAvailable,
        boolean dirtyAvailable
    ) {
        long totalSizeKb = totals.getOrDefault("sizeKb", 0L);
        long totalRssKb = totals.getOrDefault("rssKb", 0L);
        long totalDirtyKb = totals.getOrDefault("dirtyKb", 0L);
        Map<String, Object> anonSummary = categorySummaries.getOrDefault("anon", Map.of());
        Map<String, Object> largestMapping = topByMetric(mappings, "sizeKb", false);
        Map<String, Object> largestResidentMapping = topByMetric(mappings, "rssKb", true);
        Map<String, Object> largestDirtyMapping = topByMetric(mappings, "dirtyKb", true);
        Map<String, Object> largestReservedGapMapping = topByMetric(mappings, "reservedGapKb", true);

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("mappingCount", (long) mappings.size());
        summary.put("rssAvailable", rssAvailable);
        summary.put("dirtyAvailable", dirtyAvailable);
        summary.put("totalSizeKb", totalSizeKb);
        summary.put("anonSizeKb", longValue(anonSummary, "sizeKb"));
        summary.put("anonMappingCount", longValue(anonSummary, "count"));
        summary.put("anonVirtualShare", ratio(longValue(anonSummary, "sizeKb"), totalSizeKb));
        putIfNotNull(summary, "topSizeCategory", topCategory(categorySummaries, "sizeKb"));
        addMappingSummary(summary, "largestMapping", largestMapping);

        if (rssAvailable) {
            summary.put("totalRssKb", totalRssKb);
            summary.put("residentRatio", ratio(totalRssKb, totalSizeKb));
            summary.put("reservedGapKb", Math.max(0L, totalSizeKb - totalRssKb));
            summary.put("reservedGapRatio", ratio(Math.max(0L, totalSizeKb - totalRssKb), totalSizeKb));
            summary.put("anonRssKb", longValue(anonSummary, "rssKb"));
            summary.put("anonResidentShare", ratio(longValue(anonSummary, "rssKb"), totalRssKb));
            summary.put("anonResidentRatio", ratio(longValue(anonSummary, "rssKb"), longValue(anonSummary, "sizeKb")));
            summary.put("anonReservedGapKb", Math.max(0L, longValue(anonSummary, "sizeKb") - longValue(anonSummary, "rssKb")));
            summary.put(
                "anonReservedGapRatio",
                ratio(Math.max(0L, longValue(anonSummary, "sizeKb") - longValue(anonSummary, "rssKb")), longValue(anonSummary, "sizeKb"))
            );
            putIfNotNull(summary, "topRssCategory", topCategory(categorySummaries, "rssKb"));
            addMappingSummary(summary, "largestResidentMapping", largestResidentMapping);
            addMappingSummary(summary, "largestReservedGapMapping", largestReservedGapMapping);
        }

        if (dirtyAvailable) {
            summary.put("totalDirtyKb", totalDirtyKb);
            summary.put("dirtyRatio", ratio(totalDirtyKb, totalSizeKb));
            summary.put("anonDirtyKb", longValue(anonSummary, "dirtyKb"));
            putIfNotNull(summary, "topDirtyCategory", topCategory(categorySummaries, "dirtyKb"));
            addMappingSummary(summary, "largestDirtyMapping", largestDirtyMapping);
        }

        return Map.copyOf(summary);
    }

    private void addMappingSummary(Map<String, Object> target, String prefix, Map<String, Object> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        target.put(prefix + "Name", mapping.get("mapping"));
        target.put(prefix + "Category", mapping.get("category"));
        target.put(prefix + "Mode", mapping.get("mode"));
        target.put(prefix + "SizeKb", longValue(mapping, "sizeKb"));
        if (mapping.containsKey("rssKb")) {
            target.put(prefix + "RssKb", longValue(mapping, "rssKb"));
            target.put(prefix + "ResidentRatio", doubleValue(mapping, "residentRatio"));
            target.put(prefix + "ReservedGapKb", longValue(mapping, "reservedGapKb"));
        }
        if (mapping.containsKey("dirtyKb")) {
            target.put(prefix + "DirtyKb", longValue(mapping, "dirtyKb"));
            target.put(prefix + "DirtyRatio", doubleValue(mapping, "dirtyRatio"));
        }
    }

    private List<Map<String, Object>> sortMappings(List<Map<String, Object>> mappings, String metricKey, boolean positiveOnly, int limit) {
        return mappings.stream()
            .filter(mapping -> !positiveOnly || longValue(mapping, metricKey) > 0L)
            .sorted(Comparator.comparingLong((Map<String, Object> mapping) -> longValue(mapping, metricKey)).reversed())
            .limit(limit)
            .toList();
    }

    private Map<String, Object> topByMetric(List<Map<String, Object>> mappings, String metricKey, boolean positiveOnly) {
        return mappings.stream()
            .filter(mapping -> !positiveOnly || longValue(mapping, metricKey) > 0L)
            .max(Comparator.comparingLong(mapping -> longValue(mapping, metricKey)))
            .orElse(Map.of());
    }

    private String topCategory(Map<String, Map<String, Object>> categorySummaries, String metricKey) {
        return categorySummaries.entrySet().stream()
            .filter(entry -> longValue(entry.getValue(), metricKey) > 0L)
            .max(Comparator.comparingLong(entry -> longValue(entry.getValue(), metricKey)))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private Map<String, Object> mappingMetrics(Map<String, Object> mapping) {
        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sizeKb", longValue(mapping, "sizeKb"));
        metrics.put("mode", mapping.get("mode"));
        metrics.put("category", mapping.get("category"));
        if (mapping.containsKey("rssKb")) {
            metrics.put("rssKb", longValue(mapping, "rssKb"));
            metrics.put("residentRatio", doubleValue(mapping, "residentRatio"));
            metrics.put("reservedGapKb", longValue(mapping, "reservedGapKb"));
        }
        if (mapping.containsKey("dirtyKb")) {
            metrics.put("dirtyKb", longValue(mapping, "dirtyKb"));
            metrics.put("dirtyRatio", doubleValue(mapping, "dirtyRatio"));
        }
        return Map.copyOf(metrics);
    }

    private Map<String, Object> residentGapMetrics(Map<String, Object> summary) {
        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalSizeKb", longValue(summary, "totalSizeKb"));
        metrics.put("totalRssKb", longValue(summary, "totalRssKb"));
        metrics.put("reservedGapKb", longValue(summary, "reservedGapKb"));
        metrics.put("reservedGapRatio", doubleValue(summary, "reservedGapRatio"));
        metrics.put("anonSizeKb", longValue(summary, "anonSizeKb"));
        metrics.put("anonRssKb", longValue(summary, "anonRssKb"));
        metrics.put("anonReservedGapKb", longValue(summary, "anonReservedGapKb"));
        metrics.put("anonResidentRatio", doubleValue(summary, "anonResidentRatio"));
        return Map.copyOf(metrics);
    }

    private boolean hasMetric(List<Map<String, Object>> mappings, String metricKey) {
        return mappings.stream().anyMatch(mapping -> mapping.containsKey(metricKey));
    }

    private String findTotalLine(String content) {
        for (String line : ParserUtils.lines(content)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("total")) {
                return trimmed;
            }
        }
        return null;
    }

    private long longValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double doubleValue(Map<String, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private double ratio(long numerator, long denominator) {
        if (numerator <= 0L || denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String categorizeMapping(String mapping) {
        if (mapping.contains("[ anon ]")) {
            return "anon";
        }
        if (mapping.contains("[ heap ]")) {
            return "heap";
        }
        if (mapping.contains("[ stack ]")) {
            return "stack";
        }
        if (mapping.endsWith(".so") || mapping.contains(".so.") || mapping.endsWith(".dylib")) {
            return "shared-library";
        }
        if (!mapping.isBlank() && !mapping.startsWith("[")) {
            return "file-backed";
        }
        return "other";
    }

    private static final class CategoryAccumulator {
        private long count;
        private long sizeKb;
        private long rssKb;
        private long dirtyKb;
    }
}
