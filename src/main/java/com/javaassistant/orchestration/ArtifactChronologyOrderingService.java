package com.javaassistant.orchestration;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.orchestration.AgentDiagnosticContextBuilder.ArtifactGrounding;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers chronological ordering for same-type snapshot sets used by auto-routed analyze flows.
 */
final class ArtifactChronologyOrderingService {

    private static final List<String> EXPLICIT_CAPTURE_TIME_ATTRIBUTE_KEYS = List.of(
        "captureTime",
        "capturedAt",
        "snapshotTime",
        "recordedAt"
    );
    private static final List<String> EXPLICIT_CAPTURE_START_ATTRIBUTE_KEYS = List.of(
        "captureStartTime",
        "capturedStartTime",
        "snapshotStartTime"
    );
    private static final List<String> EXPLICIT_CAPTURE_END_ATTRIBUTE_KEYS = List.of(
        "captureEndTime",
        "capturedEndTime",
        "snapshotEndTime"
    );
    private static final List<String> SUMMARY_START_TIME_KEYS = List.of(
        "captureTime",
        "snapshotTime",
        "startTime",
        "earliestAbsoluteEventTime"
    );
    private static final List<String> SUMMARY_END_TIME_KEYS = List.of(
        "captureTime",
        "snapshotTime",
        "endTime",
        "latestAbsoluteEventTime"
    );
    private static final Set<String> EARLIER_ROLE_TOKENS = Set.of(
        "baseline",
        "base",
        "before",
        "older",
        "old",
        "previous",
        "prev",
        "earlier",
        "first",
        "initial"
    );
    private static final Set<String> LATER_ROLE_TOKENS = Set.of(
        "current",
        "after",
        "newer",
        "new",
        "latest",
        "last",
        "final"
    );
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SEPARATED_DATETIME_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[-_](\\d{2})[-_](\\d{2})[T _-](\\d{2})[-_.:](\\d{2})[-_.:](\\d{2})(Z|[+-]\\d{2}:?\\d{2})?(?!\\d)"
    );
    private static final Pattern COMPACT_DATETIME_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{8})[T_-]?(\\d{6})(Z|[+-]\\d{2}:?\\d{2})?(?!\\d)"
    );
    private static final Pattern SEPARATED_DATE_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[-_](\\d{2})[-_](\\d{2})(?!\\d)"
    );
    private static final Pattern COMPACT_DATE_PATTERN = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");

    OrderingDecision orderPair(List<ArtifactGrounding> groundings) {
        List<ChronologySignal> signals = chronologySignals(groundings, 2);
        OrderingDecision decision = orderByExplicitCaptureTime(signals);
        if (decision != null) {
            return decision;
        }
        decision = orderPairByFilenameRole(signals);
        if (decision != null) {
            return decision;
        }
        decision = orderByFilenameTimestamp(signals);
        if (decision != null) {
            return decision;
        }
        decision = orderByFilesystemModifiedTime(signals);
        if (decision != null) {
            return decision;
        }
        return originalInputOrder(signals);
    }

    OrderingDecision orderSequence(List<ArtifactGrounding> groundings) {
        List<ChronologySignal> signals = chronologySignals(groundings, 3);
        OrderingDecision decision = orderByExplicitCaptureTime(signals);
        if (decision != null) {
            return decision;
        }
        decision = orderByFilenameTimestamp(signals);
        if (decision != null) {
            return decision;
        }
        decision = orderByFilesystemModifiedTime(signals);
        if (decision != null) {
            return decision;
        }
        return originalInputOrder(signals);
    }

    private List<ChronologySignal> chronologySignals(List<ArtifactGrounding> groundings, int minimumSize) {
        if (groundings == null || groundings.size() < minimumSize) {
            throw new IllegalArgumentException("Expected at least " + minimumSize + " grounded artifacts.");
        }
        ArtifactType artifactType = null;
        List<ChronologySignal> signals = new ArrayList<>();
        for (int index = 0; index < groundings.size(); index++) {
            ArtifactGrounding grounding = groundings.get(index);
            if (grounding == null || grounding.inputArtifact() == null || grounding.parsedArtifact() == null) {
                throw new IllegalArgumentException("Chronology ordering requires non-null grounded artifacts.");
            }
            ArtifactType currentType = grounding.inputArtifact().type();
            if (artifactType == null) {
                artifactType = currentType;
            } else if (artifactType != currentType) {
                throw new IllegalArgumentException("Chronology ordering requires same-type grounded artifacts.");
            }
            signals.add(new ChronologySignal(
                grounding,
                index,
                resolveCaptureStart(grounding.parsedArtifact()),
                resolveCaptureEnd(grounding.parsedArtifact()),
                resolveRoleHint(grounding),
                resolveFilenameTimestamp(grounding),
                resolveFilesystemModifiedTime(grounding)
            ));
        }
        return List.copyOf(signals);
    }

    private OrderingDecision orderByExplicitCaptureTime(List<ChronologySignal> signals) {
        if (!signals.stream().allMatch(signal -> signal.captureStart() != null)) {
            return null;
        }
        if (distinctCaptureWindows(signals).size() <= 1) {
            return null;
        }
        return buildDecision(
            signals,
            OrderingBasis.EXPLICIT_CAPTURE_TIME,
            Comparator.comparing(ChronologySignal::captureStart)
                .thenComparing(ChronologySignal::captureEnd, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(ChronologySignal::originalIndex)
        );
    }

    private OrderingDecision orderPairByFilenameRole(List<ChronologySignal> signals) {
        if (signals.size() != 2) {
            return null;
        }
        ChronologySignal first = signals.get(0);
        ChronologySignal second = signals.get(1);
        if (first.roleHint() == second.roleHint() || first.roleHint() == RoleHint.NEUTRAL && second.roleHint() == RoleHint.NEUTRAL) {
            return null;
        }
        return buildDecision(
            signals,
            OrderingBasis.FILENAME_ROLE_HINT,
            Comparator.comparingInt((ChronologySignal signal) -> signal.roleHint().sortOrder())
                .thenComparingInt(ChronologySignal::originalIndex)
        );
    }

    private OrderingDecision orderByFilenameTimestamp(List<ChronologySignal> signals) {
        if (!signals.stream().allMatch(signal -> signal.filenameTimestamp() != null)) {
            return null;
        }
        if (distinctInstants(signals.stream().map(ChronologySignal::filenameTimestamp).toList()).size() <= 1) {
            return null;
        }
        return buildDecision(
            signals,
            OrderingBasis.FILENAME_TIMESTAMP,
            Comparator.comparing(ChronologySignal::filenameTimestamp).thenComparingInt(ChronologySignal::originalIndex)
        );
    }

    private OrderingDecision orderByFilesystemModifiedTime(List<ChronologySignal> signals) {
        if (!signals.stream().allMatch(signal -> signal.filesystemModifiedTime() != null)) {
            return null;
        }
        if (distinctInstants(signals.stream().map(ChronologySignal::filesystemModifiedTime).toList()).size() <= 1) {
            return null;
        }
        return buildDecision(
            signals,
            OrderingBasis.FILESYSTEM_MODIFIED_TIME,
            Comparator.comparing(ChronologySignal::filesystemModifiedTime).thenComparingInt(ChronologySignal::originalIndex)
        );
    }

    private OrderingDecision originalInputOrder(List<ChronologySignal> signals) {
        return new OrderingDecision(
            signals.stream().map(ChronologySignal::grounding).toList(),
            OrderingBasis.ORIGINAL_INPUT_ORDER,
            false
        );
    }

    private OrderingDecision buildDecision(
        List<ChronologySignal> originalSignals,
        OrderingBasis basis,
        Comparator<ChronologySignal> comparator
    ) {
        List<ChronologySignal> orderedSignals = new ArrayList<>(originalSignals);
        orderedSignals.sort(comparator);
        List<ArtifactGrounding> orderedGroundings = orderedSignals.stream().map(ChronologySignal::grounding).toList();
        boolean changed = false;
        for (int index = 0; index < originalSignals.size(); index++) {
            if (originalSignals.get(index).grounding() != orderedGroundings.get(index)) {
                changed = true;
                break;
            }
        }
        return new OrderingDecision(orderedGroundings, basis, changed);
    }

    private Instant resolveCaptureStart(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null) {
            return null;
        }
        Instant metadataStart = firstAttributeInstant(parsedArtifact, EXPLICIT_CAPTURE_START_ATTRIBUTE_KEYS);
        if (metadataStart != null) {
            return metadataStart;
        }
        Instant metadataPoint = firstAttributeInstant(parsedArtifact, EXPLICIT_CAPTURE_TIME_ATTRIBUTE_KEYS);
        if (metadataPoint != null) {
            return metadataPoint;
        }
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        Instant summaryStart = firstInstant(summary, SUMMARY_START_TIME_KEYS);
        if (summaryStart != null) {
            return summaryStart;
        }
        return switch (parsedArtifact.type()) {
            case GC_LOG -> gcAbsoluteTimestamp(parsedArtifact, true);
            default -> null;
        };
    }

    private Instant resolveCaptureEnd(ParsedArtifact parsedArtifact) {
        if (parsedArtifact == null) {
            return null;
        }
        Instant metadataEnd = firstAttributeInstant(parsedArtifact, EXPLICIT_CAPTURE_END_ATTRIBUTE_KEYS);
        if (metadataEnd != null) {
            return metadataEnd;
        }
        Instant metadataPoint = firstAttributeInstant(parsedArtifact, EXPLICIT_CAPTURE_TIME_ATTRIBUTE_KEYS);
        if (metadataPoint != null) {
            return metadataPoint;
        }
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        Instant summaryEnd = firstInstant(summary, SUMMARY_END_TIME_KEYS);
        if (summaryEnd != null) {
            return summaryEnd;
        }
        return switch (parsedArtifact.type()) {
            case GC_LOG -> gcAbsoluteTimestamp(parsedArtifact, false);
            default -> null;
        };
    }

    private Instant gcAbsoluteTimestamp(ParsedArtifact parsedArtifact, boolean earliest) {
        if (parsedArtifact == null || parsedArtifact.extractedData().isEmpty()) {
            return null;
        }

        List<Instant> timestamps = new ArrayList<>();
        addAbsoluteTimestamps(timestamps, parsedArtifact.extractedData().get("pauses"));
        addAbsoluteTimestamps(timestamps, parsedArtifact.extractedData().get("gcCycles"));
        addAbsoluteTimestamps(timestamps, parsedArtifact.extractedData().get("allocationStalls"));
        addAbsoluteTimestamps(timestamps, parsedArtifact.extractedData().get("failureSignals"));
        if (timestamps.isEmpty()) {
            return null;
        }
        timestamps.sort(Comparator.naturalOrder());
        return earliest ? timestamps.getFirst() : timestamps.getLast();
    }

    private void addAbsoluteTimestamps(List<Instant> timestamps, Object candidate) {
        if (!(candidate instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Instant absoluteTimestamp = instantValue(map.get("absoluteTimestamp"));
            if (absoluteTimestamp != null) {
                timestamps.add(absoluteTimestamp);
            }
        }
    }

    private Instant firstAttributeInstant(ParsedArtifact parsedArtifact, List<String> keys) {
        if (parsedArtifact == null || parsedArtifact.metadata() == null || parsedArtifact.metadata().attributes() == null) {
            return null;
        }
        for (String key : keys) {
            Instant instant = instantValue(parsedArtifact.metadata().attributes().get(key));
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    private Instant firstInstant(Map<String, Object> values, List<String> keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Instant instant = instantValue(values.get(key));
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    private RoleHint resolveRoleHint(ArtifactGrounding grounding) {
        String candidateName = primaryName(grounding);
        if (candidateName == null || candidateName.isBlank()) {
            return RoleHint.NEUTRAL;
        }
        List<String> tokens = Arrays.stream(TOKEN_SPLIT_PATTERN.split(stripExtension(candidateName.toLowerCase(Locale.ROOT))))
            .filter(token -> !token.isBlank())
            .toList();
        boolean earlier = tokens.stream().anyMatch(EARLIER_ROLE_TOKENS::contains);
        boolean later = tokens.stream().anyMatch(LATER_ROLE_TOKENS::contains);
        if (earlier == later) {
            return RoleHint.NEUTRAL;
        }
        return earlier ? RoleHint.EARLIER : RoleHint.LATER;
    }

    private Instant resolveFilenameTimestamp(ArtifactGrounding grounding) {
        String candidateName = primaryName(grounding);
        if (candidateName == null || candidateName.isBlank()) {
            return null;
        }
        String stem = stripExtension(candidateName);
        Instant separatedDateTime = parseSeparatedDateTime(stem);
        if (separatedDateTime != null) {
            return separatedDateTime;
        }
        Instant compactDateTime = parseCompactDateTime(stem);
        if (compactDateTime != null) {
            return compactDateTime;
        }
        Instant separatedDate = parseSeparatedDate(stem);
        if (separatedDate != null) {
            return separatedDate;
        }
        return parseCompactDate(stem);
    }

    private Instant parseSeparatedDateTime(String text) {
        Matcher matcher = SEPARATED_DATETIME_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        LocalDateTime dateTime = LocalDateTime.of(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4)),
            Integer.parseInt(matcher.group(5)),
            Integer.parseInt(matcher.group(6))
        );
        String offset = matcher.group(7);
        if (offset != null && !offset.isBlank()) {
            return dateTime.atOffset(ZoneOffset.of(normalizeOffset(offset))).toInstant();
        }
        return dateTime.toInstant(ZoneOffset.UTC);
    }

    private Instant parseCompactDateTime(String text) {
        Matcher matcher = COMPACT_DATETIME_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String datePart = matcher.group(1);
        String timePart = matcher.group(2);
        LocalDateTime dateTime = LocalDateTime.of(
            Integer.parseInt(datePart.substring(0, 4)),
            Integer.parseInt(datePart.substring(4, 6)),
            Integer.parseInt(datePart.substring(6, 8)),
            Integer.parseInt(timePart.substring(0, 2)),
            Integer.parseInt(timePart.substring(2, 4)),
            Integer.parseInt(timePart.substring(4, 6))
        );
        String offset = matcher.group(3);
        if (offset != null && !offset.isBlank()) {
            return dateTime.atOffset(ZoneOffset.of(normalizeOffset(offset))).toInstant();
        }
        return dateTime.toInstant(ZoneOffset.UTC);
    }

    private Instant parseSeparatedDate(String text) {
        Matcher matcher = SEPARATED_DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        LocalDate date = LocalDate.of(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        );
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Instant parseCompactDate(String text) {
        Matcher matcher = COMPACT_DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String datePart = matcher.group(1);
        LocalDate date = LocalDate.of(
            Integer.parseInt(datePart.substring(0, 4)),
            Integer.parseInt(datePart.substring(4, 6)),
            Integer.parseInt(datePart.substring(6, 8))
        );
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Instant resolveFilesystemModifiedTime(ArtifactGrounding grounding) {
        String sourcePath = grounding != null
            && grounding.inputArtifact() != null
            && grounding.inputArtifact().metadata() != null
            ? grounding.inputArtifact().metadata().sourcePath()
            : null;
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(sourcePath);
            if (!Files.exists(path)) {
                return null;
            }
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            return lastModifiedTime != null ? lastModifiedTime.toInstant() : null;
        } catch (InvalidPathException | java.io.IOException ignored) {
            return null;
        }
    }

    private Set<String> distinctCaptureWindows(List<ChronologySignal> signals) {
        LinkedHashSet<String> windows = new LinkedHashSet<>();
        for (ChronologySignal signal : signals) {
            windows.add(String.valueOf(signal.captureStart()) + "|" + String.valueOf(signal.captureEnd()));
        }
        return windows;
    }

    private Set<Instant> distinctInstants(List<Instant> instants) {
        LinkedHashSet<Instant> distinct = new LinkedHashSet<>();
        for (Instant instant : instants) {
            if (instant != null) {
                distinct.add(instant);
            }
        }
        return distinct;
    }

    private String primaryName(ArtifactGrounding grounding) {
        if (grounding == null || grounding.inputArtifact() == null || grounding.inputArtifact().metadata() == null) {
            return null;
        }
        String displayName = grounding.inputArtifact().metadata().displayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String sourcePath = grounding.inputArtifact().metadata().sourcePath();
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(sourcePath);
            Path fileName = path.getFileName();
            return fileName != null ? fileName.toString() : sourcePath;
        } catch (InvalidPathException ignored) {
            return sourcePath;
        }
    }

    private String stripExtension(String value) {
        int extensionIndex = value.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return value;
        }
        return value.substring(0, extensionIndex);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> mapped = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    mapped.put(key, entry.getValue());
                }
            }
            return mapped;
        }
        return Map.of();
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
        }
        try {
            return OffsetDateTime.parse(normalizeOffset(text)).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeOffset(String text) {
        if (text == null || text.isBlank() || text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return text;
        }
        if (text.matches(".*[+-]\\d{4}$")) {
            return text.substring(0, text.length() - 5)
                + text.substring(text.length() - 5, text.length() - 2)
                + ":"
                + text.substring(text.length() - 2);
        }
        return text;
    }

    record OrderingDecision(
        List<ArtifactGrounding> orderedGroundings,
        OrderingBasis basis,
        boolean changed
    ) { }

    enum OrderingBasis {
        EXPLICIT_CAPTURE_TIME,
        FILENAME_ROLE_HINT,
        FILENAME_TIMESTAMP,
        FILESYSTEM_MODIFIED_TIME,
        ORIGINAL_INPUT_ORDER
    }

    private enum RoleHint {
        EARLIER(-1),
        NEUTRAL(0),
        LATER(1);

        private final int sortOrder;

        RoleHint(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        int sortOrder() {
            return sortOrder;
        }
    }

    private record ChronologySignal(
        ArtifactGrounding grounding,
        int originalIndex,
        Instant captureStart,
        Instant captureEnd,
        RoleHint roleHint,
        Instant filenameTimestamp,
        Instant filesystemModifiedTime
    ) { }
}
