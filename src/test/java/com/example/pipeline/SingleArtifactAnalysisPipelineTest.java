package com.example.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ai.StructuredReportSummarizer;
import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.ArtifactParsingService;
import com.example.parse.GcLogArtifactParser;
import com.example.parse.HeapHistogramArtifactParser;
import com.example.parse.HsErrArtifactParser;
import com.example.parse.NmtArtifactParser;
import com.example.parse.PmapArtifactParser;
import com.example.render.ConsoleReportRenderer;
import com.example.report.AnalysisReportAssembler;
import com.example.rules.ArtifactRuleEngineService;
import com.example.rules.GcLogArtifactRuleEngine;
import com.example.rules.HeapHistogramArtifactRuleEngine;
import com.example.rules.HsErrArtifactRuleEngine;
import com.example.rules.NmtArtifactRuleEngine;
import com.example.rules.PmapArtifactRuleEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingleArtifactAnalysisPipelineTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final SingleArtifactAnalysisPipeline pipeline = new SingleArtifactAnalysisPipeline(
        new ArtifactParsingService(List.of(
            new GcLogArtifactParser(),
            new HsErrArtifactParser(),
            new NmtArtifactParser(),
            new HeapHistogramArtifactParser(),
            new PmapArtifactParser()
        )),
        new ArtifactRuleEngineService(List.of(
            new GcLogArtifactRuleEngine(),
            new HsErrArtifactRuleEngine(),
            new NmtArtifactRuleEngine(),
            new HeapHistogramArtifactRuleEngine(),
            new PmapArtifactRuleEngine()
        )),
        new AnalysisReportAssembler(),
        new StructuredReportSummarizer(),
        new ConsoleReportRenderer()
    );

    @Test
    void analyzesArtifactWithoutLlmNarrative() throws Exception {
        var report = pipeline.analyze(loader.load(Path.of("samples/hs_err_pid69848.log")), null);
        String rendered = pipeline.render(report);

        assertTrue(report.operatorNarrative() == null || report.operatorNarrative().isBlank());
        assertTrue(rendered.contains("Fatal JVM crash recorded"));
    }

    @Test
    void includesNarrativeWhenChatModelIsAvailable() throws Exception {
        var report = pipeline.analyze(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")), new StubChatModel());

        assertFalse(report.operatorNarrative().isBlank());
        assertTrue(report.operatorNarrative().contains("Bounded narrative"));
    }

    private static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("Bounded narrative based on structured report"))
                .build();
        }
    }
}
