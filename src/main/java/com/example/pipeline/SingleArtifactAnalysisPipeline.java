package com.example.pipeline;

import com.example.ai.StructuredReportSummarizer;
import com.example.model.AnalysisReport;
import com.example.model.InputArtifact;
import com.example.parse.ArtifactParsingService;
import com.example.render.ConsoleReportRenderer;
import com.example.report.AnalysisReportAssembler;
import com.example.rules.ArtifactRuleEngineService;
import dev.langchain4j.model.chat.ChatModel;

/**
 * End-to-end structured analysis pipeline for one supported artifact.
 */
public class SingleArtifactAnalysisPipeline {

    private final ArtifactParsingService parsingService;
    private final ArtifactRuleEngineService ruleEngineService;
    private final AnalysisReportAssembler reportAssembler;
    private final StructuredReportSummarizer summarizer;
    private final ConsoleReportRenderer renderer;

    public SingleArtifactAnalysisPipeline(
        ArtifactParsingService parsingService,
        ArtifactRuleEngineService ruleEngineService,
        AnalysisReportAssembler reportAssembler,
        StructuredReportSummarizer summarizer,
        ConsoleReportRenderer renderer
    ) {
        this.parsingService = parsingService;
        this.ruleEngineService = ruleEngineService;
        this.reportAssembler = reportAssembler;
        this.summarizer = summarizer;
        this.renderer = renderer;
    }

    public AnalysisReport analyze(InputArtifact artifact, ChatModel chatModel) {
        var parsedArtifact = parsingService.parse(artifact);
        var evaluation = ruleEngineService.evaluate(parsedArtifact);
        var baseReport = reportAssembler.assemble(artifact, parsedArtifact, evaluation);
        String operatorNarrative = null;
        try {
            operatorNarrative = summarizer.summarize(baseReport, chatModel);
        } catch (RuntimeException ignored) {
            operatorNarrative = null;
        }
        return reportAssembler.assemble(artifact, parsedArtifact, evaluation, operatorNarrative);
    }

    public String render(AnalysisReport report) {
        return renderer.render(report);
    }
}
