package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.HsErrArtifactParser;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HsErrArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final HsErrArtifactParser parser = new HsErrArtifactParser();
    private final HsErrArtifactAssessor engine = new HsErrArtifactAssessor();

    @TempDir
    Path tempDir;

    @Test
    void emitsFatalCrashFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-g1-fullgc-crash")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-g1-fullgc-crash")));
    }

    @Test
    void emitsNativeAllocationFailureFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-native-allocation-failure") && finding.evidenceIds().contains("hs-err-native-allocation-failure")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-compiler-thread-native-oom")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-native-allocation-failure")));
    }

    @Test
    void emitsNativeThreadExhaustionFindings() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createNativeThreadExhaustionBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-native-thread-exhaustion") && finding.evidenceIds().contains("hs-err-native-thread-exhaustion")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-native-thread-exhaustion")));
    }

    @Test
    void emitsCodeCacheExhaustionFindings() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCodeCacheFullBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-code-cache-full") && finding.evidenceIds().contains("hs-err-code-cache-status")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-hs-err-code-cache-full")));
    }

    @Test
    void emitsCompressedClassSpaceExhaustionFindings() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-compressed-class-space-oom") && finding.evidenceIds().contains("hs-err-compressed-class-space")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-hs-err-compressed-class-space-oom")
        ));
    }

    @Test
    void emitsGeneratedDirectBufferNativeOomFindings() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createDirectBufferNativeOomBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("hs-err-native-allocation-failure") && finding.evidenceIds().contains("hs-err-native-allocation-failure")
        ));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action ->
            action.id().equals("action-hs-err-native-allocation-failure")
        ));
    }

    @Test
    void emitsFatalSignalFindingForTruncatedHsErrLog() {
        var parsed = parser.parse(syntheticArtifact(
            "samples/hs_err_truncated.log",
            """
                #
                # A fatal error has been detected by the Java Runtime Environment:
                #
                #  SIGSEGV (0xb) at pc=0x000000010a9b02, pid=4242, tid=0x0000000000005703
                #
                # JRE version: OpenJDK Runtime Environment Temurin-25+36 (25+36)
                # Java VM: OpenJDK 64-Bit Server VM Temurin-25+36
                Time: Thu Apr  9 13:14:23 2026 UTC elapsed time: 18.250 seconds
                Current thread (0x0000000102800000):  JavaThread "main" [_thread_in_native, id=5703, stack(0x000000016f9d3000,0x000000016fad3000)]
                # Problematic frame:
                #  [truncated before frame symbol line
                """
        ));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("hs-err-fatal-signal")));
        assertTrue(evaluation.findings().stream().noneMatch(finding -> finding.id().equals("hs-err-g1-fullgc-crash")));
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.HS_ERR_LOG,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
