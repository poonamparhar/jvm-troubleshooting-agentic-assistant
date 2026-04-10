package com.javaassistant.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ThreadingScenarioLab implements ScenarioLab {

    private static final Set<String> SUPPORTED_SCENARIO_IDS = Set.of(
        "executor-pool-stall"
    );

    @Override
    public Set<String> supportedScenarioIds() {
        return SUPPORTED_SCENARIO_IDS;
    }

    @Override
    public Map<String, Path> generate(String scenarioId, Path tempDirectory) throws Exception {
        return switch (scenarioId) {
            case "executor-pool-stall" -> createExecutorPoolStallBundle(tempDirectory);
            default -> throw new IllegalStateException("Unsupported threading scenario: " + scenarioId);
        };
    }

    private Map<String, Path> createExecutorPoolStallBundle(Path tempDirectory) throws IOException {
        LinkedHashMap<String, Path> generated = new LinkedHashMap<>();
        Path baseline = writeExecutorPoolBaselineThreadDump(tempDirectory.resolve("generated-executor-pool-stall-baseline.txt"));
        Path current = writeExecutorPoolStallThreadDump(tempDirectory.resolve("generated-executor-pool-stall-current.txt"));
        generated.put("baseline", baseline);
        generated.put("current", current);
        generated.put("thread-dump", current);
        generated.put("primary", current);
        return Map.copyOf(generated);
    }

    private Path writeExecutorPoolBaselineThreadDump(Path path) throws IOException {
        String content = """
            Capture time: 2026-04-08T16:12:05Z
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "main" #1 prio=5 os_prio=31 cpu=55.10ms elapsed=310.24s tid=0x0000000102800000 nid=0x8103 runnable [0x000000016f9d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.cli.CommandLoop.read(CommandLoop.java:118)
                at com.acme.cli.CommandLoop.run(CommandLoop.java:77)

            "checkout-exec-17" #17 daemon prio=5 os_prio=31 cpu=186.42ms elapsed=41.02s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.checkout.CheckoutPipeline.process(CheckoutPipeline.java:141)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-18" #18 daemon prio=5 os_prio=31 cpu=8.41ms elapsed=40.98s tid=0x0000000102a54000 nid=0x8303 waiting on condition [0x000000016f5d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700310001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130)

            "checkout-exec-19" #19 daemon prio=5 os_prio=31 cpu=7.92ms elapsed=40.97s tid=0x0000000102a86000 nid=0x8403 waiting on condition [0x000000016f4d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700310001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130)

            "checkout-exec-20" #20 daemon prio=5 os_prio=31 cpu=7.16ms elapsed=40.95s tid=0x0000000102ab7000 nid=0x8503 waiting on condition [0x000000016f3d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700310001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130)

            "Reference Handler" #2 daemon prio=10 os_prio=31 cpu=0.44ms elapsed=310.20s tid=0x0000000102819000 nid=0x8603 runnable [0x000000016f0d3000]
               java.lang.Thread.State: RUNNABLE
                at java.lang.ref.Reference.waitForReferencePendingList(Native Method)
                at java.lang.ref.Reference.processPendingReferences(Reference.java:246)

            JNI global refs: 38, weak refs: 0
            """;
        Files.writeString(path, content);
        return path;
    }

    private Path writeExecutorPoolStallThreadDump(Path path) throws IOException {
        String content = """
            Capture time: 2026-04-08T16:14:05Z
            Full thread dump OpenJDK 64-Bit Server VM (25+36 mixed mode, sharing):

            "main" #1 prio=5 os_prio=31 cpu=58.31ms elapsed=312.21s tid=0x0000000102800000 nid=0x8103 runnable [0x000000016f9d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.cli.CommandLoop.read(CommandLoop.java:118)
                at com.acme.cli.CommandLoop.run(CommandLoop.java:77)

            "checkout-exec-17" #17 daemon prio=5 os_prio=31 cpu=933.12ms elapsed=14.82s tid=0x0000000102a23000 nid=0x8203 runnable [0x000000016f6d3000]
               java.lang.Thread.State: RUNNABLE
                at com.acme.checkout.OrderStateCoordinator.acquireDispatchSlot(OrderStateCoordinator.java:88)
                - locked <0x0000000700300001> (a java.lang.Object)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-18" #18 daemon prio=5 os_prio=31 cpu=122.11ms elapsed=14.79s tid=0x0000000102a54000 nid=0x8303 waiting for monitor entry [0x000000016f5d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.checkout.OrderStateCoordinator.acquireDispatchSlot(OrderStateCoordinator.java:88)
                - waiting to lock <0x0000000700300001> (a java.lang.Object)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-19" #19 daemon prio=5 os_prio=31 cpu=121.93ms elapsed=14.78s tid=0x0000000102a86000 nid=0x8403 waiting for monitor entry [0x000000016f4d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.checkout.OrderStateCoordinator.acquireDispatchSlot(OrderStateCoordinator.java:88)
                - waiting to lock <0x0000000700300001> (a java.lang.Object)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-20" #20 daemon prio=5 os_prio=31 cpu=120.52ms elapsed=14.77s tid=0x0000000102ab7000 nid=0x8503 waiting for monitor entry [0x000000016f3d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.checkout.OrderStateCoordinator.acquireDispatchSlot(OrderStateCoordinator.java:88)
                - waiting to lock <0x0000000700300001> (a java.lang.Object)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-21" #21 daemon prio=5 os_prio=31 cpu=119.84ms elapsed=14.76s tid=0x0000000102ac7000 nid=0x8603 waiting for monitor entry [0x000000016f2d3000]
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.acme.checkout.OrderStateCoordinator.acquireDispatchSlot(OrderStateCoordinator.java:88)
                - waiting to lock <0x0000000700300001> (a java.lang.Object)
                at com.acme.checkout.CheckoutWorker.run(CheckoutWorker.java:62)

            "checkout-exec-22" #22 daemon prio=5 os_prio=31 cpu=8.42ms elapsed=14.74s tid=0x0000000102ae8000 nid=0x8703 waiting on condition [0x000000016f1d3000]
               java.lang.Thread.State: WAITING (parking)
                at jdk.internal.misc.Unsafe.park(Native Method)
                - parking to wait for  <0x0000000700310001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)
                at java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)
                at java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1070)
                at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1130)

            "Reference Handler" #2 daemon prio=10 os_prio=31 cpu=0.44ms elapsed=312.18s tid=0x0000000102819000 nid=0x8803 runnable [0x000000016f0d3000]
               java.lang.Thread.State: RUNNABLE
                at java.lang.ref.Reference.waitForReferencePendingList(Native Method)
                at java.lang.ref.Reference.processPendingReferences(Reference.java:246)

            JNI global refs: 38, weak refs: 0
            """;
        Files.writeString(path, content);
        return path;
    }
}
