package com.javaassistant.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisReportTest {

    @Test
    void detectsWhetherTheUserNarrativeIsAiAgentBacked() {
        assertTrue(reportWithSelectedAgentNarrative().hasAiAgentBackedUserNarrative());
        assertFalse(reportWithFallbackNarrative().hasAiAgentBackedUserNarrative());
        assertFalse(reportWithoutNarrative().hasAiAgentBackedUserNarrative());
    }

    @Test
    void projectsSelectedNarrativeModelExecutionIntoReportMetadata() {
        AnalysisReport report = reportWithSelectedAgentNarrative();

        @SuppressWarnings("unchecked")
        Map<String, Object> reportMetadata = (Map<String, Object>) report.toCanonicalMap().get("reportMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentParticipationSummary = (Map<String, Object>) reportMetadata.get("agentParticipationSummary");

        assertEquals("TEST", agentParticipationSummary.get("selectedNarrativeProvider"));
        assertEquals("RoutingStubChatModel", agentParticipationSummary.get("selectedNarrativeModel"));
        assertEquals("GCLogAgent.analyze", agentParticipationSummary.get("selectedNarrativeTemplateId"));
        assertEquals("v1", agentParticipationSummary.get("selectedNarrativeTemplateVersion"));
    }

    private AnalysisReport reportWithSelectedAgentNarrative() {
        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-ai",
            LocalDateTime.of(2026, 3, 31, 17, 0),
            "Agent-backed summary",
            "AI agent analysis",
            List.of(new AgentTraceability(
                "single-artifact-specialist-analysis",
                "GCLogAgent",
                AgentNarrativeSource.SPECIALIST_AGENT,
                null,
                List.of(),
                List.of(),
                true,
                List.of(),
                List.of(),
                new ModelExecutionTraceability(
                    "TEST",
                    "Test Chat Model",
                    "RoutingStubChatModel",
                    "RoutingStubChatModel",
                    "GCLogAgent.analyze",
                    "v1"
                )
            )),
            null,
            SeverityLevel.HIGH,
            ConfidenceLevel.HIGH,
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
    }

    private AnalysisReport reportWithFallbackNarrative() {
        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-fallback",
            LocalDateTime.of(2026, 3, 31, 17, 5),
            "Fallback summary",
            "Deterministic fallback analysis",
            List.of(new AgentTraceability(
                "single-artifact-specialist-analysis",
                "DeterministicAssessmentFallback",
                AgentNarrativeSource.DETERMINISTIC_FALLBACK,
                null,
                List.of(),
                List.of(),
                true,
                List.of()
            )),
            null,
            SeverityLevel.HIGH,
            ConfidenceLevel.HIGH,
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
    }

    private AnalysisReport reportWithoutNarrative() {
        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-none",
            LocalDateTime.of(2026, 3, 31, 17, 10),
            "No narrative",
            null,
            List.of(),
            null,
            SeverityLevel.LOW,
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
    }
}
