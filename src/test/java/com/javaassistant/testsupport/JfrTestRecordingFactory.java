package com.javaassistant.testsupport;

import java.nio.file.Path;
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

    public static Path createComparisonBaselineRecording(Path recordingPath) throws Exception {
        try (Recording recording = new Recording()) {
            enable(recording, TestJavaMonitorBlockedEvent.class);
            enable(recording, TestGarbageCollectionEvent.class);
            enable(recording, TestExecutionSampleEvent.class);
            enable(recording, TestObjectAllocationInNewTLABEvent.class);
            enable(recording, TestObjectAllocationSampleEvent.class);
            enable(recording, TestOldObjectSampleEvent.class);

            recording.start();

            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 70L);
            commitDurationEvent(new TestGarbageCollectionEvent(), 130L);
            for (int i = 0; i < 4; i++) {
                emitReportExecutionSample();
            }
            for (int i = 0; i < 2; i++) {
                emitCheckoutExecutionSample();
            }
            emitCheckoutAllocationInTlab(450_000L, 600_000L, StringBuilder.class, "baseline-cache");
            emitCheckoutAllocationSample(240_000L, StringBuilder.class, "baseline-cache");
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

            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 150L);
            commitDurationEvent(new TestJavaMonitorBlockedEvent(), 120L);
            commitDurationEvent(new TestGarbageCollectionEvent(), 260L);
            commitDurationEvent(new TestGarbageCollectionEvent(), 180L);
            for (int i = 0; i < 7; i++) {
                emitCheckoutExecutionSample();
            }
            emitReportExecutionSample();
            for (int i = 0; i < 4; i++) {
                emitCheckoutAllocationInTlab(1_100_000L, 1_500_000L, byte[].class, "current-cache");
            }
            emitCheckoutAllocationSample(950_000L, byte[].class, "current-cache");
            emitPricingAllocationOutsideTlab(700_000L, byte[].class, "current-buffer");
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

            recording.stop();
            recording.dump(recordingPath);
        }
        return recordingPath;
    }

    private static void enable(Recording recording, Class<? extends Event> eventType) {
        EventSettings eventSettings = recording.enable(eventType);
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
}
