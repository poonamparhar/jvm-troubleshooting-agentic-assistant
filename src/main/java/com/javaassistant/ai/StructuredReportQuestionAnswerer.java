package com.javaassistant.ai;

import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.SupervisorTrace;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.stream.Collectors;

/**
 * Answers user questions using the canonical structured report as the only source of truth.
 */
public class StructuredReportQuestionAnswerer {

    public static final String REPORT_REQUIRES_AGENT_ANALYSIS_MESSAGE =
        "AI agent-backed troubleshooting analysis is unavailable for this report.";
    public static final String CHAT_MODEL_UNAVAILABLE_MESSAGE =
        "AI follow-up assistance is unavailable right now. No answer was generated.";

    public String answer(AnalysisReport report, String question, ChatModel chatModel) {
        if (report == null || !report.hasAiAgentBackedUserNarrative()) {
            return REPORT_REQUIRES_AGENT_ANALYSIS_MESSAGE;
        }

        if (chatModel == null) {
            return CHAT_MODEL_UNAVAILABLE_MESSAGE;
        }

        String systemPrompt = """
            You are a JVM incident triage assistant.

            You will receive a structured analysis report produced from deterministic grounding plus an AI-agent troubleshooting narrative.
            Answer the user's question using only facts from that report.

            Rules:
            1. Do not invent metrics, causes, findings, or recommendations.
            2. If the report does not support the answer, say that directly.
            3. Prefer the AI agent analysis, findings, recommended actions, and missing-data notes over speculation.
            4. Keep the response concise and practical.
            """;

        String userPrompt = """
            Answer this question about the structured JVM incident report.

            Question: %s

            %s
            """.formatted(question, toPrompt(report));

        return chatModel.chat(
            SystemMessage.systemMessage(systemPrompt),
            UserMessage.userMessage(userPrompt)
        ).aiMessage().text();
    }

    private String toPrompt(AnalysisReport report) {
        String findings = report.findings().stream()
            .map(finding -> "- %s | %s | %s".formatted(finding.severity(), finding.title(), finding.summary()))
            .collect(Collectors.joining("\n"));
        String actions = report.recommendedActions().stream()
            .map(action -> "- %s | %s".formatted(action.priority(), action.summary()))
            .collect(Collectors.joining("\n"));
        String missing = report.missingData().isEmpty()
            ? "- none"
            : report.missingData().stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
        String userNarrative = report.userNarrative() == null || report.userNarrative().isBlank()
            ? "- none"
            : report.userNarrative();
        String traceability = report.agentTraceability().isEmpty()
            ? "- none"
            : report.agentTraceability().stream()
                .map(this::formatTraceability)
                .collect(Collectors.joining("\n"));
        String supervisorTrace = report.supervisorTrace() == null || report.supervisorTrace().steps().isEmpty()
            ? "- none"
            : formatSupervisorTrace(report.supervisorTrace());

        return """
            Analysis ID: %s
            Incident Summary: %s
            Overall Severity: %s
            Confidence: %s

            AI Agent Analysis:
            %s

            Agent Traceability:
            %s

            Supervisor Trace:
            %s

            Findings:
            %s

            Recommended Actions:
            %s

            Missing Data:
            %s
            """.formatted(
            report.analysisId(),
            report.incidentSummary(),
            report.overallSeverity(),
            report.confidence(),
            userNarrative,
            traceability,
            supervisorTrace,
            findings.isBlank() ? "- none" : findings,
            actions.isBlank() ? "- none" : actions,
            missing
        );
    }

    private String formatTraceability(AgentTraceability traceability) {
        String modelExecution = traceability.modelExecutionTraceability() == null
            ? ""
            : " | %s/%s | template=%s@%s".formatted(
                traceability.modelExecutionTraceability().providerId(),
                traceability.modelExecutionTraceability().modelName(),
                traceability.modelExecutionTraceability().templateId(),
                traceability.modelExecutionTraceability().templateVersion()
            );
        return "- %s | %s | %s | selected=%s%s".formatted(
            traceability.agentName(),
            traceability.stageId(),
            traceability.narrativeSource(),
            traceability.selectedForUserNarrative(),
            modelExecution
        );
    }

    private String formatSupervisorTrace(SupervisorTrace supervisorTrace) {
        return supervisorTrace.steps().stream()
            .map(step -> "- %s | %s | %s".formatted(
                step.stepId(),
                step.stepType(),
                step.agentName() != null && !step.agentName().isBlank() ? step.agentName() : step.decision()
            ))
            .collect(Collectors.joining("\n", "- workflow=" + supervisorTrace.workflowType() + "\n", ""));
    }
}
