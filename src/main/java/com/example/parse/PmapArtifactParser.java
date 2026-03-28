package com.example.parse;

import com.example.model.ArtifactType;
import com.example.model.Evidence;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;
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
    private static final Pattern HEADERLESS_ENTRY_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]+)\\s+(\\d+)K\\s+([rwx-]{5})\\s+(.*)$",
        Pattern.MULTILINE
    );
    private static final Pattern HEADER_ENTRY_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([rwx-]{5})\\s+(.*)$",
        Pattern.MULTILINE
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PMAP;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, Object> header = parseHeader(artifact.content());
        List<Map<String, Object>> mappings = parseMappings(artifact.content());
        Map<String, Long> categoryBreakdown = calculateCategoryBreakdown(mappings);
        Map<String, Long> totals = parseTotals(artifact.content(), mappings);
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (mappings.isEmpty()) {
            warnings.add("Unable to parse pmap mappings.");
        } else {
            Map<String, Object> largestMapping = mappings.stream()
                .max(Comparator.comparingLong(entry -> ((Number) entry.get("sizeKb")).longValue()))
                .orElseThrow();
            evidence.add(ParserUtils.evidence(
                "pmap-largest-mapping",
                artifact,
                "Largest memory mapping",
                "Largest parsed pmap region by virtual size.",
                String.valueOf(largestMapping.get("mapping")),
                Map.of(
                    "sizeKb", largestMapping.get("sizeKb"),
                    "mode", largestMapping.get("mode")
                )
            ));
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("header", header);
        extractedData.put("totals", totals);
        extractedData.put("categoryBreakdown", categoryBreakdown);
        extractedData.put("largestMappings", mappings.stream().sorted(
            Comparator.comparingLong(entry -> -((Number) entry.get("sizeKb")).longValue())
        ).limit(10).toList());

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

        long sizeKb = mappings.stream().mapToLong(entry -> ((Number) entry.get("sizeKb")).longValue()).sum();
        return Map.of("sizeKb", sizeKb);
    }

    private List<Map<String, Object>> parseMappings(String content) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        Matcher headerMatcher = HEADER_ENTRY_PATTERN.matcher(content);
        while (headerMatcher.find()) {
            String mappingName = headerMatcher.group(6).trim();
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("address", headerMatcher.group(1));
            mapping.put("sizeKb", Long.parseLong(headerMatcher.group(2)));
            mapping.put("rssKb", Long.parseLong(headerMatcher.group(3)));
            mapping.put("dirtyKb", Long.parseLong(headerMatcher.group(4)));
            mapping.put("mode", headerMatcher.group(5));
            mapping.put("mapping", mappingName);
            mapping.put("category", categorizeMapping(mappingName));
            mappings.add(Map.copyOf(mapping));
        }

        if (!mappings.isEmpty()) {
            return mappings;
        }

        Matcher headerlessMatcher = HEADERLESS_ENTRY_PATTERN.matcher(content);
        while (headerlessMatcher.find()) {
            String mappingName = headerlessMatcher.group(4).trim();
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("address", headerlessMatcher.group(1));
            mapping.put("sizeKb", Long.parseLong(headerlessMatcher.group(2)));
            mapping.put("mode", headerlessMatcher.group(3));
            mapping.put("mapping", mappingName);
            mapping.put("category", categorizeMapping(mappingName));
            mappings.add(Map.copyOf(mapping));
        }
        return mappings;
    }

    private Map<String, Long> calculateCategoryBreakdown(List<Map<String, Object>> mappings) {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (Map<String, Object> mapping : mappings) {
            String category = String.valueOf(mapping.get("category"));
            long sizeKb = ((Number) mapping.get("sizeKb")).longValue();
            breakdown.put(category, breakdown.getOrDefault(category, 0L) + sizeKb);
        }
        return breakdown;
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
        if (mapping.endsWith(".so") || mapping.endsWith(".dylib")) {
            return "shared-library";
        }
        if (mapping.startsWith("/")) {
            return "file-backed";
        }
        return "other";
    }
}
