package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTrace;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportRendererAgentTraceabilityTest {

    @Test
    void rendersAgentTraceabilityAcrossShareableFormats() {
        AnalysisReport report = new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-1",
            LocalDateTime.of(2026, 3, 31, 9, 0),
            "Synthetic report",
            "AI narrative",
            List.of(new AgentTraceability(
                "single-artifact-specialist-analysis",
                "JfrAgent",
                AgentNarrativeSource.SPECIALIST_AGENT,
                ArtifactType.JFR,
                List.of("samples/test.jfr"),
                List.of("evidence-1"),
                true,
                List.of(
                    new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.PASSED, "Narrative present."),
                    new AgentQualityGateResult("missing-data-awareness", AgentQualityGateStatus.WARNING, "Missing data was not mentioned.")
                ),
                List.of(),
                new ModelExecutionTraceability(
                    "OLLAMA",
                    "Ollama",
                    "llama3.2",
                    "llama3",
                    "JfrAgent.analyze",
                    "v1"
                )
            )),
            new SupervisorTrace(
                OrchestrationWorkflowType.SINGLE_ARTIFACT,
                List.of("samples/test.jfr"),
                List.of(
                    new SupervisorTraceStep(
                        "artifact-grounding",
                        SupervisorTraceStepType.ARTIFACT_GROUNDING,
                        null,
                        "Supervisor grounded the single artifact as JFR and carried 1 deterministic finding.",
                        ArtifactType.JFR,
                        List.of("samples/test.jfr"),
                        List.of("evidence-1"),
                        List.of("finding-1"),
                        null,
                        null,
                        false
                    ),
                    new SupervisorTraceStep(
                        "single-artifact-specialist-analysis",
                        SupervisorTraceStepType.SPECIALIST_SELECTION,
                        "single-artifact-specialist-analysis",
                        "Supervisor selected JfrAgent via SPECIALIST_AGENT for single-artifact analysis.",
                        ArtifactType.JFR,
                        List.of("samples/test.jfr"),
                        List.of("evidence-1"),
                        List.of("finding-1"),
                        "JfrAgent",
                        AgentNarrativeSource.SPECIALIST_AGENT,
                        true,
                        List.of(),
                        new ModelExecutionTraceability(
                            "OLLAMA",
                            "Ollama",
                            "llama3.2",
                            "llama3",
                            "JfrAgent.analyze",
                            "v1"
                        )
                    )
                )
            ),
            SeverityLevel.HIGH,
            ConfidenceLevel.MEDIUM,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String console = new ConsoleReportRenderer().render(report);
        String markdown = new MarkdownReportRenderer().render(report);
        String html = new HtmlReportRenderer().render(report);

        assertTrue(console.contains("Analysis Path: Specialist AI agent selected"));
        assertTrue(console.contains("AI Agent Involvement: yes, selected for the final narrative"));
        assertTrue(console.contains("Final Narrative Producer: JfrAgent"));
        assertTrue(console.contains("LLM Used For Final Narrative: yes"));
        assertTrue(console.contains("Final Narrative Provider: OLLAMA"));
        assertTrue(console.contains("Final Narrative Model: llama3.2 (family llama3)"));
        assertTrue(console.contains("Final Narrative Template: JfrAgent.analyze@v1"));
        assertTrue(console.contains("Agent Traceability:"));
        assertTrue(console.contains("JfrAgent"));
        assertTrue(console.contains("Model: OLLAMA / llama3.2 (family llama3)"));
        assertTrue(console.contains("Template: JfrAgent.analyze@v1"));
        assertTrue(console.contains("missing-data-awareness"));
        assertTrue(console.contains("Supervisor Trace:"));
        assertTrue(console.contains("artifact-grounding"));

        assertTrue(markdown.contains("## Analysis Path"));
        assertTrue(markdown.contains("Specialist AI agent selected"));
        assertTrue(markdown.contains("Final Narrative Provider: `OLLAMA`"));
        assertTrue(markdown.contains("Final Narrative Model: `llama3.2 (family llama3)`"));
        assertTrue(markdown.contains("Final Narrative Template: `JfrAgent.analyze@v1`"));
        assertTrue(markdown.contains("## Agent Traceability"));
        assertTrue(markdown.contains("JfrAgent"));
        assertTrue(markdown.contains("Model: `OLLAMA / llama3.2 (family llama3)`"));
        assertTrue(markdown.contains("Template: `JfrAgent.analyze@v1`"));
        assertTrue(markdown.contains("missing-data-awareness"));
        assertTrue(markdown.contains("## Supervisor Trace"));
        assertTrue(markdown.contains("artifact-grounding"));

        assertTrue(html.contains("<h2>Analysis Path</h2>"));
        assertTrue(html.contains("AI Agent Involvement"));
        assertTrue(html.contains("Final Narrative Provider:</strong> OLLAMA"));
        assertTrue(html.contains("Final Narrative Model:</strong> llama3.2 (family llama3)"));
        assertTrue(html.contains("Final Narrative Template:</strong> JfrAgent.analyze@v1"));
        assertTrue(html.contains("<h2>Agent Traceability</h2>"));
        assertTrue(html.contains("JfrAgent"));
        assertTrue(html.contains("<strong>Model:</strong> OLLAMA / llama3.2 (family llama3)"));
        assertTrue(html.contains("<strong>Template:</strong> JfrAgent.analyze@v1"));
        assertTrue(html.contains("missing-data-awareness"));
        assertTrue(html.contains("<h2>Supervisor Trace</h2>"));
        assertTrue(html.contains("artifact-grounding"));

        @SuppressWarnings("unchecked")
        Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentParticipationSummary = (Map<String, Object>) reportMetadata.get("agentParticipationSummary");
        assertEquals("OLLAMA", agentParticipationSummary.get("selectedNarrativeProvider"));
        assertEquals("llama3.2", agentParticipationSummary.get("selectedNarrativeModel"));
        assertEquals("JfrAgent.analyze", agentParticipationSummary.get("selectedNarrativeTemplateId"));
        assertEquals("v1", agentParticipationSummary.get("selectedNarrativeTemplateVersion"));
    }

    @Test
    void distinguishesAgentAttemptFromSelectedNarrativePath() {
        AnalysisReport report = new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-2",
            LocalDateTime.of(2026, 3, 31, 10, 15),
            "Synthetic fallback report",
            "Fallback summarizer narrative",
            List.of(
                new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "JfrAgent",
                    AgentNarrativeSource.SPECIALIST_AGENT,
                    ArtifactType.JFR,
                    List.of("samples/test.jfr"),
                    List.of("evidence-1"),
                    false,
                    List.of(
                        new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.FAILED, "Narrative missing evidence.")
                    )
                ),
                new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "StructuredReportSummarizer",
                    AgentNarrativeSource.FALLBACK_SUMMARIZER,
                    ArtifactType.JFR,
                    List.of("samples/test.jfr"),
                    List.of("evidence-1"),
                    true,
                    List.of(
                        new AgentQualityGateResult("response-not-empty", AgentQualityGateStatus.PASSED, "Narrative present.")
                    )
                )
            ),
            null,
            SeverityLevel.MEDIUM,
            ConfidenceLevel.MEDIUM,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentParticipationSummary = (Map<String, Object>) reportMetadata.get("agentParticipationSummary");

        assertEquals(Boolean.TRUE, agentParticipationSummary.get("aiAgentAttempted"));
        assertEquals(1L, agentParticipationSummary.get("aiAgentAttemptCount"));
        assertEquals(Boolean.FALSE, agentParticipationSummary.get("aiAgentSelectedForUserNarrative"));
        assertEquals(Boolean.TRUE, agentParticipationSummary.get("llmNarrativeSelectedForUserNarrative"));
        assertEquals("StructuredReportSummarizer", agentParticipationSummary.get("selectedNarrativeAgent"));
        assertEquals("FALLBACK_SUMMARIZER", agentParticipationSummary.get("selectedNarrativeSource"));

        String console = new ConsoleReportRenderer().render(report);

        assertTrue(console.contains("Analysis Path: Fallback LLM summarizer selected"));
        assertTrue(console.contains("AI Agent Involvement: attempted, but not selected for the final narrative"));
        assertTrue(console.contains("Final Narrative Producer: StructuredReportSummarizer"));
        assertTrue(console.contains("Final Narrative Source: FALLBACK_SUMMARIZER"));
    }
}
