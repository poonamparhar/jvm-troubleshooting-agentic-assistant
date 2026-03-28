package com.example.parse;

import com.example.model.ArtifactType;
import com.example.model.Evidence;
import com.example.model.InputArtifact;
import com.example.model.ParsedArtifact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GcLogArtifactParser implements ArtifactParser {

    private static final Pattern PAUSE_PATTERN = Pattern.compile(
        "Pause\\s+([^\\n]*?)\\s+(\\d+)M->(\\d+)M\\((\\d+)M\\)\\s+([0-9.]+)ms",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METASPACE_PATTERN = Pattern.compile(
        "Metaspace:\\s*(\\d+)K\\((\\d+)K\\)->(\\d+)K\\((\\d+)K\\)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.GC_LOG;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        List<Map<String, Object>> pauses = parsePauses(artifact.content());
        Map<String, Object> summary = summarizePauses(pauses, artifact.content());
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (pauses.isEmpty()) {
            warnings.add("Unable to parse GC pause events.");
        } else {
            Map<String, Object> longestPause = pauses.stream()
                .max((left, right) -> Double.compare(
                    ((Number) left.get("pauseMs")).doubleValue(),
                    ((Number) right.get("pauseMs")).doubleValue()
                ))
                .orElseThrow();
            evidence.add(ParserUtils.evidence(
                "gc-longest-pause",
                artifact,
                "Longest GC pause",
                "Longest parsed GC pause event in the log.",
                String.valueOf(longestPause.get("rawLine")),
                Map.of(
                    "pauseMs", longestPause.get("pauseMs"),
                    "beforeHeapMb", longestPause.get("beforeHeapMb"),
                    "afterHeapMb", longestPause.get("afterHeapMb"),
                    "heapCapacityMb", longestPause.get("heapCapacityMb")
                )
            ));
        }

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("collector", detectCollector(artifact.content()));
        extractedData.put("pauses", pauses);
        extractedData.put("summary", summary);
        extractedData.put("metaspace", parseMetaspace(artifact.content()));

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "gc-log-v1", extractedData, evidence, warnings);
    }

    private String detectCollector(String content) {
        String lower = content.toLowerCase();
        if (lower.contains("zgc") || lower.contains("z garbage collector")) {
            return "ZGC";
        }
        if (lower.contains("g1 evacuation pause") || lower.contains("[gc,heap")) {
            return "G1";
        }
        if (lower.contains("shenandoah")) {
            return "Shenandoah";
        }
        return "UNKNOWN";
    }

    private List<Map<String, Object>> parsePauses(String content) {
        List<Map<String, Object>> pauses = new ArrayList<>();
        Matcher matcher = PAUSE_PATTERN.matcher(content);
        while (matcher.find()) {
            Map<String, Object> pause = new LinkedHashMap<>();
            pause.put("event", matcher.group(1).trim());
            pause.put("beforeHeapMb", Long.parseLong(matcher.group(2)));
            pause.put("afterHeapMb", Long.parseLong(matcher.group(3)));
            pause.put("heapCapacityMb", Long.parseLong(matcher.group(4)));
            pause.put("pauseMs", Double.parseDouble(matcher.group(5)));
            pause.put("rawLine", matcher.group(0));
            pauses.add(Map.copyOf(pause));
        }
        return pauses;
    }

    private Map<String, Object> summarizePauses(List<Map<String, Object>> pauses, String content) {
        if (pauses.isEmpty()) {
            return Map.of("eventCount", 0L, "fullGcCount", countOccurrences(content.toLowerCase(), "full gc"));
        }

        double totalPauseMs = 0.0;
        double maxPauseMs = 0.0;
        long fullGcCount = 0;
        long maxHeapCapacityMb = 0;
        long maxAfterHeapMb = 0;

        for (Map<String, Object> pause : pauses) {
            double pauseMs = ((Number) pause.get("pauseMs")).doubleValue();
            totalPauseMs += pauseMs;
            maxPauseMs = Math.max(maxPauseMs, pauseMs);
            String event = String.valueOf(pause.get("event")).toLowerCase();
            if (event.contains("full")) {
                fullGcCount++;
            }
            maxHeapCapacityMb = Math.max(maxHeapCapacityMb, ((Number) pause.get("heapCapacityMb")).longValue());
            maxAfterHeapMb = Math.max(maxAfterHeapMb, ((Number) pause.get("afterHeapMb")).longValue());
        }

        double averagePauseMs = totalPauseMs / pauses.size();
        double occupancyRatio = maxHeapCapacityMb > 0 ? (double) maxAfterHeapMb / maxHeapCapacityMb : 0.0;

        return Map.of(
            "eventCount", pauses.size(),
            "fullGcCount", fullGcCount,
            "averagePauseMs", averagePauseMs,
            "maxPauseMs", maxPauseMs,
            "peakHeapOccupancyRatio", occupancyRatio
        );
    }

    private Map<String, Long> parseMetaspace(String content) {
        Matcher matcher = METASPACE_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }
        return Map.of(
            "beforeUsedKb", Long.parseLong(matcher.group(1)),
            "beforeCapacityKb", Long.parseLong(matcher.group(2)),
            "afterUsedKb", Long.parseLong(matcher.group(3)),
            "afterCapacityKb", Long.parseLong(matcher.group(4))
        );
    }

    private long countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
