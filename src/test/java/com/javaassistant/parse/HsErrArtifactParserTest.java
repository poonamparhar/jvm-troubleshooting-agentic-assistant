package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.MemoryPressureFixtureFactory;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HsErrArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final HsErrArtifactParser parser = new HsErrArtifactParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesCrashHeaderAndProblematicFrame() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid69848.log")));

        @SuppressWarnings("unchecked")
        Map<String, String> problematicFrame = (Map<String, String>) parsed.extractedData().get("problematicFrame");

        assertEquals("SIGBUS", parsed.extractedData().get("signal"));
        assertEquals("2020-01-16T19:14:59Z", parsed.extractedData().get("crashTime"));
        assertTrue(String.valueOf(parsed.extractedData().get("jreVersion")).contains("11.0.4+10"));
        assertTrue(problematicFrame.get("symbol").contains("G1FullGCMarker::follow_object"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-crash-time")));
        assertFalse(parsed.evidence().isEmpty());
    }

    @Test
    void parsesNativeAllocationFailureCrashDetails() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/hs_err_pid2866366.log")));

        @SuppressWarnings("unchecked")
        Map<String, Object> nativeAllocationFailure = (Map<String, Object>) parsed.extractedData().get("nativeAllocationFailure");

        assertEquals("native_allocation_failure", parsed.extractedData().get("crashType"));
        assertEquals("2022-11-03T06:52:17Z", parsed.extractedData().get("crashTime"));
        assertEquals("C2 CompilerThread0", parsed.extractedData().get("currentThreadName"));
        assertEquals(32_744L, ((Number) nativeAllocationFailure.get("bytes")).longValue());
        assertTrue(String.valueOf(nativeAllocationFailure.get("requestSite")).contains("ChunkPool::allocate"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-native-allocation-failure")));
        assertFalse(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-problematic-frame")));
    }

    @Test
    void parsesNativeThreadExhaustionCrashDetails() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createNativeThreadExhaustionBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));

        @SuppressWarnings("unchecked")
        Map<String, Object> nativeThreadExhaustion = (Map<String, Object>) parsed.extractedData().get("nativeThreadExhaustion");

        assertEquals("native_thread_exhaustion", parsed.extractedData().get("crashType"));
        assertEquals("2026-04-07T17:02:18Z", parsed.extractedData().get("crashTime"));
        assertEquals("http-worker-193", parsed.extractedData().get("currentThreadName"));
        assertTrue(String.valueOf(nativeThreadExhaustion.get("reason")).contains("unable to create new native thread"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-native-thread-exhaustion")));
        assertFalse(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-signal")));
    }

    @Test
    void parsesCodeCacheExhaustionCrashDetails() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCodeCacheFullBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));

        @SuppressWarnings("unchecked")
        Map<String, Object> codeCacheStatus = (Map<String, Object>) parsed.extractedData().get("codeCacheStatus");

        assertEquals("code_cache_full", parsed.extractedData().get("crashType"));
        assertEquals("SIGTRAP", parsed.extractedData().get("signal"));
        assertEquals("C2 CompilerThread0", parsed.extractedData().get("currentThreadName"));
        assertEquals(245_760L, ((Number) codeCacheStatus.get("sizeKb")).longValue());
        assertEquals(245_120L, ((Number) codeCacheStatus.get("usedKb")).longValue());
        assertEquals(640L, ((Number) codeCacheStatus.get("freeKb")).longValue());
        assertTrue(String.valueOf(codeCacheStatus.get("reason")).contains("CodeCache is full"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-code-cache-status")));
    }

    @Test
    void parsesCompressedClassSpaceCrashDetails() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createCompressedClassSpaceOomBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));

        @SuppressWarnings("unchecked")
        Map<String, Object> compressedClassSpaceFailure = (Map<String, Object>) parsed.extractedData().get("compressedClassSpaceFailure");

        assertEquals("compressed_class_space_oom", parsed.extractedData().get("crashType"));
        assertEquals("SIGABRT", parsed.extractedData().get("signal"));
        assertEquals("2026-04-08T09:42:31Z", parsed.extractedData().get("crashTime"));
        assertEquals("class-define-worker-1", parsed.extractedData().get("currentThreadName"));
        assertEquals(1_048_576L, ((Number) compressedClassSpaceFailure.get("requestedBytes")).longValue());
        assertTrue(String.valueOf(compressedClassSpaceFailure.get("reason")).contains("Compressed class space"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-compressed-class-space")));
    }

    @Test
    void parsesGeneratedDirectBufferNativeOomCrashDetails() throws Exception {
        var bundle = MemoryPressureFixtureFactory.createDirectBufferNativeOomBundle(tempDir);
        var parsed = parser.parse(loader.load(bundle.get("hs-err")));

        @SuppressWarnings("unchecked")
        Map<String, Object> nativeAllocationFailure = (Map<String, Object>) parsed.extractedData().get("nativeAllocationFailure");

        assertEquals("native_allocation_failure", parsed.extractedData().get("crashType"));
        assertEquals("nio-direct-buffer-worker", parsed.extractedData().get("currentThreadName"));
        assertEquals(4_194_304L, ((Number) nativeAllocationFailure.get("bytes")).longValue());
        assertTrue(String.valueOf(nativeAllocationFailure.get("requestSite")).contains("DirectByteBuffer::reserveMemory"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-native-allocation-failure")));
    }

    @Test
    void warnsWhenHsErrLogIsTruncatedBeforeProblematicFrame() {
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

        assertEquals("SIGSEGV", parsed.extractedData().get("signal"));
        assertEquals("2026-04-09T13:14:23Z", parsed.extractedData().get("crashTime"));
        assertTrue(((Map<?, ?>) parsed.extractedData().get("problematicFrame")).isEmpty());
        assertTrue(parsed.warnings().stream().anyMatch(warning -> warning.contains("problematic frame")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("hs-err-signal")));
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
