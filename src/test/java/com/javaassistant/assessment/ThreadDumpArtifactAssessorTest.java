package com.javaassistant.assessment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThreadDumpArtifactAssessorTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();
    private final ThreadDumpArtifactAssessor engine = new ThreadDumpArtifactAssessor();

    @Test
    void emitsDeadlockContentionAndPoolStallFindings() throws Exception {
        var parsed = parser.parse(loader.load(Path.of("samples/thread_dump_deadlock.txt")));
        var evaluation = engine.evaluate(parsed);

        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("thread-dump-java-deadlock") && finding.evidenceIds().contains("thread-dump-deadlock")
        ));
        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-lock-contention-hotspot")));
        assertTrue(evaluation.findings().stream().anyMatch(finding ->
            finding.id().equals("thread-dump-stuck-thread-pool") && finding.evidenceIds().contains("thread-dump-pool-http-nio-8080-exec")
        ));
        assertFalse(evaluation.recommendedActions().isEmpty());
        assertTrue(evaluation.missingData().isEmpty());
    }

    @Test
    void emitsDownstreamIoPileupFinding() {
        var evaluation = evaluateSyntheticThreadDump(
            "samples/thread-dump-io-pileup.txt",
            """
                Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

                "checkout-io-pool-17" #17 daemon prio=5 os_prio=31 cpu=932.40ms elapsed=18.00s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
                   java.lang.Thread.State: RUNNABLE
                    at sun.nio.ch.SocketDispatcher.read0(Native Method)
                    at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

                "checkout-io-pool-18" #18 daemon prio=5 os_prio=31 cpu=910.10ms elapsed=17.80s tid=0x0000000102a54000 nid=0x8303 runnable [0x000000016f5d3000]
                   java.lang.Thread.State: RUNNABLE
                    at sun.nio.ch.SocketDispatcher.read0(Native Method)
                    at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

                "checkout-io-pool-19" #19 daemon prio=5 os_prio=31 cpu=902.70ms elapsed=17.60s tid=0x0000000102a86000 nid=0x8403 runnable [0x000000016f4d3000]
                   java.lang.Thread.State: RUNNABLE
                    at sun.nio.ch.SocketDispatcher.read0(Native Method)
                    at com.acme.checkout.DownstreamClient.fetch(DownstreamClient.java:88)

                "checkout-io-pool-20" #20 daemon prio=5 os_prio=31 cpu=8.40ms elapsed=17.55s tid=0x0000000102ab7000 nid=0x8503 waiting on condition [0x000000016f3d3000]
                   java.lang.Thread.State: WAITING (parking)
                    at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                    at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                """
        );

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-downstream-io-pileup")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-thread-dump-downstream-io-pileup")));
    }

    @Test
    void emitsForkJoinStarvationFinding() {
        var evaluation = evaluateSyntheticThreadDump(
            "samples/thread-dump-forkjoin-starvation.txt",
            """
                Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

                "ForkJoinPool.commonPool-worker-1" #31 daemon prio=5 os_prio=31 cpu=120.10ms elapsed=21.00s tid=0x0000000102a23000 nid=0x8203 waiting on condition [0x000000016f6d3000]
                   java.lang.Thread.State: WAITING (parking)
                    at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
                    at java.util.concurrent.ForkJoinTask.join(ForkJoinTask.java:670)

                "ForkJoinPool.commonPool-worker-2" #32 daemon prio=5 os_prio=31 cpu=118.40ms elapsed=20.70s tid=0x0000000102a54000 nid=0x8303 waiting on condition [0x000000016f5d3000]
                   java.lang.Thread.State: WAITING (parking)
                    at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
                    at java.util.concurrent.ForkJoinTask.join(ForkJoinTask.java:670)

                "ForkJoinPool.commonPool-worker-3" #33 daemon prio=5 os_prio=31 cpu=117.90ms elapsed=20.40s tid=0x0000000102a86000 nid=0x8403 waiting on condition [0x000000016f4d3000]
                   java.lang.Thread.State: WAITING (parking)
                    at java.util.concurrent.CompletableFuture$Signaller.block(CompletableFuture.java:1864)
                    at java.util.concurrent.ForkJoinTask.awaitDone(ForkJoinTask.java:433)
                """
        );

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-forkjoin-starvation")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-thread-dump-forkjoin-starvation")));
    }

    @Test
    void emitsVirtualThreadPinningFinding() {
        var evaluation = evaluateSyntheticThreadDump(
            "samples/thread-dump-virtual-thread-pinning.txt",
            """
                Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

                "VirtualThread[#42]/runnable@ForkJoinPool-1-worker-3" #42 daemon prio=5 os_prio=31 cpu=834.20ms elapsed=9.10s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
                   java.lang.Thread.State: RUNNABLE
                    at java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:687)
                    at com.acme.jdbc.BlockingQuery.run(BlockingQuery.java:88)

                "ForkJoinPool-1-worker-3" #43 daemon prio=5 os_prio=31 cpu=812.10ms elapsed=9.05s tid=0x0000000102a54000 nid=0x8303 runnable [0x000000016f5d3000]
                   java.lang.Thread.State: RUNNABLE
                    at java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:687)
                    at com.acme.jdbc.BlockingQuery.run(BlockingQuery.java:88)
                """
        );

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-virtual-thread-pinning")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-thread-dump-virtual-thread-pinning")));
    }

    @Test
    void emitsBusySpinFinding() {
        var evaluation = evaluateSyntheticThreadDump(
            "samples/thread-dump-busy-spin.txt",
            """
                Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

                "pricing-hot-loop-1" #51 daemon prio=5 os_prio=31 cpu=18432.50ms elapsed=19.20s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
                   java.lang.Thread.State: RUNNABLE
                    at com.acme.pricing.SpinLoop.awaitSignal(SpinLoop.java:51)
                    at com.acme.pricing.QuoteWorker.run(QuoteWorker.java:81)

                "pricing-hot-loop-2" #52 daemon prio=5 os_prio=31 cpu=12.10ms elapsed=19.00s tid=0x0000000102a54000 nid=0x8303 waiting on condition [0x000000016f5d3000]
                   java.lang.Thread.State: WAITING (parking)
                    at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                    at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                """
        );

        assertTrue(evaluation.findings().stream().anyMatch(finding -> finding.id().equals("thread-dump-busy-spin-thread")));
        assertTrue(evaluation.recommendedActions().stream().anyMatch(action -> action.id().equals("action-thread-dump-busy-spin-thread")));
    }

    private AssessmentResult evaluateSyntheticThreadDump(String sourcePath, String content) {
        var parsed = parser.parse(syntheticArtifact(sourcePath, content));
        return engine.evaluate(parsed);
    }

    private InputArtifact syntheticArtifact(String sourcePath, String content) {
        String normalizedContent = content.strip();
        return new InputArtifact(
            ArtifactType.THREAD_DUMP,
            new ArtifactMetadata(sourcePath, Path.of(sourcePath).getFileName().toString(), normalizedContent.length()),
            normalizedContent
        );
    }
}
