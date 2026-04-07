package com.javaassistant.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.SupervisorTrace;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.report.AnalysisReportAssembler;
import com.javaassistant.assessment.NmtArtifactAssessor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredReportQuestionAnswererTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactAssessor assessor = new NmtArtifactAssessor();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();
    private final StructuredReportQuestionAnswerer answerer = new StructuredReportQuestionAnswerer();

    @Test
    void usesStructuredReportToAnswerQuestions() throws Exception {
        var report = agentBackedReport();
        var chatModel = new StubChatModel("Structured answer");

        String answer = answerer.answer(report, "What is the main issue?", chatModel);

        assertEquals("Structured answer", answer);
        assertTrue(chatModel.lastPrompt().contains(report.incidentSummary()));
        assertTrue(chatModel.lastPrompt().contains(report.userNarrative()));
        assertTrue(chatModel.lastPrompt().contains("Supervisor Trace"));
        assertTrue(chatModel.lastPrompt().contains("single-artifact-specialist-analysis"));
        assertTrue(chatModel.lastPrompt().contains("Question: What is the main issue?"));
    }

    @Test
    void refusesToAnswerWhenNoChatModelIsConfigured() throws Exception {
        var report = agentBackedReport();

        String answer = answerer.answer(report, "What are the recommended next steps?", null);

        assertEquals(StructuredReportQuestionAnswerer.CHAT_MODEL_UNAVAILABLE_MESSAGE, answer);
    }

    @Test
    void refusesReportsWithoutAgentBackedNarrative() throws Exception {
        var report = sampleReport();

        String answer = answerer.answer(report, "What is the main issue?", new StubChatModel("unused"));

        assertEquals(StructuredReportQuestionAnswerer.REPORT_REQUIRES_AGENT_ANALYSIS_MESSAGE, answer);
    }

    private com.javaassistant.diagnostics.AnalysisReport sampleReport() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = assessor.evaluate(parsedArtifact);
        return assembler.assemble(inputArtifact, parsedArtifact, evaluation);
    }

    private com.javaassistant.diagnostics.AnalysisReport agentBackedReport() throws Exception {
        return sampleReport()
            .withUserNarrativeAndTraceability(
                "AI agent narrative",
                List.of(new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "NMTAgent",
                    AgentNarrativeSource.SPECIALIST_AGENT,
                    ArtifactType.NMT,
                    List.of("samples/single_process_data/java_nmt_summary_3391237.txt"),
                    List.of("nmt-total"),
                    true,
                    List.of()
                ))
            )
            .withSupervisorTrace(new SupervisorTrace(
                OrchestrationWorkflowType.SINGLE_ARTIFACT,
                List.of("samples/single_process_data/java_nmt_summary_3391237.txt"),
                List.of(new SupervisorTraceStep(
                    "single-artifact-specialist-analysis",
                    SupervisorTraceStepType.SPECIALIST_SELECTION,
                    "single-artifact-specialist-analysis",
                    "Supervisor selected NMTAgent via SPECIALIST_AGENT for single-artifact analysis.",
                    ArtifactType.NMT,
                    List.of("samples/single_process_data/java_nmt_summary_3391237.txt"),
                    List.of("nmt-total"),
                    List.of("nmt-gc-native-pressure"),
                    "NMTAgent",
                    AgentNarrativeSource.SPECIALIST_AGENT,
                    true
                ))
            ));
    }

    private static class StubChatModel implements ChatModel {
        private final String responseText;
        private String lastPrompt;

        private StubChatModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            lastPrompt = chatRequest.messages().stream().map(Object::toString).reduce("", (left, right) -> left + "\n" + right);
            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(responseText))
                .build();
        }

        String lastPrompt() {
            return lastPrompt;
        }
    }
}
