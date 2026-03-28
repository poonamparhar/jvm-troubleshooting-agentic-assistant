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

public class HeapHistogramArtifactParser implements ArtifactParser {

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
        "\\s*(\\d+):\\s*(\\d+)\\s*(\\d+)\\s+(.+)$",
        Pattern.MULTILINE
    );
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "^Total\\s+(\\d+)\\s+(\\d+)$",
        Pattern.MULTILINE
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HEAP_HISTOGRAM;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        List<Map<String, Object>> entries = parseEntries(artifact.content());
        Map<String, Object> totals = parseTotals(artifact.content(), entries);
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (entries.isEmpty()) {
            warnings.add("Unable to parse heap histogram entries.");
        } else {
            Map<String, Object> topEntry = entries.get(0);
            evidence.add(ParserUtils.evidence(
                "histogram-top-consumer",
                artifact,
                "Top heap consumer",
                "Largest class entry in the heap histogram by bytes.",
                String.valueOf(topEntry.get("className")),
                Map.of(
                    "bytes", topEntry.get("bytes"),
                    "instances", topEntry.get("instances"),
                    "rank", topEntry.get("rank")
                )
            ));
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("entries", entries);
        extractedData.put("totals", totals);
        extractedData.put("topConsumers", entries.stream().limit(10).toList());

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "heap-histogram-v1", extractedData, evidence, warnings);
    }

    private List<Map<String, Object>> parseEntries(String content) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", Long.parseLong(matcher.group(1)));
            entry.put("instances", Long.parseLong(matcher.group(2)));
            entry.put("bytes", Long.parseLong(matcher.group(3)));
            entry.put("className", matcher.group(4).trim());
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

        long totalInstances = 0;
        long totalBytes = 0;
        for (Map<String, Object> entry : entries) {
            totalInstances += ((Number) entry.get("instances")).longValue();
            totalBytes += ((Number) entry.get("bytes")).longValue();
        }
        return Map.of("instances", totalInstances, "bytes", totalBytes);
    }
}
