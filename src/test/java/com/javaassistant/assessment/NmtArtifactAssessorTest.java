package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.parse.NmtArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NmtArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtArtifactAssessor engine = new NmtArtifactAssessor();

    @Test
    void emitsMetaspacePressureFindingForSample() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_summary_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("nmt-metaspace-pressure") && finding.evidenceIds().contains("nmt-metaspace-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("nmt-gc-native-pressure")));
        assertFalse(evaluation.recommendedActions().isEmpty());
    }

    @Test
    void parsesDiffSampleWellEnoughToEmitPressureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/java_nmt_diff_3391237.txt")));
        var evaluation = engine.evaluate(parsed);

        assertEquals("diff", parsed.extractedData().get("snapshotKind"));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("nmt-metaspace-pressure")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("nmt-class-metadata-growth") && finding.evidenceIds().contains("nmt-class-summary-delta")
        ));
    }

    @Test
    void emitsNativeGrowthFindingForPositiveDiffSnapshot() {
        var parsed = parser.parse(new InputArtifact(
            ArtifactType.NMT,
            new ArtifactMetadata("synthetic-diff.nmt", "synthetic-diff.nmt", 0L),
            """
            111:

            Native Memory Tracking:

            Total: reserved=180000KB +24000KB, committed=90000KB +32768KB

            -                     Class (reserved=28000KB +2000KB, committed=20000KB +2048KB)
                                    (classes #1500 +100)
                                    (  Metadata:   )
                                    (    reserved=30000KB, committed=18000KB +1024KB)
                                    (    used=16000KB +512KB)

            -                    Thread (reserved=12000KB +1024KB, committed=1500KB +128KB)
                                    (thread #40 +8)
                                    (stack: reserved=11000KB +1024KB, committed=1200KB +96KB)

            -                      Code (reserved=17000KB, committed=2300KB +128KB)
            -                        GC (reserved=19000KB, committed=5000KB +256KB)
            """
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("nmt-native-allocation-growth")));
    }

    @Test
    void emitsCodeCachePressureFindingForLargeCodeCategory() {
        var parsed = parser.parse(new InputArtifact(
            ArtifactType.NMT,
            new ArtifactMetadata("synthetic-code.nmt", "synthetic-code.nmt", 0L),
            """
            222:

            Native Memory Tracking:

            Total: reserved=400000KB, committed=95000KB

            -                     Class (reserved=32000KB, committed=18000KB)
                                    (classes #2500)
                                    (  Metadata:   )
                                    (    reserved=24000KB, committed=16000KB)
                                    (    used=14000KB)

            -                    Thread (reserved=12000KB, committed=1500KB)
                                    (thread #45)
                                    (stack: reserved=11000KB, committed=1200KB)

            -                      Code (reserved=64000KB, committed=32768KB)
            -                        GC (reserved=19000KB, committed=6000KB)
            """
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("nmt-code-cache-pressure") && finding.evidenceIds().contains("nmt-category-code")
        ));
    }
}
