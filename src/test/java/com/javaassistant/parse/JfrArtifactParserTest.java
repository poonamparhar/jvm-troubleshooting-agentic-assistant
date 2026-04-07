package com.javaassistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrArtifactParserTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final JfrArtifactParser parser = new JfrArtifactParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesRecordingMetadataAndSignalCoverage() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createContentionAndGcRecording(tempDir.resolve("recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> lockSummary = (Map<String, Object>) parsed.extractedData().get("lockSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> gcSummary = (Map<String, Object>) parsed.extractedData().get("gcSummary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observedEventTypes = (List<Map<String, Object>>) parsed.extractedData().get("observedEventTypes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topEventTypes = (List<Map<String, Object>>) parsed.extractedData().get("topEventTypes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declaredEventTypes = (List<Map<String, Object>>) parsed.extractedData().get("declaredEventTypes");

        assertEquals(Boolean.TRUE, coverage.get("lockEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("gcEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("allocationEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("executionSamplesPresent"));
        assertTrue(((Boolean) coverage.get("declaredEventMetadataPresent")));
        assertTrue(((Boolean) coverage.get("observedEventCatalogPresent")));
        assertTrue(((Number) summary.get("eventCount")).longValue() >= 5L);
        assertTrue(((Number) summary.get("durationMs")).longValue() >= 300L);
        assertEquals(2L, ((Number) lockSummary.get("eventCount")).longValue());
        assertEquals(1L, ((Number) gcSummary.get("eventCount")).longValue());
        assertTrue(observedEventTypes.size() >= topEventTypes.size());
        assertTrue(topEventTypes.stream().anyMatch(item -> String.valueOf(item.get("name")).contains("JavaMonitorBlocked")));
        assertTrue(declaredEventTypes.stream().anyMatch(item -> String.valueOf(item.get("name")).startsWith("jdk.")));
        assertTrue(declaredEventTypes.stream().anyMatch(item -> ((Number) item.get("fieldCount")).intValue() > 0));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-recording-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-lock-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-gc-summary")));
    }

    @Test
    void parsesDeeperRuntimeSignalsFromRecording() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createDeeperAnalyticsRecording(tempDir.resolve("deeper-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> threadParkSummary = (Map<String, Object>) parsed.extractedData().get("threadParkSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> ioSummary = (Map<String, Object>) parsed.extractedData().get("ioSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> exceptionSummary = (Map<String, Object>) parsed.extractedData().get("exceptionSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> safepointSummary = (Map<String, Object>) parsed.extractedData().get("safepointSummary");

        assertEquals(Boolean.TRUE, coverage.get("threadParkEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("ioEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("exceptionEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("safepointEventsPresent"));
        assertEquals(2L, ((Number) threadParkSummary.get("eventCount")).longValue());
        assertEquals(2L, ((Number) ioSummary.get("eventCount")).longValue());
        assertEquals(32L, ((Number) exceptionSummary.get("eventCount")).longValue());
        assertEquals(2L, ((Number) safepointSummary.get("eventCount")).longValue());
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-thread-park-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-io-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-exception-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-safepoint-summary")));
    }

    @Test
    void parsesExecutionAndRuntimeHotspotsFromStackBearingEvents() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createHotPathRecording(tempDir.resolve("hot-path-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> executionHotspotSummary = (Map<String, Object>) parsed.extractedData().get("executionHotspotSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeHotspotSummary = (Map<String, Object>) parsed.extractedData().get("runtimeHotspotSummary");

        assertEquals(Boolean.TRUE, coverage.get("executionHotspotsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("runtimeHotspotsPresent"));
        assertEquals(8L, ((Number) executionHotspotSummary.get("stackEventCount")).longValue());
        assertEquals("com.javaassistant.testsupport.JfrTestRecordingFactory.checkoutService", executionHotspotSummary.get("topMethod"));
        assertEquals(6L, ((Number) executionHotspotSummary.get("topMethodCount")).longValue());
        assertEquals(4L, ((Number) runtimeHotspotSummary.get("stackEventCount")).longValue());
        assertEquals("com.javaassistant.testsupport.JfrTestRecordingFactory.emitCheckoutWaitPath", runtimeHotspotSummary.get("topMethod"));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-execution-hotspots")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-runtime-hotspots")));
    }

    @Test
    void parsesAllocationFieldLevelAndHotPathAnalytics() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createAllocationPathRecording(tempDir.resolve("allocation-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> allocationFieldSummary = (Map<String, Object>) parsed.extractedData().get("allocationFieldSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> allocationHotspotSummary = (Map<String, Object>) parsed.extractedData().get("allocationHotspotSummary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observedFields = (List<Map<String, Object>>) allocationFieldSummary.get("observedFields");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topAllocatingClasses = (List<Map<String, Object>>) allocationFieldSummary.get("topAllocatingClasses");

        assertEquals(Boolean.TRUE, coverage.get("allocationEventsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("allocationFieldDetailsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("allocationHotspotsPresent"));
        assertEquals(10L, ((Number) allocationFieldSummary.get("eventCount")).longValue());
        assertEquals(10L, ((Number) allocationFieldSummary.get("fieldRichEventCount")).longValue());
        assertEquals(10L, ((Number) allocationFieldSummary.get("sizedEventCount")).longValue());
        assertEquals(7L, ((Number) allocationFieldSummary.get("topClassEventCount")).longValue());
        assertEquals("java.lang.String", allocationFieldSummary.get("topClass"));
        assertEquals(15_000_000L, ((Number) allocationFieldSummary.get("totalAllocatedBytes")).longValue());
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("objectClass")));
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("allocationSize")));
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("weight")));
        assertEquals("java.lang.String", topAllocatingClasses.getFirst().get("className"));
        assertEquals(10L, ((Number) allocationHotspotSummary.get("stackEventCount")).longValue());
        assertEquals("com.javaassistant.testsupport.JfrTestRecordingFactory.checkoutAllocationService", allocationHotspotSummary.get("topMethod"));
        assertEquals(6L, ((Number) allocationHotspotSummary.get("topMethodCount")).longValue());
        assertEquals(12_000_000L, ((Number) allocationHotspotSummary.get("topMethodAllocatedBytes")).longValue());
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-allocation-field-summary")));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-allocation-hotspots")));
    }

    @Test
    void parsesOldObjectFieldLevelAndDepthAnalytics() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createRetainedObjectRecording(tempDir.resolve("old-object-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> oldObjectFieldSummary = (Map<String, Object>) parsed.extractedData().get("oldObjectFieldSummary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) parsed.extractedData().get("timelineEvents");

        assertEquals(Boolean.TRUE, coverage.get("oldObjectSamplingPresent"));
        assertEquals(Boolean.TRUE, coverage.get("oldObjectFieldDetailsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("oldObjectRootDetailsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("oldObjectDepthDetailsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("timelineEventsPresent"));
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("eventCount")).longValue());
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("fieldRichEventCount")).longValue());
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("sizedEventCount")).longValue());
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("agedEventCount")).longValue());
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("rootedEventCount")).longValue());
        assertEquals(3L, ((Number) oldObjectFieldSummary.get("depthEventCount")).longValue());
        assertEquals(3_140_000L, ((Number) oldObjectFieldSummary.get("totalSampledObjectBytes")).longValue());
        assertEquals(180_000L, ((Number) oldObjectFieldSummary.get("maxObjectAgeMs")).longValue());
        assertEquals(5L, ((Number) oldObjectFieldSummary.get("maxReferenceDepth")).longValue());
        assertEquals("java.util.LinkedHashMap", oldObjectFieldSummary.get("topClass"));
        assertEquals(2L, ((Number) oldObjectFieldSummary.get("topClassEventCount")).longValue());
        assertEquals(2_500_000L, ((Number) oldObjectFieldSummary.get("topClassSampledObjectBytes")).longValue());
        assertEquals("JNI Global", oldObjectFieldSummary.get("topRootType"));
        assertEquals("Threads", oldObjectFieldSummary.get("topRootSystem"));
        assertTrue(timelineEvents.stream().anyMatch(event -> String.valueOf(event.get("rootDescription")).contains("worker-thread cache")));
        assertTrue(timelineEvents.stream().anyMatch(event -> ((Number) event.getOrDefault("objectAgeMs", 0L)).longValue() >= 95_000L));
        assertTrue(parsed.evidence().stream().anyMatch(evidence -> evidence.id().equals("jfr-old-object-field-summary")));
    }

    @Test
    void parsesGenericPerEventTypeDetailsForUnclassifiedEvents() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createGenericEventDetailRecording(tempDir.resolve("generic-event-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eventTypeDetails = (List<Map<String, Object>>) parsed.extractedData().get("eventTypeDetails");

        Map<String, Object> queueBacklogDetail = eventTypeDetails.stream()
            .filter(detail -> "com.javaassistant.test.QueueBacklog".equals(detail.get("name")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observedFields = (List<Map<String, Object>>) queueBacklogDetail.get("observedFields");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sampleEvents = (List<Map<String, Object>>) queueBacklogDetail.get("sampleEvents");

        assertEquals(Boolean.TRUE, coverage.get("genericEventDetailsPresent"));
        assertEquals("Queue Backlog", queueBacklogDetail.get("label"));
        assertEquals(3L, ((Number) queueBacklogDetail.get("eventCount")).longValue());
        assertTrue(((Number) queueBacklogDetail.get("maxDurationMs")).longValue() >= 30L);
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("service")));
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("region")));
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("backlog")));
        assertTrue(observedFields.stream().anyMatch(field -> field.get("field").equals("saturated")));
        assertTrue(sampleEvents.stream().anyMatch(sample -> String.valueOf(sample).contains("checkout")));
        assertTrue(sampleEvents.stream().anyMatch(sample -> String.valueOf(sample).contains("us-phoenix-1")));
        assertTrue(sampleEvents.stream().anyMatch(sample -> String.valueOf(sample).contains("185")));
    }

    @Test
    void parsesIncidentWindowsAndChronologyHighlights() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("incident-window-recording.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) parsed.extractedData().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> incidentWindowSummary = (Map<String, Object>) parsed.extractedData().get("incidentWindowSummary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incidentWindows = (List<Map<String, Object>>) parsed.extractedData().get("incidentWindows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chronologyHighlights = (List<Map<String, Object>>) parsed.extractedData().get("chronologyHighlights");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) parsed.extractedData().get("timelineEvents");

        Map<String, Object> runtimeWindow = incidentWindows.stream()
            .filter(window -> "runtime-pressure".equals(window.get("windowId")))
            .findFirst()
            .orElseThrow();
        Map<String, Object> allocationWindow = incidentWindows.stream()
            .filter(window -> "allocation-pressure".equals(window.get("windowId")))
            .findFirst()
            .orElseThrow();
        Map<String, Object> retainedWindow = incidentWindows.stream()
            .filter(window -> "retained-object-pressure".equals(window.get("windowId")))
            .findFirst()
            .orElseThrow();

        assertEquals(Boolean.TRUE, coverage.get("incidentWindowsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("incidentWindowSummaryPresent"));
        assertEquals(Boolean.TRUE, coverage.get("chronologyHighlightsPresent"));
        assertEquals(Boolean.TRUE, coverage.get("timelineEventsPresent"));
        assertTrue(incidentWindowSummary.containsKey("summaryLines"));
        assertTrue(String.valueOf(incidentWindowSummary).contains("Runtime incident"));
        assertTrue(String.valueOf(runtimeWindow).contains("GC pauses"));
        assertTrue(String.valueOf(runtimeWindow).contains("thread parks"));
        assertTrue(String.valueOf(allocationWindow).contains("java.lang.String"));
        assertTrue(String.valueOf(allocationWindow).contains("allocatedBytes"));
        assertTrue(String.valueOf(retainedWindow).contains("JNI Global"));
        assertTrue(String.valueOf(retainedWindow).contains("referenceDepth"));
        assertTrue(chronologyHighlights.stream().anyMatch(highlight ->
            String.valueOf(highlight).contains("Garbage Collection") || String.valueOf(highlight).contains("GarbageCollection")
        ));
        assertTrue(chronologyHighlights.stream().anyMatch(highlight ->
            String.valueOf(highlight).contains("Object Allocation") || String.valueOf(highlight).contains("ObjectAllocation")
        ));
        assertTrue(chronologyHighlights.stream().anyMatch(highlight ->
            String.valueOf(highlight).contains("Old Object Sample")
                || String.valueOf(highlight).contains("OldObjectSample")
                || String.valueOf(highlight).contains("JNI Global")
                || String.valueOf(highlight).contains("LinkedHashMap")
        ));
        assertTrue(timelineEvents.stream().anyMatch(event -> "executionSample".equals(event.get("signalFamily"))));
        assertTrue(timelineEvents.stream().anyMatch(event -> "allocation".equals(event.get("signalFamily")) && event.containsKey("allocatedBytes")));
        assertTrue(timelineEvents.stream().anyMatch(event -> "oldObject".equals(event.get("signalFamily")) && event.containsKey("sampledObjectBytes")));
    }

    @Test
    void extractsJvmRuntimeInfoWhenJvmInformationEventIsPresent() throws Exception {
        Path recordingPath = JfrTestRecordingFactory.createIncidentWindowRecordingWithJvmInfo(tempDir.resolve("incident-window-jvm-info.jfr"));
        var parsed = parser.parse(loader.load(recordingPath));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) parsed.extractedData().get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> jvmRuntimeInfo = (Map<String, Object>) parsed.extractedData().get("jvmRuntimeInfo");

        assertNotNull(jvmRuntimeInfo);
        assertEquals("jfr-analytics-v9", parsed.parserVersion());
        assertTrue(jvmRuntimeInfo.containsKey("jvmStartTime"));
        assertTrue(jvmRuntimeInfo.containsKey("jvmStartTimeEpochMs"));
        assertTrue(jvmRuntimeInfo.containsKey("recordingStartOffsetFromJvmStartMs"));

        Instant recordingStart = Instant.parse(summary.get("startTime").toString());
        Instant jvmStartTime = Instant.parse(jvmRuntimeInfo.get("jvmStartTime").toString());
        assertTrue(!jvmStartTime.isAfter(recordingStart));
        assertTrue(((Number) jvmRuntimeInfo.get("recordingStartOffsetFromJvmStartMs")).longValue() >= 0L);
    }
}
