package com.example.ai;

import com.example.model.AnalysisReport;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.stream.Collectors;

/**
 * Uses the LLM only as a bounded explainer over structured report data.
 */
public class StructuredReportSummarizer {

    public String summarize(AnalysisReport report, ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }

        String systemPrompt = """
            You are a JVM incident triage explainer.

            You will receive a structured analysis report derived from deterministic parsers and rule engines.
            Your job is to write a concise operator-friendly narrative.

            Rules:
            1. Use only the facts present in the report.
            2. Do not invent metrics, files, causes, or recommendations.
            3. Prefer the report findings and evidence over speculation.
            4. If confidence is limited or missing-data notes exist, say so clearly.
            5. Keep the response concise and practical.
            """;

        String userPrompt = """
            Write a short narrative summary for this structured JVM incident report.

            %s
            """.formatted(toPrompt(report));

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

        return """
            Analysis ID: %s
            Incident Summary: %s
            Overall Severity: %s
            Confidence: %s

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
            findings.isBlank() ? "- none" : findings,
            actions.isBlank() ? "- none" : actions,
            missing
        );
    }
}
