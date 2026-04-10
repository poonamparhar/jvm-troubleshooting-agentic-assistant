package com.javaassistant.testsupport;

import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Event;
import jdk.jfr.EventSettings;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.Timestamp;
import jdk.jfr.Timespan;

public final class JfrTestRecordingFactory {

    private JfrTestRecordingFactory() {
    }

    public static Path createContentionAndGcRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);

            recording.start();

            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 140L);
            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 30L);
            commitDurationEvent(new TestGarbageCollectionEvent(), 220L);
            new TestExecutionSampleEvent().commit();
            emitBaselineAllocationEvent(String.class, 256_000L, 384_000L, "baseline");

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createDeeperAnalyticsRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestThreadParkEvent.class);
            enable(recording, TestSocketReadEvent.class);
            enable(recording, TestFileWriteEvent.class);
            enable(recording, TestJavaExceptionThrowEvent.class);
            enable(recording, TestSafepointPauseEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);

            recording.start();

            commitDurationEvent(new TestThreadParkEvent(), 180L);
            commitDurationEvent(new TestThreadParkEvent(), 120L);
            commitDurationEvent(new TestSocketReadEvent(), 220L);
            commitDurationEvent(new TestFileWriteEvent(), 140L);
            commitDurationEvent(new TestSafepointPauseEvent(), 130L);
            commitDurationEvent(new TestSafepointPauseEvent(), 90L);
            for (int i = 0; i < 32; i++) {
                new TestJavaExceptionThrowEvent().commit();
            }
            new TestExecutionSampleEvent().commit();
            emitBaselineAllocationEvent(StringBuilder.class, 192_000L, 256_000L, "runtime");

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createHotPathRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestThreadParkEvent.class);
            enable(recording, TestSocketReadEvent.class);
            enable(recording, TestFileWriteEvent.class);

            recording.start();

            for (int i = 0; i < 6; i++) {
                emitCheckoutExecutionSample();
            }
            for (int i = 0; i < 2; i++) {
                emitReportExecutionSample();
            }

            emitCheckoutWaitPath(90L);
            emitCheckoutWaitPath(80L);
            emitCheckoutSocketHotPath(110L);
            emitReportFileHotPath(70L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createAllocationPathRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationOutsideTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);

            recording.start();

            for (int i = 0; i < 6; i++) {
                emitCheckoutAllocationInTlab(2_000_000L, 2_500_000L, String.class, "checkout-cache");
            }
            emitCheckoutAllocationSample(1_500_000L, String.class, "checkout-cache");
            emitPricingAllocationOutsideTlab(600_000L, byte[].class, "pricing-buffer");
            emitReportAllocationOutsideTlab(500_000L, java.util.HashMap.class, "report-export");
            emitReportAllocationSample(400_000L, java.util.HashMap.class, "report-export");

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createRetainedObjectRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestOldObjectSampleEvent.class);
            enable(recording, TestThreadParkEvent.class);

            recording.start();

            emitCheckoutOldObjectSample(
                1_400_000L,
                180_000L,
                96_000_000L,
                java.util.LinkedHashMap.class,
                5,
                "JNI Global",
                "Threads",
                "worker-thread cache",
                "checkout-session-cache"
            );
            emitCheckoutOldObjectSample(
                1_100_000L,
                150_000L,
                96_000_000L,
                java.util.LinkedHashMap.class,
                4,
                "JNI Global",
                "Threads",
                "worker-thread cache",
                "checkout-session-cache"
            );
            emitReportOldObjectSample(
                640_000L,
                95_000L,
                92_000_000L,
                byte[].class,
                2,
                "Code Cache",
                "Code",
                "export buffer",
                "report-export-buffer"
            );
            emitCheckoutWaitPath(40L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createHumongousAllocationPressureRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationOutsideTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            runInThread("image-cache-worker", () -> {
                commitDurationEvent(new TestGarbageCollectionEvent(), 145L);
                for (int index = 0; index < 5; index++) {
                    emitCheckoutAllocationInTlab(2_400_000L, 2_800_000L, byte[].class, "image-fragment-cache");
                }
                emitCheckoutAllocationSample(1_900_000L, byte[].class, "image-fragment-cache");
                emitCheckoutOldObjectSample(
                    18_000_000L,
                    240_000L,
                    998_000_000L,
                    byte[].class,
                    5,
                    "JNI Global",
                    "Threads",
                    "image-fragment cache",
                    "humongous-image-cache"
                );
                emitCheckoutOldObjectSample(
                    16_500_000L,
                    210_000L,
                    998_000_000L,
                    byte[].class,
                    4,
                    "JNI Global",
                    "Threads",
                    "image-fragment cache",
                    "humongous-image-cache"
                );
            });
            runInThread("report-buffer-worker", () -> {
                commitDurationEvent(new TestGarbageCollectionEvent(), 120L);
                emitPricingAllocationOutsideTlab(3_200_000L, byte[].class, "report-batch-buffer");
                emitReportAllocationSample(1_700_000L, byte[].class, "report-batch-buffer");
                emitReportOldObjectSample(
                    14_000_000L,
                    180_000L,
                    998_000_000L,
                    byte[].class,
                    4,
                    "JNI Global",
                    "Threads",
                    "report batch buffer",
                    "humongous-report-buffer"
                );
            });
            emitCheckoutExecutionSample();
            Thread.sleep(250L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createClassLoadingPressureRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestClassDefineEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            runInThread("dynamic-loader-1", () -> {
                for (int index = 0; index < 6; index++) {
                    emitCheckoutGeneratedClassDefine(
                        "com.acme.generated.checkout.Proxy" + index,
                        "DynamicProxyLoader",
                        224_000L + (index * 8_000L),
                        "proxy-generation"
                    );
                    Thread.sleep(35L);
                }
            });
            runInThread("dynamic-loader-2", () -> {
                for (int index = 0; index < 4; index++) {
                    emitCheckoutGeneratedClassDefine(
                        "com.acme.generated.checkout.Route" + index,
                        "DynamicProxyLoader",
                        208_000L + (index * 8_000L),
                        "request-routing"
                    );
                    Thread.sleep(35L);
                }
                emitReportGeneratedClassDefine(
                    "com.acme.generated.reporting.Template0",
                    "TemplateLoader",
                    176_000L,
                    "template-refresh"
                );
                Thread.sleep(35L);
                emitReportGeneratedClassDefine(
                    "com.acme.generated.reporting.Template1",
                    "TemplateLoader",
                    184_000L,
                    "template-refresh"
                );
            });
            emitCheckoutExecutionSample();
            Thread.sleep(350L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createCodeCachePressureRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestCompilationEvent.class);
            enable(recording, TestCodeCacheFullEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            runInThread("C2 CompilerThread0", () -> {
                emitCompilationEvent(
                    "com.acme.checkout.QuoteCompiler.compilePlan",
                    "C2",
                    "4",
                    14L,
                    232_783_872L,
                    12_976_128L,
                    245_760_000L,
                    80L
                );
                emitCompilationEvent(
                    "com.acme.checkout.PricingEngine.compileRoute",
                    "C2",
                    "4",
                    18L,
                    237_895_680L,
                    7_864_320L,
                    245_760_000L,
                    95L
                );
                emitCompilationEvent(
                    "com.acme.checkout.QuoteCompiler.compilePlan",
                    "C2",
                    "4",
                    23L,
                    241_041_408L,
                    4_718_592L,
                    245_760_000L,
                    110L
                );
                emitCompilationEvent(
                    "com.acme.checkout.RoutePlanner.compileHotPath",
                    "C2",
                    "4",
                    29L,
                    243_531_776L,
                    2_228_224L,
                    245_760_000L,
                    120L
                );
                emitCodeCacheFullEvent(
                    "C2",
                    245_104_640L,
                    655_360L,
                    245_760_000L,
                    34L,
                    "CodeCache is full. Compiler has been disabled."
                );
            });

            runInThread("C1 CompilerThread1", () -> {
                emitCompilationEvent(
                    "com.acme.reporting.TemplateCompiler.compileRender",
                    "C1",
                    "3",
                    9L,
                    229_638_144L,
                    16_121_856L,
                    245_760_000L,
                    65L
                );
                emitCompilationEvent(
                    "com.acme.reporting.TemplateCompiler.compileRender",
                    "C1",
                    "3",
                    12L,
                    234_225_664L,
                    11_534_336L,
                    245_760_000L,
                    70L
                );
                emitCompilationEvent(
                    "com.acme.gateway.DispatchCompiler.compileInvoker",
                    "C1",
                    "3",
                    17L,
                    238_419_968L,
                    7_340_032L,
                    245_760_000L,
                    85L
                );
            });

            emitCheckoutExecutionSample();
            Thread.sleep(80L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createVirtualThreadPinningRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestVirtualThreadPinnedEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            runInThread("ForkJoinPool-1-worker-3", () -> {
                TestVirtualThreadPinnedEvent event = new TestVirtualThreadPinnedEvent();
                event.reason = "synchronized JDBC call on carrier";
                commitDurationEvent(event, 140L);
            });
            runInThread("ForkJoinPool-1-worker-4", () -> {
                TestVirtualThreadPinnedEvent event = new TestVirtualThreadPinnedEvent();
                event.reason = "blocking native TLS handshake";
                commitDurationEvent(event, 110L);
            });
            new TestExecutionSampleEvent().commit();

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createMonitorWaitRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorWaitEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            runInThread("checkout-monitor-waiter-1", () -> {
                emitCheckoutMonitorWaitPath(160L, "queue handoff backlog");
                emitCheckoutMonitorWaitPath(130L, "queue handoff backlog");
            });
            runInThread("checkout-monitor-waiter-2", () -> emitCheckoutMonitorWaitPath(140L, "consumer backlog"));
            runInThread("report-monitor-waiter-1", () -> emitReportMonitorWaitPath(70L, "report export drain"));
            emitCheckoutExecutionSample();

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createCpuLoadSaturationRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestCPULoadEvent.class);
            enable(recording, TestThreadCPULoadEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            emitCpuLoadEvent(0.91d, 0.54d, 0.23d);
            Thread.sleep(20L);
            emitCpuLoadEvent(0.94d, 0.57d, 0.24d);
            Thread.sleep(20L);
            runInThread("checkout-cpu-hot-thread", () -> {
                emitThreadCpuLoadEvent(0.73d, 0.14d);
                emitCheckoutExecutionSample();
                Thread.sleep(15L);
                emitThreadCpuLoadEvent(0.78d, 0.12d);
                emitCheckoutExecutionSample();
            });
            Thread.sleep(15L);
            emitCpuLoadEvent(0.97d, 0.60d, 0.27d);
            Thread.sleep(20L);
            runInThread("report-cpu-thread", () -> {
                emitThreadCpuLoadEvent(0.31d, 0.08d);
                emitReportExecutionSample();
            });
            Thread.sleep(15L);
            emitCpuLoadEvent(0.95d, 0.59d, 0.25d);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createThresholdBlindSpotRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestThreadParkEvent.class, Duration.ofMillis(50L));

            recording.start();

            for (int i = 0; i < 6; i++) {
                emitCheckoutExecutionSample();
                emitCheckoutWaitPath(15L);
            }

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createVirtualThreadSubmitFailedRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestVirtualThreadSubmitFailedEvent.class);
            enable(recording, TestExecutionSampleEvent.class);

            recording.start();

            runInThread("ForkJoinPool-1-worker-7", () -> {
                emitVirtualThreadSubmitFailedEvent("carrier scheduler saturated", 384L, 0L);
                Thread.sleep(20L);
                emitVirtualThreadSubmitFailedEvent("carrier scheduler saturated", 512L, 0L);
            });
            runInThread("ForkJoinPool-1-worker-8", () ->
                emitVirtualThreadSubmitFailedEvent("resource temporarily unavailable creating carrier", 448L, 1L)
            );
            emitCheckoutExecutionSample();

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createDirectBufferPressureRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestObjectAllocationOutsideTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            runInThread("nio-direct-buffer-worker", () -> {
                for (int index = 0; index < 6; index++) {
                    emitDirectBufferAllocationOutsideTlab(
                        2_200_000L + (index * 120_000L),
                        java.nio.ByteBuffer.class,
                        "nio-direct-buffer"
                    );
                    Thread.sleep(25L);
                }
                for (int index = 0; index < 2; index++) {
                    emitDirectBufferAllocationSample(
                        1_800_000L + (index * 160_000L),
                        java.nio.ByteBuffer.class,
                        "nio-direct-buffer"
                    );
                    Thread.sleep(25L);
                }
                emitCheckoutOldObjectSample(
                    1_300_000L,
                    180_000L,
                    72_000_000L,
                    java.nio.ByteBuffer.class,
                    4,
                    "JNI Global",
                    "Native",
                    "buffer pool",
                    "nio-direct-buffer-wrapper"
                );
                Thread.sleep(25L);
                emitCheckoutOldObjectSample(
                    1_100_000L,
                    150_000L,
                    72_000_000L,
                    java.nio.ByteBuffer.class,
                    5,
                    "JNI Global",
                    "Native",
                    "buffer pool",
                    "nio-direct-buffer-wrapper"
                );
                Thread.sleep(25L);
                emitDirectBufferExecutionSample();
            });

            Thread.sleep(120L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createGenericEventDetailRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestQueueBacklogEvent.class);

            recording.start();

            emitQueueBacklogEvent("checkout", "us-phoenix-1", 120L, false, 40L);
            emitQueueBacklogEvent("checkout", "us-phoenix-1", 185L, true, 70L);
            emitQueueBacklogEvent("reporting", "us-ashburn-1", 90L, false, 30L);

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createIncidentWindowRecording(Path recordingPath) throws Exception {
        return createIncidentWindowRecording(recordingPath, false);
    }

    public static Path createIncidentWindowRecordingWithJvmInfo(Path recordingPath) throws Exception {
        return createIncidentWindowRecording(recordingPath, true);
    }

    public static Path createIncidentWindowRecordingWithThreadJoins(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestThreadParkEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            runInThread("http-nio-8080-exec-17", () -> {
                emitCheckoutWaitPath(70L);
                commitDurationEvent(new TestJavaMonitorBlockedEvent(), 110L);
            });
            runInThread("http-nio-8080-exec-18", () -> commitDurationEvent(new TestJavaMonitorBlockedEvent(), 95L));
            runInThread("Deadlock-Worker-1", () -> commitDurationEvent(new TestJavaMonitorBlockedEvent(), 85L));
            commitDurationEvent(new TestGarbageCollectionEvent(), 160L);

            Thread.sleep(220L);

            emitCheckoutExecutionSample();
            emitCheckoutAllocationInTlab(1_400_000L, 1_900_000L, String.class, "checkout-cache");
            emitCheckoutOldObjectSample(
                1_250_000L,
                170_000L,
                88_000_000L,
                java.util.LinkedHashMap.class,
                4,
                "JNI Global",
                "Threads",
                "worker-thread cache",
                "checkout-session-cache"
            );

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    private static Path createIncidentWindowRecording(Path recordingPath, boolean includeJvmInfo) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestThreadParkEvent.class);
            enable(recording, TestSocketReadEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);
            if (includeJvmInfo) {
                enable(recording, "jdk.JVMInformation");
            }

            recording.start();

            emitCheckoutExecutionSample();
            emitCheckoutExecutionSample();
            emitCheckoutWaitPath(90L);
            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 120L);
            commitDurationEvent(new TestGarbageCollectionEvent(), 180L);
            emitCheckoutSocketHotPath(70L);

            Thread.sleep(320L);

            for (int i = 0; i < 4; i++) {
                emitCheckoutAllocationInTlab(1_800_000L, 2_300_000L, String.class, "checkout-cache");
            }
            emitCheckoutAllocationSample(1_200_000L, String.class, "checkout-cache");
            emitPricingAllocationOutsideTlab(480_000L, byte[].class, "pricing-buffer");

            Thread.sleep(320L);

            emitCheckoutOldObjectSample(
                1_450_000L,
                190_000L,
                96_000_000L,
                java.util.LinkedHashMap.class,
                5,
                "JNI Global",
                "Threads",
                "worker-thread cache",
                "checkout-session-cache"
            );
            emitCheckoutOldObjectSample(
                1_050_000L,
                160_000L,
                96_000_000L,
                java.util.LinkedHashMap.class,
                4,
                "JNI Global",
                "Threads",
                "worker-thread cache",
                "checkout-session-cache"
            );
            emitReportOldObjectSample(
                640_000L,
                105_000L,
                92_000_000L,
                byte[].class,
                2,
                "Code Cache",
                "Code",
                "export buffer",
                "report-export-buffer"
            );

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createComparisonBaselineRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            runInThread("report-worker", () -> {
                commitDurationEvent(new TestJavaMonitorBlockedEvent(), 70L);
                commitDurationEvent(new TestGarbageCollectionEvent(), 130L);
                for (int i = 0; i < 4; i++) {
                    emitReportExecutionSample();
                }
                emitReportAllocationOutsideTlab(220_000L, byte[].class, "baseline-export");
                emitReportOldObjectSample(
                    320_000L,
                    40_000L,
                    64_000_000L,
                    byte[].class,
                    1,
                    "Code Cache",
                    "Code",
                    "baseline-export",
                    "baseline-export-buffer"
                );
            });
            runInThread("checkout-worker", () -> {
                for (int i = 0; i < 2; i++) {
                    emitCheckoutExecutionSample();
                }
                emitCheckoutAllocationInTlab(450_000L, 600_000L, StringBuilder.class, "baseline-cache");
                emitCheckoutAllocationSample(240_000L, StringBuilder.class, "baseline-cache");
            });

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    public static Path createComparisonCurrentRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestObjectAllocationOutsideTLABEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            runInThread("checkout-worker", () -> {
                commitDurationEvent(new TestJavaMonitorBlockedEvent(), 150L);
                commitDurationEvent(new TestJavaMonitorBlockedEvent(), 120L);
                commitDurationEvent(new TestGarbageCollectionEvent(), 260L);
                commitDurationEvent(new TestGarbageCollectionEvent(), 180L);
                for (int i = 0; i < 7; i++) {
                    emitCheckoutExecutionSample();
                }
                for (int i = 0; i < 4; i++) {
                    emitCheckoutAllocationInTlab(1_100_000L, 1_500_000L, byte[].class, "current-cache");
                }
                emitCheckoutAllocationSample(950_000L, byte[].class, "current-cache");
                emitCheckoutOldObjectSample(
                    1_800_000L,
                    240_000L,
                    124_000_000L,
                    java.util.LinkedHashMap.class,
                    6,
                    "JNI Global",
                    "Threads",
                    "worker-thread cache",
                    "checkout-session-cache"
                );
                emitCheckoutOldObjectSample(
                    1_500_000L,
                    210_000L,
                    124_000_000L,
                    java.util.LinkedHashMap.class,
                    5,
                    "JNI Global",
                    "Threads",
                    "worker-thread cache",
                    "checkout-session-cache"
                );
            });
            runInThread("pricing-worker", () -> emitPricingAllocationOutsideTlab(700_000L, byte[].class, "current-buffer"));
            runInThread("report-worker", () -> {
                emitReportExecutionSample();
                emitReportOldObjectSample(
                    720_000L,
                    160_000L,
                    124_000_000L,
                    byte[].class,
                    4,
                    "JNI Global",
                    "Threads",
                    "report-cache",
                    "report-export-buffer"
                );
            });

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void runInThread(String threadName, ThrowingRunnable action) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        }, threadName);
        thread.start();
        thread.join();
        if (failure[0] instanceof Exception exception) {
            throw exception;
        }
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
    }

    private static void enable(Recording recording, Class<? extends Event> eventType) {
        enable(recording, eventType, Duration.ZERO);
    }

    private static void enable(Recording recording, Class<? extends Event> eventType, Duration threshold) {
        EventSettings eventSettings = recording.enable(eventType);
        eventSettings.withThreshold(threshold != null ? threshold : Duration.ZERO);
    }

    private static void enable(Recording recording, String eventName) {
        EventSettings eventSettings = recording.enable(eventName);
        eventSettings.withThreshold(java.time.Duration.ZERO);
    }

    private static void commitDurationEvent(Event event, long sleepMillis) throws InterruptedException {
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitCheckoutExecutionSample() {
        checkoutController();
    }

    private static void checkoutController() {
        checkoutService();
    }

    private static void checkoutService() {
        new TestExecutionSampleEvent().commit();
    }

    private static void emitReportExecutionSample() {
        reportController();
    }

    private static void reportController() {
        reportService();
    }

    private static void reportService() {
        new TestExecutionSampleEvent().commit();
    }

    private static void emitCheckoutWaitPath(long sleepMillis) throws InterruptedException {
        TestThreadParkEvent event = new TestThreadParkEvent();
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitCheckoutSocketHotPath(long sleepMillis) throws InterruptedException {
        TestSocketReadEvent event = new TestSocketReadEvent();
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitReportFileHotPath(long sleepMillis) throws InterruptedException {
        TestFileWriteEvent event = new TestFileWriteEvent();
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitCheckoutMonitorWaitPath(long sleepMillis, String reason) throws InterruptedException {
        checkoutMonitorWaitController(sleepMillis, reason);
    }

    private static void checkoutMonitorWaitController(long sleepMillis, String reason) throws InterruptedException {
        checkoutMonitorWaitService(sleepMillis, reason);
    }

    private static void checkoutMonitorWaitService(long sleepMillis, String reason) throws InterruptedException {
        emitMonitorWaitEvent(sleepMillis, reason, "java.util.concurrent.ArrayBlockingQueue");
    }

    private static void emitReportMonitorWaitPath(long sleepMillis, String reason) throws InterruptedException {
        reportMonitorWaitController(sleepMillis, reason);
    }

    private static void reportMonitorWaitController(long sleepMillis, String reason) throws InterruptedException {
        reportMonitorWaitService(sleepMillis, reason);
    }

    private static void reportMonitorWaitService(long sleepMillis, String reason) throws InterruptedException {
        emitMonitorWaitEvent(sleepMillis, reason, "java.util.concurrent.LinkedBlockingQueue");
    }

    private static void emitMonitorWaitEvent(long sleepMillis, String reason, String monitorClass) throws InterruptedException {
        TestJavaMonitorWaitEvent event = new TestJavaMonitorWaitEvent();
        event.reason = reason;
        event.monitorClass = monitorClass;
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitDirectBufferExecutionSample() {
        directBufferController();
    }

    private static void directBufferController() {
        directBufferService();
    }

    private static void directBufferService() {
        new TestExecutionSampleEvent().commit();
    }

    private static void emitQueueBacklogEvent(
        String service,
        String region,
        long backlog,
        boolean saturated,
        long sleepMillis
    ) throws InterruptedException {
        TestQueueBacklogEvent event = new TestQueueBacklogEvent();
        event.service = service;
        event.region = region;
        event.backlog = backlog;
        event.saturated = saturated;
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitCheckoutGeneratedClassDefine(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        checkoutClassLoadingController(className, loaderName, metadataBytes, reason);
    }

    private static void checkoutClassLoadingController(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        checkoutClassLoadingService(className, loaderName, metadataBytes, reason);
    }

    private static void checkoutClassLoadingService(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        emitClassDefine(className, loaderName, metadataBytes, reason);
    }

    private static void emitReportGeneratedClassDefine(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        reportClassLoadingController(className, loaderName, metadataBytes, reason);
    }

    private static void reportClassLoadingController(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        reportClassLoadingService(className, loaderName, metadataBytes, reason);
    }

    private static void reportClassLoadingService(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        emitClassDefine(className, loaderName, metadataBytes, reason);
    }

    private static void emitClassDefine(
        String className,
        String loaderName,
        long metadataBytes,
        String reason
    ) {
        TestClassDefineEvent event = new TestClassDefineEvent();
        event.className = className;
        event.loaderName = loaderName;
        event.metadataBytes = metadataBytes;
        event.reason = reason;
        event.commit();
    }

    private static void emitCompilationEvent(
        String compiledMethod,
        String compiler,
        String compileLevel,
        long compileQueueSize,
        long codeCacheUsedBytes,
        long codeCacheFreeBytes,
        long codeCacheSizeBytes,
        long sleepMillis
    ) throws InterruptedException {
        TestCompilationEvent event = new TestCompilationEvent();
        event.compiledMethod = compiledMethod;
        event.compiler = compiler;
        event.compileLevel = compileLevel;
        event.compileQueueSize = compileQueueSize;
        event.codeCacheUsedBytes = codeCacheUsedBytes;
        event.codeCacheFreeBytes = codeCacheFreeBytes;
        event.codeCacheSizeBytes = codeCacheSizeBytes;
        event.begin();
        Thread.sleep(sleepMillis);
        event.end();
        event.commit();
    }

    private static void emitCodeCacheFullEvent(
        String compiler,
        long codeCacheUsedBytes,
        long codeCacheFreeBytes,
        long codeCacheSizeBytes,
        long compileQueueSize,
        String reason
    ) {
        TestCodeCacheFullEvent event = new TestCodeCacheFullEvent();
        event.compiler = compiler;
        event.compilerDisabled = true;
        event.codeCacheUsedBytes = codeCacheUsedBytes;
        event.codeCacheFreeBytes = codeCacheFreeBytes;
        event.codeCacheSizeBytes = codeCacheSizeBytes;
        event.compileQueueSize = compileQueueSize;
        event.reason = reason;
        event.commit();
    }

    private static void emitCpuLoadEvent(double machineTotal, double jvmUser, double jvmSystem) {
        TestCPULoadEvent event = new TestCPULoadEvent();
        event.machineTotal = machineTotal;
        event.jvmUser = jvmUser;
        event.jvmSystem = jvmSystem;
        event.commit();
    }

    private static void emitThreadCpuLoadEvent(double user, double system) {
        TestThreadCPULoadEvent event = new TestThreadCPULoadEvent();
        event.user = user;
        event.system = system;
        event.sampledThreadName = Thread.currentThread().getName();
        event.commit();
    }

    private static void emitVirtualThreadSubmitFailedEvent(
        String reason,
        long queuedTaskCount,
        long availableParallelism
    ) {
        TestVirtualThreadSubmitFailedEvent event = new TestVirtualThreadSubmitFailedEvent();
        event.reason = reason;
        event.queuedTaskCount = queuedTaskCount;
        event.availableParallelism = availableParallelism;
        event.commit();
    }

    private static void emitBaselineAllocationEvent(
        Class<?> objectClass,
        long allocationSize,
        long tlabSize,
        String allocator
    ) {
        TestObjectAllocationInNewTLABEvent event = new TestObjectAllocationInNewTLABEvent();
        event.objectClass = objectClass;
        event.allocationSize = allocationSize;
        event.tlabSize = tlabSize;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitCheckoutAllocationInTlab(
        long allocationSize,
        long tlabSize,
        Class<?> objectClass,
        String allocator
    ) {
        checkoutAllocationController(allocationSize, tlabSize, objectClass, allocator);
    }

    private static void checkoutAllocationController(
        long allocationSize,
        long tlabSize,
        Class<?> objectClass,
        String allocator
    ) {
        checkoutAllocationService(allocationSize, tlabSize, objectClass, allocator);
    }

    private static void checkoutAllocationService(
        long allocationSize,
        long tlabSize,
        Class<?> objectClass,
        String allocator
    ) {
        TestObjectAllocationInNewTLABEvent event = new TestObjectAllocationInNewTLABEvent();
        event.objectClass = objectClass;
        event.allocationSize = allocationSize;
        event.tlabSize = tlabSize;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitCheckoutAllocationSample(long weight, Class<?> objectClass, String allocator) {
        checkoutAllocationSampleController(weight, objectClass, allocator);
    }

    private static void checkoutAllocationSampleController(long weight, Class<?> objectClass, String allocator) {
        checkoutAllocationSampleService(weight, objectClass, allocator);
    }

    private static void checkoutAllocationSampleService(long weight, Class<?> objectClass, String allocator) {
        TestObjectAllocationSampleEvent event = new TestObjectAllocationSampleEvent();
        event.objectClass = objectClass;
        event.weight = weight;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitPricingAllocationOutsideTlab(long allocationSize, Class<?> objectClass, String allocator) {
        pricingAllocationController(allocationSize, objectClass, allocator);
    }

    private static void pricingAllocationController(long allocationSize, Class<?> objectClass, String allocator) {
        pricingAllocationService(allocationSize, objectClass, allocator);
    }

    private static void pricingAllocationService(long allocationSize, Class<?> objectClass, String allocator) {
        TestObjectAllocationOutsideTLABEvent event = new TestObjectAllocationOutsideTLABEvent();
        event.objectClass = objectClass;
        event.allocationSize = allocationSize;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitDirectBufferAllocationOutsideTlab(long allocationSize, Class<?> objectClass, String allocator) {
        directBufferAllocationController(allocationSize, objectClass, allocator);
    }

    private static void directBufferAllocationController(long allocationSize, Class<?> objectClass, String allocator) {
        directBufferAllocationService(allocationSize, objectClass, allocator);
    }

    private static void directBufferAllocationService(long allocationSize, Class<?> objectClass, String allocator) {
        TestObjectAllocationOutsideTLABEvent event = new TestObjectAllocationOutsideTLABEvent();
        event.objectClass = objectClass;
        event.allocationSize = allocationSize;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitReportAllocationOutsideTlab(long allocationSize, Class<?> objectClass, String allocator) {
        reportAllocationController(allocationSize, objectClass, allocator);
    }

    private static void reportAllocationController(long allocationSize, Class<?> objectClass, String allocator) {
        reportAllocationService(allocationSize, objectClass, allocator);
    }

    private static void reportAllocationService(long allocationSize, Class<?> objectClass, String allocator) {
        TestObjectAllocationOutsideTLABEvent event = new TestObjectAllocationOutsideTLABEvent();
        event.objectClass = objectClass;
        event.allocationSize = allocationSize;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitReportAllocationSample(long weight, Class<?> objectClass, String allocator) {
        reportAllocationSampleController(weight, objectClass, allocator);
    }

    private static void reportAllocationSampleController(long weight, Class<?> objectClass, String allocator) {
        reportAllocationSampleService(weight, objectClass, allocator);
    }

    private static void reportAllocationSampleService(long weight, Class<?> objectClass, String allocator) {
        TestObjectAllocationSampleEvent event = new TestObjectAllocationSampleEvent();
        event.objectClass = objectClass;
        event.weight = weight;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitDirectBufferAllocationSample(long weight, Class<?> objectClass, String allocator) {
        directBufferAllocationSampleController(weight, objectClass, allocator);
    }

    private static void directBufferAllocationSampleController(long weight, Class<?> objectClass, String allocator) {
        directBufferAllocationSampleService(weight, objectClass, allocator);
    }

    private static void directBufferAllocationSampleService(long weight, Class<?> objectClass, String allocator) {
        TestObjectAllocationSampleEvent event = new TestObjectAllocationSampleEvent();
        event.objectClass = objectClass;
        event.weight = weight;
        event.allocator = allocator;
        event.commit();
    }

    private static void emitCheckoutOldObjectSample(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        checkoutRetentionController(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void checkoutRetentionController(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        checkoutRetentionService(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void checkoutRetentionService(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        emitOldObjectSample(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void emitReportOldObjectSample(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        reportRetentionController(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void reportRetentionController(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        reportRetentionService(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void reportRetentionService(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        emitOldObjectSample(
            objectSize,
            objectAgeMs,
            lastKnownHeapUsage,
            objectClass,
            referenceDepth,
            rootType,
            rootSystem,
            rootDescription,
            description
        );
    }

    private static void emitOldObjectSample(
        long objectSize,
        long objectAgeMs,
        long lastKnownHeapUsage,
        Class<?> objectClass,
        int referenceDepth,
        String rootType,
        String rootSystem,
        String rootDescription,
        String description
    ) {
        TestOldObjectSampleEvent event = new TestOldObjectSampleEvent();
        event.allocationTime = System.currentTimeMillis() - objectAgeMs;
        event.objectSize = objectSize;
        event.objectAge = objectAgeMs;
        event.lastKnownHeapUsage = lastKnownHeapUsage;
        event.objectClass = objectClass;
        event.arrayElements = objectClass != null && objectClass.isArray() ? Math.max(1, (int) Math.min(Integer.MAX_VALUE, objectSize / 64L)) : Integer.MIN_VALUE;
        event.rootType = rootType;
        event.rootSystem = rootSystem;
        event.rootDescription = rootDescription;
        event.referenceDepth = referenceDepth;
        event.description = description;
        event.commit();
    }

    @Name("com.javaassistant.test.JavaMonitorBlocked")
    @Label("Java Monitor Blocked")
    public static class TestJavaMonitorBlockedEvent extends Event {
    }

    @Name("com.javaassistant.test.GarbageCollection")
    @Label("Garbage Collection")
    public static class TestGarbageCollectionEvent extends Event {
    }

    @Name("com.javaassistant.test.JavaMonitorWait")
    @Label("Java Monitor Wait")
    public static class TestJavaMonitorWaitEvent extends Event {
        @Label("Reason")
        String reason;

        @Label("Monitor Class")
        String monitorClass;
    }

    @Name("com.javaassistant.test.ExecutionSample")
    @Label("Execution Sample")
    public static class TestExecutionSampleEvent extends Event {
    }

    @Name("com.javaassistant.test.ObjectAllocationInNewTLAB")
    @Label("Object Allocation In New TLAB")
    public static class TestObjectAllocationInNewTLABEvent extends Event {
        @Label("Object Class")
        Class<?> objectClass;

        @Label("Allocation Size")
        long allocationSize;

        @Label("TLAB Size")
        long tlabSize;

        @Label("Allocator")
        String allocator;
    }

    @Name("com.javaassistant.test.ObjectAllocationOutsideTLAB")
    @Label("Object Allocation Outside TLAB")
    public static class TestObjectAllocationOutsideTLABEvent extends Event {
        @Label("Object Class")
        Class<?> objectClass;

        @Label("Allocation Size")
        long allocationSize;

        @Label("Allocator")
        String allocator;
    }

    @Name("com.javaassistant.test.ObjectAllocationSample")
    @Label("Object Allocation Sample")
    public static class TestObjectAllocationSampleEvent extends Event {
        @Label("Object Class")
        Class<?> objectClass;

        @Label("Weight")
        long weight;

        @Label("Allocator")
        String allocator;
    }

    @Name("com.javaassistant.test.ThreadPark")
    @Label("Thread Park")
    public static class TestThreadParkEvent extends Event {
    }

    @Name("com.javaassistant.test.ClassDefine")
    @Label("Class Define")
    public static class TestClassDefineEvent extends Event {
        @Label("Class Name")
        String className;

        @Label("Class Loader")
        String loaderName;

        @Label("Metadata Bytes")
        long metadataBytes;

        @Label("Reason")
        String reason;
    }

    @Name("com.javaassistant.test.Compilation")
    @Label("Compilation")
    public static class TestCompilationEvent extends Event {
        @Label("Compiled Method")
        String compiledMethod;

        @Label("Compiler")
        String compiler;

        @Label("Compile Level")
        String compileLevel;

        @Label("Compile Queue Size")
        long compileQueueSize;

        @Label("Code Cache Used Bytes")
        long codeCacheUsedBytes;

        @Label("Code Cache Free Bytes")
        long codeCacheFreeBytes;

        @Label("Code Cache Size Bytes")
        long codeCacheSizeBytes;
    }

    @Name("com.javaassistant.test.CodeCacheFull")
    @Label("Code Cache Full")
    public static class TestCodeCacheFullEvent extends Event {
        @Label("Compiler")
        String compiler;

        @Label("Compiler Disabled")
        boolean compilerDisabled;

        @Label("Code Cache Used Bytes")
        long codeCacheUsedBytes;

        @Label("Code Cache Free Bytes")
        long codeCacheFreeBytes;

        @Label("Code Cache Size Bytes")
        long codeCacheSizeBytes;

        @Label("Compile Queue Size")
        long compileQueueSize;

        @Label("Reason")
        String reason;
    }

    @Name("com.javaassistant.test.CPULoad")
    @Label("CPU Load")
    public static class TestCPULoadEvent extends Event {
        @Label("JVM User")
        double jvmUser;

        @Label("JVM System")
        double jvmSystem;

        @Label("Machine Total")
        double machineTotal;
    }

    @Name("com.javaassistant.test.ThreadCPULoad")
    @Label("Thread CPU Load")
    public static class TestThreadCPULoadEvent extends Event {
        @Label("User")
        double user;

        @Label("System")
        double system;

        @Label("Sampled Thread")
        String sampledThreadName;
    }

    @Name("com.javaassistant.test.SocketRead")
    @Label("Socket Read")
    public static class TestSocketReadEvent extends Event {
    }

    @Name("com.javaassistant.test.FileWrite")
    @Label("File Write")
    public static class TestFileWriteEvent extends Event {
    }

    @Name("com.javaassistant.test.JavaExceptionThrow")
    @Label("Java Exception Throw")
    public static class TestJavaExceptionThrowEvent extends Event {
    }

    @Name("com.javaassistant.test.SafepointPause")
    @Label("Safepoint Pause")
    public static class TestSafepointPauseEvent extends Event {
    }

    @Name("com.javaassistant.test.OldObjectSample")
    @Label("Old Object Sample")
    public static class TestOldObjectSampleEvent extends Event {
        @Label("Allocation Time")
        @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
        long allocationTime;

        @Label("Object Size")
        long objectSize;

        @Label("Object Age")
        @Timespan(Timespan.MILLISECONDS)
        long objectAge;

        @Label("Last Known Heap Usage")
        long lastKnownHeapUsage;

        @Label("Object Class")
        Class<?> objectClass;

        @Label("Array Elements")
        int arrayElements = Integer.MIN_VALUE;

        @Label("Root Type")
        String rootType;

        @Label("Root System")
        String rootSystem;

        @Label("Root Description")
        String rootDescription;

        @Label("Reference Depth")
        int referenceDepth;

        @Label("Object Description")
        String description;
    }

    @Name("com.javaassistant.test.QueueBacklog")
    @Label("Queue Backlog")
    public static class TestQueueBacklogEvent extends Event {
        @Label("Service")
        String service;

        @Label("Region")
        String region;

        @Label("Backlog")
        long backlog;

        @Label("Saturated")
        boolean saturated;
    }

    @Name("com.javaassistant.test.VirtualThreadPinned")
    @Label("Virtual Thread Pinned")
    public static class TestVirtualThreadPinnedEvent extends Event {
        @Label("Reason")
        String reason;
    }

    @Name("com.javaassistant.test.VirtualThreadSubmitFailed")
    @Label("Virtual Thread Submit Failed")
    public static class TestVirtualThreadSubmitFailedEvent extends Event {
        @Label("Reason")
        String reason;

        @Label("Queued Task Count")
        long queuedTaskCount;

        @Label("Available Parallelism")
        long availableParallelism;
    }
}
