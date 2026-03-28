package com.example.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.NmtArtifactParser;
import com.example.report.AnalysisReportAssembler;
import com.example.rules.NmtArtifactRuleEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StructuredReportSummarizerTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactRuleEngine ruleEngine = new NmtArtifactRuleEngine();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();
    private final StructuredReportSummarizer summarizer = new StructuredReportSummarizer();

    @Test
    void returnsNullWhenNoChatModelIsProvided() throws Exception {
        var report = sampleReport();
        assertNull(summarizer.summarize(report, null));
    }

    @Test
    void usesStructuredReportToAskForNarrative() throws Exception {
        var report = sampleReport();
        var chatModel = new StubChatModel("Synthetic structured narrative");

        String summary = summarizer.summarize(report, chatModel);

        assertEquals("Synthetic structured narrative", summary);
        assertTrue(chatModel.lastPrompt().contains(report.incidentSummary()));
        assertTrue(chatModel.lastPrompt().contains("Recommended Actions"));
    }

    private com.example.model.AnalysisReport sampleReport() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = ruleEngine.evaluate(parsedArtifact);
        return assembler.assemble(inputArtifact, parsedArtifact, evaluation);
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
