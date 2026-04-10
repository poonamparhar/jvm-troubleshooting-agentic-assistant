package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NmtArtifactParser implements ArtifactParser {

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "Total:\\s*reserved=(\\d+)KB(?:\\s+([+-]\\d+)KB)?,\\s*committed=(\\d+)KB(?:\\s+([+-]\\d+)KB)?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
        "^-\\s*([^\\(\\r\\n]+?)\\s*\\(reserved=(\\d+)KB(?:\\s+([+-]\\d+)KB)?,\\s*committed=(\\d+)KB(?:\\s+([+-]\\d+)KB)?(?:,\\s*readonly=(\\d+)KB(?:\\s+([+-]\\d+)KB)?)?\\)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLASSES_PATTERN = Pattern.compile("\\(classes #(\\d+)(?:\\s+([+-]\\d+))?\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_COUNT_PATTERN = Pattern.compile("\\(thread #(\\d+)(?:\\s+([+-]\\d+))?\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_PATTERN = Pattern.compile(
        "\\(stack:\\s*reserved=(\\d+)KB(?:\\s+([+-]\\d+)KB)?,\\s*committed=(\\d+)KB(?:\\s+([+-]\\d+)KB)?\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METASPACE_RESERVED_PATTERN = Pattern.compile(
        "\\(\\s*reserved=(\\d+)KB(?:\\s+([+-]\\d+)KB)?,\\s*committed=(\\d+)KB(?:\\s+([+-]\\d+)KB)?\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METASPACE_USED_PATTERN = Pattern.compile("\\(\\s*used=(\\d+)KB(?:\\s+([+-]\\d+)KB)?\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_SPACE_WASTE_PATTERN = Pattern.compile(
        "\\(\\s*waste=(\\d+)KB(?:\\s+([+-]\\d+)KB)?\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)%\\)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.NMT;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, Object> extractedData = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        ParsedLongMap totalMetrics = parseTotal(artifact.content());
        Map<String, Long> total = totalMetrics.values();
        Map<String, Long> totalDelta = totalMetrics.deltas();
        extractedData.put("totalKb", total);
        extractedData.put("totalDeltaKb", totalDelta);
        if (total.isEmpty()) {
            warnings.add("Unable to parse NMT total reserved/committed memory.");
        } else {
            maybeAddEvidence(evidence, artifact, "nmt-total", "NMT total memory", "Top-level native memory totals from NMT.", totalMetrics.snippet(), total);
            maybeAddEvidence(
                evidence,
                artifact,
                "nmt-total-delta",
                "NMT total memory delta",
                "Top-level native memory deltas from an NMT diff artifact.",
                totalMetrics.snippet(),
                totalDelta
            );
        }

        CategoryParseResult categoryParseResult = parseCategories(artifact.content());
        Map<String, Map<String, Long>> categories = categoryParseResult.values();
        Map<String, Map<String, Long>> categoryDeltas = categoryParseResult.deltas();
        extractedData.put("categories", categories);
        extractedData.put("categoryDeltas", categoryDeltas);
        addCategoryEvidence(evidence, artifact, categoryParseResult);

        ParsedObjectMap classSummaryParse = parseClassSummary(artifact.content());
        Map<String, Object> classSummary = classSummaryParse.values();
        Map<String, Long> classSummaryDeltas = classSummaryParse.deltas();
        extractedData.put("classSummary", classSummary);
        extractedData.put("classSummaryDeltas", classSummaryDeltas);
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-class-summary",
            "NMT class summary",
            "Loaded class count extracted from the NMT Class section.",
            classSummaryParse.snippet(),
            classSummary
        );
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-class-summary-delta",
            "NMT class summary delta",
            "Loaded class count delta extracted from an NMT diff artifact.",
            classSummaryParse.snippet(),
            classSummaryDeltas
        );

        ParsedLongMap threadSummaryParse = parseThreadSummary(artifact.content());
        Map<String, Long> threadSummary = threadSummaryParse.values();
        Map<String, Long> threadSummaryDeltas = threadSummaryParse.deltas();
        extractedData.put("threadSummary", threadSummary);
        extractedData.put("threadSummaryDeltas", threadSummaryDeltas);
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-thread-summary",
            "NMT thread summary",
            "Thread count and stack reservation extracted from the NMT Thread section.",
            threadSummaryParse.snippet(),
            threadSummary
        );
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-thread-summary-delta",
            "NMT thread summary delta",
            "Thread count and stack reservation deltas extracted from an NMT diff artifact.",
            threadSummaryParse.snippet(),
            threadSummaryDeltas
        );

        ParsedLongMap metaspaceSummaryParse = parseMetaspaceSummary(artifact.content());
        Map<String, Long> metaspaceSummary = metaspaceSummaryParse.values();
        Map<String, Long> metaspaceSummaryDeltas = metaspaceSummaryParse.deltas();
        extractedData.put("metaspaceSummary", metaspaceSummary);
        extractedData.put("metaspaceSummaryDeltas", metaspaceSummaryDeltas);
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-metaspace-summary",
            "NMT metaspace summary",
            "Metaspace reserved, committed, and used metrics extracted from the NMT metadata section.",
            metaspaceSummaryParse.snippet(),
            metaspaceSummary
        );
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-metaspace-summary-delta",
            "NMT metaspace summary delta",
            "Metaspace reserved, committed, and used deltas extracted from an NMT diff artifact.",
            metaspaceSummaryParse.snippet(),
            metaspaceSummaryDeltas
        );

        ParsedObjectMap classSpaceSummaryParse = parseClassSpaceSummary(artifact.content());
        Map<String, Object> classSpaceSummary = classSpaceSummaryParse.values();
        Map<String, Long> classSpaceSummaryDeltas = classSpaceSummaryParse.deltas();
        extractedData.put("classSpaceSummary", classSpaceSummary);
        extractedData.put("classSpaceSummaryDeltas", classSpaceSummaryDeltas);
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-class-space-summary",
            "NMT compressed class space summary",
            "Compressed class space reserved, committed, used, and waste metrics extracted from the NMT Class section.",
            classSpaceSummaryParse.snippet(),
            classSpaceSummary
        );
        maybeAddEvidence(
            evidence,
            artifact,
            "nmt-class-space-summary-delta",
            "NMT compressed class space summary delta",
            "Compressed class space deltas extracted from an NMT diff artifact.",
            classSpaceSummaryParse.snippet(),
            classSpaceSummaryDeltas
        );
        extractedData.put(
            "snapshotKind",
            !totalDelta.isEmpty()
                || !categoryDeltas.isEmpty()
                || !classSummaryDeltas.isEmpty()
                || !threadSummaryDeltas.isEmpty()
                || !metaspaceSummaryDeltas.isEmpty()
                || !classSpaceSummaryDeltas.isEmpty()
                ? "diff"
                : "summary"
        );

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

    private void addCategoryEvidence(List<Evidence> evidence, InputArtifact artifact, CategoryParseResult categoryParseResult) {
        for (Map.Entry<String, Map<String, Long>> entry : categoryParseResult.values().entrySet()) {
            String categoryName = entry.getKey();
            String snippet = categoryParseResult.snippets().get(categoryName);
            maybeAddEvidence(
                evidence,
                artifact,
                categoryEvidenceId("nmt-category", categoryName),
                "NMT " + categoryName + " category",
                "Native memory totals for the " + categoryName + " category.",
                snippet,
                entry.getValue()
            );
        }

        for (Map.Entry<String, Map<String, Long>> entry : categoryParseResult.deltas().entrySet()) {
            String categoryName = entry.getKey();
            String snippet = categoryParseResult.snippets().get(categoryName);
            maybeAddEvidence(
                evidence,
                artifact,
                categoryEvidenceId("nmt-category-delta", categoryName),
                "NMT " + categoryName + " category delta",
                "Native memory deltas for the " + categoryName + " category from an NMT diff artifact.",
                snippet,
                entry.getValue()
            );
        }
    }

    private ParsedLongMap parseTotal(String content) {
        Matcher matcher = TOTAL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return new ParsedLongMap(Map.of(), Map.of(), null);
        }
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("reservedKb", Long.parseLong(matcher.group(1)));
        values.put("committedKb", Long.parseLong(matcher.group(3)));

        Map<String, Long> deltas = new LinkedHashMap<>();
        putSignedLong(deltas, "reservedKb", matcher.group(2));
        putSignedLong(deltas, "committedKb", matcher.group(4));
        return new ParsedLongMap(Map.copyOf(values), immutableOrEmpty(deltas), "Total:");
    }

    private CategoryParseResult parseCategories(String content) {
        Map<String, Map<String, Long>> categories = new LinkedHashMap<>();
        Map<String, Map<String, Long>> categoryDeltas = new LinkedHashMap<>();
        Map<String, String> snippets = new LinkedHashMap<>();
        Matcher matcher = CATEGORY_PATTERN.matcher(content);
        while (matcher.find()) {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("reservedKb", Long.parseLong(matcher.group(2)));
            values.put("committedKb", Long.parseLong(matcher.group(4)));
            if (matcher.group(6) != null) {
                values.put("readonlyKb", Long.parseLong(matcher.group(6)));
            }
            String categoryName = matcher.group(1).trim();
            categories.put(categoryName, Map.copyOf(values));
            snippets.put(categoryName, matcher.group(0).trim());

            Map<String, Long> deltas = new LinkedHashMap<>();
            putSignedLong(deltas, "reservedKb", matcher.group(3));
            putSignedLong(deltas, "committedKb", matcher.group(5));
            putSignedLong(deltas, "readonlyKb", matcher.group(7));
            if (!deltas.isEmpty()) {
                categoryDeltas.put(categoryName, Map.copyOf(deltas));
            }
        }
        return new CategoryParseResult(Map.copyOf(categories), immutableNestedOrEmpty(categoryDeltas), snippets.isEmpty() ? Map.of() : Map.copyOf(snippets));
    }

    private ParsedObjectMap parseClassSummary(String content) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> deltas = new LinkedHashMap<>();
        String snippet = null;
        Matcher classesMatcher = CLASSES_PATTERN.matcher(content);
        if (classesMatcher.find()) {
            summary.put("classCount", Long.parseLong(classesMatcher.group(1)));
            putSignedLong(deltas, "classCount", classesMatcher.group(2));
            snippet = classesMatcher.group(0).trim();
        }
        return new ParsedObjectMap(Map.copyOf(summary), immutableOrEmpty(deltas), snippet);
    }

    private ParsedLongMap parseThreadSummary(String content) {
        Map<String, Long> summary = new LinkedHashMap<>();
        Map<String, Long> deltas = new LinkedHashMap<>();
        String snippet = null;
        Matcher threadMatcher = THREAD_COUNT_PATTERN.matcher(content);
        if (threadMatcher.find()) {
            summary.put("threadCount", Long.parseLong(threadMatcher.group(1)));
            putSignedLong(deltas, "threadCount", threadMatcher.group(2));
            snippet = threadMatcher.group(0).trim();
        }
        Matcher stackMatcher = STACK_PATTERN.matcher(content);
        if (stackMatcher.find()) {
            summary.put("stackReservedKb", Long.parseLong(stackMatcher.group(1)));
            summary.put("stackCommittedKb", Long.parseLong(stackMatcher.group(3)));
            putSignedLong(deltas, "stackReservedKb", stackMatcher.group(2));
            putSignedLong(deltas, "stackCommittedKb", stackMatcher.group(4));
            if (snippet == null) {
                snippet = stackMatcher.group(0).trim();
            }
        }
        return new ParsedLongMap(Map.copyOf(summary), immutableOrEmpty(deltas), snippet);
    }

    private ParsedLongMap parseMetaspaceSummary(String content) {
        Map<String, Long> summary = new LinkedHashMap<>();
        Map<String, Long> deltas = new LinkedHashMap<>();
        int metadataIndex = content.indexOf("Metadata");
        if (metadataIndex < 0) {
            metadataIndex = content.indexOf("(    reserved=");
        }
        if (metadataIndex < 0) {
            return new ParsedLongMap(Map.of(), Map.of(), null);
        }

        String metadataSection = content.substring(metadataIndex, Math.min(content.length(), metadataIndex + 300));
        String snippet = content.contains("Metadata") ? "Metadata" : (content.contains("Metaspace") ? "Metaspace" : null);
        Matcher reservedMatcher = METASPACE_RESERVED_PATTERN.matcher(metadataSection);
        if (reservedMatcher.find()) {
            summary.put("reservedKb", Long.parseLong(reservedMatcher.group(1)));
            summary.put("committedKb", Long.parseLong(reservedMatcher.group(3)));
            putSignedLong(deltas, "reservedKb", reservedMatcher.group(2));
            putSignedLong(deltas, "committedKb", reservedMatcher.group(4));
        }
        Matcher usedMatcher = METASPACE_USED_PATTERN.matcher(metadataSection);
        if (usedMatcher.find()) {
            summary.put("usedKb", Long.parseLong(usedMatcher.group(1)));
            putSignedLong(deltas, "usedKb", usedMatcher.group(2));
        }
        return new ParsedLongMap(Map.copyOf(summary), immutableOrEmpty(deltas), snippet);
    }

    private ParsedObjectMap parseClassSpaceSummary(String content) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> deltas = new LinkedHashMap<>();
        int classSpaceIndex = content.indexOf("Class space:");
        if (classSpaceIndex < 0) {
            return new ParsedObjectMap(Map.of(), Map.of(), null);
        }

        String classSpaceSection = content.substring(classSpaceIndex, Math.min(content.length(), classSpaceIndex + 260));
        Matcher reservedMatcher = METASPACE_RESERVED_PATTERN.matcher(classSpaceSection);
        if (reservedMatcher.find()) {
            summary.put("reservedKb", Long.parseLong(reservedMatcher.group(1)));
            summary.put("committedKb", Long.parseLong(reservedMatcher.group(3)));
            putSignedLong(deltas, "reservedKb", reservedMatcher.group(2));
            putSignedLong(deltas, "committedKb", reservedMatcher.group(4));
        }
        Matcher usedMatcher = METASPACE_USED_PATTERN.matcher(classSpaceSection);
        if (usedMatcher.find()) {
            summary.put("usedKb", Long.parseLong(usedMatcher.group(1)));
            putSignedLong(deltas, "usedKb", usedMatcher.group(2));
        }
        Matcher wasteMatcher = CLASS_SPACE_WASTE_PATTERN.matcher(classSpaceSection);
        if (wasteMatcher.find()) {
            summary.put("wasteKb", Long.parseLong(wasteMatcher.group(1)));
            putSignedLong(deltas, "wasteKb", wasteMatcher.group(2));
            summary.put("wastePct", Double.parseDouble(wasteMatcher.group(3)));
        }
        return new ParsedObjectMap(Map.copyOf(summary), immutableOrEmpty(deltas), "Class space");
    }

    private void maybeAddEvidence(
        List<Evidence> evidence,
        InputArtifact artifact,
        String id,
        String label,
        String detail,
        String snippet,
        Map<String, ?> metrics
    ) {
        if (snippet == null || snippet.isBlank() || metrics == null || metrics.isEmpty()) {
            return;
        }

        Map<String, Object> copiedMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : metrics.entrySet()) {
            copiedMetrics.put(entry.getKey(), entry.getValue());
        }

        evidence.add(ParserUtils.evidence(id, artifact, label, detail, snippet, Map.copyOf(copiedMetrics)));
    }

    private String categoryEvidenceId(String prefix, String categoryName) {
        return prefix + "-" + slugify(categoryName);
    }

    private String slugify(String value) {
        return value.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
    }

    private void putSignedLong(Map<String, Long> target, String key, String rawValue) {
        if (rawValue != null) {
            target.put(key, Long.parseLong(rawValue));
        }
    }

    private Map<String, Long> immutableOrEmpty(Map<String, Long> source) {
        return source.isEmpty() ? Map.of() : Map.copyOf(source);
    }

    private Map<String, Map<String, Long>> immutableNestedOrEmpty(Map<String, Map<String, Long>> source) {
        return source.isEmpty() ? Map.of() : Map.copyOf(source);
    }

    private record ParsedLongMap(Map<String, Long> values, Map<String, Long> deltas, String snippet) { }

    private record ParsedObjectMap(Map<String, Object> values, Map<String, Long> deltas, String snippet) { }

    private record CategoryParseResult(
        Map<String, Map<String, Long>> values,
        Map<String, Map<String, Long>> deltas,
        Map<String, String> snippets
    ) { }
}
