package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.GcComparisonToolCallingStubChatModel;
import com.javaassistant.testsupport.GcToolCallingStubChatModel;
import com.javaassistant.testsupport.GcWindowStreakToolCallingStubChatModel;
import com.javaassistant.testsupport.CorrelationToolCallingStubChatModel;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import com.javaassistant.testsupport.JfrToolCallingStubChatModel;
import com.javaassistant.testsupport.LegacyGcToolCallingStubChatModel;
import com.javaassistant.testsupport.LegacyGcWindowStreakToolCallingStubChatModel;
import com.javaassistant.testsupport.OrchestratorTestSupport;
import com.javaassistant.testsupport.RoutingStubChatModel;
import dev.langchain4j.model.chat.ChatModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class ReferenceIncidentBundleEvaluationTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final com.javaassistant.parse.JfrArtifactParser jfrParser = new com.javaassistant.parse.JfrArtifactParser();
    private final Map<String, Map<String, Path>> generatedCorrelationArtifactsByScenario = new LinkedHashMap<>();

    @TestFactory
    Stream<DynamicTest> referenceIncidentBundlesPreserveAgentWorkflowExpectations() throws Exception {
        return loadBundles().stream()
            .map(bundle -> DynamicTest.dynamicTest(bundle.bundleId(), () -> evaluateBundle(bundle)));
    }

    private void evaluateBundle(ReferenceIncidentBundle bundle) throws Exception {
        DiagnosticAgentOrchestrator orchestrator = OrchestratorTestSupport.createOrchestrator(chatModelFor(bundle.stubMode()));
        AnalysisReport report = switch (bundle.workflowType()) {
            case COMPARE -> orchestrator.compare(loadArtifact(bundle.artifactPaths().get(0)), loadArtifact(bundle.artifactPaths().get(1)));
            case SEQUENCE -> orchestrator.sequence(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case CORRELATE -> orchestrator.correlate(bundle.artifactPaths().stream().map(this::loadArtifact).toList());
            case SINGLE_ARTIFACT -> orchestrator.analyze(loadArtifact(bundle.artifactPaths().getFirst()));
        };

        assertNotNull(report.supervisorTrace(), "reference bundles should persist supervisor trace data");
        assertEquals(bundle.workflowType(), report.supervisorTrace().workflowType(), "workflow type should round-trip into the report");
        assertTrue(
            report.supervisorTrace().steps().size() >= bundle.minSupervisorTraceSteps(),
            "supervisor trace should include the expected minimum number of steps"
        );
        if (bundle.expectUserNarrative()) {
            assertTrue(report.hasAiAgentBackedUserNarrative(), "reference bundles should preserve an AI-agent-backed user narrative");
            assertNotNull(report.userNarrative(), "reference bundles should preserve a user narrative");
            assertFalse(report.userNarrative().isBlank(), "reference bundles should preserve a non-empty user narrative");
            assertHasTroubleshootingSections(report.userNarrative());
            assertEquals(
                1,
                report.supervisorTrace().steps().stream().filter(SupervisorTraceStep::selectedForUserNarrative).count(),
                "exactly one supervisor trace step should be marked as the user-facing selection"
            );

            AgentTraceability selectedTraceability = report.agentTraceability().stream()
                .filter(AgentTraceability::selectedForUserNarrative)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a selected narrative traceability entry"));
            assertHasModelExecutionTraceability(selectedTraceability);
            assertEquals(bundle.expectedSelectedAgent(), selectedTraceability.agentName(), "selected narrative agent changed");
            assertEquals(bundle.expectedSelectedSource(), selectedTraceability.narrativeSource(), "selected narrative source changed");
            assertTrue(
                selectedTraceability.qualityGates().stream().noneMatch(result -> result.status() == AgentQualityGateStatus.FAILED),
                "selected narrative should not fail blocking quality gates"
            );
            assertTrue(
                selectedTraceability.toolInvocations().size() >= bundle.minSelectedToolInvocations(),
                "selected narrative used fewer tool invocations than the reference minimum"
            );
            for (String requiredToolName : bundle.requiredSelectedToolNames()) {
                assertTrue(
                    selectedTraceability.toolInvocations().stream().anyMatch(invocation -> requiredToolName.equals(invocation.toolName())),
                    "missing required selected-tool invocation: " + requiredToolName
                );
            }

            SupervisorTraceStep selectedTraceStep = report.supervisorTrace().steps().stream()
                .filter(SupervisorTraceStep::selectedForUserNarrative)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a selected supervisor trace step"));
            assertNotNull(selectedTraceStep.modelExecutionTraceability(), "selected supervisor trace step must carry model execution traceability");
            assertEquals(
                selectedTraceability.modelExecutionTraceability().templateId(),
                selectedTraceStep.modelExecutionTraceability().templateId(),
                "selected supervisor trace step must align with selected narrative template traceability"
            );
        } else {
            assertFalse(report.hasAiAgentBackedUserNarrative(), "bundles marked as retrieval-dependent should not preserve an accepted user narrative under the non-tooling stub");
            assertTrue(report.userNarrative() == null || report.userNarrative().isBlank(), "no user narrative should be accepted when the stub never expands bounded context");
            assertEquals(
                0,
                report.supervisorTrace().steps().stream().filter(SupervisorTraceStep::selectedForUserNarrative).count(),
                "no supervisor trace step should be marked as selected when the bounded-context gate rejects the narrative"
            );
            assertTrue(
                report.agentTraceability().stream().noneMatch(AgentTraceability::selectedForUserNarrative),
                "no traceability entry should be selected when the bounded-context gate rejects the narrative"
            );
            assertTrue(
                report.agentTraceability().stream().anyMatch(traceability ->
                    traceability.qualityGates().stream().anyMatch(result ->
                        result.gateId().equals("coverage-aware-confidence")
                            && result.status() == AgentQualityGateStatus.FAILED
                    )
                ),
                "retrieval-dependent bundles should fail the coverage-aware confidence gate under the non-tooling stub"
            );
        }

        for (SupervisorTraceStepType requiredType : bundle.requiredStepTypes()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> step.stepType() == requiredType),
                "missing required supervisor step type: " + requiredType
            );
        }
        for (String requiredStepId : bundle.requiredStepIds()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> requiredStepId.equals(step.stepId())),
                "missing required supervisor step id: " + requiredStepId
            );
        }
        for (String requiredAgent : bundle.requiredTraceabilityAgents()) {
            assertTrue(
                report.agentTraceability().stream().anyMatch(traceability -> requiredAgent.equals(traceability.agentName())),
                "missing required traceability agent: " + requiredAgent
            );
        }
        for (String requiredAgent : bundle.requiredTraceAgents()) {
            assertTrue(
                report.supervisorTrace().steps().stream().anyMatch(step -> requiredAgent.equals(step.agentName())),
                "missing required supervisor trace agent: " + requiredAgent
            );
        }

        assertTrue(report.findings().size() >= bundle.minFindings(), "finding count dropped below the reference minimum");
        for (String expectedFindingId : bundle.expectedFindingIds()) {
            assertTrue(
                report.findings().stream().anyMatch(finding -> expectedFindingId.equals(finding.id())),
                "missing expected finding id: " + expectedFindingId
            );
        }

        assertTrue(
            report.findings().stream()
                .filter(this::isHighSeverity)
                .allMatch(finding -> !finding.evidenceIds().isEmpty()),
            "high-severity findings must stay supported by evidence"
        );
    }

    private boolean isHighSeverity(Finding finding) {
        return finding.severity() == SeverityLevel.HIGH || finding.severity() == SeverityLevel.CRITICAL;
    }

    private void assertHasTroubleshootingSections(String narrative) {
        for (String sectionLabel : List.of("Summary:", "Key metrics:", "Likely issues:", "Recommended actions:")) {
            assertTrue(
                narrative.contains(sectionLabel),
                "accepted reference narratives must preserve the troubleshooting section " + sectionLabel
            );
        }
    }

    private void assertHasModelExecutionTraceability(AgentTraceability traceability) {
        assertNotNull(traceability.modelExecutionTraceability(), "selected narrative must carry model execution traceability");
        assertTrue(hasText(traceability.modelExecutionTraceability().providerId()), "selected narrative provider traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelName()), "selected narrative model traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().modelFamily()), "selected narrative model-family traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateId()), "selected narrative template traceability missing");
        assertTrue(hasText(traceability.modelExecutionTraceability().templateVersion()), "selected narrative template version missing");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private com.javaassistant.diagnostics.InputArtifact loadArtifact(String artifactPath) {
        try {
            return loader.load(resolveArtifactPath(artifactPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load reference incident artifact: " + artifactPath, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to materialize reference incident artifact: " + artifactPath, exception);
        }
    }

    private List<ReferenceIncidentBundle> loadBundles() throws IOException {
        Path root = Path.of("src/test/resources/reference-incidents");
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                .filter(Files::isDirectory)
                .sorted()
                .map(this::loadBundle)
                .toList();
        }
    }

    private ReferenceIncidentBundle loadBundle(Path bundleDir) {
        Properties properties = new Properties();
        Path manifestPath = bundleDir.resolve("manifest.properties");
        try (InputStream inputStream = Files.newInputStream(manifestPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load reference incident manifest: " + manifestPath, exception);
        }

        return new ReferenceIncidentBundle(
            bundleDir.getFileName().toString(),
            OrchestrationWorkflowType.valueOf(required(properties, "workflowType")),
            split(properties.getProperty("artifactPaths")),
            bool(properties.getProperty("expectUserNarrative"), true),
            required(properties, "expectedSelectedAgent"),
            AgentNarrativeSource.valueOf(required(properties, "expectedSelectedSource")),
            split(properties.getProperty("requiredTraceabilityAgents")),
            split(properties.getProperty("requiredTraceAgents")),
            split(properties.getProperty("requiredStepIds")),
            split(properties.getProperty("expectedFindingIds")),
            split(properties.getProperty("requiredStepTypes")).stream()
                .map(SupervisorTraceStepType::valueOf)
                .toList(),
            integer(properties.getProperty("minFindings"), 1),
            integer(properties.getProperty("minSupervisorTraceSteps"), 1),
            enumValue(properties.getProperty("stubMode"), StubMode.NON_TOOLING, StubMode.class),
            split(properties.getProperty("requiredSelectedToolNames")),
            integer(properties.getProperty("minSelectedToolInvocations"), 0)
        );
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required reference incident property: " + key);
        }
        return value.strip();
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private int integer(String value, int defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.strip());
    }

    private boolean bool(String value, boolean defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.strip());
    }

    private <E extends Enum<E>> E enumValue(String value, E defaultValue, Class<E> enumType) {
        return value == null || value.isBlank() ? defaultValue : Enum.valueOf(enumType, value.strip());
    }

    private ChatModel chatModelFor(StubMode stubMode) {
        return switch (stubMode) {
            case CORRELATION_TOOL_RETRIEVAL -> new CorrelationToolCallingStubChatModel();
            case GC_COMPARE_TOOL_RETRIEVAL -> new GcComparisonToolCallingStubChatModel();
            case GC_TOOL_RETRIEVAL -> new GcToolCallingStubChatModel();
            case LEGACY_GC_TOOL_RETRIEVAL -> new LegacyGcToolCallingStubChatModel();
            case GC_WINDOW_STREAK_TOOL_RETRIEVAL -> new GcWindowStreakToolCallingStubChatModel();
            case LEGACY_GC_WINDOW_STREAK_TOOL_RETRIEVAL -> new LegacyGcWindowStreakToolCallingStubChatModel();
            case JFR_TOOL_RETRIEVAL -> new JfrToolCallingStubChatModel();
            case NON_TOOLING -> new RoutingStubChatModel();
        };
    }

    private Path resolveArtifactPath(String artifactPath) throws Exception {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalStateException("Reference incident artifact path must not be blank.");
        }
        if (artifactPath.startsWith("generated-correlation:")) {
            return resolveGeneratedCorrelationArtifact(artifactPath);
        }
        if (!artifactPath.startsWith("generated-jfr:")) {
            return Path.of(artifactPath);
        }

        String scenario = artifactPath.substring("generated-jfr:".length()).strip();
        Path tempDirectory = Files.createTempDirectory("reference-jfr-bundle");
        tempDirectory.toFile().deleteOnExit();
        Path recordingPath = tempDirectory.resolve(scenario + ".jfr");
        return switch (scenario) {
            case "contention-and-gc" -> JfrTestRecordingFactory.createContentionAndGcRecording(recordingPath);
            case "deeper-analytics" -> JfrTestRecordingFactory.createDeeperAnalyticsRecording(recordingPath);
            case "hot-path" -> JfrTestRecordingFactory.createHotPathRecording(recordingPath);
            case "allocation-path" -> JfrTestRecordingFactory.createAllocationPathRecording(recordingPath);
            case "retained-objects" -> JfrTestRecordingFactory.createRetainedObjectRecording(recordingPath);
            case "comparison-baseline" -> JfrTestRecordingFactory.createComparisonBaselineRecording(recordingPath);
            case "comparison-current" -> JfrTestRecordingFactory.createComparisonCurrentRecording(recordingPath);
            default -> throw new IllegalStateException("Unsupported generated JFR scenario: " + scenario);
        };
    }

    private Path resolveGeneratedCorrelationArtifact(String artifactPath) throws Exception {
        String remainder = artifactPath.substring("generated-correlation:".length()).strip();
        String[] parts = remainder.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException("Unsupported generated correlation artifact path: " + artifactPath);
        }

        Map<String, Path> generatedArtifacts = generatedCorrelationArtifacts(parts[0].strip());
        Path path = generatedArtifacts.get(parts[1].strip());
        if (path == null) {
            throw new IllegalStateException(
                "Unsupported generated correlation artifact key " + parts[1].strip()
                    + " for scenario " + parts[0].strip()
                    + ". Available keys: " + generatedArtifacts.keySet()
            );
        }
        return path;
    }

    private Map<String, Path> generatedCorrelationArtifacts(String scenario) throws Exception {
        Map<String, Path> cached = generatedCorrelationArtifactsByScenario.get(scenario);
        if (cached != null) {
            return cached;
        }

        Path tempDirectory = Files.createTempDirectory("reference-correlation-" + scenario);
        tempDirectory.toFile().deleteOnExit();

        Map<String, Path> generated = switch (scenario) {
            case "jfr-gc-heap" -> createJfrGcHeapScenario(tempDirectory);
            case "jfr-thread-dump" -> createJfrThreadDumpScenario(tempDirectory);
            default -> throw new IllegalStateException("Unsupported generated correlation scenario: " + scenario);
        };

        Map<String, Path> immutable = Map.copyOf(generated);
        generatedCorrelationArtifactsByScenario.put(scenario, immutable);
        return immutable;
    }

    private Map<String, Path> createJfrGcHeapScenario(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createIncidentWindowRecording(tempDirectory.resolve("correlate-jfr-gc-heap-recording.jfr"));
        com.javaassistant.diagnostics.ParsedArtifact jfrParsed = jfrParser.parse(loader.load(jfrPath));
        Path gcPath = createGcLogOverlappingIncidentWindow(tempDirectory.resolve("correlate-jfr-gc-heap.log"), jfrParsed);
        Path heapPath = createMatchingHeapHistogram(tempDirectory.resolve("correlate-jfr-gc-heap.txt"));
        generated.put("jfr", jfrPath);
        generated.put("gc", gcPath);
        generated.put("heap", heapPath);
        return generated;
    }

    private Map<String, Path> createJfrThreadDumpScenario(Path tempDirectory) throws Exception {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path jfrPath = JfrTestRecordingFactory.createIncidentWindowRecordingWithThreadJoins(
            tempDirectory.resolve("correlate-jfr-thread-contention-recording.jfr")
        );
        com.javaassistant.diagnostics.ParsedArtifact jfrParsed = jfrParser.parse(loader.load(jfrPath));
        Path threadDumpPath = createTimedThreadDump(
            tempDirectory.resolve("correlate-jfr-thread-contention.txt"),
            firstIncidentWindowMidpoint(jfrParsed)
        );
        generated.put("jfr", jfrPath);
        generated.put("thread-dump", threadDumpPath);
        return generated;
    }

    private Path createMatchingHeapHistogram(Path path) throws Exception {
        String content = """
            num     #instances         #bytes  class name
            ----------------------------------------------
               1:         42000       16800000  java.util.LinkedHashMap
               2:         90000        9600000  [B
               3:         80000        6400000  java.lang.String
               4:         30000        4800000  java.util.LinkedHashMap$Entry
            Total        242000       36800000
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path createGcLogOverlappingIncidentWindow(Path path, com.javaassistant.diagnostics.ParsedArtifact jfrParsed) throws Exception {
        Instant incidentStart = incidentWindowStart(jfrParsed);
        Instant gcFirst = incidentStart;
        Instant gcSecond = incidentStart.plusMillis(350L);
        Instant bootstrap = gcFirst.minusSeconds(1L);

        String content = ""
            + "[" + bootstrap + "][0.100s][info][gc] Using G1\n"
            + "[" + gcFirst + "][1.100s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 1020M->1018M(1024M) 220.000ms\n"
            + "[" + gcSecond + "][1.450s][info][gc] GC(2) Pause Full (G1 Compaction Pause) 1022M->1020M(1024M) 260.000ms\n";
        Files.writeString(path, content);
        return path;
    }

    private Path createTimedThreadDump(Path path, Instant captureTime) throws Exception {
        String sample = Files.readString(Path.of("samples/thread_dump_deadlock.txt"));
        int firstNewline = sample.indexOf('\n');
        String threadDumpBody = firstNewline >= 0 ? sample.substring(firstNewline + 1).stripLeading() : sample;
        Files.writeString(path, "Capture time: " + captureTime + "\n" + threadDumpBody);
        return path;
    }

    private Instant incidentWindowStart(com.javaassistant.diagnostics.ParsedArtifact jfrParsed) {
        return Instant.parse(firstIncidentWindow(jfrParsed).get("startTime").toString());
    }

    private Instant firstIncidentWindowMidpoint(com.javaassistant.diagnostics.ParsedArtifact jfrParsed) {
        Map<String, Object> incidentWindow = firstIncidentWindow(jfrParsed);
        Instant start = Instant.parse(incidentWindow.get("startTime").toString());
        Instant end = Instant.parse(incidentWindow.get("endTime").toString());
        if (!end.isAfter(start)) {
            return start;
        }
        return start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstIncidentWindow(com.javaassistant.diagnostics.ParsedArtifact jfrParsed) {
        List<Map<String, Object>> incidentWindows = (List<Map<String, Object>>) jfrParsed.extractedData().get("incidentWindows");
        return incidentWindows.getFirst();
    }

    private record ReferenceIncidentBundle(
        String bundleId,
        OrchestrationWorkflowType workflowType,
        List<String> artifactPaths,
        boolean expectUserNarrative,
        String expectedSelectedAgent,
        AgentNarrativeSource expectedSelectedSource,
        List<String> requiredTraceabilityAgents,
        List<String> requiredTraceAgents,
        List<String> requiredStepIds,
        List<String> expectedFindingIds,
        List<SupervisorTraceStepType> requiredStepTypes,
        int minFindings,
        int minSupervisorTraceSteps,
        StubMode stubMode,
        List<String> requiredSelectedToolNames,
        int minSelectedToolInvocations
    ) { }

    private enum StubMode {
        NON_TOOLING,
        CORRELATION_TOOL_RETRIEVAL,
        GC_COMPARE_TOOL_RETRIEVAL,
        GC_TOOL_RETRIEVAL,
        LEGACY_GC_TOOL_RETRIEVAL,
        GC_WINDOW_STREAK_TOOL_RETRIEVAL,
        LEGACY_GC_WINDOW_STREAK_TOOL_RETRIEVAL,
        JFR_TOOL_RETRIEVAL
    }
}
