package com.example.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.NmtArtifactParser;
import com.example.rules.NmtArtifactRuleEngine;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalysisReportAssemblerTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactRuleEngine ruleEngine = new NmtArtifactRuleEngine();
    private final AnalysisReportAssembler assembler = new AnalysisReportAssembler();

    @Test
    void assemblesCanonicalReportFromParsedArtifactAndRules() throws Exception {
        var inputArtifact = loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt"));
        var parsedArtifact = parser.parse(inputArtifact);
        var evaluation = ruleEngine.evaluate(parsedArtifact);
        var report = assembler.assemble(inputArtifact, parsedArtifact, evaluation);

        assertNotNull(report.analysisId());
        assertFalse(report.findings().isEmpty());
        assertTrue(report.incidentSummary().contains("analysis found"));
        assertFalse(report.followUpCommands().isEmpty());
    }
}
