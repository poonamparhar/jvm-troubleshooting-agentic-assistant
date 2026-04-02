package com.javaassistant.report;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AgentToolInvocation;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTrace;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads canonical AnalysisReport JSON that was emitted by JsonReportRenderer.
 */
final class AnalysisReportJsonCodec {

    AnalysisReport fromJson(String json) {
        Object parsed = new JsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Saved report JSON did not contain a top-level object.");
        }
        return toAnalysisReport(castMap(map));
    }

    private AnalysisReport toAnalysisReport(Map<String, Object> map) {
        return new AnalysisReport(
            intValue(map.get("schemaVersion"), AnalysisReport.CURRENT_SCHEMA_VERSION),
            stringValue(map.get("analysisId")),
            dateTimeValue(map.get("createdAt")),
            stringValue(map.get("incidentSummary")),
            stringValue(map.get("userNarrative")),
            toAgentTraceability(map.get("agentTraceability")),
            toSupervisorTrace(map.get("supervisorTrace")),
            enumValue(SeverityLevel.class, stringValue(map.get("overallSeverity"))),
            enumValue(ConfidenceLevel.class, stringValue(map.get("confidence"))),
            toInputArtifacts(map.get("inputArtifacts")),
            toParsedArtifacts(map.get("parsedArtifacts")),
            toEvidenceList(map.get("evidence")),
            toFindings(map.get("findings")),
            toActions(map.get("recommendedActions")),
            toStringList(map.get("missingData")),
            toStringList(map.get("followUpCommands")),
            toArtifactInventory(map.get("artifactInventory")),
            toCorrelationResult(map.get("correlationResult"))
        );
    }

    private SupervisorTrace toSupervisorTrace(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = asMap(value);
        return new SupervisorTrace(
            enumValue(OrchestrationWorkflowType.class, stringValue(map.get("workflowType"))),
            toStringList(map.get("artifactPaths")),
            toSupervisorTraceSteps(map.get("steps"))
        );
    }

    private List<SupervisorTraceStep> toSupervisorTraceSteps(Object value) {
        List<SupervisorTraceStep> steps = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            steps.add(new SupervisorTraceStep(
                stringValue(map.get("stepId")),
                enumValue(SupervisorTraceStepType.class, stringValue(map.get("stepType"))),
                stringValue(map.get("stageId")),
                stringValue(map.get("decision")),
                enumValue(ArtifactType.class, stringValue(map.get("artifactType"))),
                toStringList(map.get("artifactPaths")),
                toStringList(map.get("evidenceIds")),
                toStringList(map.get("findingIds")),
                stringValue(map.get("agentName")),
                enumValue(AgentNarrativeSource.class, stringValue(map.get("narrativeSource"))),
                booleanValue(map.get("selectedForUserNarrative")),
                toAgentToolInvocations(map.get("toolInvocations")),
                toModelExecutionTraceability(map.get("modelExecutionTraceability"))
            ));
        }
        return steps;
    }

    private List<AgentTraceability> toAgentTraceability(Object value) {
        List<AgentTraceability> traceability = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            traceability.add(new AgentTraceability(
                stringValue(map.get("stageId")),
                stringValue(map.get("agentName")),
                enumValue(AgentNarrativeSource.class, stringValue(map.get("narrativeSource"))),
                enumValue(ArtifactType.class, stringValue(map.get("artifactType"))),
                toStringList(map.get("artifactPaths")),
                toStringList(map.get("evidenceIds")),
                booleanValue(map.get("selectedForUserNarrative")),
                toAgentQualityGates(map.get("qualityGates")),
                toAgentToolInvocations(map.get("toolInvocations")),
                toModelExecutionTraceability(map.get("modelExecutionTraceability"))
            ));
        }
        return traceability;
    }

    private ModelExecutionTraceability toModelExecutionTraceability(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = asMap(value);
        return new ModelExecutionTraceability(
            stringValue(map.get("providerId")),
            stringValue(map.get("providerLabel")),
            stringValue(map.get("modelName")),
            stringValue(map.get("modelFamily")),
            stringValue(map.get("templateId")),
            stringValue(map.get("templateVersion"))
        );
    }

    private List<AgentToolInvocation> toAgentToolInvocations(Object value) {
        List<AgentToolInvocation> invocations = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            invocations.add(new AgentToolInvocation(
                stringValue(map.get("toolName")),
                stringValue(map.get("toolFamily")),
                enumValue(ArtifactType.class, stringValue(map.get("artifactType"))),
                stringValue(map.get("artifactPath")),
                stringValue(map.get("request")),
                stringValue(map.get("sliceId")),
                stringValue(map.get("label")),
                stringValue(map.get("traceability")),
                booleanValue(map.get("truncated")),
                booleanValue(map.get("moreAvailable"))
            ));
        }
        return invocations;
    }

    private List<AgentQualityGateResult> toAgentQualityGates(Object value) {
        List<AgentQualityGateResult> gates = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            gates.add(new AgentQualityGateResult(
                stringValue(map.get("gateId")),
                enumValue(AgentQualityGateStatus.class, stringValue(map.get("status"))),
                stringValue(map.get("detail"))
            ));
        }
        return gates;
    }

    private List<InputArtifact> toInputArtifacts(Object value) {
        List<InputArtifact> artifacts = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            artifacts.add(new InputArtifact(
                enumValue(ArtifactType.class, stringValue(map.get("type"))),
                toArtifactMetadata(map.get("metadata")),
                stringValue(map.get("content"))
            ));
        }
        return artifacts;
    }

    private ArtifactMetadata toArtifactMetadata(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = asMap(value);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(map.get("attributes")).entrySet()) {
            attributes.put(entry.getKey(), stringValue(entry.getValue()));
        }
        return new ArtifactMetadata(
            stringValue(map.get("sourcePath")),
            stringValue(map.get("displayName")),
            longValue(map.get("contentLength")),
            dateTimeValue(map.get("discoveredAt")),
            attributes
        );
    }

    private List<ParsedArtifact> toParsedArtifacts(Object value) {
        List<ParsedArtifact> artifacts = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            artifacts.add(new ParsedArtifact(
                enumValue(ArtifactType.class, stringValue(map.get("type"))),
                toArtifactMetadata(map.get("metadata")),
                stringValue(map.get("parserVersion")),
                deepCopyMap(map.get("extractedData")),
                toEvidenceList(map.get("evidence")),
                toStringList(map.get("warnings"))
            ));
        }
        return artifacts;
    }

    private List<Evidence> toEvidenceList(Object value) {
        List<Evidence> evidence = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            evidence.add(new Evidence(
                stringValue(map.get("id")),
                stringValue(map.get("artifactPath")),
                stringValue(map.get("label")),
                stringValue(map.get("detail")),
                stringValue(map.get("snippet")),
                toIntegerList(map.get("lineNumbers")),
                deepCopyMap(map.get("metrics"))
            ));
        }
        return evidence;
    }

    private List<Finding> toFindings(Object value) {
        List<Finding> findings = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            findings.add(new Finding(
                stringValue(map.get("id")),
                stringValue(map.get("title")),
                stringValue(map.get("summary")),
                stringValue(map.get("category")),
                enumValue(SeverityLevel.class, stringValue(map.get("severity"))),
                enumValue(ConfidenceLevel.class, stringValue(map.get("confidence"))),
                enumValue(FindingStatus.class, stringValue(map.get("status"))),
                toStringList(map.get("artifactPaths")),
                toStringList(map.get("evidenceIds")),
                stringValue(map.get("rationale"))
            ));
        }
        return findings;
    }

    private List<ArtifactInventoryEntry> toArtifactInventory(Object value) {
        List<ArtifactInventoryEntry> inventory = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            inventory.add(new ArtifactInventoryEntry(
                stringValue(map.get("sourcePath")),
                stringValue(map.get("displayName")),
                enumValue(ArtifactType.class, stringValue(map.get("artifactType"))),
                enumValue(ArtifactInventoryStatus.class, stringValue(map.get("status"))),
                stringValue(map.get("detail"))
            ));
        }
        return inventory;
    }

    private List<RecommendedAction> toActions(Object value) {
        List<RecommendedAction> actions = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            Map<String, Object> map = asMap(item);
            actions.add(new RecommendedAction(
                stringValue(map.get("id")),
                stringValue(map.get("summary")),
                stringValue(map.get("rationale")),
                enumValue(ActionType.class, stringValue(map.get("actionType"))),
                enumValue(ActionPriority.class, stringValue(map.get("priority"))),
                toStringList(map.get("steps")),
                toStringList(map.get("relatedFindingIds"))
            ));
        }
        return actions;
    }

    private CorrelationResult toCorrelationResult(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = asMap(value);
        return new CorrelationResult(
            stringValue(map.get("summary")),
            enumValue(ConfidenceLevel.class, stringValue(map.get("confidence"))),
            toFindings(map.get("findings")),
            toActions(map.get("recommendedActions")),
            toStringList(map.get("contributingArtifactPaths"))
        );
    }

    private List<String> toStringList(Object value) {
        List<String> list = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            list.add(stringValue(item));
        }
        return list;
    }

    private List<Integer> toIntegerList(Object value) {
        List<Integer> list = new ArrayList<>();
        for (Object item : toObjectList(value)) {
            list.add(item instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(item)));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> value) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private List<Object> toObjectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDateTime dateTimeValue(Object value) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text);
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumType, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(enumType, value);
    }

    private static final class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected trailing data in saved report JSON.");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input.");
            }
            return switch (text.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("Invalid escape sequence in saved report JSON.");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape in saved report JSON.");
                            }
                            String hex = text.substring(index, index + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape sequence in saved report JSON: \\" + escaped);
                    }
                } else {
                    builder.append(current);
                }
            }
            throw new IllegalArgumentException("Unterminated string in saved report JSON.");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Invalid token in saved report JSON.");
            }
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                return Double.parseDouble(text.substring(start, index));
            }
            return Long.parseLong(text.substring(start, index));
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in saved report JSON.");
            }
            index++;
        }
    }
}
