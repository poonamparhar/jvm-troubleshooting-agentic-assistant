package com.example.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.ArtifactParsingService;
import com.example.parse.GcLogArtifactParser;
import com.example.parse.HeapHistogramArtifactParser;
import com.example.parse.HsErrArtifactParser;
import com.example.parse.NmtArtifactParser;
import com.example.parse.PmapArtifactParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactRuleEngineServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final ArtifactParsingService parsingService = new ArtifactParsingService(List.of(
        new GcLogArtifactParser(),
        new HsErrArtifactParser(),
        new NmtArtifactParser(),
        new HeapHistogramArtifactParser(),
        new PmapArtifactParser()
    ));
    private final ArtifactRuleEngineService ruleEngineService = new ArtifactRuleEngineService(List.of(
        new GcLogArtifactRuleEngine(),
        new HsErrArtifactRuleEngine(),
        new NmtArtifactRuleEngine(),
        new HeapHistogramArtifactRuleEngine(),
        new PmapArtifactRuleEngine()
    ));

    @Test
    void evaluatesParsedArtifactThroughRegistry() throws Exception {
        var parsed = parsingService.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var evaluation = ruleEngineService.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
    }
}
