package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GcLogArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser parser = new GcLogArtifactParser();
    private final GcLogArtifactAssessor engine = new GcLogArtifactAssessor();

    @Test
    void emitsFullGcAndHeapPressureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-repeated-full-gcs")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-heap-saturation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-repeated-full-gcs")));
    }

    @Test
    void emitsAllocationStallPressureFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/zgc_21_allocation_stall.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-allocation-stall-pressure") && finding.evidenceIds().contains("gc-allocation-stall-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-allocation-stall-pressure")));
    }

    @Test
    void emitsMetaspaceFullGcFinding() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/single_process_data/gclog_metaspace.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-metaspace-full-gcs") && finding.evidenceIds().contains("gc-metaspace-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-metaspace-full-gcs")));
    }

    @Test
    void emitsLegacyParallelFullGcPressureFindings() throws Exception {
        var parsed = parser.parse(loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-first-pass-parallel-full-gc/gc_parallel_full_gc_small.log")
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-repeated-full-gcs")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-heap-saturation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-repeated-full-gcs")));
    }

    @Test
    void emitsG1EvacuationFailureFindingForGeneratedBundle() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createG1EvacuationFailureBundle(Files.createTempDirectory("g1-evacuation-failure"));
        var parsed = parser.parse(loader.load(bundle.get("gc")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-g1-evacuation-failure-distress")
                && finding.evidenceIds().contains("gc-full-gc-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-gc-g1-evacuation-failure-distress")
        ));
    }

    @Test
    void emitsG1HumongousPressureFindingForGeneratedBundle() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createG1HumongousAllocationPressureBundle(Files.createTempDirectory("g1-humongous-pressure"));
        var parsed = parser.parse(loader.load(bundle.get("gc")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-g1-humongous-pressure")
                && finding.evidenceIds().contains("gc-humongous-summary")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("gc-heap-saturation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-gc-g1-humongous-pressure")
        ));
    }

    @Test
    void emitsCmsPromotionFailureFinding() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/cms_legacy_promotion_failure.log",
            """
                2026-04-03T10:00:00.000+0000: 1.234: [GC (CMS Initial Mark) [1 CMS-initial-mark: 350000K(524288K)] 360000K(713280K), 0.0123456 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]
                2026-04-03T10:00:01.000+0000: 2.234: [Full GC (promotion failed) [CMS: 500000K->498000K(524288K)] 530000K->500000K(713280K), 0.2500000 secs] [Times: user=0.30 sys=0.00, real=0.25 secs]
                2026-04-03T10:00:02.000+0000: 3.234: [Full GC (promotion failed) [CMS: 501000K->499000K(524288K)] 531000K->501000K(713280K), 0.2600000 secs] [Times: user=0.30 sys=0.00, real=0.26 secs]
                """
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("gc-cms-promotion-failure") && finding.evidenceIds().contains("gc-full-gc-summary")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-gc-cms-promotion-failure")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
