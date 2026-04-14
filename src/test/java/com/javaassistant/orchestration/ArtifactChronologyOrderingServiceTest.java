package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.DiagnosticRuntimeFactory;
import com.javaassistant.assessment.ArtifactAssessmentService;
import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.orchestration.AgentDiagnosticContextBuilder.ArtifactGrounding;
import com.javaassistant.parse.ArtifactParsingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactChronologyOrderingServiceTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ArtifactParsingService parsingService = DiagnosticRuntimeFactory.parsingService();
    private final ArtifactAssessmentService assessmentService = DiagnosticRuntimeFactory.assessmentService();
    private final ArtifactChronologyOrderingService orderingService = new ArtifactChronologyOrderingService();

    @TempDir
    Path tempDir;

    @Test
    void ordersPairsByExplicitCaptureTime() throws Exception {
        Path olderPath = createTimedThreadDump(
            tempDir.resolve("thread-dump-older.txt"),
            Instant.parse("2026-04-06T10:15:00Z")
        );
        Path newerPath = createTimedThreadDump(
            tempDir.resolve("thread-dump-newer.txt"),
            Instant.parse("2026-04-06T10:20:00Z")
        );

        ArtifactChronologyOrderingService.OrderingDecision decision = orderingService.orderPair(List.of(
            grounding(newerPath),
            grounding(olderPath)
        ));

        assertEquals(ArtifactChronologyOrderingService.OrderingBasis.EXPLICIT_CAPTURE_TIME, decision.basis());
        assertEquals(olderPath.toString(), sourcePath(decision.orderedGroundings().get(0)));
        assertEquals(newerPath.toString(), sourcePath(decision.orderedGroundings().get(1)));
        assertTrue(decision.changed());
    }

    @Test
    void ordersPairsByFilenameRoleWhenCaptureTimesAreUnavailable() throws Exception {
        Path currentPath = copySample(
            Path.of("samples/heap_histogram_2.txt"),
            tempDir.resolve("orders-current-heap.txt")
        );
        Path baselinePath = copySample(
            Path.of("samples/heap_histogram_1.txt"),
            tempDir.resolve("orders-baseline-heap.txt")
        );

        ArtifactChronologyOrderingService.OrderingDecision decision = orderingService.orderPair(List.of(
            grounding(currentPath),
            grounding(baselinePath)
        ));

        assertEquals(ArtifactChronologyOrderingService.OrderingBasis.FILENAME_ROLE_HINT, decision.basis());
        assertEquals(baselinePath.toString(), sourcePath(decision.orderedGroundings().get(0)));
        assertEquals(currentPath.toString(), sourcePath(decision.orderedGroundings().get(1)));
        assertTrue(decision.changed());
    }

    @Test
    void ordersSequencesByFilenameTimestamp() throws Exception {
        Path latest = copySample(
            Path.of("samples/heap_histogram_2.txt"),
            tempDir.resolve("heap-20260406-102500.txt")
        );
        Path earliest = copySample(
            Path.of("samples/heap_histogram_1.txt"),
            tempDir.resolve("heap-20260406-101000.txt")
        );
        Path middle = copySample(
            Path.of("samples/heap_histogram_2.txt"),
            tempDir.resolve("heap-20260406-101500.txt")
        );

        ArtifactChronologyOrderingService.OrderingDecision decision = orderingService.orderSequence(List.of(
            grounding(latest),
            grounding(earliest),
            grounding(middle)
        ));

        assertEquals(ArtifactChronologyOrderingService.OrderingBasis.FILENAME_TIMESTAMP, decision.basis());
        assertEquals(
            List.of(earliest.toString(), middle.toString(), latest.toString()),
            decision.orderedGroundings().stream().map(this::sourcePath).toList()
        );
        assertTrue(decision.changed());
    }

    @Test
    void preservesInputOrderWhenNoChronologySignalsAreAvailable() {
        ArtifactGrounding first = syntheticGrounding("snapshot-a.txt");
        ArtifactGrounding second = syntheticGrounding("snapshot-b.txt");

        ArtifactChronologyOrderingService.OrderingDecision decision = orderingService.orderPair(List.of(first, second));

        assertEquals(ArtifactChronologyOrderingService.OrderingBasis.ORIGINAL_INPUT_ORDER, decision.basis());
        assertEquals(List.of("snapshot-a.txt", "snapshot-b.txt"), decision.orderedGroundings().stream().map(this::sourcePath).toList());
        assertFalse(decision.changed());
    }

    private ArtifactGrounding grounding(Path path) throws Exception {
        InputArtifact artifact = loader.load(path);
        ParsedArtifact parsedArtifact = parsingService.parse(artifact);
        return new ArtifactGrounding(artifact, parsedArtifact, assessmentService.evaluate(parsedArtifact));
    }

    private ArtifactGrounding syntheticGrounding(String sourcePath) {
        InputArtifact artifact = new InputArtifact(
            ArtifactType.HEAP_HISTOGRAM,
            new ArtifactMetadata(sourcePath, sourcePath, 16L),
            "synthetic"
        );
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.HEAP_HISTOGRAM,
            artifact.metadata(),
            "test",
            Map.of(),
            List.of(),
            List.of()
        );
        return new ArtifactGrounding(artifact, parsedArtifact, new AssessmentResult(List.of(), List.of(), List.of()));
    }

    private String sourcePath(ArtifactGrounding grounding) {
        return grounding.inputArtifact().metadata().sourcePath();
    }

    private Path copySample(Path source, Path target) throws Exception {
        Files.copy(source, target);
        return target;
    }

    private Path createTimedThreadDump(Path path, Instant captureTime) throws Exception {
        String sample = Files.readString(Path.of("samples/thread_dump_deadlock.txt"));
        int firstNewline = sample.indexOf('\n');
        String threadDumpBody = firstNewline >= 0 ? sample.substring(firstNewline + 1).stripLeading() : sample;
        Files.writeString(path, "Capture time: " + captureTime + "\n" + threadDumpBody);
        return path;
    }
}
