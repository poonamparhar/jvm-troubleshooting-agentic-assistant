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

public class NmtArtifactParser implements ArtifactParser {

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "Total:\\s*reserved=(\\d+)KB,\\s*committed=(\\d+)KB",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
        "-\\s*([^\\(]+?)\\s*\\(reserved=(\\d+)KB,\\s*committed=(\\d+)KB(?:,\\s*readonly=(\\d+)KB)?\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLASSES_PATTERN = Pattern.compile("\\(classes #(\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_COUNT_PATTERN = Pattern.compile("\\(thread #(\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_PATTERN = Pattern.compile(
        "\\(stack:\\s*reserved=(\\d+)KB,\\s*committed=(\\d+)KB\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METASPACE_RESERVED_PATTERN = Pattern.compile(
        "\\(\\s*reserved=(\\d+)KB,\\s*committed=(\\d+)KB\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METASPACE_USED_PATTERN = Pattern.compile("\\(\\s*used=(\\d+)KB\\)", Pattern.CASE_INSENSITIVE);

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.NMT;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, Object> extractedData = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Long> total = parseTotal(artifact.content());
        extractedData.put("totalKb", total);
        if (total.isEmpty()) {
            warnings.add("Unable to parse NMT total reserved/committed memory.");
        } else {
            evidence.add(ParserUtils.evidence(
                "nmt-total",
                artifact,
                "NMT total memory",
                "Top-level native memory totals from NMT.",
                "Total:",
                Map.of(
                    "reservedKb", total.get("reservedKb"),
                    "committedKb", total.get("committedKb")
                )
            ));
        }

        Map<String, Map<String, Long>> categories = parseCategories(artifact.content());
        extractedData.put("categories", categories);

        Map<String, Object> classSummary = parseClassSummary(artifact.content());
        extractedData.put("classSummary", classSummary);

        Map<String, Long> threadSummary = parseThreadSummary(artifact.content());
        extractedData.put("threadSummary", threadSummary);

        Map<String, Long> metaspaceSummary = parseMetaspaceSummary(artifact.content());
        extractedData.put("metaspaceSummary", metaspaceSummary);

        if (!categories.containsKey("Class")) {
            warnings.add("Class category was not found in NMT output.");
        }
        if (!categories.containsKey("Thread")) {
            warnings.add("Thread category was not found in NMT output.");
        }
        if (!categories.containsKey("Code")) {
            warnings.add("Code category was not found in NMT output.");
        }
        if (!categories.containsKey("GC")) {
            warnings.add("GC category was not found in NMT output.");
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "nmt-v1", extractedData, evidence, warnings);
    }

    private Map<String, Long> parseTotal(String content) {
        Matcher matcher = TOTAL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }
        return Map.of(
            "reservedKb", Long.parseLong(matcher.group(1)),
            "committedKb", Long.parseLong(matcher.group(2))
        );
    }

    private Map<String, Map<String, Long>> parseCategories(String content) {
        Map<String, Map<String, Long>> categories = new LinkedHashMap<>();
        Matcher matcher = CATEGORY_PATTERN.matcher(content);
        while (matcher.find()) {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("reservedKb", Long.parseLong(matcher.group(2)));
            values.put("committedKb", Long.parseLong(matcher.group(3)));
            if (matcher.group(4) != null) {
                values.put("readonlyKb", Long.parseLong(matcher.group(4)));
            }
            categories.put(matcher.group(1).trim(), Map.copyOf(values));
        }
        return categories;
    }

    private Map<String, Object> parseClassSummary(String content) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Matcher classesMatcher = CLASSES_PATTERN.matcher(content);
        if (classesMatcher.find()) {
            summary.put("classCount", Long.parseLong(classesMatcher.group(1)));
        }
        return summary;
    }

    private Map<String, Long> parseThreadSummary(String content) {
        Map<String, Long> summary = new LinkedHashMap<>();
        Matcher threadMatcher = THREAD_COUNT_PATTERN.matcher(content);
        if (threadMatcher.find()) {
            summary.put("threadCount", Long.parseLong(threadMatcher.group(1)));
        }
        Matcher stackMatcher = STACK_PATTERN.matcher(content);
        if (stackMatcher.find()) {
            summary.put("stackReservedKb", Long.parseLong(stackMatcher.group(1)));
            summary.put("stackCommittedKb", Long.parseLong(stackMatcher.group(2)));
        }
        return summary;
    }

    private Map<String, Long> parseMetaspaceSummary(String content) {
        Map<String, Long> summary = new LinkedHashMap<>();
        int metadataIndex = content.indexOf("(  Metadata:");
        if (metadataIndex < 0) {
            metadataIndex = content.indexOf("(    reserved=");
        }
        if (metadataIndex < 0) {
            return summary;
        }

        String metadataSection = content.substring(metadataIndex, Math.min(content.length(), metadataIndex + 300));
        Matcher reservedMatcher = METASPACE_RESERVED_PATTERN.matcher(metadataSection);
        if (reservedMatcher.find()) {
            summary.put("reservedKb", Long.parseLong(reservedMatcher.group(1)));
            summary.put("committedKb", Long.parseLong(reservedMatcher.group(2)));
        }
        Matcher usedMatcher = METASPACE_USED_PATTERN.matcher(metadataSection);
        if (usedMatcher.find()) {
            summary.put("usedKb", Long.parseLong(usedMatcher.group(1)));
        }
        return summary;
    }
}
