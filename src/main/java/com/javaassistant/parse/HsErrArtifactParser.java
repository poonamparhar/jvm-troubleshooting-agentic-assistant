package com.javaassistant.parse;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HsErrArtifactParser implements ArtifactParser {

    private static final Pattern SIGNAL_PATTERN = Pattern.compile("#\\s+(SIG[A-Z]+)\\s+\\([^\\)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JRE_PATTERN = Pattern.compile("#\\s+JRE version:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern VM_PATTERN = Pattern.compile("#\\s+Java VM:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern PROBLEMATIC_FRAME_PATTERN = Pattern.compile("#\\s+V\\s+\\[(.+?)\\]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern VM_ERROR_PATTERN = Pattern.compile(
        "#\\s+Out of Memory Error \\(([^\\)]+)\\), pid=(\\d+), tid=(\\d+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NATIVE_ALLOCATION_FAILURE_PATTERN = Pattern.compile(
        "^#\\s+Native memory allocation \\(([^\\)]+)\\) failed to allocate (\\d+) bytes for (.+)$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMMAND_LINE_PATTERN = Pattern.compile("^Command Line:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CURRENT_THREAD_PATTERN = Pattern.compile("^Current thread .*?:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CURRENT_THREAD_NAME_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern HOST_PATTERN = Pattern.compile("^Host:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "^Time:\\s+(.+?)\\s+elapsed time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s+seconds.*$",
        Pattern.MULTILINE
    );
    private static final DateTimeFormatter HS_ERR_TIME_FORMATTER = DateTimeFormatter.ofPattern(
        "EEE MMM d HH:mm:ss yyyy z",
        Locale.ENGLISH
    );

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.HS_ERR_LOG;
    }

    @Override
    public ParsedArtifact parse(InputArtifact artifact) {
        Map<String, Object> extractedData = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String signal = firstGroup(SIGNAL_PATTERN, artifact.content(), 1);
        String jreVersion = firstGroup(JRE_PATTERN, artifact.content(), 1);
        String vm = firstGroup(VM_PATTERN, artifact.content(), 1);
        String commandLine = firstGroup(COMMAND_LINE_PATTERN, artifact.content(), 1);
        String currentThread = firstGroup(CURRENT_THREAD_PATTERN, artifact.content(), 1);
        String currentThreadName = parseCurrentThreadName(currentThread);
        String host = firstGroup(HOST_PATTERN, artifact.content(), 1);
        Map<String, Object> vmError = parseVmError(artifact.content());
        Map<String, Object> nativeAllocationFailure = parseNativeAllocationFailure(artifact.content());
        Map<String, Object> crashTimeSummary = parseCrashTimeSummary(artifact.content());

        Map<String, String> problematicFrame = parseProblematicFrame(artifact.content());
        String crashType = detectCrashType(signal, nativeAllocationFailure, problematicFrame);

        extractedData.put("signal", signal);
        extractedData.put("crashType", crashType);
        extractedData.put("jreVersion", jreVersion);
        extractedData.put("vm", vm);
        extractedData.put("vmError", vmError);
        extractedData.put("nativeAllocationFailure", nativeAllocationFailure);
        extractedData.put("commandLine", commandLine);
        extractedData.put("currentThread", currentThread);
        extractedData.put("currentThreadName", currentThreadName);
        extractedData.put("host", host);
        extractedData.put("problematicFrame", problematicFrame);
        extractedData.put("crashTime", crashTimeSummary.get("crashTime"));
        extractedData.put("crashTimeText", crashTimeSummary.get("crashTimeText"));
        extractedData.put("elapsedTimeSeconds", crashTimeSummary.get("elapsedTimeSeconds"));

        if (signal == null && !"native_allocation_failure".equals(crashType)) {
            warnings.add("Unable to parse fatal signal from hs_err log.");
        } else if (signal != null) {
            evidence.add(ParserUtils.evidence(
                "hs-err-signal",
                artifact,
                "Fatal signal",
                "Fatal signal recorded in the hs_err crash header.",
                signal,
                Map.of("signal", signal)
            ));
        }

        if (!vmError.isEmpty()) {
            evidence.add(ParserUtils.evidence(
                "hs-err-vm-error",
                artifact,
                "VM error header",
                "VM error header recorded in the hs_err crash log.",
                "Out of Memory Error",
                vmError
            ));
        }

        if (crashTimeSummary.containsKey("crashTime")) {
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("crashTime", crashTimeSummary.get("crashTime"));
            if (crashTimeSummary.containsKey("elapsedTimeSeconds")) {
                metrics.put("elapsedTimeSeconds", crashTimeSummary.get("elapsedTimeSeconds"));
            }
            evidence.add(ParserUtils.evidence(
                "hs-err-crash-time",
                artifact,
                "Crash time",
                "Absolute crash time reported in the hs_err summary header.",
                String.valueOf(crashTimeSummary.get("crashTimeText")),
                metrics
            ));
        }

        if (!nativeAllocationFailure.isEmpty()) {
            evidence.add(ParserUtils.evidence(
                "hs-err-native-allocation-failure",
                artifact,
                "Native allocation failure",
                "Native memory allocation failure recorded in the hs_err header.",
                "Native memory allocation",
                nativeAllocationFailure
            ));
        }

        if (currentThread != null) {
            evidence.add(ParserUtils.evidence(
                "hs-err-current-thread",
                artifact,
                "Current thread",
                "Thread that was current when the hs_err crash log was written.",
                currentThreadName != null ? "\"" + currentThreadName + "\"" : currentThread,
                currentThreadName != null ? Map.of("currentThreadName", currentThreadName) : Map.of()
            ));
        }

        if (problematicFrame.isEmpty()) {
            if ("fatal_signal".equals(crashType)) {
                warnings.add("Unable to parse problematic frame from hs_err log.");
            }
        } else {
            evidence.add(ParserUtils.evidence(
                "hs-err-problematic-frame",
                artifact,
                "Problematic frame",
                "Problematic VM frame reported in the crash header.",
                String.valueOf(problematicFrame.get("symbol")),
                Map.of()
            ));
        }

        return new ParsedArtifact(artifact.type(), artifact.metadata(), "hs-err-v1", extractedData, evidence, warnings);
    }

    private String firstGroup(Pattern pattern, String content, int group) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(group).trim() : null;
    }

    private Map<String, Object> parseVmError(String content) {
        Matcher matcher = VM_ERROR_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }

        return Map.of(
            "location", matcher.group(1).trim(),
            "pid", Long.parseLong(matcher.group(2)),
            "tid", Long.parseLong(matcher.group(3))
        );
    }

    private Map<String, Object> parseNativeAllocationFailure(String content) {
        Matcher matcher = NATIVE_ALLOCATION_FAILURE_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }

        return Map.of(
            "allocator", matcher.group(1).trim(),
            "bytes", Long.parseLong(matcher.group(2)),
            "requestSite", matcher.group(3).trim()
        );
    }

    private String parseCurrentThreadName(String currentThread) {
        if (currentThread == null || currentThread.isBlank()) {
            return null;
        }
        Matcher matcher = CURRENT_THREAD_NAME_PATTERN.matcher(currentThread);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private Map<String, Object> parseCrashTimeSummary(String content) {
        Matcher matcher = TIME_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }

        String crashTimeText = matcher.group(1).replaceAll("\\s+", " ").trim();
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("crashTimeText", crashTimeText);

        Instant crashTime = parseHsErrTime(crashTimeText);
        if (crashTime != null) {
            summary.put("crashTime", crashTime.toString());
        }

        try {
            summary.put("elapsedTimeSeconds", Double.parseDouble(matcher.group(2)));
        } catch (NumberFormatException ignored) {
            // Keep the parsed crash time even if the elapsed-seconds field is malformed.
        }

        return Map.copyOf(summary);
    }

    private Instant parseHsErrTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(rawTime, HS_ERR_TIME_FORMATTER).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String detectCrashType(String signal, Map<String, Object> nativeAllocationFailure, Map<String, String> problematicFrame) {
        if (signal != null) {
            return "fatal_signal";
        }
        if (!nativeAllocationFailure.isEmpty()) {
            return "native_allocation_failure";
        }
        if (!problematicFrame.isEmpty()) {
            return "fatal_signal";
        }
        return "unknown";
    }

    private Map<String, String> parseProblematicFrame(String content) {
        int markerIndex = content.indexOf("# Problematic frame:");
        if (markerIndex < 0) {
            return Map.of();
        }

        String section = content.substring(markerIndex, Math.min(content.length(), markerIndex + 300));
        Matcher matcher = PROBLEMATIC_FRAME_PATTERN.matcher(section);
        if (!matcher.find()) {
            return Map.of();
        }

        return Map.of(
            "library", matcher.group(1).trim(),
            "symbol", matcher.group(2).trim()
        );
    }
}
