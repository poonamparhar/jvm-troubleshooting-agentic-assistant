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

public class HsErrArtifactParser implements ArtifactParser {

    private static final Pattern SIGNAL_PATTERN = Pattern.compile("#\\s+(SIG[A-Z]+)\\s+\\([^\\)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JRE_PATTERN = Pattern.compile("#\\s+JRE version:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern VM_PATTERN = Pattern.compile("#\\s+Java VM:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern PROBLEMATIC_FRAME_PATTERN = Pattern.compile("#\\s+V\\s+\\[(.+?)\\]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern COMMAND_LINE_PATTERN = Pattern.compile("^Command Line:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CURRENT_THREAD_PATTERN = Pattern.compile("^Current thread .*?:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern HOST_PATTERN = Pattern.compile("^Host:\\s+(.+)$", Pattern.MULTILINE);

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
        String host = firstGroup(HOST_PATTERN, artifact.content(), 1);

        Map<String, String> problematicFrame = parseProblematicFrame(artifact.content());

        extractedData.put("signal", signal);
        extractedData.put("jreVersion", jreVersion);
        extractedData.put("vm", vm);
        extractedData.put("commandLine", commandLine);
        extractedData.put("currentThread", currentThread);
        extractedData.put("host", host);
        extractedData.put("problematicFrame", problematicFrame);

        if (signal == null) {
            warnings.add("Unable to parse fatal signal from hs_err log.");
        }
        if (problematicFrame.isEmpty()) {
            warnings.add("Unable to parse problematic frame from hs_err log.");
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
