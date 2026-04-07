package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.agents.CorrelationTools;
import com.javaassistant.agents.GCTools;
import com.javaassistant.agents.JfrTools;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.testsupport.JfrTestRecordingFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentToolRuntimeTest {

    private final ArtifactLoader loader = new ArtifactLoader();
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final JfrArtifactParser jfrParser = new JfrArtifactParser();
    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();

    @TempDir
    Path tempDir;

    @Test
    void recordsRetrievalAndFocusedComputationInvocations() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            new AgentToolRuntime.ToolBudget(6, 4, Integer.MAX_VALUE),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String retrieval = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=45"));
        String computation = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "pause-percentiles"));

        assertTrue(retrieval.contains("Artifact:"));
        assertTrue(retrieval.contains("Slice: GC event 45"));
        assertTrue(retrieval.contains("Source:"));
        assertFalse(retrieval.contains("Artifact type:"));
        assertFalse(retrieval.contains("Kind:"));
        assertFalse(retrieval.contains("Traceability:"));
        assertTrue(computation.contains("GC pause percentile summary"));
        assertFalse(computation.contains("extractedData."));
        assertEquals(2, session.toolInvocations().size());
        assertEquals("fetchGcContext", session.toolInvocations().get(0).toolName());
        assertEquals("RETRIEVAL", session.toolInvocations().get(0).toolFamily());
        assertEquals(ArtifactType.GC_LOG, session.toolInvocations().get(0).artifactType());
        assertEquals("computeGcView", session.toolInvocations().get(1).toolName());
        assertEquals("COMPUTATION", session.toolInvocations().get(1).toolFamily());
    }

    @Test
    void enforcesAnalyzeRetrievalBudget() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            new AgentToolRuntime.ToolBudget(6, 4, Integer.MAX_VALUE),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=24"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=45"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "pattern=Evacuation Failure"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "lines=640-650"));
        String fifthResponse = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "section=summary"));

        assertTrue(fifthResponse.contains("Tool budget exhausted"));
        assertEquals(5, session.toolInvocations().size());
        assertEquals("retrieval-budget-exhausted", session.toolInvocations().getLast().sliceId());
    }

    @Test
    void unresolvedCorrelationArtifactRequestsListAvailableReferences() throws Exception {
        var firstArtifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var secondArtifact = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var firstContext = indexer.index(firstArtifact, gcParser.parse(firstArtifact));
        var secondContext = indexer.index(secondArtifact, gcParser.parse(secondArtifact));

        LinkedHashMap<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        contexts.put("artifact-1", firstContext);
        contexts.put("artifact-2", secondContext);
        contexts.put("primary", firstContext);

        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "correlation",
            AgentToolRuntime.ToolBudget.correlate(),
            contexts
        );

        CorrelationTools tools = new CorrelationTools();
        String response = AgentToolRuntime.withSession(
            session,
            () -> tools.computeRelevantArtifactView("artifact-9", "dominant-window-summary")
        );

        assertTrue(response.contains("No matching artifact context was available."));
        assertTrue(response.contains("Available artifact references:"));
        assertTrue(response.contains("artifact-1 -> GC_LOG"));
        assertTrue(response.contains("artifact-2 -> GC_LOG"));
    }

    @Test
    void exposesRicherGcCauseAndFailureComputationViews() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String causeDistribution = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "cause-distribution"));
        String recoverySummary = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "recovery-summary"));
        String g1CycleProgression = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "g1-cycle-progression"));
        String failureSummary = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "failure-summary"));

        assertTrue(causeDistribution.contains("Evacuation Failure"));
        assertTrue(causeDistribution.contains("G1 Compaction Pause"));
        assertTrue(causeDistribution.contains("dominantPauseCauseByTotalPauseMs"));
        assertTrue(causeDistribution.contains("causeTotalPauseMs"));
        assertTrue(recoverySummary.contains("GC recovery and headroom summary"));
        assertTrue(recoverySummary.contains("nearCapacityAfterGcCount"));
        assertTrue(recoverySummary.contains("averagePostGcOccupancyRatio"));
        assertTrue(g1CycleProgression.contains("G1 cycle-progression summary"));
        assertTrue(g1CycleProgression.contains("mixedPausesBeforeFirstFullGc"));
        assertTrue(g1CycleProgression.contains("lowReclaimHighRetentionFullGcCount"));
        assertTrue(failureSummary.contains("concurrentMarkAbortCount"));
        assertTrue(failureSummary.contains("fullCompactionAttemptCount"));
    }

    @Test
    void supportsTargetedGcCausePhaseAndGcIdTooling() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String causeContext = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "cause=G1 Compaction Pause"));
        String phaseContext = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "phaseKind=CONCURRENT"));
        String causeView = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "cause=G1 Compaction Pause"));
        String gcIdView = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "gcId=45"));

        assertTrue(causeContext.contains("G1 Compaction Pause"));
        assertTrue(phaseContext.contains("Concurrent"));
        assertTrue(causeView.contains("requestedCause"));
        assertTrue(causeView.contains("matchingPauseCount"));
        assertTrue(gcIdView.contains("gcId"));
        assertTrue(gcIdView.contains("45"));
    }

    @Test
    void supportsGcTimeWindowAndStreakFocusedViews() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String timeWindowView = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "start=6.6s,end=7.35s"));
        String streakView = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "streak=distress"));

        assertTrue(timeWindowView.contains("GC time-window summary"));
        assertTrue(timeWindowView.contains("resolvedStartElapsedSeconds"));
        assertTrue(timeWindowView.contains("resolvedEndElapsedSeconds"));
        assertTrue(timeWindowView.contains("fullGcCount"));
        assertTrue(streakView.contains("GC distress-cluster summary"));
        assertTrue(streakView.contains("distressEventCount"));
        assertTrue(streakView.contains("failureSignalCount"));
    }

    @Test
    void supportsCollectorPressureComputationViewsAcrossCollectors() throws Exception {
        GCTools tools = new GCTools();

        var g1Artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var g1Context = indexer.index(g1Artifact, gcParser.parse(g1Artifact));
        AgentToolRuntime.Session g1Session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(g1Context)
        );
        String g1Pressure = AgentToolRuntime.withSession(g1Session, () -> tools.computeGcView("", "collector-pressure-summary"));

        assertTrue(g1Pressure.contains("GC collector-focused pressure summary"));
        assertTrue(g1Pressure.contains("lowReclaimHighRetentionFullGcCount"));
        assertTrue(g1Pressure.contains("maxNearCapacityPauseStreak"));

        var legacyCmsArtifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-cms-pressure/gc_legacy_cms_pressure_large.log")
        );
        var legacyCmsContext = indexer.index(legacyCmsArtifact, gcParser.parse(legacyCmsArtifact));
        AgentToolRuntime.Session cmsSession = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(legacyCmsContext)
        );
        String cmsPressure = AgentToolRuntime.withSession(cmsSession, () -> tools.computeGcView("", "cms-pressure-summary"));

        assertTrue(cmsPressure.contains("concurrentModeFailureCount"));
        assertTrue(cmsPressure.contains("maxConcurrentModeFailureStreak"));
        assertTrue(cmsPressure.contains("longestConcurrentPhaseMs"));

        var legacySerialArtifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-serial-pressure/gc_legacy_serial_pressure_large.log")
        );
        var legacySerialContext = indexer.index(legacySerialArtifact, gcParser.parse(legacySerialArtifact));
        AgentToolRuntime.Session serialSession = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(legacySerialContext)
        );
        String serialPressure = AgentToolRuntime.withSession(serialSession, () -> tools.computeGcView("", "serial-pressure-summary"));

        assertTrue(serialPressure.contains("maxFullGcStreak"));
        assertTrue(serialPressure.contains("averageFullPostGcOccupancyRatio"));
        assertTrue(serialPressure.contains("dominantPauseCauseByTotalPauseMs"));
    }

    @Test
    void supportsLegacyGcWindowAndStreakToolingAcrossCollectors() throws Exception {
        GCTools tools = new GCTools();

        var legacyG1Artifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log")
        );
        var legacyG1Context = indexer.index(legacyG1Artifact, gcParser.parse(legacyG1Artifact));
        AgentToolRuntime.Session g1Session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(legacyG1Context)
        );
        String legacyG1Window = AgentToolRuntime.withSession(g1Session, () -> tools.fetchGcContext("", "start=24s,end=32.6s"));
        String legacyG1Streak = AgentToolRuntime.withSession(g1Session, () -> tools.computeGcView("", "streak=distress"));

        assertTrue(legacyG1Window.contains("to-space exhausted"));
        assertTrue(legacyG1Window.contains("G1 Compaction Pause"));
        assertTrue(legacyG1Streak.contains("GC distress-cluster summary"));
        assertTrue(legacyG1Streak.contains("distressEventCount"));
        assertTrue(legacyG1Streak.contains("fullGcCount"));

        var legacyCmsArtifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-cms-pressure/gc_legacy_cms_pressure_large.log")
        );
        var legacyCmsContext = indexer.index(legacyCmsArtifact, gcParser.parse(legacyCmsArtifact));
        AgentToolRuntime.Session cmsSession = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(legacyCmsContext)
        );
        String legacyCmsWindow = AgentToolRuntime.withSession(cmsSession, () -> tools.fetchGcContext("", "start=24.0s,end=24.9s"));
        String legacyCmsStreak = AgentToolRuntime.withSession(cmsSession, () -> tools.computeGcView("", "streak=failure"));

        assertTrue(legacyCmsWindow.contains("CMS-concurrent-mark"));
        assertTrue(legacyCmsWindow.contains("concurrent mode failure"));
        assertTrue(legacyCmsStreak.contains("GC failure-signal streak summary"));
        assertTrue(legacyCmsStreak.contains("signalTypeCounts"));
        assertTrue(legacyCmsStreak.contains("CONCURRENT_MODE_FAILURE"));

        var legacySerialArtifact = loader.load(
            Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-serial-pressure/gc_legacy_serial_pressure_large.log")
        );
        var legacySerialContext = indexer.index(legacySerialArtifact, gcParser.parse(legacySerialArtifact));
        AgentToolRuntime.Session serialSession = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(legacySerialContext)
        );
        String legacySerialWindow = AgentToolRuntime.withSession(serialSession, () -> tools.fetchGcContext("", "start=155s,end=161s"));
        String legacySerialStreak = AgentToolRuntime.withSession(serialSession, () -> tools.computeGcView("", "streak=full-gc"));

        assertTrue(legacySerialWindow.contains("Allocation Failure"));
        assertTrue(legacySerialStreak.contains("GC full-GC streak summary"));
        assertTrue(legacySerialStreak.contains("streakEventCount"));
        assertTrue(legacySerialStreak.contains("161"));
    }

    @Test
    void supportsCompareAwareGcIncidentRetrievalAndDominantWindowComputation() throws Exception {
        GCTools tools = new GCTools();

        var baselineArtifact = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var baselineContext = indexer.index(baselineArtifact, gcParser.parse(baselineArtifact));
        var currentArtifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var currentContext = indexer.index(currentArtifact, gcParser.parse(currentArtifact));

        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "comparison",
            AgentToolRuntime.ToolBudget.compare(),
            comparisonSessionContexts(baselineContext, currentContext)
        );

        String currentIncident = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("current", "incident=dominant-pressure"));
        String currentWindow = AgentToolRuntime.withSession(session, () -> tools.computeGcView("current", "dominant-window-summary"));
        String baselineWindow = AgentToolRuntime.withSession(session, () -> tools.computeGcView("baseline", "dominant-window-summary"));

        assertTrue(currentIncident.contains("Dominant G1 distress window"));
        assertTrue(currentIncident.contains("Evacuation Failure"));
        assertTrue(currentIncident.contains("G1 Compaction Pause"));
        assertTrue(currentWindow.contains("Dominant G1 distress window summary"));
        assertTrue(currentWindow.contains("fullGcCount"));
        assertTrue(currentWindow.contains("peakPostGcOccupancyRatio"));
        assertTrue(baselineWindow.contains("Dominant G1 pressure window summary"));
        assertTrue(baselineWindow.contains("pauseEventCount"));
        assertTrue(baselineWindow.contains("averagePostGcOccupancyRatio"));
        assertEquals(3, session.toolInvocations().size());
        assertEquals(currentArtifact.metadata().sourcePath(), session.toolInvocations().get(0).artifactPath());
        assertEquals("incident=dominant-pressure", session.toolInvocations().get(0).request());
        assertEquals(currentArtifact.metadata().sourcePath(), session.toolInvocations().get(1).artifactPath());
        assertEquals("dominant-window-summary", session.toolInvocations().get(1).request());
        assertEquals(baselineArtifact.metadata().sourcePath(), session.toolInvocations().get(2).artifactPath());
        assertEquals("dominant-window-summary", session.toolInvocations().get(2).request());
    }

    @Test
    void supportsCompareAwareGcIncidentRetrievalForLegacyG1CurrentLogs() throws Exception {
        GCTools tools = new GCTools();

        var baselineArtifact = loader.load(Path.of("src/test/resources/reference-incidents/compare-gc-regression/gc_baseline_small.log"));
        var baselineContext = indexer.index(baselineArtifact, gcParser.parse(baselineArtifact));
        var currentArtifact = loader.load(Path.of("src/test/resources/reference-incidents/analyze-gc-legacy-g1-pressure/gc_legacy_g1_pressure_large.log"));
        var currentContext = indexer.index(currentArtifact, gcParser.parse(currentArtifact));

        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "comparison",
            AgentToolRuntime.ToolBudget.compare(),
            comparisonSessionContexts(baselineContext, currentContext)
        );

        String currentIncident = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("current", "incident=dominant-pressure"));
        String currentWindow = AgentToolRuntime.withSession(session, () -> tools.computeGcView("current", "dominant-window-summary"));
        String baselineWindow = AgentToolRuntime.withSession(session, () -> tools.computeGcView("baseline", "dominant-window-summary"));

        assertTrue(currentIncident.contains("Dominant G1 distress window"));
        assertTrue(currentIncident.contains("to-space exhausted"));
        assertTrue(currentIncident.contains("G1 Compaction Pause"));
        assertTrue(currentWindow.contains("Dominant G1 distress window summary"));
        assertTrue(currentWindow.contains("resolvedStartElapsedSeconds"));
        assertTrue(currentWindow.contains("fullGcCount"));
        assertTrue(baselineWindow.contains("Dominant G1 pressure window summary"));
        assertTrue(baselineWindow.contains("pauseEventCount"));
        assertTrue(baselineWindow.contains("averagePostGcOccupancyRatio"));
        assertEquals(3, session.toolInvocations().size());
        assertEquals(currentArtifact.metadata().sourcePath(), session.toolInvocations().get(0).artifactPath());
        assertEquals("incident=dominant-pressure", session.toolInvocations().get(0).request());
        assertEquals(currentArtifact.metadata().sourcePath(), session.toolInvocations().get(1).artifactPath());
        assertEquals("dominant-window-summary", session.toolInvocations().get(1).request());
        assertEquals(baselineArtifact.metadata().sourcePath(), session.toolInvocations().get(2).artifactPath());
        assertEquals("dominant-window-summary", session.toolInvocations().get(2).request());
    }

    @Test
    void blankRetrievalProgressesAcrossTheSessionInsteadOfRepeatingTheFirstOmittedSlice() {
        var indexedContext = syntheticIndexedContext(320, 15);
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String first = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", ""));
        String second = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", ""));

        assertTrue(first.contains("[section13]"));
        assertTrue(second.contains("[section14]"));
    }

    @Test
    void supportsJfrIncidentAndChronologyComputationViews() throws Exception {
        var artifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-incident-window.jfr")));
        var indexedContext = indexer.index(artifact, jfrParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        JfrTools tools = new JfrTools();
        String incidentSummary = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "incident-window-summary"));
        String runtimeIncident = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "runtime-incident-summary"));
        String chronologySummary = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "chronology-summary"));
        String timeWindowSummary = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "start=0.200s,end=0.900s"));

        assertTrue(incidentSummary.contains("JFR incident-window summary"));
        assertTrue(incidentSummary.contains("primaryIncident"));
        assertTrue(runtimeIncident.contains("JFR runtime incident summary"));
        assertFalse(runtimeIncident.isBlank());
        assertTrue(runtimeIncident.contains("summaryLine") || runtimeIncident.contains("windowId"));
        assertTrue(runtimeIncident.contains("dominantSignals"));
        assertTrue(chronologySummary.contains("JFR chronology summary"));
        assertTrue(chronologySummary.contains("chronologyHighlights"));
        assertTrue(timeWindowSummary.contains("JFR time-window summary"));
        assertTrue(timeWindowSummary.contains("Runtime pressure window") || timeWindowSummary.contains("GC pauses"));
        assertTrue(timeWindowSummary.contains("chronologyHighlights") || timeWindowSummary.contains("GarbageCollection"));
        assertTrue(timeWindowSummary.contains("signalFamilies"));
        assertTrue(timeWindowSummary.contains("representativeEvents"));
    }

    @Test
    void supportsJfrFocusedHotspotAllocationAndRetentionComputationViews() throws Exception {
        var artifact = loader.load(JfrTestRecordingFactory.createIncidentWindowRecording(tempDir.resolve("jfr-focused-neighborhoods.jfr")));
        var indexedContext = indexer.index(artifact, jfrParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        JfrTools tools = new JfrTools();
        String hotspot = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "hotspot=checkoutService"));
        String allocation = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "allocationClass=java.lang.String"));
        String retained = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "oldObject=JNI Global"));

        assertTrue(hotspot.contains("JFR hotspot computation view"));
        assertTrue(hotspot.contains("matchingHotspotSummaries") || hotspot.contains("topStacks"));
        assertTrue(hotspot.contains("representativeEvents"));

        assertTrue(allocation.contains("JFR allocation-path computation view"));
        assertTrue(allocation.contains("matchingClasses"));
        assertTrue(allocation.contains("topMethods"));
        assertTrue(allocation.contains("representativeEvents"));

        assertTrue(retained.contains("JFR retained-object computation view"));
        assertTrue(retained.contains("matchingRoots"));
        assertTrue(retained.contains("maxReferenceDepth"));
        assertTrue(retained.contains("representativeEvents"));
    }

    @Test
    void supportsJfrEventFamilyAndThreadComputationViews() throws Exception {
        var artifact = loader.load(JfrTestRecordingFactory.createComparisonCurrentRecording(tempDir.resolve("jfr-thread-compute.jfr")));
        var indexedContext = indexer.index(artifact, jfrParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        JfrTools tools = new JfrTools();
        String eventFamily = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "eventType=gc"));
        String thread = AgentToolRuntime.withSession(session, () -> tools.computeJfrView("", "thread=checkout-worker"));

        assertTrue(eventFamily.contains("JFR event-family computation view"));
        assertTrue(eventFamily.contains("eventTypeQuery"));
        assertTrue(eventFamily.contains("representativeEvents"));
        assertTrue(eventFamily.contains("signalFamilies") || eventFamily.contains("matchingEventTypes"));

        assertTrue(thread.contains("JFR thread-focused computation view"));
        assertTrue(thread.contains("threadQuery"));
        assertTrue(thread.contains("signalFamilies"));
        assertTrue(thread.contains("representativeEvents"));
        assertTrue(thread.contains("checkout-worker"));
    }

    private Map<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> sessionContexts(
        com.javaassistant.context.IndexedArtifactDiagnosticContext indexedContext
    ) {
        LinkedHashMap<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        contexts.put("current", indexedContext);
        contexts.put("primary", indexedContext);
        return contexts;
    }

    private Map<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> comparisonSessionContexts(
        com.javaassistant.context.IndexedArtifactDiagnosticContext baselineContext,
        com.javaassistant.context.IndexedArtifactDiagnosticContext currentContext
    ) {
        LinkedHashMap<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        contexts.put("baseline", baselineContext);
        contexts.put("current", currentContext);
        contexts.put("primary", currentContext);
        return contexts;
    }

    private com.javaassistant.context.IndexedArtifactDiagnosticContext syntheticIndexedContext(int lineCount, int sectionCount) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            builder.append("synthetic-line-").append(lineNumber);
            if (lineNumber == lineCount) {
                builder.append(" late-clue");
            }
            if (lineNumber < lineCount) {
                builder.append('\n');
            }
        }
        InputArtifact artifact = new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/tmp/synthetic-gc.log",
                "synthetic-gc.log",
                builder.length(),
                LocalDateTime.of(2026, 4, 1, 12, 0),
                Map.of()
            ),
            builder.toString()
        );
        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        for (int index = 1; index <= sectionCount; index++) {
            extractedData.put("section" + index, Map.of("value", "v".repeat(180)));
        }
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.GC_LOG,
            artifact.metadata(),
            "synthetic-parser",
            extractedData,
            List.of(new Evidence(
                "late-clue",
                artifact.metadata().sourcePath(),
                "Late clue",
                "A late clue is present near the file tail.",
                "synthetic-line-320 late-clue",
                List.of(320),
                Map.of("lineNumber", 320)
            )),
            List.of()
        );
        return indexer.index(artifact, parsedArtifact);
    }
}
