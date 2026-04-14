package com.javaassistant.compare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NmtComparatorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final NmtArtifactParser parser = new NmtArtifactParser();
    private final NmtComparator comparator = new NmtComparator();

    @TempDir
    Path tempDir;

    @Test
    void emitsNativeAndMetaspaceGrowthFindings() {
        var baseline = artifact("""
            111:

            Native Memory Tracking:

            Total: reserved=120000KB, committed=40000KB

            -                     Class (reserved=22000KB, committed=12000KB)
                                    (classes #1000)
                                    (  Metadata:   )
                                    (    reserved=24000KB, committed=12000KB)
                                    (    used=9000KB)

            -                    Thread (reserved=8000KB, committed=1000KB)
                                    (thread #12)
                                    (stack: reserved=7800KB, committed=800KB)

            -                      Code (reserved=16000KB, committed=2000KB)
            -                        GC (reserved=18000KB, committed=4000KB)
            """);
        var current = artifact("""
            111:

            Native Memory Tracking:

            Total: reserved=170000KB, committed=70000KB

            -                     Class (reserved=38000KB, committed=26000KB)
                                    (classes #1800)
                                    (  Metadata:   )
                                    (    reserved=40000KB, committed=26000KB)
                                    (    used=22000KB)

            -                    Thread (reserved=9000KB, committed=1200KB)
                                    (thread #14)
                                    (stack: reserved=8600KB, committed=1000KB)

            -                      Code (reserved=17000KB, committed=2400KB)
            -                        GC (reserved=19000KB, committed=4500KB)
            """);

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-native-growth")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-metaspace-growth")));
    }

    @Test
    void ignoresSmallNmtChanges() {
        var baseline = artifact("""
            111:

            Native Memory Tracking:

            Total: reserved=120000KB, committed=40000KB

            -                     Class (reserved=22000KB, committed=12000KB)
                                    (classes #1000)
                                    (  Metadata:   )
                                    (    reserved=24000KB, committed=12000KB)
                                    (    used=9000KB)

            -                    Thread (reserved=8000KB, committed=1000KB)
                                    (thread #12)
                                    (stack: reserved=7800KB, committed=800KB)

            -                      Code (reserved=16000KB, committed=2000KB)
            -                        GC (reserved=18000KB, committed=4000KB)
            """);
        var current = artifact("""
            111:

            Native Memory Tracking:

            Total: reserved=123000KB, committed=43000KB

            -                     Class (reserved=23000KB, committed=13000KB)
                                    (classes #1025)
                                    (  Metadata:   )
                                    (    reserved=25000KB, committed=13000KB)
                                    (    used=9800KB)

            -                    Thread (reserved=8200KB, committed=1040KB)
                                    (thread #14)
                                    (stack: reserved=8000KB, committed=820KB)

            -                      Code (reserved=16000KB, committed=2100KB)
            -                        GC (reserved=18000KB, committed=4200KB)
            """);

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().startsWith("compare-nmt-")));
    }

    @Test
    void emitsInternalArenaGrowthFindingForGeneratedSnapshots() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createInternalArenaGrowthBundle(tempDir);
        var baseline = loader.load(bundle.get("baseline"));
        var current = loader.load(bundle.get("current"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-internal-arena-growth")));
    }

    @Test
    void emitsReservedExpansionFindingForGeneratedSnapshots() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createReservedCommittedMismatchBundle(tempDir);
        var baseline = loader.load(bundle.get("nmt-baseline"));
        var current = loader.load(bundle.get("nmt-current"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-reserved-expansion")));
    }

    @Test
    void emitsNativeGrowthFindingForGeneratedActiveNativeSnapshots() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createActiveNativeGrowthBundle(tempDir);
        var baseline = loader.load(bundle.get("nmt-baseline"));
        var current = loader.load(bundle.get("nmt-current"));

        var evaluation = comparator.compare(baseline, parser.parse(baseline), current, parser.parse(current));

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-native-growth")));
        assertFalse(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("compare-nmt-reserved-expansion")));
    }

    private InputArtifact artifact(String content) {
        return new InputArtifact(
            ArtifactType.NMT,
            new ArtifactMetadata("synthetic.nmt", "synthetic.nmt", content.length()),
            content
        );
    }
}
