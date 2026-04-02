package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.parse.PmapArtifactParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PmapComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final PmapArtifactParser parser = new PmapArtifactParser();
    private final PmapComparator comparator = new PmapComparator();

    @Test
    void emitsPmapGrowthFindingFromHeaderlessVirtualSizeWhenResidentMetricsAreUnavailable() {
        var baseline = artifact("111: java\n0000000000000000 1000K rw---   [ anon ]\n");
        var current = artifact("111: java\n0000000000000000 8000K rw---   [ anon ]\n");
        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-growth")));
    }

    @Test
    void emitsPmapGrowthFindingWhenResidentFootprintGrows() {
        var baseline = artifact(headeredPmap(
            List.of(
                "0000000000000000    4000    1000    1000 rw---   [ anon ]",
                "0000000000010000    6000     500       0 r-x-- libjvm.so"
            ),
            10_000L,
            1_500L,
            1_000L
        ));
        var current = artifact(headeredPmap(
            List.of(
                "0000000000000000   12000    9000    9000 rw---   [ anon ]",
                "0000000000010000    6000     500       0 r-x-- libjvm.so"
            ),
            18_000L,
            9_500L,
            9_000L
        ));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-growth")));
    }

    @Test
    void distinguishesReservedExpansionFromResidentGrowthWhenRssIsFlat() {
        var baseline = artifact(headeredPmap(
            List.of(
                "0000000000000000    4000    1000    1000 rw---   [ anon ]",
                "0000000000010000    6000     500       0 r-x-- libjvm.so"
            ),
            10_000L,
            1_500L,
            1_000L
        ));
        var current = artifact(headeredPmap(
            List.of(
                "0000000000000000    4000    1000    1000 rw---   [ anon ]",
                "0000000000010000    6000     500       0 r-x-- libjvm.so",
                "0000000000100000  120000       0       0 -----   [ anon ]"
            ),
            130_000L,
            1_500L,
            1_000L
        ));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-growth")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-reserved-expansion")));
    }

    @Test
    void ignoresReshapedMappingsWhenTotalFootprintIsFlat() throws Exception {
        var baseline = loader.load(Path.of("samples/pmap.1"));
        var current = loader.load(Path.of("samples/pmap.2"));
        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-pmap-growth")));
    }

    private InputArtifact artifact(String content) {
        return new InputArtifact(
            ArtifactType.PMAP,
            new ArtifactMetadata("synthetic.pmap", "synthetic.pmap", content.length()),
            content
        );
    }

    private String headeredPmap(List<String> mappings, long totalSizeKb, long totalRssKb, long totalDirtyKb) {
        return "111: java\n"
            + "Address           Kbytes     RSS   Dirty Mode  Mapping\n"
            + String.join("\n", mappings)
            + "\n---------------- ------- ------- -------\n"
            + String.format("total kB         %d  %d   %d%n", totalSizeKb, totalRssKb, totalDirtyKb);
    }
}
