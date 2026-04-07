package com.javaassistant.correlate;

import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ParsedArtifact;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds neutral cross-artifact signal summaries that can support agent synthesis without handing agents conclusions.
 */
public class CrossArtifactSignalAnalyzer {

    private static final int MAX_EXACT_CLASSES = 5;
    private static final int MAX_FAMILY_CLASSES = 4;
    private static final int MAX_HOTSPOT_METHODS = 4;
    private static final int MAX_JOIN_THREADS = 6;
    private static final int MAX_JOIN_POOLS = 4;
    private static final int MAX_JOIN_LOCK_KEYS = 6;
    private static final int MAX_JOIN_FRAMES = 5;
    private static final int MAX_ALIGNMENTS = 10;
    private static final int MAX_TIMING_WINDOWS = 6;
    private static final int MAX_TIMING_ALIGNMENTS = 10;
    private static final double GC_STREAK_MAX_GAP_SECONDS = 2.0d;
    private static final int GC_STREAK_MAX_GAP_LINES = 160;
    private static final long HS_ERR_NATIVE_SEQUENCE_TOLERANCE_SECONDS = 30L * 60L;
    private static final long CONTAINER_OOM_SEQUENCE_TOLERANCE_SECONDS = 15L * 60L;
    private static final Pattern TRAILING_THREAD_INDEX_PATTERN = Pattern.compile("(.+?)-\\d+$");
    private static final Pattern THREAD_DUMP_METHOD_PATTERN = Pattern.compile("^at\\s+([^\\(\\s]+).*");
    private static final Pattern HEX_LOCK_KEY_PATTERN = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Set<ArtifactType> TIMED_COMPANION_ARTIFACT_TYPES = Set.of(
        ArtifactType.THREAD_DUMP,
        ArtifactType.HEAP_HISTOGRAM,
        ArtifactType.HS_ERR_LOG,
        ArtifactType.NMT,
        ArtifactType.PMAP,
        ArtifactType.CONTAINER_MEMORY,
        ArtifactType.OOM_SIGNAL
    );
    private static final List<String> EXPLICIT_CAPTURE_TIME_ATTRIBUTE_KEYS = List.of(
        "captureTime",
        "capturedAt",
        "snapshotTime",
        "recordedAt"
    );
    private static final List<String> EXPLICIT_CAPTURE_START_ATTRIBUTE_KEYS = List.of(
        "captureStartTime",
        "capturedStartTime",
        "snapshotStartTime"
    );
    private static final List<String> EXPLICIT_CAPTURE_END_ATTRIBUTE_KEYS = List.of(
        "captureEndTime",
        "capturedEndTime",
        "snapshotEndTime"
    );

    public CrossArtifactSignalSummary summarize(List<ParsedArtifact> parsedArtifacts) {
        if (parsedArtifacts == null || parsedArtifacts.isEmpty()) {
            return new CrossArtifactSignalSummary(List.of(), List.of(), new CrossArtifactTimingSummary(List.of(), List.of()));
        }

        List<ArtifactSignalProfile> profiles = parsedArtifacts.stream()
            .filter(parsedArtifact -> parsedArtifact != null)
            .map(this::profile)
            .toList();
        List<ArtifactTimingProfile> timingProfiles = parsedArtifacts.stream()
            .filter(parsedArtifact -> parsedArtifact != null)
            .map(this::timingProfile)
            .filter(ArtifactTimingProfile::hasUsableContent)
            .toList();

        ArtifactSignalProfile jfr = firstProfile(profiles, ArtifactType.JFR);
        ArtifactSignalProfile gcLog = firstProfile(profiles, ArtifactType.GC_LOG);
        ArtifactSignalProfile threadDump = firstProfile(profiles, ArtifactType.THREAD_DUMP);
        ArtifactSignalProfile heapHistogram = firstProfile(profiles, ArtifactType.HEAP_HISTOGRAM);
        ArtifactSignalProfile hsErrLog = firstProfile(profiles, ArtifactType.HS_ERR_LOG);
        ArtifactSignalProfile containerMemory = firstProfile(profiles, ArtifactType.CONTAINER_MEMORY);
        ArtifactSignalProfile oomSignal = firstProfile(profiles, ArtifactType.OOM_SIGNAL);
        ArtifactTimingProfile jfrTiming = firstTimingProfile(timingProfiles, ArtifactType.JFR);
        ArtifactTimingProfile gcLogTiming = firstTimingProfile(timingProfiles, ArtifactType.GC_LOG);
        List<ArtifactSignalProfile> nativeProfiles = profiles.stream()
            .filter(profile -> profile.artifactType() == ArtifactType.NMT || profile.artifactType() == ArtifactType.PMAP)
            .filter(profile -> profile.signalFamilies().contains("native-pressure"))
            .toList();

        CrossArtifactTimingSummary timingSummary = buildTimingSummary(jfrTiming, gcLogTiming, timingProfiles);

        List<SignalAlignment> alignments = new ArrayList<>();
        maybeAddJfrGcAlignment(alignments, jfr, gcLog, timingSummary);
        maybeAddJfrThreadDumpAlignment(alignments, jfr, threadDump, timingSummary);
        maybeAddJfrHeapClassOverlap(alignments, jfr, heapHistogram, timingSummary);
        maybeAddJfrGcHeapAlignment(alignments, jfr, gcLog, heapHistogram, timingSummary);
        maybeAddJfrNativeAlignment(alignments, jfr, gcLog, nativeProfiles, timingSummary);
        maybeAddHsErrNativeAlignment(alignments, hsErrLog, nativeProfiles, timingSummary);
        maybeAddContainerOomAlignment(alignments, containerMemory, oomSignal, timingSummary);

        alignments.sort(Comparator.comparingInt(SignalAlignment::score).reversed().thenComparing(SignalAlignment::alignmentId));
        List<SignalAlignment> strongestAlignments = alignments.stream().limit(MAX_ALIGNMENTS).toList();
        return new CrossArtifactSignalSummary(profiles, strongestAlignments, timingSummary);
    }

    private ArtifactSignalProfile firstProfile(List<ArtifactSignalProfile> profiles, ArtifactType artifactType) {
        return profiles.stream()
            .filter(profile -> profile.artifactType() == artifactType)
            .findFirst()
            .orElse(null);
    }

    private ArtifactTimingProfile firstTimingProfile(List<ArtifactTimingProfile> profiles, ArtifactType artifactType) {
        return profiles.stream()
            .filter(profile -> profile.artifactType() == artifactType)
            .findFirst()
            .orElse(null);
    }

    private ArtifactSignalProfile profile(ParsedArtifact parsedArtifact) {
        return switch (parsedArtifact.type()) {
            case JFR -> jfrProfile(parsedArtifact);
            case GC_LOG -> gcLogProfile(parsedArtifact);
            case THREAD_DUMP -> threadDumpProfile(parsedArtifact);
            case HEAP_HISTOGRAM -> heapHistogramProfile(parsedArtifact);
            case HS_ERR_LOG -> hsErrProfile(parsedArtifact);
            case NMT -> nmtProfile(parsedArtifact);
            case PMAP -> pmapProfile(parsedArtifact);
            case CONTAINER_MEMORY -> containerMemoryProfile(parsedArtifact);
            case OOM_SIGNAL -> oomSignalProfile(parsedArtifact);
            default -> new ArtifactSignalProfile(
                parsedArtifact.type(),
                sourcePath(parsedArtifact),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
            );
        };
    }

    private ArtifactTimingProfile timingProfile(ParsedArtifact parsedArtifact) {
        return switch (parsedArtifact.type()) {
            case JFR -> jfrTimingProfile(parsedArtifact);
            case GC_LOG -> gcLogTimingProfile(parsedArtifact);
            case HS_ERR_LOG -> hsErrTimingProfile(parsedArtifact);
            case OOM_SIGNAL -> oomSignalTimingProfile(parsedArtifact);
            case THREAD_DUMP, HEAP_HISTOGRAM, NMT, PMAP, CONTAINER_MEMORY -> timedCompanionProfile(parsedArtifact);
            default -> new ArtifactTimingProfile(
                parsedArtifact.type(),
                sourcePath(parsedArtifact),
                null,
                null,
                List.of(),
                List.of()
            );
        };
    }

    private CrossArtifactTimingSummary buildTimingSummary(
        ArtifactTimingProfile jfrTiming,
        ArtifactTimingProfile gcLogTiming,
        List<ArtifactTimingProfile> timingProfiles
    ) {
        ArtifactTimingProfile effectiveGcLogTiming = maybeAnchorElapsedOnlyGcTiming(gcLogTiming, jfrTiming);
        List<ArtifactTimingProfile> timedCompanionProfiles = timingProfiles == null
            ? List.of()
            : timingProfiles.stream()
                .filter(profile -> TIMED_COMPANION_ARTIFACT_TYPES.contains(profile.artifactType()))
                .toList();
        ArtifactTimingProfile hsErrTiming = firstTimingProfile(timingProfiles, ArtifactType.HS_ERR_LOG);
        ArtifactTimingProfile containerTiming = firstTimingProfile(timingProfiles, ArtifactType.CONTAINER_MEMORY);
        ArtifactTimingProfile oomTiming = firstTimingProfile(timingProfiles, ArtifactType.OOM_SIGNAL);
        ArtifactTimingProfile nmtTiming = firstTimingProfile(timingProfiles, ArtifactType.NMT);
        ArtifactTimingProfile pmapTiming = firstTimingProfile(timingProfiles, ArtifactType.PMAP);

        List<ArtifactTimingProfile> timingCoverage = new ArrayList<>();
        if (jfrTiming != null && jfrTiming.hasUsableContent()) {
            timingCoverage.add(jfrTiming);
        }
        if (effectiveGcLogTiming != null && effectiveGcLogTiming.hasUsableContent()) {
            timingCoverage.add(effectiveGcLogTiming);
        }
        if (timedCompanionProfiles != null && !timedCompanionProfiles.isEmpty()) {
            timingCoverage.addAll(timedCompanionProfiles.stream().filter(ArtifactTimingProfile::hasUsableContent).toList());
        }

        List<TimingAlignment> timingAlignments = new ArrayList<>();
        maybeAddJfrGcTimeAlignment(timingAlignments, jfrTiming, effectiveGcLogTiming);
        if (timedCompanionProfiles != null) {
            for (ArtifactTimingProfile companion : timedCompanionProfiles) {
                maybeAddTimedCompanionAlignment(timingAlignments, companion, jfrTiming, effectiveGcLogTiming);
            }
        }
        maybeAddHsErrNativeTimeAlignment(timingAlignments, hsErrTiming, nmtTiming, pmapTiming);
        maybeAddContainerOomTimeAlignment(timingAlignments, containerTiming, oomTiming);
        timingAlignments.sort(Comparator.comparingInt(TimingAlignment::score).reversed().thenComparing(TimingAlignment::alignmentId));
        return new CrossArtifactTimingSummary(
            timingCoverage,
            timingAlignments.stream().limit(MAX_TIMING_ALIGNMENTS).toList()
        );
    }

    private ArtifactTimingProfile jfrTimingProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> summary = mapValue(extractedData.get("summary"));
        Map<String, Object> jvmRuntimeInfo = mapValue(extractedData.get("jvmRuntimeInfo"));
        List<Map<String, Object>> incidentWindows = listOfMaps(extractedData.get("incidentWindows"));
        Instant recordingStart = instantValue(summary.get("startTime"));
        Instant recordingEnd = instantValue(summary.get("endTime"));
        Instant jvmStartAnchor = instantValue(jvmRuntimeInfo.get("jvmStartTime"));

        List<Map<String, Object>> timingWindows = new ArrayList<>();
        if (recordingStart != null && recordingEnd != null) {
            timingWindows.add(absoluteWindow(
                "recording-window",
                "JFR recording window",
                recordingStart,
                recordingEnd,
                Map.of()
            ));
        }
        Map<String, Object> jvmStartWindow = jfrJvmStartAnchorWindow(jvmRuntimeInfo, jvmStartAnchor);
        if (!jvmStartWindow.isEmpty()) {
            timingWindows.add(jvmStartWindow);
        }
        incidentWindows.stream()
            .limit(MAX_TIMING_WINDOWS - Math.min(MAX_TIMING_WINDOWS, timingWindows.size()))
            .map(this::jfrIncidentTimingWindow)
            .forEach(timingWindows::add);

        List<String> notes = new ArrayList<>();
        if (recordingStart == null || recordingEnd == null) {
            notes.add("The JFR recording did not expose a complete absolute recording window.");
        }
        if (incidentWindows.isEmpty()) {
            notes.add("No incident windows were extracted from the JFR recording.");
        }

        String status;
        String basis;
        if (recordingStart != null && recordingEnd != null) {
            status = jvmStartAnchor != null ? "ABSOLUTE_WINDOW_WITH_JVM_START_ANCHOR" : "ABSOLUTE_WINDOW";
            basis = jvmStartAnchor != null ? "JFR event timestamps and JVM start anchor" : "JFR event timestamps";
        } else if (jvmStartAnchor != null) {
            status = "JVM_START_ANCHOR_ONLY";
            basis = "JFR JVM start anchor";
        } else {
            status = "UNTIMED";
            basis = "JFR timing unavailable";
        }

        return new ArtifactTimingProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            status,
            basis,
            List.copyOf(timingWindows),
            List.copyOf(notes)
        );
    }

    private ArtifactTimingProfile gcLogTimingProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        List<Map<String, Object>> pauses = listOfMaps(extractedData.get("pauses"));
        List<Map<String, Object>> gcCycles = listOfMaps(extractedData.get("gcCycles"));
        List<Map<String, Object>> allocationStalls = listOfMaps(extractedData.get("allocationStalls"));
        List<Map<String, Object>> phaseSamples = listOfMaps(extractedData.get("phaseSamples"));
        List<Map<String, Object>> failureSignals = listOfMaps(extractedData.get("failureSignals"));

        List<Map<String, Object>> timelineEvents = gcTimelineEvents(pauses, gcCycles, allocationStalls, phaseSamples, failureSignals);
        List<Map<String, Object>> distressCluster = bestGcEventCluster(gcDistressEvents(pauses, failureSignals));

        List<Map<String, Object>> timingWindows = new ArrayList<>();
        Map<String, Object> overallWindow = gcOverallTimingWindow(timelineEvents);
        if (!overallWindow.isEmpty()) {
            timingWindows.add(overallWindow);
        }
        Map<String, Object> distressWindow = gcDistressTimingWindow(distressCluster);
        if (!distressWindow.isEmpty()) {
            timingWindows.add(distressWindow);
        }

        boolean hasAbsoluteWindow = timingWindows.stream().anyMatch(window -> window.containsKey("startTime"));
        boolean hasElapsedWindow = timingWindows.stream().anyMatch(window -> window.containsKey("startElapsedSeconds"));

        List<String> notes = new ArrayList<>();
        if (!hasAbsoluteWindow && hasElapsedWindow) {
            notes.add("The GC log only exposed elapsed seconds, which are not directly comparable to JFR recording-relative offsets.");
        } else if (!hasAbsoluteWindow) {
            notes.add("The GC log did not expose a reliable absolute wall-clock window.");
        }
        if (distressCluster.isEmpty()) {
            notes.add("No focused GC distress cluster could be isolated from parsed pauses and failure signals.");
        }

        String status;
        String basis;
        if (hasAbsoluteWindow) {
            status = "ABSOLUTE_WINDOW";
            basis = "GC log absolute timestamps";
        } else if (hasElapsedWindow) {
            status = "ELAPSED_ONLY";
            basis = "GC log elapsed seconds";
        } else {
            status = "UNTIMED";
            basis = "GC log timing unavailable";
        }

        return new ArtifactTimingProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            status,
            basis,
            List.copyOf(timingWindows),
            List.copyOf(notes)
        );
    }

    private ArtifactTimingProfile maybeAnchorElapsedOnlyGcTiming(
        ArtifactTimingProfile gcLogTiming,
        ArtifactTimingProfile jfrTiming
    ) {
        if (gcLogTiming == null || !"ELAPSED_ONLY".equals(gcLogTiming.timingStatus()) || jfrTiming == null) {
            return gcLogTiming;
        }

        Map<String, Object> jvmStartWindow = timingWindowById(jfrTiming, "jvm-start-anchor");
        Instant jvmStartTime = windowStartInstant(jvmStartWindow);
        if (jvmStartTime == null) {
            return gcLogTiming;
        }

        List<Map<String, Object>> anchoredWindows = gcLogTiming.timingWindows().stream()
            .map(window -> anchorElapsedTimingWindow(window, jvmStartTime))
            .toList();
        boolean anchoredAny = anchoredWindows.stream().anyMatch(window -> windowStartInstant(window) != null);
        if (!anchoredAny) {
            return gcLogTiming;
        }

        List<String> notes = new ArrayList<>();
        notes.add("The GC log exposed elapsed seconds only; those windows were anchored to wall-clock time using jdk.JVMInformation.jvmStartTime from the JFR recording.");
        gcLogTiming.notes().stream()
            .filter(note -> !note.contains("only exposed elapsed seconds"))
            .forEach(notes::add);

        return new ArtifactTimingProfile(
            gcLogTiming.artifactType(),
            gcLogTiming.sourcePath(),
            "ELAPSED_ANCHORED_WITH_JFR_JVM_START",
            "GC log elapsed seconds anchored by JFR JVM start time",
            anchoredWindows,
            notes
        );
    }

    private ArtifactTimingProfile hsErrTimingProfile(ParsedArtifact parsedArtifact) {
        Instant crashTime = instantValue(parsedArtifact.extractedData().get("crashTime"));
        if (crashTime == null) {
            return timedCompanionProfile(parsedArtifact);
        }

        return new ArtifactTimingProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            "ABSOLUTE_WINDOW",
            "hs_err crash time",
            List.of(absoluteWindow("capture-window", explicitCaptureLabel(parsedArtifact.type()), crashTime, crashTime, Map.of())),
            List.of()
        );
    }

    private ArtifactTimingProfile oomSignalTimingProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        Instant startTime = instantValue(summary.get("earliestAbsoluteEventTime"));
        Instant endTime = instantValue(summary.get("latestAbsoluteEventTime"));
        if (startTime == null && endTime == null) {
            return timedCompanionProfile(parsedArtifact);
        }
        if (startTime == null) {
            startTime = endTime;
        }
        if (endTime == null) {
            endTime = startTime;
        }
        if (endTime != null && startTime != null && endTime.isBefore(startTime)) {
            endTime = startTime;
        }

        return new ArtifactTimingProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            "ABSOLUTE_WINDOW",
            "OOM signal absolute event time",
            List.of(absoluteWindow("capture-window", explicitCaptureLabel(parsedArtifact.type()), startTime, endTime, Map.of())),
            List.of()
        );
    }

    private ArtifactTimingProfile timedCompanionProfile(ParsedArtifact parsedArtifact) {
        Map<String, String> attributes = parsedArtifact.metadata() != null ? parsedArtifact.metadata().attributes() : Map.of();
        Instant captureTime = firstAttributeInstant(attributes, EXPLICIT_CAPTURE_TIME_ATTRIBUTE_KEYS);
        Instant captureStart = firstAttributeInstant(attributes, EXPLICIT_CAPTURE_START_ATTRIBUTE_KEYS);
        Instant captureEnd = firstAttributeInstant(attributes, EXPLICIT_CAPTURE_END_ATTRIBUTE_KEYS);
        if (captureStart == null && captureEnd == null && captureTime != null) {
            captureStart = captureTime;
            captureEnd = captureTime;
        } else if (captureStart != null && captureEnd == null) {
            captureEnd = captureStart;
        } else if (captureStart == null && captureEnd != null) {
            captureStart = captureEnd;
        }
        if (captureStart != null && captureEnd != null && captureEnd.isBefore(captureStart)) {
            captureEnd = captureStart;
        }

        List<Map<String, Object>> timingWindows = captureStart != null && captureEnd != null
            ? List.of(absoluteWindow("capture-window", explicitCaptureLabel(parsedArtifact.type()), captureStart, captureEnd, Map.of()))
            : List.of();
        List<String> notes = captureStart != null && captureEnd != null
            ? List.of()
            : List.of("No explicit capture timestamp was provided for this artifact.");

        return new ArtifactTimingProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            captureStart != null && captureEnd != null ? "EXPLICIT_CAPTURE_TIME" : "UNTIMED_COMPANION",
            captureStart != null && captureEnd != null ? "Artifact metadata capture time" : "No explicit capture time",
            timingWindows,
            notes
        );
    }

    private ArtifactSignalProfile jfrProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> summary = mapValue(extractedData.get("summary"));
        Map<String, Object> lockSummary = mapValue(extractedData.get("lockSummary"));
        Map<String, Object> gcSummary = mapValue(extractedData.get("gcSummary"));
        Map<String, Object> allocationFieldSummary = mapValue(extractedData.get("allocationFieldSummary"));
        Map<String, Object> allocationHotspotSummary = mapValue(extractedData.get("allocationHotspotSummary"));
        Map<String, Object> oldObjectFieldSummary = mapValue(extractedData.get("oldObjectFieldSummary"));
        Map<String, Object> executionHotspotSummary = mapValue(extractedData.get("executionHotspotSummary"));
        Map<String, Object> runtimeHotspotSummary = mapValue(extractedData.get("runtimeHotspotSummary"));
        List<Map<String, Object>> timelineEvents = listOfMaps(extractedData.get("timelineEvents"));
        List<Map<String, Object>> eventTypeDetails = listOfMaps(extractedData.get("eventTypeDetails"));
        List<Map<String, Object>> lockTimelineEvents = jfrSignalEvents(timelineEvents, "lockContention");
        List<Map<String, Object>> lockEventTypeDetails = jfrLockEventTypeDetails(eventTypeDetails);

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (longValue(lockSummary, "eventCount") > 0L
            && (longValue(lockSummary, "maxDurationMs") >= 75L || longValue(lockSummary, "totalDurationMs") >= 150L)) {
            signalFamilies.add("lock-contention");
        }
        if (longValue(gcSummary, "eventCount") > 0L
            && (longValue(gcSummary, "maxDurationMs") >= 150L || longValue(gcSummary, "totalDurationMs") >= 300L)) {
            signalFamilies.add("gc-distress");
        }
        if (longValue(allocationFieldSummary, "eventCount") >= 5L
            && (longValue(allocationFieldSummary, "totalAllocatedBytes") >= 8_000_000L
                || longValue(allocationFieldSummary, "maxAllocatedBytes") >= 1_000_000L
                || longValue(allocationFieldSummary, "topClassEventCount") >= 4L)) {
            signalFamilies.add("allocation-pressure");
        }
        if (longValue(oldObjectFieldSummary, "eventCount") >= 2L
            && (longValue(oldObjectFieldSummary, "totalSampledObjectBytes") >= 512_000L
                || longValue(oldObjectFieldSummary, "maxObjectAgeMs") >= 60_000L
                || longValue(oldObjectFieldSummary, "maxReferenceDepth") >= 4L)) {
            signalFamilies.add("retention-pressure");
        }

        List<String> exactClasses = uniqueLimitedStrings(
            rawStrings(
                stringValue(allocationFieldSummary.get("topClass")),
                stringValue(oldObjectFieldSummary.get("topClass"))
            ),
            listOfMapsClassNames(allocationFieldSummary.get("topAllocatingClasses"), "className", 3),
            listOfMapsClassNames(oldObjectFieldSummary.get("topOldObjectClasses"), "className", 3)
        );
        List<String> classFamilies = classFamilies(exactClasses);
        List<String> hotspotMethods = uniqueLimitedStrings(
            rawStrings(
                stringValue(allocationHotspotSummary.get("topMethod")),
                stringValue(executionHotspotSummary.get("topMethod")),
                stringValue(runtimeHotspotSummary.get("topMethod"))
            )
        );
        List<String> threadNames = joinThreadNames(
            listOfMapsStrings(lockTimelineEvents, "eventThread", MAX_JOIN_THREADS),
            jfrTopThreads(lockEventTypeDetails),
            jfrSampleEventThreads(lockEventTypeDetails),
            jfrSampleFieldThreads(lockEventTypeDetails)
        );
        List<String> poolNames = joinPoolNames(threadNames);
        List<String> lockKeys = joinLockKeys(jfrSampleFieldLockKeys(lockEventTypeDetails));
        List<String> hotspotFrames = joinMethodIdentities(
            listOfMapsStrings(lockTimelineEvents, "topMethod", MAX_JOIN_FRAMES),
            jfrSampleEventMethods(lockEventTypeDetails)
        );

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveLong(keyMetrics, "recordingDurationMs", longValue(summary, "durationMs"));
        putIfPositiveLong(keyMetrics, "lockEventCount", longValue(lockSummary, "eventCount"));
        putIfPositiveLong(keyMetrics, "maxLockDurationMs", longValue(lockSummary, "maxDurationMs"));
        putIfPositiveLong(keyMetrics, "gcPauseEventCount", longValue(gcSummary, "eventCount"));
        putIfPositiveLong(keyMetrics, "maxJfrGcPauseMs", longValue(gcSummary, "maxDurationMs"));
        putIfPositiveLong(keyMetrics, "totalAllocatedBytes", longValue(allocationFieldSummary, "totalAllocatedBytes"));
        putIfPresent(keyMetrics, "topAllocationClass", normalizeClassName(stringValue(allocationFieldSummary.get("topClass"))));
        putIfPositiveLong(keyMetrics, "topAllocationClassEventCount", longValue(allocationFieldSummary, "topClassEventCount"));
        putIfPresent(keyMetrics, "topOldObjectClass", normalizeClassName(stringValue(oldObjectFieldSummary.get("topClass"))));
        putIfPositiveLong(keyMetrics, "topOldObjectEventCount", longValue(oldObjectFieldSummary, "topClassEventCount"));
        putIfPositiveLong(keyMetrics, "totalSampledOldObjectBytes", longValue(oldObjectFieldSummary, "totalSampledObjectBytes"));
        putIfPositiveLong(keyMetrics, "maxReferenceDepth", longValue(oldObjectFieldSummary, "maxReferenceDepth"));
        putIfPresent(keyMetrics, "recordingStartTime", stringValue(summary.get("startTime")));
        putIfPresent(keyMetrics, "recordingEndTime", stringValue(summary.get("endTime")));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            exactClasses,
            classFamilies,
            hotspotMethods.stream().limit(MAX_HOTSPOT_METHODS).toList(),
            threadNames,
            poolNames,
            lockKeys,
            hotspotFrames,
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile gcLogProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> summary = mapValue(extractedData.get("summary"));
        Map<String, Object> pressure = mapValue(extractedData.get("collectorPressureSummary"));
        Map<String, Object> recovery = mapValue(extractedData.get("recoverySummary"));
        Map<String, Object> metaspace = mapValue(extractedData.get("metaspace"));

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        long fullGcCount = firstPositiveLong(pressure, "fullGcCount", summary, "fullGcCount");
        long allocationStallCount = firstPositiveLong(pressure, "allocationStallCount", summary, "allocationStallCount");
        double p95PauseMs = firstPositiveDouble(pressure, "p95PauseMs", summary, "p95PauseMs");
        double maxFullGcPauseMs = firstPositiveDouble(summary, "maxFullGcPauseMs", pressure, "maxFullGcPauseMs");
        double peakPostGcOccupancyRatio = firstPositiveDouble(
            pressure,
            "peakPostGcOccupancyRatio",
            recovery,
            "peakPostGcOccupancyRatio",
            summary,
            "peakHeapOccupancyRatio"
        );
        if (fullGcCount > 0L || allocationStallCount > 0L || p95PauseMs >= 100.0d || maxFullGcPauseMs >= 200.0d) {
            signalFamilies.add("gc-distress");
        }
        if (peakPostGcOccupancyRatio >= 0.90d || firstPositiveLong(pressure, "nearCapacityAfterGcCount", recovery, "nearCapacityAfterGcCount") > 0L) {
            signalFamilies.add("heap-pressure");
        }
        if (firstPositiveLong(pressure, "metaspaceTriggeredFullGcCount", summary, "metaspaceTriggeredFullGcCount") > 0L
            || doubleValue(metaspace, "peakUsageRatio") >= 0.80d) {
            signalFamilies.add("metaspace-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPresent(keyMetrics, "collector", stringValue(extractedData.get("collector")));
        putIfPositiveLong(keyMetrics, "fullGcCount", fullGcCount);
        putIfPositiveDouble(keyMetrics, "maxFullGcPauseMs", maxFullGcPauseMs);
        putIfPositiveLong(keyMetrics, "allocationStallCount", allocationStallCount);
        putIfPositiveDouble(keyMetrics, "peakPostGcOccupancyRatio", peakPostGcOccupancyRatio);
        putIfPositiveLong(
            keyMetrics,
            "metaspaceTriggeredFullGcCount",
            firstPositiveLong(pressure, "metaspaceTriggeredFullGcCount", summary, "metaspaceTriggeredFullGcCount")
        );

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile threadDumpProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> deadlock = mapValue(extractedData.get("deadlock"));
        List<Map<String, Object>> contentionHotspots = listOfMaps(extractedData.get("contentionHotspots"));
        List<Map<String, Object>> poolSummaries = listOfMaps(extractedData.get("poolSummaries"));
        List<Map<String, Object>> threads = listOfMaps(extractedData.get("threads"));

        long blockedThreadCount = longValue(extractedData, "blockedThreadCount");
        boolean deadlockDetected = booleanValue(deadlock.get("detected"));
        long strongestBlockedWaiterCount = contentionHotspots.stream()
            .mapToLong(hotspot -> longValue(hotspot, "blockedWaiterCount"))
            .max()
            .orElse(0L);
        long mostBlockedPoolThreads = poolSummaries.stream()
            .mapToLong(summary -> longValue(summary, "blockedCount"))
            .max()
            .orElse(0L);

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (deadlockDetected || blockedThreadCount > 0L || strongestBlockedWaiterCount >= 2L) {
            signalFamilies.add("lock-contention");
        }
        if (mostBlockedPoolThreads >= 2L) {
            signalFamilies.add("thread-pool-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveLong(keyMetrics, "threadCount", longValue(extractedData, "threadCount"));
        putIfPositiveLong(keyMetrics, "blockedThreadCount", blockedThreadCount);
        putIfPositiveLong(keyMetrics, "deadlockedThreadCount", listOfStrings(deadlock.get("threadNames")).size());
        putIfPositiveLong(keyMetrics, "strongestBlockedWaiterCount", strongestBlockedWaiterCount);
        putIfPositiveLong(keyMetrics, "mostBlockedPoolThreads", mostBlockedPoolThreads);
        keyMetrics.put("deadlockDetected", deadlockDetected);
        List<String> threadNames = joinThreadNames(
            listOfStrings(extractedData.get("blockedThreadNames")),
            listOfStrings(deadlock.get("threadNames")),
            contentionThreadNames(contentionHotspots),
            blockedOrDeadlockedThreadNames(threads)
        );
        List<String> poolNames = joinPoolNames(
            threadNames,
            listOfMapsStrings(poolSummaries, "poolName", MAX_JOIN_POOLS)
        );
        List<String> lockKeys = joinLockKeys(
            listOfMapsStrings(contentionHotspots, "monitorId", MAX_JOIN_LOCK_KEYS),
            threadMonitorIds(threads)
        );
        List<String> hotspotFrames = joinMethodIdentities(
            blockedOrDeadlockedTopFrames(threads)
        );

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            threadNames,
            poolNames,
            lockKeys,
            hotspotFrames,
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile heapHistogramProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> summary = mapValue(extractedData.get("summary"));
        List<String> exactClasses = uniqueLimitedStrings(
            rawStrings(stringValue(summary.get("topConsumerClassName"))),
            listOfMapsClassNames(extractedData.get("topConsumers"), "className", MAX_EXACT_CLASSES)
        );
        LinkedHashSet<String> classFamilies = new LinkedHashSet<>(classFamilies(exactClasses));
        if (longValue(summary, "payloadBytes") >= 8_000_000L || doubleValue(summary, "payloadVisibleShare") >= 0.20d) {
            classFamilies.add("payload");
        }
        if (longValue(summary, "collectionBytes") >= 4_000_000L || doubleValue(summary, "collectionVisibleShare") >= 0.20d) {
            classFamilies.add("collections");
        }
        if (longValue(summary, "cacheLikeBytes") >= 2_000_000L || doubleValue(summary, "cacheLikeVisibleShare") >= 0.15d) {
            classFamilies.add("cache");
        }

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (longValue(summary, "payloadBytes") >= 8_000_000L
            || longValue(summary, "collectionBytes") >= 4_000_000L
            || longValue(summary, "cacheLikeBytes") >= 2_000_000L
            || doubleValue(summary, "topConsumerTotalShare") >= 0.30d) {
            signalFamilies.add("retention-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPresent(keyMetrics, "topConsumerClass", normalizeClassName(stringValue(summary.get("topConsumerClassName"))));
        putIfPositiveDouble(keyMetrics, "topConsumerTotalShare", doubleValue(summary, "topConsumerTotalShare"));
        putIfPositiveLong(keyMetrics, "payloadBytes", longValue(summary, "payloadBytes"));
        putIfPositiveLong(keyMetrics, "collectionBytes", longValue(summary, "collectionBytes"));
        putIfPositiveLong(keyMetrics, "cacheLikeBytes", longValue(summary, "cacheLikeBytes"));
        putIfPositiveLong(keyMetrics, "totalBytes", longValue(summary, "totalBytes"));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            exactClasses,
            List.copyOf(classFamilies).stream().limit(MAX_FAMILY_CLASSES).toList(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile hsErrProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> nativeAllocationFailure = mapValue(extractedData.get("nativeAllocationFailure"));
        Map<String, Object> problematicFrame = mapValue(extractedData.get("problematicFrame"));
        String crashType = stringValue(extractedData.get("crashType"));
        String problematicSymbol = stringValue(problematicFrame.get("symbol"));

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if ("fatal_signal".equals(crashType)) {
            signalFamilies.add("crash-distress");
        }
        if (!nativeAllocationFailure.isEmpty()) {
            signalFamilies.add("native-pressure");
        }
        if (problematicSymbol != null && problematicSymbol.contains("FullGC")) {
            signalFamilies.add("gc-distress");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPresent(keyMetrics, "signal", stringValue(extractedData.get("signal")));
        putIfPresent(keyMetrics, "crashType", crashType);
        putIfPresent(keyMetrics, "currentThreadName", stringValue(extractedData.get("currentThreadName")));
        putIfPresent(keyMetrics, "crashTime", stringValue(extractedData.get("crashTime")));
        putIfPresent(keyMetrics, "problematicSymbol", problematicSymbol);
        putIfPositiveLong(keyMetrics, "nativeAllocationBytes", longValue(nativeAllocationFailure, "bytes"));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            rawStrings(problematicSymbol),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile nmtProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> totalDelta = mapValue(extractedData.get("totalDeltaKb"));
        Map<String, Object> threadSummary = mapValue(extractedData.get("threadSummary"));
        Map<String, Object> metaspaceSummary = mapValue(extractedData.get("metaspaceSummary"));
        Map<String, Object> metaspaceSummaryDeltas = mapValue(extractedData.get("metaspaceSummaryDeltas"));
        Map<String, Object> classSummaryDeltas = mapValue(extractedData.get("classSummaryDeltas"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categories = extractedData.get("categories") instanceof Map<?, ?> map
            ? (Map<String, Map<String, Long>>) map
            : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> categoryDeltas = extractedData.get("categoryDeltas") instanceof Map<?, ?> map
            ? (Map<String, Map<String, Long>>) map
            : Map.of();

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        long committedDeltaKb = longValue(totalDelta, "committedKb");
        long threadStackReservedKb = longValue(threadSummary, "stackReservedKb");
        long gcCommittedKb = nestedLongValue(categories, "GC", "committedKb");
        if (committedDeltaKb >= 16_384L || threadStackReservedKb >= 32_768L || gcCommittedKb >= 32_768L) {
            signalFamilies.add("native-pressure");
        }
        long metaspaceCommittedKb = longValue(metaspaceSummary, "committedKb");
        long metaspaceUsedKb = longValue(metaspaceSummary, "usedKb");
        if ((metaspaceCommittedKb > 0L && ratio(metaspaceUsedKb, metaspaceCommittedKb) >= 0.85d)
            || longValue(metaspaceSummaryDeltas, "usedKb") >= 8_192L
            || nestedLongValue(categoryDeltas, "Class", "committedKb") >= 8_192L
            || longValue(classSummaryDeltas, "classCount") >= 5_000L) {
            signalFamilies.add("metaspace-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveLong(keyMetrics, "committedDeltaKb", committedDeltaKb);
        putIfPositiveLong(keyMetrics, "threadCount", longValue(threadSummary, "threadCount"));
        putIfPositiveLong(keyMetrics, "threadStackReservedKb", threadStackReservedKb);
        putIfPositiveLong(keyMetrics, "gcCommittedKb", gcCommittedKb);
        putIfPositiveLong(keyMetrics, "metaspaceUsedKb", metaspaceUsedKb);
        putIfPositiveLong(keyMetrics, "metaspaceCommittedKb", metaspaceCommittedKb);
        putIfPositiveLong(keyMetrics, "classCountDelta", longValue(classSummaryDeltas, "classCount"));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile pmapProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));
        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (doubleValue(summary, "anonVirtualShare") >= 0.70d
            || doubleValue(summary, "anonResidentShare") >= 0.60d
            || longValue(summary, "anonSizeKb") >= 262_144L) {
            signalFamilies.add("native-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveDouble(keyMetrics, "anonVirtualShare", doubleValue(summary, "anonVirtualShare"));
        putIfPositiveDouble(keyMetrics, "anonResidentShare", doubleValue(summary, "anonResidentShare"));
        putIfPositiveLong(keyMetrics, "anonSizeKb", longValue(summary, "anonSizeKb"));
        putIfPositiveLong(keyMetrics, "anonRssKb", longValue(summary, "anonRssKb"));
        putIfPositiveLong(keyMetrics, "totalRssKb", longValue(summary, "totalRssKb"));
        putIfPositiveLong(keyMetrics, "reservedGapKb", longValue(summary, "reservedGapKb"));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile containerMemoryProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> extractedData = parsedArtifact.extractedData();
        Map<String, Object> summary = mapValue(extractedData.get("summary"));
        Map<String, Object> events = mapValue(extractedData.get("events"));
        Map<String, Object> pressure = mapValue(extractedData.get("pressure"));
        Map<String, Object> fullPressure = mapValue(pressure.get("full"));
        Map<String, Object> somePressure = mapValue(pressure.get("some"));

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (doubleValue(summary, "usageOfMaxRatio") >= 0.90d
            || doubleValue(summary, "usageOfHighRatio") >= 0.95d
            || longValue(events, "oom_kill") > 0L
            || longValue(events, "oom") > 0L
            || longValue(events, "high") > 0L
            || doubleValue(fullPressure, "avg10") >= 1.0d
            || doubleValue(somePressure, "avg10") >= 1.0d) {
            signalFamilies.add("container-pressure");
        }
        if (longValue(events, "oom_kill") > 0L || longValue(events, "oom") > 0L) {
            signalFamilies.add("oom-termination");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveLong(keyMetrics, "currentBytes", longValue(summary, "currentBytes"));
        putIfPositiveLong(keyMetrics, "maxBytes", longValue(summary, "maxBytes"));
        putIfPositiveLong(keyMetrics, "highBytes", longValue(summary, "highBytes"));
        putIfPositiveDouble(keyMetrics, "usageOfMaxRatio", doubleValue(summary, "usageOfMaxRatio"));
        putIfPositiveDouble(keyMetrics, "usageOfHighRatio", doubleValue(summary, "usageOfHighRatio"));
        putIfPositiveLong(keyMetrics, "highEventCount", longValue(events, "high"));
        putIfPositiveLong(keyMetrics, "oomEventCount", longValue(events, "oom"));
        putIfPositiveLong(keyMetrics, "oomKillEventCount", longValue(events, "oom_kill"));
        putIfPositiveDouble(keyMetrics, "fullPressureAvg10", doubleValue(fullPressure, "avg10"));
        putIfPositiveDouble(keyMetrics, "somePressureAvg10", doubleValue(somePressure, "avg10"));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private ArtifactSignalProfile oomSignalProfile(ParsedArtifact parsedArtifact) {
        Map<String, Object> summary = mapValue(parsedArtifact.extractedData().get("summary"));

        LinkedHashSet<String> signalFamilies = new LinkedHashSet<>();
        if (longValue(summary, "kernelOomKillCount") > 0L || longValue(summary, "podOomKilledCount") > 0L) {
            signalFamilies.add("oom-termination");
        }
        if (longValue(summary, "crashLoopBackOffCount") > 0L || longValue(summary, "maxRestartCount") > 0L) {
            signalFamilies.add("restart-pressure");
        }

        LinkedHashMap<String, Object> keyMetrics = new LinkedHashMap<>();
        putIfPositiveLong(keyMetrics, "kernelOomKillCount", longValue(summary, "kernelOomKillCount"));
        putIfPositiveLong(keyMetrics, "podOomKilledCount", longValue(summary, "podOomKilledCount"));
        putIfPositiveLong(keyMetrics, "crashLoopBackOffCount", longValue(summary, "crashLoopBackOffCount"));
        putIfPositiveLong(keyMetrics, "maxRestartCount", longValue(summary, "maxRestartCount"));
        putIfPresent(keyMetrics, "podName", stringValue(summary.get("podName")));
        putIfPresent(keyMetrics, "namespace", stringValue(summary.get("namespace")));
        putIfPresent(keyMetrics, "latestAbsoluteEventTime", stringValue(summary.get("latestAbsoluteEventTime")));

        return new ArtifactSignalProfile(
            parsedArtifact.type(),
            sourcePath(parsedArtifact),
            List.copyOf(signalFamilies),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.copyOf(keyMetrics)
        );
    }

    private void maybeAddJfrGcAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile jfr,
        ArtifactSignalProfile gcLog,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (jfr == null || gcLog == null) {
            return;
        }
        if (!gcLog.signalFamilies().contains("gc-distress")) {
            return;
        }
        List<String> jfrPressureSignals = overlap(jfr.signalFamilies(), List.of("gc-distress", "allocation-pressure", "retention-pressure"));
        if (jfrPressureSignals.isEmpty()) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, jfr.keyMetrics(), "maxJfrGcPauseMs");
        mergeMetric(metrics, jfr.keyMetrics(), "totalAllocatedBytes");
        mergeMetric(metrics, jfr.keyMetrics(), "topAllocationClass");
        mergeMetric(metrics, jfr.keyMetrics(), "topOldObjectClass");
        mergeMetric(metrics, gcLog.keyMetrics(), "collector");
        mergeMetric(metrics, gcLog.keyMetrics(), "fullGcCount");
        mergeMetric(metrics, gcLog.keyMetrics(), "maxFullGcPauseMs");
        mergeMetric(metrics, gcLog.keyMetrics(), "peakPostGcOccupancyRatio");
        mergeMetric(metrics, gcLog.keyMetrics(), "allocationStallCount");

        TimingAlignment timingAlignment = timingSummary != null ? timingSummary.alignment("jfr-gc-time-alignment") : null;
        String detail = "The JFR recording carries " + joinedSignals(jfrPressureSignals)
            + ", while the GC log carries " + joinedSignals(intersection(gcLog.signalFamilies(), Set.of("gc-distress", "heap-pressure", "metaspace-pressure"))) + ".";
        int score = 80;
        if (timingAlignment != null && "ABSOLUTE_OVERLAP".equals(timingAlignment.status())) {
            detail += " Their high-pressure windows also overlap in wall-clock time.";
            score += 8;
        } else if (timingAlignment != null && "NO_SHARED_CLOCK".equals(timingAlignment.status())) {
            detail += " A precise time match could not be confirmed because the artifacts do not share a reliable clock.";
        }

        alignments.add(new SignalAlignment(
            "jfr-gc-pressure-alignment",
            "JFR pressure signals and GC-log distress are both present",
            detail,
            artifactPaths(jfr, gcLog),
            artifactTypes(jfr, gcLog),
            uniqueStrings(jfrPressureSignals, intersection(gcLog.signalFamilies(), Set.of("gc-distress", "heap-pressure", "metaspace-pressure"))),
            List.of(),
            List.of(),
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddJfrThreadDumpAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile jfr,
        ArtifactSignalProfile threadDump,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (jfr == null || threadDump == null) {
            return;
        }
        if (!jfr.signalFamilies().contains("lock-contention") || !threadDump.signalFamilies().contains("lock-contention")) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, jfr.keyMetrics(), "lockEventCount");
        mergeMetric(metrics, jfr.keyMetrics(), "maxLockDurationMs");
        mergeMetric(metrics, threadDump.keyMetrics(), "blockedThreadCount");
        mergeMetric(metrics, threadDump.keyMetrics(), "deadlockedThreadCount");
        Object deadlockDetected = threadDump.keyMetrics().get("deadlockDetected");
        if (deadlockDetected instanceof Boolean) {
            metrics.put("deadlockDetected", deadlockDetected);
        }
        List<String> sharedThreads = overlap(jfr.threadNames(), threadDump.threadNames());
        List<String> sharedPools = overlap(jfr.poolNames(), threadDump.poolNames());
        List<String> sharedFrames = overlap(jfr.hotspotFrames(), threadDump.hotspotFrames());
        List<String> sharedLocks = overlap(jfr.lockKeys(), threadDump.lockKeys());
        putIfNotEmpty(metrics, "sharedThreadNames", sharedThreads);
        putIfNotEmpty(metrics, "sharedPoolNames", sharedPools);
        putIfNotEmpty(metrics, "sharedHotspotFrames", sharedFrames);
        putIfNotEmpty(metrics, "sharedLockKeys", sharedLocks);

        TimingAlignment threadDumpTiming = companionTimingAlignment(timingSummary, ArtifactType.THREAD_DUMP);
        if (threadDumpTiming != null) {
            metrics.put("threadDumpTimePlacementStatus", threadDumpTiming.status());
        }

        String detail = "The JFR recording shows lock-contention activity, and the thread dump shows blocked threads or a deadlock at capture time.";
        int score = 84;
        List<String> concreteJoins = new ArrayList<>();
        if (!sharedThreads.isEmpty()) {
            concreteJoins.add("shared thread names " + joinedExamples(sharedThreads));
            score += 6;
        }
        if (!sharedPools.isEmpty()) {
            concreteJoins.add("shared executor or worker pools " + joinedExamples(sharedPools));
            score += 4;
        }
        if (!sharedFrames.isEmpty()) {
            concreteJoins.add("shared blocking frames " + joinedExamples(sharedFrames));
            score += 4;
        }
        if (!sharedLocks.isEmpty()) {
            concreteJoins.add("shared lock identifiers " + joinedExamples(sharedLocks));
            score += 4;
        }
        if (!concreteJoins.isEmpty()) {
            detail += " Concrete joins were found via " + joinedClauses(concreteJoins) + ".";
        } else {
            detail += " No concrete thread, pool, frame, or lock identifier overlap was extracted, so this remains a timing-and-signal correlation.";
        }
        if (isAbsoluteOverlap(threadDumpTiming)) {
            detail += " The thread dump capture time also overlaps the timed JVM incident window.";
            score += 8;
        } else if (isAbsoluteNoOverlap(threadDumpTiming)) {
            detail += " The thread dump has an explicit capture time outside the timed JVM incident windows, so the blockage may belong to a different interval.";
            score = Math.max(58, score - 12);
        } else if (isUntimedCompanion(threadDumpTiming)) {
            detail += " The thread dump did not expose a capture time, so the exact timing relationship remains unconfirmed.";
        }

        alignments.add(new SignalAlignment(
            "jfr-thread-dump-contention-alignment",
            "JFR lock contention and thread-dump blockage reinforce each other",
            detail,
            artifactPaths(jfr, threadDump),
            artifactTypes(jfr, threadDump),
            List.of("lock-contention"),
            List.of(),
            List.of(),
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddJfrHeapClassOverlap(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile jfr,
        ArtifactSignalProfile heapHistogram,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (jfr == null || heapHistogram == null) {
            return;
        }

        List<String> sharedClasses = overlap(jfr.exactClasses(), heapHistogram.exactClasses());
        List<String> sharedFamilies = overlap(jfr.classFamilies(), heapHistogram.classFamilies());
        if (sharedClasses.isEmpty() && sharedFamilies.isEmpty()) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, jfr.keyMetrics(), "topAllocationClass");
        mergeMetric(metrics, jfr.keyMetrics(), "topOldObjectClass");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "topConsumerClass");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "payloadBytes");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "collectionBytes");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "cacheLikeBytes");
        TimingAlignment heapTiming = companionTimingAlignment(timingSummary, ArtifactType.HEAP_HISTOGRAM);
        if (heapTiming != null) {
            metrics.put("heapTimePlacementStatus", heapTiming.status());
        }

        String detail = !sharedClasses.isEmpty()
            ? "The same dominant class names appear in both the JFR recording and the heap histogram."
            : "The JFR recording and the heap histogram emphasize the same object families even when the exact class names differ.";
        int score = !sharedClasses.isEmpty() ? 90 : 75;
        if (isAbsoluteOverlap(heapTiming)) {
            detail += " The heap histogram capture time also overlaps the timed JVM incident window.";
            score += 8;
        } else if (isAbsoluteNoOverlap(heapTiming)) {
            detail += " The heap histogram has an explicit capture time outside the timed JVM incident windows, so the overlap may describe a different interval.";
            score = Math.max(58, score - 12);
        } else if (isUntimedCompanion(heapTiming)) {
            detail += " The heap histogram did not expose a capture time, so the timing relationship remains unconfirmed.";
        }
        alignments.add(new SignalAlignment(
            "jfr-heap-class-overlap",
            "JFR and heap data overlap on dominant object classes or families",
            detail,
            artifactPaths(jfr, heapHistogram),
            artifactTypes(jfr, heapHistogram),
            List.of(),
            sharedClasses,
            sharedFamilies,
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddHsErrNativeAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile hsErrLog,
        List<ArtifactSignalProfile> nativeProfiles,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (hsErrLog == null || nativeProfiles == null || nativeProfiles.isEmpty()) {
            return;
        }
        if (!hsErrLog.signalFamilies().contains("native-pressure")) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, hsErrLog.keyMetrics(), "signal");
        mergeMetric(metrics, hsErrLog.keyMetrics(), "crashType");
        mergeMetric(metrics, hsErrLog.keyMetrics(), "currentThreadName");
        mergeMetric(metrics, hsErrLog.keyMetrics(), "problematicSymbol");
        mergeMetric(metrics, hsErrLog.keyMetrics(), "nativeAllocationBytes");
        for (ArtifactSignalProfile nativeProfile : nativeProfiles) {
            mergeMetric(metrics, nativeProfile.keyMetrics(), "committedDeltaKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "gcCommittedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "threadStackReservedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "metaspaceUsedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "anonResidentShare");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "anonSizeKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "totalRssKb");
        }

        TimingAlignment nativeTiming = timingSummary != null ? timingSummary.alignment("hs-err-native-time-alignment") : null;
        if (nativeTiming != null) {
            metrics.put("hsErrNativeTimeAlignmentStatus", nativeTiming.status());
            mergeMetric(metrics, nativeTiming.metrics(), "overlappingTimedCompanionCount");
            mergeMetric(metrics, nativeTiming.metrics(), "explicitNoOverlapTimedCompanionCount");
            mergeMetric(metrics, nativeTiming.metrics(), "nearestSequentialGapSeconds");
            if (nativeTiming.metrics().get("sequenceDirection") instanceof String sequenceDirection && !sequenceDirection.isBlank()) {
                metrics.put("sequenceDirection", sequenceDirection);
            }
        }

        String detail = "The hs_err log reports a native-memory crash condition, and the native-memory artifacts also carry native-pressure signals.";
        int score = 82;
        if (nativeTiming != null && "ABSOLUTE_OVERLAP".equals(nativeTiming.status())) {
            detail += " The explicitly timed native-memory snapshots also overlap the crash window.";
            score += 8;
        } else if (nativeTiming != null
            && "ABSOLUTE_SEQUENCE_NEARBY".equals(nativeTiming.status())
            && "PRIMARY_AFTER_COMPANION".equals(stringValue(nativeTiming.metrics().get("sequenceDirection")))) {
            detail += " A timed native-memory snapshot was captured about "
                + humanDuration(longValue(nativeTiming.metrics(), "nearestSequentialGapSeconds"))
                + " before the crash window, which fits an escalating native-memory failure sequence.";
            score += 5;
        } else if (nativeTiming != null && "ABSOLUTE_SEQUENCE_NEARBY".equals(nativeTiming.status())) {
            detail += " The timed native-memory evidence is nearby on the clock, but its sequence relative to the crash is less direct.";
            score += 2;
        } else if (nativeTiming != null && "ABSOLUTE_NO_OVERLAP".equals(nativeTiming.status())) {
            detail += " The explicitly timed native-memory snapshots sit outside the crash window, so the coexistence may span different intervals.";
            score = Math.max(56, score - 10);
        } else if (nativeTiming != null && "NO_SHARED_CLOCK".equals(nativeTiming.status())) {
            detail += " A precise time match could not be confirmed because the supporting native-memory snapshots do not expose a comparable absolute time window.";
        }

        List<String> paths = new ArrayList<>(artifactPaths(hsErrLog));
        for (ArtifactSignalProfile nativeProfile : nativeProfiles) {
            paths.addAll(artifactPaths(nativeProfile));
        }
        List<ArtifactType> types = new ArrayList<>();
        types.add(hsErrLog.artifactType());
        types.addAll(nativeProfiles.stream().map(ArtifactSignalProfile::artifactType).toList());

        alignments.add(new SignalAlignment(
            "hs-err-native-pressure-alignment",
            "hs_err native crash signals and native-memory pressure coexist",
            detail,
            uniqueStrings(paths),
            uniqueArtifactTypes(types),
            uniqueStrings(List.of("native-pressure"), nativeProfiles.stream().flatMap(profile -> profile.signalFamilies().stream()).toList()),
            List.of(),
            List.of(),
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddContainerOomAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile containerMemory,
        ArtifactSignalProfile oomSignal,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (containerMemory == null || oomSignal == null) {
            return;
        }
        if (!containerMemory.signalFamilies().contains("container-pressure") || !oomSignal.signalFamilies().contains("oom-termination")) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, containerMemory.keyMetrics(), "currentBytes");
        mergeMetric(metrics, containerMemory.keyMetrics(), "maxBytes");
        mergeMetric(metrics, containerMemory.keyMetrics(), "highBytes");
        mergeMetric(metrics, containerMemory.keyMetrics(), "usageOfMaxRatio");
        mergeMetric(metrics, containerMemory.keyMetrics(), "usageOfHighRatio");
        mergeMetric(metrics, containerMemory.keyMetrics(), "oomKillEventCount");
        mergeMetric(metrics, oomSignal.keyMetrics(), "kernelOomKillCount");
        mergeMetric(metrics, oomSignal.keyMetrics(), "podOomKilledCount");
        mergeMetric(metrics, oomSignal.keyMetrics(), "crashLoopBackOffCount");
        mergeMetric(metrics, oomSignal.keyMetrics(), "maxRestartCount");
        mergeMetric(metrics, oomSignal.keyMetrics(), "podName");
        mergeMetric(metrics, oomSignal.keyMetrics(), "namespace");

        TimingAlignment containerOomTiming = timingSummary != null ? timingSummary.alignment("container-oom-time-alignment") : null;
        if (containerOomTiming != null) {
            metrics.put("containerOomTimeAlignmentStatus", containerOomTiming.status());
            mergeMetric(metrics, containerOomTiming.metrics(), "nearestSequentialGapSeconds");
            if (containerOomTiming.metrics().get("sequenceDirection") instanceof String sequenceDirection && !sequenceDirection.isBlank()) {
                metrics.put("sequenceDirection", sequenceDirection);
            }
        }

        String detail = "The container-memory snapshot shows cgroup memory pressure, and the OOM or restart signals show that the workload was killed or restarted under memory enforcement.";
        int score = 88;
        if (containerOomTiming != null && "ABSOLUTE_OVERLAP".equals(containerOomTiming.status())) {
            detail += " Their explicit times also overlap, so they describe the same memory-budget incident.";
            score += 8;
        } else if (containerOomTiming != null
            && "ABSOLUTE_SEQUENCE_NEARBY".equals(containerOomTiming.status())
            && "PRIMARY_BEFORE_COMPANION".equals(stringValue(containerOomTiming.metrics().get("sequenceDirection")))) {
            detail += " The pressure snapshot precedes the OOM window by about "
                + humanDuration(longValue(containerOomTiming.metrics(), "nearestSequentialGapSeconds"))
                + ", which fits a nearby escalation into enforcement.";
            score += 5;
        } else if (containerOomTiming != null && "ABSOLUTE_SEQUENCE_NEARBY".equals(containerOomTiming.status())) {
            detail += " The pressure snapshot and the OOM window are close together on the clock, but their sequence is less direct.";
            score += 2;
        } else if (containerOomTiming != null && "ABSOLUTE_NO_OVERLAP".equals(containerOomTiming.status())) {
            detail += " Their explicit times do not overlap, so the pressure snapshot and the OOM signals may describe different incidents.";
            score = Math.max(58, score - 14);
        } else if (containerOomTiming != null && "NO_SHARED_CLOCK".equals(containerOomTiming.status())) {
            detail += " A precise time match could not be confirmed because one side did not expose a comparable absolute time window.";
        }

        alignments.add(new SignalAlignment(
            "container-oom-pressure-alignment",
            "Container memory pressure and OOM signals reinforce each other",
            detail,
            artifactPaths(containerMemory, oomSignal),
            artifactTypes(containerMemory, oomSignal),
            uniqueStrings(List.of("container-pressure"), List.of("oom-termination")),
            List.of(),
            List.of(),
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddJfrGcHeapAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile jfr,
        ArtifactSignalProfile gcLog,
        ArtifactSignalProfile heapHistogram,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (jfr == null || gcLog == null || heapHistogram == null) {
            return;
        }
        if (!gcLog.signalFamilies().contains("gc-distress")
            || !heapHistogram.signalFamilies().contains("retention-pressure")
            || overlap(jfr.signalFamilies(), List.of("allocation-pressure", "retention-pressure")).isEmpty()) {
            return;
        }

        List<String> sharedClasses = overlap(jfr.exactClasses(), heapHistogram.exactClasses());
        List<String> sharedFamilies = uniqueStrings(overlap(jfr.classFamilies(), heapHistogram.classFamilies()));
        TimingAlignment jfrGcTiming = timingSummary != null ? timingSummary.alignment("jfr-gc-time-alignment") : null;
        TimingAlignment heapTiming = companionTimingAlignment(timingSummary, ArtifactType.HEAP_HISTOGRAM);
        if (isAbsoluteNoOverlap(jfrGcTiming) || isAbsoluteNoOverlap(heapTiming)) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, jfr.keyMetrics(), "topAllocationClass");
        mergeMetric(metrics, jfr.keyMetrics(), "topOldObjectClass");
        mergeMetric(metrics, jfr.keyMetrics(), "totalAllocatedBytes");
        mergeMetric(metrics, jfr.keyMetrics(), "totalSampledOldObjectBytes");
        mergeMetric(metrics, gcLog.keyMetrics(), "fullGcCount");
        mergeMetric(metrics, gcLog.keyMetrics(), "maxFullGcPauseMs");
        mergeMetric(metrics, gcLog.keyMetrics(), "peakPostGcOccupancyRatio");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "topConsumerClass");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "payloadBytes");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "collectionBytes");
        mergeMetric(metrics, heapHistogram.keyMetrics(), "cacheLikeBytes");
        if (jfrGcTiming != null) {
            metrics.put("jfrGcTimeAlignmentStatus", jfrGcTiming.status());
        }
        if (heapTiming != null) {
            metrics.put("heapTimePlacementStatus", heapTiming.status());
        }

        String detail = "The JFR recording exposes heap-side pressure, the GC log exposes collector distress, and the heap histogram exposes retained-memory concentration.";
        int score = !sharedClasses.isEmpty() ? 100 : 92;
        if (!sharedClasses.isEmpty()) {
            detail += " Exact class overlap is present between the JFR recording and the heap histogram.";
        } else if (!sharedFamilies.isEmpty()) {
            detail += " The same object families are emphasized across the JFR recording and the heap histogram.";
        }
        if (isAbsoluteOverlap(jfrGcTiming)) {
            detail += " The GC distress interval also overlaps a JFR incident window in absolute time.";
            score += 5;
        } else if (jfrGcTiming != null && "NO_SHARED_CLOCK".equals(jfrGcTiming.status())) {
            detail += " A precise JFR-to-GC time match could not be confirmed because those artifacts do not share a reliable absolute clock.";
        }
        if (isAbsoluteOverlap(heapTiming)) {
            detail += " The heap histogram capture time also lands inside that timed incident window.";
            score += 4;
        } else if (isUntimedCompanion(heapTiming)) {
            detail += " The heap histogram does not expose a capture time, so its placement on that timeline remains unconfirmed.";
        }

        alignments.add(new SignalAlignment(
            "jfr-gc-heap-pressure-alignment",
            "JFR heap-side signals, GC distress, and heap retention coexist",
            detail,
            artifactPaths(jfr, gcLog, heapHistogram),
            artifactTypes(jfr, gcLog, heapHistogram),
            uniqueStrings(intersection(jfr.signalFamilies(), Set.of("allocation-pressure", "retention-pressure")), List.of("gc-distress", "retention-pressure")),
            sharedClasses,
            sharedFamilies,
            Map.copyOf(metrics),
            score
        ));
    }

    private void maybeAddJfrNativeAlignment(
        List<SignalAlignment> alignments,
        ArtifactSignalProfile jfr,
        ArtifactSignalProfile gcLog,
        List<ArtifactSignalProfile> nativeProfiles,
        CrossArtifactTimingSummary timingSummary
    ) {
        if (jfr == null || nativeProfiles == null || nativeProfiles.isEmpty()) {
            return;
        }
        List<String> heapSideSignals = overlap(jfr.signalFamilies(), List.of("gc-distress", "allocation-pressure", "retention-pressure"));
        if (heapSideSignals.isEmpty()) {
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        mergeMetric(metrics, jfr.keyMetrics(), "topAllocationClass");
        mergeMetric(metrics, jfr.keyMetrics(), "topOldObjectClass");
        mergeMetric(metrics, jfr.keyMetrics(), "totalAllocatedBytes");
        mergeMetric(metrics, jfr.keyMetrics(), "totalSampledOldObjectBytes");
        if (gcLog != null) {
            mergeMetric(metrics, gcLog.keyMetrics(), "fullGcCount");
            mergeMetric(metrics, gcLog.keyMetrics(), "peakPostGcOccupancyRatio");
        }
        for (ArtifactSignalProfile nativeProfile : nativeProfiles) {
            mergeMetric(metrics, nativeProfile.keyMetrics(), "committedDeltaKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "gcCommittedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "threadStackReservedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "metaspaceUsedKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "anonVirtualShare");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "anonResidentShare");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "anonSizeKb");
            mergeMetric(metrics, nativeProfile.keyMetrics(), "totalRssKb");
        }

        List<String> artifactPaths = new ArrayList<>();
        artifactPaths.addAll(artifactPaths(jfr));
        if (gcLog != null) {
            artifactPaths.addAll(artifactPaths(gcLog));
        }
        for (ArtifactSignalProfile nativeProfile : nativeProfiles) {
            artifactPaths.addAll(artifactPaths(nativeProfile));
        }

        List<ArtifactType> artifactTypes = new ArrayList<>();
        artifactTypes.add(jfr.artifactType());
        if (gcLog != null) {
            artifactTypes.add(gcLog.artifactType());
        }
        artifactTypes.addAll(nativeProfiles.stream().map(ArtifactSignalProfile::artifactType).toList());

        List<TimingAlignment> nativeTimingAlignments = new ArrayList<>();
        for (ArtifactSignalProfile nativeProfile : nativeProfiles) {
            TimingAlignment timingAlignment = companionTimingAlignment(timingSummary, nativeProfile.artifactType());
            if (timingAlignment != null) {
                nativeTimingAlignments.add(timingAlignment);
            }
        }
        long overlappingTimedNativePlacements = nativeTimingAlignments.stream().filter(this::isAbsoluteOverlap).count();
        long explicitNoOverlapTimedNativePlacements = nativeTimingAlignments.stream().filter(this::isAbsoluteNoOverlap).count();
        boolean hasUntimedNativePlacement = nativeTimingAlignments.stream().anyMatch(this::isUntimedCompanion);

        String detail = "The JFR recording carries " + joinedSignals(heapSideSignals)
            + ", and the memory artifacts carry native-pressure signals.";
        int score = gcLog != null ? 72 : 68;
        if (overlappingTimedNativePlacements > 0L) {
            List<String> overlappingTimedLabels = nativeTimingAlignments.stream()
                .filter(this::isAbsoluteOverlap)
                .map(this::timedCompanionLabel)
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .toList();
            detail += " The timed " + timedSnapshotPhrase(overlappingTimedLabels)
                + (overlappingTimedLabels.size() == 1 ? " also overlaps" : " also overlap")
                + " the timed JVM incident window.";
            score += 8;
        } else if (!nativeTimingAlignments.isEmpty() && explicitNoOverlapTimedNativePlacements == nativeTimingAlignments.size()) {
            detail += " The explicitly timed native-memory snapshots sit outside the timed JVM incident windows, so the coexistence may span different intervals.";
            score = Math.max(56, score - 8);
        } else if (hasUntimedNativePlacement) {
            detail += " Some native-memory snapshots do not expose capture times, so the exact timing relationship remains partial.";
        }

        LinkedHashMap<String, Object> enrichedMetrics = new LinkedHashMap<>(metrics);
        if (!nativeTimingAlignments.isEmpty()) {
            putIfPositiveLong(enrichedMetrics, "overlappingTimedNativePlacements", overlappingTimedNativePlacements);
            putIfPositiveLong(enrichedMetrics, "explicitNoOverlapTimedNativePlacements", explicitNoOverlapTimedNativePlacements);
            putIfPositiveLong(enrichedMetrics, "timedNativePlacementCount", nativeTimingAlignments.size());
        }

        alignments.add(new SignalAlignment(
            "jfr-native-pressure-alignment",
            "JFR heap-side signals and native-memory pressure coexist",
            detail,
            uniqueStrings(artifactPaths),
            uniqueArtifactTypes(artifactTypes),
            uniqueStrings(heapSideSignals, List.of("native-pressure")),
            List.of(),
            List.of(),
            Map.copyOf(enrichedMetrics),
            score
        ));
    }

    private void maybeAddJfrGcTimeAlignment(
        List<TimingAlignment> timingAlignments,
        ArtifactTimingProfile jfrTiming,
        ArtifactTimingProfile gcLogTiming
    ) {
        if (jfrTiming == null || gcLogTiming == null) {
            return;
        }

        Map<String, Object> jfrRecordingWindow = timingWindowById(jfrTiming, "recording-window");
        List<Map<String, Object>> jfrIncidentWindows = timingWindowsWithPrefix(jfrTiming, "incident-window:");
        Map<String, Object> gcDistressWindow = timingWindowById(gcLogTiming, "gc-distress-window");
        Map<String, Object> gcOverallWindow = timingWindowById(gcLogTiming, "gc-overall-window");
        Map<String, Object> effectiveGcWindow = !gcDistressWindow.isEmpty() ? gcDistressWindow : gcOverallWindow;

        Instant gcStart = windowStartInstant(effectiveGcWindow);
        Instant gcEnd = windowEndInstant(effectiveGcWindow);
        if (gcStart == null || gcEnd == null) {
            if ("ELAPSED_ONLY".equals(gcLogTiming.timingStatus())) {
                timingAlignments.add(new TimingAlignment(
                    "jfr-gc-time-alignment",
                    "NO_SHARED_CLOCK",
                    "JFR and GC log do not share a comparable absolute clock",
                    "The GC log only exposed elapsed seconds, so it cannot be placed on the same absolute timeline as the JFR recording without a shared JVM-start anchor.",
                    artifactPaths(jfrTiming, gcLogTiming),
                    artifactTypes(jfrTiming, gcLogTiming),
                    List.of(),
                    Map.of(),
                    50
                ));
            } else {
                timingAlignments.add(new TimingAlignment(
                    "jfr-gc-time-alignment",
                    "NO_SHARED_CLOCK",
                    "JFR and GC log cannot be time-aligned",
                    "One or both artifacts did not expose a reliable absolute window for cross-artifact time alignment.",
                    artifactPaths(jfrTiming, gcLogTiming),
                    artifactTypes(jfrTiming, gcLogTiming),
                    List.of(),
                    Map.of(),
                    40
                ));
            }
            return;
        }

        List<Map<String, Object>> candidateJfrWindows = jfrIncidentWindows.isEmpty()
            ? (jfrRecordingWindow.isEmpty() ? List.of() : List.of(jfrRecordingWindow))
            : jfrIncidentWindows;
        if (candidateJfrWindows.isEmpty()) {
            timingAlignments.add(new TimingAlignment(
                "jfr-gc-time-alignment",
                "NO_SHARED_CLOCK",
                "JFR and GC log cannot be time-aligned",
                "The GC log exposes an absolute window, but the JFR recording did not expose a usable incident or recording window for alignment.",
                artifactPaths(jfrTiming, gcLogTiming),
                artifactTypes(jfrTiming, gcLogTiming),
                List.of(),
                Map.of(),
                40
            ));
            return;
        }
        List<Map<String, Object>> overlappingJfrWindows = candidateJfrWindows.stream()
            .filter(window -> windowsOverlap(window, effectiveGcWindow))
            .toList();
        if (!overlappingJfrWindows.isEmpty()) {
            Map<String, Object> dominantJfrWindow = overlappingJfrWindows.getFirst();
            Instant overlapStart = laterInstant(windowStartInstant(dominantJfrWindow), gcStart);
            Instant overlapEnd = earlierInstant(windowEndInstant(dominantJfrWindow), gcEnd);
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
            putIfPresent(metrics, "overlapStartTime", overlapStart != null ? overlapStart.toString() : null);
            putIfPresent(metrics, "overlapEndTime", overlapEnd != null ? overlapEnd.toString() : null);
            if (overlapStart != null && overlapEnd != null && !overlapEnd.isBefore(overlapStart)) {
                putIfPositiveLong(metrics, "overlapDurationMs", Duration.between(overlapStart, overlapEnd).toMillis());
            }
            putIfPositiveLong(metrics, "overlappingWindowCount", overlappingJfrWindows.size());
            timingAlignments.add(new TimingAlignment(
                "jfr-gc-time-alignment",
                "ABSOLUTE_OVERLAP",
                "GC distress overlaps a JFR incident window",
                "The GC distress window overlaps the JFR incident timeline in wall-clock time, so the two artifacts are describing the same portion of the incident.",
                artifactPaths(jfrTiming, gcLogTiming),
                artifactTypes(jfrTiming, gcLogTiming),
                uniqueStrings(
                    overlappingJfrWindows.stream().map(window -> stringValue(window.get("windowId"))).filter(value -> value != null && !value.isBlank()).toList(),
                    List.of(stringValue(effectiveGcWindow.get("windowId")))
                ),
                Map.copyOf(metrics),
                100
            ));
            return;
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        putIfPresent(metrics, "jfrWindowStartTime", stringValue(candidateJfrWindows.getFirst().get("startTime")));
        putIfPresent(metrics, "jfrWindowEndTime", stringValue(candidateJfrWindows.getFirst().get("endTime")));
        putIfPresent(metrics, "gcWindowStartTime", stringValue(effectiveGcWindow.get("startTime")));
        putIfPresent(metrics, "gcWindowEndTime", stringValue(effectiveGcWindow.get("endTime")));
        timingAlignments.add(new TimingAlignment(
            "jfr-gc-time-alignment",
            "ABSOLUTE_NO_OVERLAP",
            "JFR and GC log have absolute clocks but different incident windows",
            "The JFR incident windows and the GC distress window do not overlap in wall-clock time, so they may describe different portions of JVM behavior.",
            artifactPaths(jfrTiming, gcLogTiming),
            artifactTypes(jfrTiming, gcLogTiming),
            List.of(stringValue(effectiveGcWindow.get("windowId"))),
            Map.copyOf(metrics),
            70
        ));
    }

    private void maybeAddTimedCompanionAlignment(
        List<TimingAlignment> timingAlignments,
        ArtifactTimingProfile companion,
        ArtifactTimingProfile jfrTiming,
        ArtifactTimingProfile gcLogTiming
    ) {
        if (companion == null) {
            return;
        }

        String alignmentId = companion.artifactType().name().toLowerCase(Locale.ROOT).replace('_', '-') + "-time-placement";
        String labelPrefix = humanArtifactType(companion.artifactType());
        if ("UNTIMED_COMPANION".equals(companion.timingStatus())) {
            timingAlignments.add(new TimingAlignment(
                alignmentId,
                "UNTIMED_COMPANION",
                labelPrefix + " has no explicit capture time",
                "The " + labelPrefix.toLowerCase(Locale.ROOT)
                    + " can support the same incident interpretation, but it cannot be placed on the same timeline because no explicit capture timestamp was provided.",
                artifactPaths(companion, jfrTiming, gcLogTiming),
                artifactTypes(companion, jfrTiming, gcLogTiming),
                List.of(),
                Map.of(),
                30
            ));
            return;
        }

        Map<String, Object> captureWindow = timingWindowById(companion, "capture-window");
        Instant captureStart = windowStartInstant(captureWindow);
        Instant captureEnd = windowEndInstant(captureWindow);
        if (captureStart == null || captureEnd == null) {
            return;
        }

        List<Map<String, Object>> referenceWindows = new ArrayList<>();
        referenceWindows.addAll(timingWindowsWithPrefix(jfrTiming, "incident-window:"));
        Map<String, Object> jfrRecordingWindow = timingWindowById(jfrTiming, "recording-window");
        if (referenceWindows.isEmpty() && !jfrRecordingWindow.isEmpty()) {
            referenceWindows.add(jfrRecordingWindow);
        }
        Map<String, Object> gcDistressWindow = timingWindowById(gcLogTiming, "gc-distress-window");
        Map<String, Object> gcOverallWindow = timingWindowById(gcLogTiming, "gc-overall-window");
        if (!gcDistressWindow.isEmpty()) {
            referenceWindows.add(gcDistressWindow);
        } else if (!gcOverallWindow.isEmpty()) {
            referenceWindows.add(gcOverallWindow);
        }

        List<Map<String, Object>> overlappingWindows = referenceWindows.stream()
            .filter(window -> windowsOverlap(captureWindow, window))
            .toList();
        if (!overlappingWindows.isEmpty()) {
            timingAlignments.add(new TimingAlignment(
                alignmentId,
                "ABSOLUTE_OVERLAP",
                labelPrefix + " capture time overlaps the timed JVM incident window",
                "The " + labelPrefix.toLowerCase(Locale.ROOT)
                    + " capture time falls inside a time-anchored JFR or GC window, so it can be placed on the shared incident timeline.",
                artifactPaths(companion, jfrTiming, gcLogTiming),
                artifactTypes(companion, jfrTiming, gcLogTiming),
                overlappingWindows.stream()
                    .map(window -> stringValue(window.get("windowId")))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList(),
                Map.of(
                    "captureStartTime", captureStart.toString(),
                    "captureEndTime", captureEnd.toString()
                ),
                60
            ));
            return;
        }

        if (referenceWindows.isEmpty()) {
            timingAlignments.add(new TimingAlignment(
                alignmentId,
                "NO_SHARED_CLOCK",
                labelPrefix + " has a capture time but no matching timed JVM window",
                "The " + labelPrefix.toLowerCase(Locale.ROOT)
                    + " exposes an explicit capture time, but the other artifacts did not expose a comparable absolute window for cross-artifact placement.",
                artifactPaths(companion, jfrTiming, gcLogTiming),
                artifactTypes(companion, jfrTiming, gcLogTiming),
                List.of("capture-window"),
                Map.of(
                    "captureStartTime", captureStart.toString(),
                    "captureEndTime", captureEnd.toString()
                ),
                35
            ));
            return;
        }

        timingAlignments.add(new TimingAlignment(
            alignmentId,
            "ABSOLUTE_NO_OVERLAP",
            labelPrefix + " capture time sits outside the timed JVM incident windows",
            "The " + labelPrefix.toLowerCase(Locale.ROOT)
                + " exposes an explicit capture time, but that time does not overlap the time-anchored JFR or GC windows.",
            artifactPaths(companion, jfrTiming, gcLogTiming),
            artifactTypes(companion, jfrTiming, gcLogTiming),
            List.of("capture-window"),
            Map.of(
                "captureStartTime", captureStart.toString(),
                "captureEndTime", captureEnd.toString()
            ),
            45
        ));
    }

    private void maybeAddHsErrNativeTimeAlignment(
        List<TimingAlignment> timingAlignments,
        ArtifactTimingProfile hsErrTiming,
        ArtifactTimingProfile nmtTiming,
        ArtifactTimingProfile pmapTiming
    ) {
        List<ArtifactTimingProfile> nativeTimings = new ArrayList<>();
        if (nmtTiming != null) {
            nativeTimings.add(nmtTiming);
        }
        if (pmapTiming != null) {
            nativeTimings.add(pmapTiming);
        }
        TimingAlignment alignment = buildPairwiseTimeAlignment(
            "hs-err-native-time-alignment",
            "hs_err crash time and native-memory snapshots",
            hsErrTiming,
            List.copyOf(nativeTimings),
            HS_ERR_NATIVE_SEQUENCE_TOLERANCE_SECONDS
        );
        if (alignment != null) {
            timingAlignments.add(alignment);
        }
    }

    private void maybeAddContainerOomTimeAlignment(
        List<TimingAlignment> timingAlignments,
        ArtifactTimingProfile containerTiming,
        ArtifactTimingProfile oomTiming
    ) {
        List<ArtifactTimingProfile> oomTimings = new ArrayList<>();
        if (oomTiming != null) {
            oomTimings.add(oomTiming);
        }
        TimingAlignment alignment = buildPairwiseTimeAlignment(
            "container-oom-time-alignment",
            "container-memory and OOM signal timing",
            containerTiming,
            List.copyOf(oomTimings),
            CONTAINER_OOM_SEQUENCE_TOLERANCE_SECONDS
        );
        if (alignment != null) {
            timingAlignments.add(alignment);
        }
    }

    private TimingAlignment buildPairwiseTimeAlignment(
        String alignmentId,
        String labelPrefix,
        ArtifactTimingProfile primary,
        List<ArtifactTimingProfile> companions,
        long sequenceToleranceSeconds
    ) {
        if (alignmentId == null || alignmentId.isBlank() || primary == null || companions == null || companions.isEmpty()) {
            return null;
        }

        List<ArtifactTimingProfile> presentCompanions = companions.stream()
            .filter(profile -> profile != null)
            .toList();
        if (presentCompanions.isEmpty()) {
            return null;
        }

        List<ArtifactTimingProfile> timedCompanions = presentCompanions.stream()
            .filter(this::hasAbsoluteTimingWindow)
            .toList();
        boolean primaryTimed = hasAbsoluteTimingWindow(primary);

        if (!primaryTimed || timedCompanions.isEmpty()) {
            return new TimingAlignment(
                alignmentId,
                "NO_SHARED_CLOCK",
                labelPrefix + " could not be placed on a shared absolute clock",
                "One or both artifact families did not expose a comparable absolute time window for precise cross-artifact placement.",
                uniqueStrings(
                    artifactPaths(primary),
                    presentCompanions.stream().flatMap(profile -> artifactPaths(profile).stream()).toList()
                ),
                artifactTypes(primary, presentCompanions),
                List.of(),
                Map.of(),
                35
            );
        }

        List<String> overlappingWindowIds = new ArrayList<>();
        long overlappingTimedCompanionCount = 0L;
        long explicitNoOverlapTimedCompanionCount = 0L;
        for (ArtifactTimingProfile companion : timedCompanions) {
            if (profilesOverlap(primary, companion)) {
                overlappingTimedCompanionCount++;
                overlappingWindowIds.addAll(overlappingWindowIds(primary, companion));
            } else {
                explicitNoOverlapTimedCompanionCount++;
            }
        }

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();
        putIfPositiveLong(metrics, "timedCompanionCount", timedCompanions.size());
        putIfPositiveLong(metrics, "overlappingTimedCompanionCount", overlappingTimedCompanionCount);
        putIfPositiveLong(metrics, "explicitNoOverlapTimedCompanionCount", explicitNoOverlapTimedCompanionCount);

        if (overlappingTimedCompanionCount > 0L) {
            return new TimingAlignment(
                alignmentId,
                "ABSOLUTE_OVERLAP",
                labelPrefix + " overlap in absolute time",
                "The explicitly timed artifact windows overlap, so they can be treated as the same incident interval.",
                uniqueStrings(
                    artifactPaths(primary),
                    presentCompanions.stream().flatMap(profile -> artifactPaths(profile).stream()).toList()
                ),
                artifactTypes(primary, presentCompanions),
                uniqueStrings(overlappingWindowIds),
                Map.copyOf(metrics),
                78
            );
        }

        SequentialGap sequentialGap = nearestSequentialGap(primary, timedCompanions, sequenceToleranceSeconds);
        if (sequentialGap != null) {
            metrics.put("sequenceDirection", sequentialGap.direction());
            putIfPositiveLong(metrics, "nearestSequentialGapSeconds", sequentialGap.gapSeconds());
            metrics.put("primarySequenceStartTime", sequentialGap.primaryStartTime().toString());
            metrics.put("primarySequenceEndTime", sequentialGap.primaryEndTime().toString());
            metrics.put("companionSequenceStartTime", sequentialGap.companionStartTime().toString());
            metrics.put("companionSequenceEndTime", sequentialGap.companionEndTime().toString());
            return new TimingAlignment(
                alignmentId,
                "ABSOLUTE_SEQUENCE_NEARBY",
                labelPrefix + " form a nearby absolute-time sequence",
                sequenceDetail(labelPrefix, sequentialGap),
                uniqueStrings(
                    artifactPaths(primary),
                    presentCompanions.stream().flatMap(profile -> artifactPaths(profile).stream()).toList()
                ),
                artifactTypes(primary, presentCompanions),
                sequentialGap.windowIds(),
                Map.copyOf(metrics),
                68
            );
        }

        return new TimingAlignment(
            alignmentId,
            "ABSOLUTE_NO_OVERLAP",
            labelPrefix + " fall on different absolute windows",
            "The explicitly timed artifact windows do not overlap, so they may describe different incidents.",
            uniqueStrings(
                artifactPaths(primary),
                presentCompanions.stream().flatMap(profile -> artifactPaths(profile).stream()).toList()
            ),
            artifactTypes(primary, presentCompanions),
            List.of(),
            Map.copyOf(metrics),
            60
        );
    }

    private SequentialGap nearestSequentialGap(
        ArtifactTimingProfile primary,
        List<ArtifactTimingProfile> companions,
        long sequenceToleranceSeconds
    ) {
        if (primary == null || companions == null || companions.isEmpty() || sequenceToleranceSeconds <= 0L) {
            return null;
        }
        SequentialGap best = null;
        for (Map<String, Object> primaryWindow : primary.timingWindows()) {
            Instant primaryStart = windowStartInstant(primaryWindow);
            Instant primaryEnd = windowEndInstant(primaryWindow);
            if (primaryStart == null || primaryEnd == null) {
                continue;
            }
            for (ArtifactTimingProfile companion : companions) {
                if (companion == null) {
                    continue;
                }
                for (Map<String, Object> companionWindow : companion.timingWindows()) {
                    Instant companionStart = windowStartInstant(companionWindow);
                    Instant companionEnd = windowEndInstant(companionWindow);
                    if (companionStart == null || companionEnd == null) {
                        continue;
                    }
                    long primaryBeforeGapSeconds = Duration.between(primaryEnd, companionStart).toSeconds();
                    if (primaryBeforeGapSeconds >= 0L && primaryBeforeGapSeconds <= sequenceToleranceSeconds) {
                        SequentialGap candidate = new SequentialGap(
                            "PRIMARY_BEFORE_COMPANION",
                            primaryBeforeGapSeconds,
                            primaryStart,
                            primaryEnd,
                            companionStart,
                            companionEnd,
                            uniqueStrings(rawStrings(stringValue(primaryWindow.get("windowId"))), rawStrings(stringValue(companionWindow.get("windowId"))))
                        );
                        if (best == null || candidate.gapSeconds() < best.gapSeconds()) {
                            best = candidate;
                        }
                    }
                    long primaryAfterGapSeconds = Duration.between(companionEnd, primaryStart).toSeconds();
                    if (primaryAfterGapSeconds >= 0L && primaryAfterGapSeconds <= sequenceToleranceSeconds) {
                        SequentialGap candidate = new SequentialGap(
                            "PRIMARY_AFTER_COMPANION",
                            primaryAfterGapSeconds,
                            primaryStart,
                            primaryEnd,
                            companionStart,
                            companionEnd,
                            uniqueStrings(rawStrings(stringValue(primaryWindow.get("windowId"))), rawStrings(stringValue(companionWindow.get("windowId"))))
                        );
                        if (best == null || candidate.gapSeconds() < best.gapSeconds()) {
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private String sequenceDetail(String labelPrefix, SequentialGap sequentialGap) {
        if (sequentialGap == null) {
            return labelPrefix + " did not expose a nearby sequence.";
        }
        String gapText = humanDuration(sequentialGap.gapSeconds());
        return switch (sequentialGap.direction()) {
            case "PRIMARY_BEFORE_COMPANION" -> "The primary artifact window ends about " + gapText
                + " before the companion window begins, which fits a nearby escalation sequence even though the windows do not overlap.";
            case "PRIMARY_AFTER_COMPANION" -> "The companion artifact window ends about " + gapText
                + " before the primary window begins, which fits a nearby escalation sequence even though the windows do not overlap.";
            default -> labelPrefix + " did not expose a nearby sequence.";
        };
    }

    private List<Map<String, Object>> gcTimelineEvents(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> gcCycles,
        List<Map<String, Object>> allocationStalls,
        List<Map<String, Object>> phaseSamples,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> timelineEvents = new ArrayList<>();
        timelineEvents.addAll(pauses);
        timelineEvents.addAll(gcCycles);
        timelineEvents.addAll(allocationStalls);
        timelineEvents.addAll(phaseSamples);
        timelineEvents.addAll(failureSignals);
        timelineEvents.sort(gcChronologyComparator());
        return List.copyOf(timelineEvents);
    }

    private List<Map<String, Object>> gcDistressEvents(
        List<Map<String, Object>> pauses,
        List<Map<String, Object>> failureSignals
    ) {
        List<Map<String, Object>> distressEvents = new ArrayList<>();
        for (Map<String, Object> pause : pauses) {
            if (isFullGcPause(pause) || isEvacuationFailurePause(pause)) {
                distressEvents.add(pause);
            }
        }
        distressEvents.addAll(failureSignals);
        distressEvents.sort(gcChronologyComparator());
        return List.copyOf(distressEvents);
    }

    private List<Map<String, Object>> bestGcEventCluster(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(gcChronologyComparator());
        List<Map<String, Object>> bestCluster = List.of();
        List<Map<String, Object>> currentCluster = new ArrayList<>();
        Map<String, Object> previous = null;
        for (Map<String, Object> event : orderedEvents) {
            if (currentCluster.isEmpty() || withinGcStreakGap(previous, event)) {
                currentCluster.add(event);
            } else {
                if (isBetterGcCluster(currentCluster, bestCluster)) {
                    bestCluster = List.copyOf(currentCluster);
                }
                currentCluster = new ArrayList<>();
                currentCluster.add(event);
            }
            previous = event;
        }
        if (isBetterGcCluster(currentCluster, bestCluster)) {
            bestCluster = List.copyOf(currentCluster);
        }
        return bestCluster;
    }

    private boolean withinGcStreakGap(Map<String, Object> previous, Map<String, Object> current) {
        if (previous == null || current == null) {
            return false;
        }
        if (hasElapsedSeconds(previous) && hasElapsedSeconds(current)) {
            double gapSeconds = Math.abs(doubleValue(current.get("elapsedSeconds")) - doubleValue(previous.get("elapsedSeconds")));
            if (gapSeconds <= GC_STREAK_MAX_GAP_SECONDS) {
                return true;
            }
        }
        long lineGap = Math.abs(longValue(current.get("lineNumber")) - longValue(previous.get("lineNumber")));
        return lineGap <= GC_STREAK_MAX_GAP_LINES;
    }

    private boolean isBetterGcCluster(List<Map<String, Object>> currentCluster, List<Map<String, Object>> bestCluster) {
        if (currentCluster == null || currentCluster.isEmpty()) {
            return false;
        }
        if (bestCluster == null || bestCluster.isEmpty()) {
            return true;
        }
        if (currentCluster.size() != bestCluster.size()) {
            return currentCluster.size() > bestCluster.size();
        }
        double currentWeight = gcClusterWeight(currentCluster);
        double bestWeight = gcClusterWeight(bestCluster);
        if (Double.compare(currentWeight, bestWeight) != 0) {
            return currentWeight > bestWeight;
        }
        return gcClusterSpanLines(currentCluster) < gcClusterSpanLines(bestCluster);
    }

    private double gcClusterWeight(List<Map<String, Object>> cluster) {
        double weight = 0.0d;
        for (Map<String, Object> event : cluster) {
            weight += Math.max(1.0d, gcDurationMs(event));
        }
        return weight;
    }

    private long gcClusterSpanLines(List<Map<String, Object>> cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long startLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).min().orElse(0L);
        long endLine = cluster.stream().mapToLong(event -> longValue(event.get("lineNumber"))).filter(line -> line > 0L).max().orElse(0L);
        return endLine >= startLine ? endLine - startLine : Long.MAX_VALUE;
    }

    private Map<String, Object> gcOverallTimingWindow(List<Map<String, Object>> timelineEvents) {
        if (timelineEvents == null || timelineEvents.isEmpty()) {
            return Map.of();
        }
        Instant start = timelineEvents.stream().map(this::gcEventStartInstant).filter(value -> value != null).min(Instant::compareTo).orElse(null);
        Instant end = timelineEvents.stream().map(this::gcEventEndInstant).filter(value -> value != null).max(Instant::compareTo).orElse(null);
        Double startSeconds = timelineEvents.stream()
            .map(event -> hasElapsedSeconds(event) ? doubleValue(event.get("elapsedSeconds")) : null)
            .filter(value -> value != null)
            .min(Double::compareTo)
            .orElse(null);
        Double endSeconds = timelineEvents.stream()
            .map(this::gcEventEndSeconds)
            .filter(value -> value != null)
            .max(Double::compareTo)
            .orElse(null);
        return timingWindowFromRange(
            "gc-overall-window",
            "GC log overall window",
            start,
            end,
            startSeconds,
            endSeconds,
            Map.of("eventCount", timelineEvents.size())
        );
    }

    private Map<String, Object> gcDistressTimingWindow(List<Map<String, Object>> distressCluster) {
        if (distressCluster == null || distressCluster.isEmpty()) {
            return Map.of();
        }
        Instant start = distressCluster.stream().map(this::gcEventStartInstant).filter(value -> value != null).min(Instant::compareTo).orElse(null);
        Instant end = distressCluster.stream().map(this::gcEventEndInstant).filter(value -> value != null).max(Instant::compareTo).orElse(null);
        Double startSeconds = distressCluster.stream()
            .map(event -> hasElapsedSeconds(event) ? doubleValue(event.get("elapsedSeconds")) : null)
            .filter(value -> value != null)
            .min(Double::compareTo)
            .orElse(null);
        Double endSeconds = distressCluster.stream()
            .map(this::gcEventEndSeconds)
            .filter(value -> value != null)
            .max(Double::compareTo)
            .orElse(null);

        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("eventCount", distressCluster.size());
        putIfPresent(details, "startGcId", stringValue(distressCluster.getFirst().get("gcId")));
        putIfPresent(details, "endGcId", stringValue(distressCluster.getLast().get("gcId")));
        putIfPositiveLong(details, "startLine", longValue(distressCluster.getFirst().get("lineNumber")));
        putIfPositiveLong(details, "endLine", longValue(distressCluster.getLast().get("lineNumber")));

        return timingWindowFromRange(
            "gc-distress-window",
            "GC distress window",
            start,
            end,
            startSeconds,
            endSeconds,
            Map.copyOf(details)
        );
    }

    private Map<String, Object> jfrIncidentTimingWindow(Map<String, Object> incidentWindow) {
        Instant start = instantValue(incidentWindow.get("startTime"));
        Instant end = instantValue(incidentWindow.get("endTime"));
        String windowId = stringValue(incidentWindow.get("windowId"));
        if (windowId == null || windowId.isBlank()) {
            windowId = "incident-window";
        }
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "focus", stringValue(incidentWindow.get("focus")));
        putIfPositiveLong(details, "eventCount", longValue(incidentWindow, "eventCount"));
        return timingWindowFromRange(
            "incident-window:" + windowId,
            "JFR incident window",
            start,
            end,
            null,
            null,
            Map.copyOf(details)
        );
    }

    private Map<String, Object> jfrJvmStartAnchorWindow(Map<String, Object> jvmRuntimeInfo, Instant jvmStartAnchor) {
        if (jvmStartAnchor == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("anchorSource", "jdk.JVMInformation.jvmStartTime");
        putIfPositiveLong(details, "recordingStartOffsetFromJvmStartMs", longValue(jvmRuntimeInfo, "recordingStartOffsetFromJvmStartMs"));
        return absoluteWindow(
            "jvm-start-anchor",
            "JVM start anchor",
            jvmStartAnchor,
            jvmStartAnchor,
            Map.copyOf(details)
        );
    }

    private Map<String, Object> absoluteWindow(String windowId, String label, Instant start, Instant end, Map<String, Object> details) {
        return timingWindowFromRange(windowId, label, start, end, null, null, details);
    }

    private Map<String, Object> anchorElapsedTimingWindow(Map<String, Object> window, Instant jvmStartTime) {
        if (window == null || window.isEmpty() || windowStartInstant(window) != null || jvmStartTime == null) {
            return window == null ? Map.of() : window;
        }
        Double startElapsedSeconds = optionalDoubleValue(window.get("startElapsedSeconds"));
        Double endElapsedSeconds = optionalDoubleValue(window.get("endElapsedSeconds"));
        if (startElapsedSeconds == null && endElapsedSeconds == null) {
            return window;
        }

        Instant anchoredStart = startElapsedSeconds != null
            ? jvmStartTime.plusMillis(Math.round(startElapsedSeconds * 1000.0d))
            : endElapsedSeconds != null ? jvmStartTime.plusMillis(Math.round(endElapsedSeconds * 1000.0d)) : null;
        Instant anchoredEnd = endElapsedSeconds != null
            ? jvmStartTime.plusMillis(Math.round(endElapsedSeconds * 1000.0d))
            : anchoredStart;
        if (anchoredStart != null && anchoredEnd != null && anchoredEnd.isBefore(anchoredStart)) {
            anchoredEnd = anchoredStart;
        }

        LinkedHashMap<String, Object> anchoredWindow = new LinkedHashMap<>(window);
        if (anchoredStart != null) {
            anchoredWindow.put("startTime", anchoredStart.toString());
        }
        if (anchoredEnd != null) {
            anchoredWindow.put("endTime", anchoredEnd.toString());
        }
        if (anchoredStart != null && anchoredEnd != null) {
            anchoredWindow.put("durationMs", Math.max(0L, Duration.between(anchoredStart, anchoredEnd).toMillis()));
        }
        anchoredWindow.put("anchoredBy", "jfr-jvm-start");
        return Map.copyOf(anchoredWindow);
    }

    private Map<String, Object> timingWindowFromRange(
        String windowId,
        String label,
        Instant start,
        Instant end,
        Double startSeconds,
        Double endSeconds,
        Map<String, Object> details
    ) {
        if (start == null && end == null && startSeconds == null && endSeconds == null) {
            return Map.of();
        }

        LinkedHashMap<String, Object> window = new LinkedHashMap<>();
        window.put("windowId", windowId);
        window.put("label", label);

        Instant effectiveStart = start != null ? start : end;
        Instant effectiveEnd = end != null ? end : start;
        if (effectiveStart != null && effectiveEnd != null && effectiveEnd.isBefore(effectiveStart)) {
            effectiveEnd = effectiveStart;
        }
        if (effectiveStart != null) {
            window.put("startTime", effectiveStart.toString());
        }
        if (effectiveEnd != null) {
            window.put("endTime", effectiveEnd.toString());
        }
        if (effectiveStart != null && effectiveEnd != null) {
            window.put("durationMs", Math.max(0L, Duration.between(effectiveStart, effectiveEnd).toMillis()));
        }

        if (startSeconds != null) {
            window.put("startElapsedSeconds", startSeconds);
        }
        if (endSeconds != null) {
            window.put("endElapsedSeconds", endSeconds);
        }
        if (details != null && !details.isEmpty()) {
            window.putAll(details);
        }
        return Map.copyOf(window);
    }

    private Comparator<Map<String, Object>> gcChronologyComparator() {
        return Comparator
            .comparing((Map<String, Object> event) -> gcEventStartInstant(event), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingDouble(event -> hasElapsedSeconds(event) ? doubleValue(event.get("elapsedSeconds")) : Double.MAX_VALUE)
            .thenComparingLong(event -> longValue(event.get("lineNumber")));
    }

    private Instant gcEventStartInstant(Map<String, Object> event) {
        return instantValue(event.get("absoluteTimestamp"));
    }

    private Instant gcEventEndInstant(Map<String, Object> event) {
        Instant start = gcEventStartInstant(event);
        if (start == null) {
            return null;
        }
        return start.plusMillis(Math.max(0L, Math.round(gcDurationMs(event))));
    }

    private Double gcEventEndSeconds(Map<String, Object> event) {
        if (!hasElapsedSeconds(event)) {
            return null;
        }
        return doubleValue(event.get("elapsedSeconds")) + (gcDurationMs(event) / 1000.0d);
    }

    private double gcDurationMs(Map<String, Object> event) {
        return Math.max(
            0.0d,
            Math.max(
                doubleValue(event.get("pauseMs")),
                Math.max(doubleValue(event.get("durationMs")), doubleValue(event.get("stallMs")))
            )
        );
    }

    private boolean hasElapsedSeconds(Map<String, Object> event) {
        return event != null && event.containsKey("elapsedSeconds");
    }

    private boolean isFullGcPause(Map<String, Object> pause) {
        return stringValue(pause.getOrDefault("event", "")).toLowerCase(Locale.ROOT).contains("full");
    }

    private boolean isEvacuationFailurePause(Map<String, Object> pause) {
        return stringValue(pause.getOrDefault("event", "")).toLowerCase(Locale.ROOT).contains("evacuation failure");
    }

    private Map<String, Object> timingWindowById(ArtifactTimingProfile profile, String windowId) {
        if (profile == null || profile.timingWindows().isEmpty() || windowId == null || windowId.isBlank()) {
            return Map.of();
        }
        return profile.timingWindows().stream()
            .filter(window -> windowId.equals(stringValue(window.get("windowId"))))
            .findFirst()
            .orElse(Map.of());
    }

    private List<Map<String, Object>> timingWindowsWithPrefix(ArtifactTimingProfile profile, String prefix) {
        if (profile == null || profile.timingWindows().isEmpty() || prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return profile.timingWindows().stream()
            .filter(window -> {
                String windowId = stringValue(window.get("windowId"));
                return windowId != null && windowId.startsWith(prefix);
            })
            .toList();
    }

    private boolean windowsOverlap(Map<String, Object> first, Map<String, Object> second) {
        Instant firstStart = windowStartInstant(first);
        Instant firstEnd = windowEndInstant(first);
        Instant secondStart = windowStartInstant(second);
        Instant secondEnd = windowEndInstant(second);
        if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) {
            return false;
        }
        return !firstStart.isAfter(secondEnd) && !firstEnd.isBefore(secondStart);
    }

    private Instant windowStartInstant(Map<String, Object> window) {
        return window == null || window.isEmpty() ? null : instantValue(window.get("startTime"));
    }

    private Instant windowEndInstant(Map<String, Object> window) {
        if (window == null || window.isEmpty()) {
            return null;
        }
        Instant end = instantValue(window.get("endTime"));
        return end != null ? end : instantValue(window.get("startTime"));
    }

    private Instant earlierInstant(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private Instant laterInstant(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private boolean hasAbsoluteTimingWindow(ArtifactTimingProfile profile) {
        return profile != null
            && profile.timingWindows().stream().anyMatch(window -> windowStartInstant(window) != null && windowEndInstant(window) != null);
    }

    private boolean profilesOverlap(ArtifactTimingProfile left, ArtifactTimingProfile right) {
        if (left == null || right == null) {
            return false;
        }
        for (Map<String, Object> leftWindow : left.timingWindows()) {
            for (Map<String, Object> rightWindow : right.timingWindows()) {
                if (windowsOverlap(leftWindow, rightWindow)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> overlappingWindowIds(ArtifactTimingProfile left, ArtifactTimingProfile right) {
        if (left == null || right == null) {
            return List.of();
        }
        LinkedHashSet<String> windowIds = new LinkedHashSet<>();
        for (Map<String, Object> leftWindow : left.timingWindows()) {
            for (Map<String, Object> rightWindow : right.timingWindows()) {
                if (!windowsOverlap(leftWindow, rightWindow)) {
                    continue;
                }
                putIfPresent(windowIds, stringValue(leftWindow.get("windowId")));
                putIfPresent(windowIds, stringValue(rightWindow.get("windowId")));
            }
        }
        return List.copyOf(windowIds);
    }

    private void putIfPresent(Set<String> target, String value) {
        if (target != null && value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private List<ArtifactType> artifactTypes(ArtifactTimingProfile primary, List<ArtifactTimingProfile> companions) {
        LinkedHashSet<ArtifactType> types = new LinkedHashSet<>();
        if (primary != null && primary.artifactType() != null) {
            types.add(primary.artifactType());
        }
        if (companions != null) {
            for (ArtifactTimingProfile companion : companions) {
                if (companion != null && companion.artifactType() != null) {
                    types.add(companion.artifactType());
                }
            }
        }
        return List.copyOf(types);
    }

    private String explicitCaptureLabel(ArtifactType artifactType) {
        return switch (artifactType) {
            case THREAD_DUMP -> "Thread dump capture time";
            case HEAP_HISTOGRAM -> "Heap histogram capture time";
            case HS_ERR_LOG -> "hs_err crash time";
            case NMT -> "NMT capture time";
            case PMAP -> "pmap capture time";
            case CONTAINER_MEMORY -> "Container-memory capture time";
            case OOM_SIGNAL -> "OOM signal event time";
            default -> "Artifact capture time";
        };
    }

    private String humanArtifactType(ArtifactType artifactType) {
        return switch (artifactType) {
            case THREAD_DUMP -> "Thread dump";
            case HEAP_HISTOGRAM -> "Heap histogram";
            case HS_ERR_LOG -> "hs_err log";
            case NMT -> "NMT output";
            case PMAP -> "pmap output";
            case CONTAINER_MEMORY -> "Container-memory snapshot";
            case OOM_SIGNAL -> "OOM or restart signal";
            case JFR -> "JFR recording";
            case GC_LOG -> "GC log";
            default -> artifactType != null ? artifactType.name().toLowerCase(Locale.ROOT).replace('_', ' ') : "artifact";
        };
    }

    private TimingAlignment companionTimingAlignment(CrossArtifactTimingSummary timingSummary, ArtifactType artifactType) {
        if (timingSummary == null || artifactType == null) {
            return null;
        }
        String alignmentId = switch (artifactType) {
            case THREAD_DUMP -> "thread-dump-time-placement";
            case HEAP_HISTOGRAM -> "heap-histogram-time-placement";
            case HS_ERR_LOG -> "hs-err-log-time-placement";
            case NMT -> "nmt-time-placement";
            case PMAP -> "pmap-time-placement";
            case CONTAINER_MEMORY -> "container-memory-time-placement";
            case OOM_SIGNAL -> "oom-signal-time-placement";
            default -> null;
        };
        return alignmentId != null ? timingSummary.alignment(alignmentId) : null;
    }

    private boolean isAbsoluteOverlap(TimingAlignment alignment) {
        return alignment != null && "ABSOLUTE_OVERLAP".equals(alignment.status());
    }

    private boolean isAbsoluteNoOverlap(TimingAlignment alignment) {
        return alignment != null && "ABSOLUTE_NO_OVERLAP".equals(alignment.status());
    }

    private boolean isUntimedCompanion(TimingAlignment alignment) {
        return alignment != null && "UNTIMED_COMPANION".equals(alignment.status());
    }

    private String timedCompanionLabel(TimingAlignment alignment) {
        if (alignment == null || alignment.artifactTypes().isEmpty()) {
            return null;
        }
        return alignment.artifactTypes().stream()
            .filter(TIMED_COMPANION_ARTIFACT_TYPES::contains)
            .findFirst()
            .map(this::humanArtifactType)
            .orElse(null);
    }

    private Instant firstAttributeInstant(Map<String, String> attributes, List<String> keys) {
        if (attributes == null || attributes.isEmpty() || keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Instant instant = instantValue(attributes.get(key));
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    rendered.add(text);
                }
            }
        }
        return List.copyOf(rendered);
    }

    private List<String> uniqueLimitedStrings(List<String> primary, List<String>... more) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : primary) {
            String normalized = normalizeClassName(value);
            if (normalized != null && !normalized.isBlank()) {
                values.add(normalized);
            }
            if (values.size() >= MAX_EXACT_CLASSES) {
                return List.copyOf(values);
            }
        }
        for (List<String> candidates : more) {
            for (String candidate : candidates) {
                String normalized = normalizeClassName(candidate);
                if (normalized != null && !normalized.isBlank()) {
                    values.add(normalized);
                }
                if (values.size() >= MAX_EXACT_CLASSES) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> rawStrings(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                rendered.add(value);
            }
        }
        return List.copyOf(rendered);
    }

    private List<String> classFamilies(List<String> exactClasses) {
        LinkedHashSet<String> families = new LinkedHashSet<>();
        for (String exactClass : exactClasses) {
            String normalized = normalizeClassName(exactClass);
            if (normalized == null) {
                continue;
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            if ("byte[]".equals(normalized) || "char[]".equals(normalized) || "java.lang.String".equals(normalized)) {
                families.add("payload");
            }
            if (lower.contains("map")
                || lower.contains("list")
                || lower.contains("set")
                || lower.contains("queue")
                || lower.contains("deque")
                || lower.contains("collection")
                || lower.contains("$entry")
                || lower.contains("$node")) {
                families.add("collections");
            }
            if (lower.contains("cache")) {
                families.add("cache");
            }
            if (lower.contains("thread")) {
                families.add("threads");
            }
        }
        return List.copyOf(families).stream().limit(MAX_FAMILY_CLASSES).toList();
    }

    private List<String> overlap(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, String> rightByNormalized = new LinkedHashMap<>();
        for (String value : right) {
            if (value == null || value.isBlank()) {
                continue;
            }
            rightByNormalized.putIfAbsent(value.trim().toLowerCase(Locale.ROOT), value.trim());
        }

        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (String value : left) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (rightByNormalized.containsKey(normalized)) {
                matches.add(value.trim());
            }
        }
        return List.copyOf(matches);
    }

    private List<String> intersection(List<String> values, Set<String> candidates) {
        if (values == null || values.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && candidates.contains(value))
            .distinct()
            .toList();
    }

    private List<String> uniqueStrings(List<String>... groups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String value : group) {
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<Map<String, Object>> jfrSignalEvents(List<Map<String, Object>> timelineEvents, String signalFamily) {
        if (timelineEvents == null || timelineEvents.isEmpty() || signalFamily == null || signalFamily.isBlank()) {
            return List.of();
        }
        return timelineEvents.stream()
            .filter(event -> signalFamily.equals(stringValue(event.get("signalFamily"))))
            .toList();
    }

    private List<Map<String, Object>> jfrLockEventTypeDetails(List<Map<String, Object>> eventTypeDetails) {
        if (eventTypeDetails == null || eventTypeDetails.isEmpty()) {
            return List.of();
        }
        return eventTypeDetails.stream()
            .filter(detail -> isLockContentionEventName(stringValue(detail.get("name"))))
            .toList();
    }

    private List<String> jfrTopThreads(List<Map<String, Object>> eventTypeDetails) {
        List<String> collected = new ArrayList<>();
        if (eventTypeDetails == null) {
            return List.of();
        }
        for (Map<String, Object> detail : eventTypeDetails) {
            collected.addAll(listOfMapsStrings(detail.get("topThreads"), "thread", MAX_JOIN_THREADS));
            if (collected.size() >= MAX_JOIN_THREADS) {
                break;
            }
        }
        return joinThreadNames(collected);
    }

    private List<String> jfrSampleEventThreads(List<Map<String, Object>> eventTypeDetails) {
        List<String> collected = new ArrayList<>();
        if (eventTypeDetails == null) {
            return List.of();
        }
        for (Map<String, Object> detail : eventTypeDetails) {
            for (Map<String, Object> sampleEvent : listOfMaps(detail.get("sampleEvents"))) {
                String eventThread = stringValue(sampleEvent.get("eventThread"));
                if (eventThread != null) {
                    collected.add(eventThread);
                }
                if (collected.size() >= MAX_JOIN_THREADS) {
                    return joinThreadNames(collected);
                }
            }
        }
        return joinThreadNames(collected);
    }

    private List<String> jfrSampleFieldThreads(List<Map<String, Object>> eventTypeDetails) {
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        if (eventTypeDetails == null) {
            return List.of();
        }
        for (Map<String, Object> detail : eventTypeDetails) {
            for (Map<String, Object> sampleEvent : listOfMaps(detail.get("sampleEvents"))) {
                Map<String, Object> fields = mapValue(sampleEvent.get("fields"));
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (!isThreadishField(entry.getKey())) {
                        continue;
                    }
                    extractThreadNames(entry.getValue(), collected);
                    if (collected.size() >= MAX_JOIN_THREADS) {
                        return List.copyOf(collected);
                    }
                }
            }
        }
        return List.copyOf(collected);
    }

    private List<String> jfrSampleFieldLockKeys(List<Map<String, Object>> eventTypeDetails) {
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        if (eventTypeDetails == null) {
            return List.of();
        }
        for (Map<String, Object> detail : eventTypeDetails) {
            for (Map<String, Object> sampleEvent : listOfMaps(detail.get("sampleEvents"))) {
                Map<String, Object> fields = mapValue(sampleEvent.get("fields"));
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (!isLockishField(entry.getKey())) {
                        continue;
                    }
                    extractLockKeys(entry.getValue(), collected);
                    if (collected.size() >= MAX_JOIN_LOCK_KEYS) {
                        return List.copyOf(collected);
                    }
                }
            }
        }
        return List.copyOf(collected);
    }

    private List<String> jfrSampleEventMethods(List<Map<String, Object>> eventTypeDetails) {
        List<String> collected = new ArrayList<>();
        if (eventTypeDetails == null) {
            return List.of();
        }
        for (Map<String, Object> detail : eventTypeDetails) {
            for (Map<String, Object> sampleEvent : listOfMaps(detail.get("sampleEvents"))) {
                String topMethod = stringValue(sampleEvent.get("topMethod"));
                if (topMethod != null) {
                    collected.add(topMethod);
                }
                if (collected.size() >= MAX_JOIN_FRAMES) {
                    return joinMethodIdentities(collected);
                }
            }
        }
        return joinMethodIdentities(collected);
    }

    private List<String> contentionThreadNames(List<Map<String, Object>> contentionHotspots) {
        List<String> collected = new ArrayList<>();
        if (contentionHotspots == null) {
            return List.of();
        }
        for (Map<String, Object> hotspot : contentionHotspots) {
            String ownerThreadName = stringValue(hotspot.get("ownerThreadName"));
            if (ownerThreadName != null) {
                collected.add(ownerThreadName);
            }
            collected.addAll(listOfStrings(hotspot.get("waitingThreadNames")));
            if (collected.size() >= MAX_JOIN_THREADS) {
                break;
            }
        }
        return joinThreadNames(collected);
    }

    private List<String> blockedOrDeadlockedThreadNames(List<Map<String, Object>> threads) {
        List<String> collected = new ArrayList<>();
        if (threads == null) {
            return List.of();
        }
        for (Map<String, Object> thread : threads) {
            String state = stringValue(thread.get("state"));
            if (!"BLOCKED".equals(state) && !booleanValue(thread.get("deadlocked"))) {
                continue;
            }
            String name = stringValue(thread.get("name"));
            if (name != null) {
                collected.add(name);
            }
            if (collected.size() >= MAX_JOIN_THREADS) {
                break;
            }
        }
        return joinThreadNames(collected);
    }

    private List<String> blockedOrDeadlockedTopFrames(List<Map<String, Object>> threads) {
        List<String> collected = new ArrayList<>();
        if (threads == null) {
            return List.of();
        }
        for (Map<String, Object> thread : threads) {
            String state = stringValue(thread.get("state"));
            if (!"BLOCKED".equals(state) && !booleanValue(thread.get("deadlocked"))) {
                continue;
            }
            String topFrame = stringValue(thread.get("topFrame"));
            if (topFrame != null) {
                collected.add(topFrame);
            }
            if (collected.size() >= MAX_JOIN_FRAMES) {
                break;
            }
        }
        return joinMethodIdentities(collected);
    }

    private List<String> threadMonitorIds(List<Map<String, Object>> threads) {
        List<String> collected = new ArrayList<>();
        if (threads == null) {
            return List.of();
        }
        for (Map<String, Object> thread : threads) {
            collected.addAll(listOfStrings(thread.get("waitingToLockIds")));
            collected.addAll(listOfStrings(thread.get("lockedMonitorIds")));
            if (collected.size() >= MAX_JOIN_LOCK_KEYS) {
                break;
            }
        }
        return joinLockKeys(collected);
    }

    private List<String> listOfMapsStrings(Object value, String key, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty() || key == null || key.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String rendered = stringValue(map.get(key));
            if (rendered != null) {
                values.add(rendered);
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private List<String> joinThreadNames(List<String>... groups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (groups == null) {
            return List.of();
        }
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String candidate : group) {
                String normalized = normalizeThreadName(candidate);
                if (normalized != null) {
                    values.add(normalized);
                }
                if (values.size() >= MAX_JOIN_THREADS) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> joinPoolNames(List<String> threadNames, List<String>... additionalPoolGroups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (threadNames != null) {
            for (String threadName : threadNames) {
                String poolName = poolNameForThread(threadName);
                if (poolName != null) {
                    values.add(poolName);
                }
                if (values.size() >= MAX_JOIN_POOLS) {
                    return List.copyOf(values);
                }
            }
        }
        if (additionalPoolGroups != null) {
            for (List<String> group : additionalPoolGroups) {
                if (group == null) {
                    continue;
                }
                for (String candidate : group) {
                    String normalized = normalizePoolName(candidate);
                    if (normalized != null) {
                        values.add(normalized);
                    }
                    if (values.size() >= MAX_JOIN_POOLS) {
                        return List.copyOf(values);
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> joinLockKeys(List<String>... groups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (groups == null) {
            return List.of();
        }
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String candidate : group) {
                String normalized = normalizeLockKey(candidate);
                if (normalized != null) {
                    values.add(normalized);
                }
                if (values.size() >= MAX_JOIN_LOCK_KEYS) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> joinMethodIdentities(List<String>... groups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (groups == null) {
            return List.of();
        }
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String candidate : group) {
                String normalized = normalizeMethodIdentity(candidate);
                if (normalized != null) {
                    values.add(normalized);
                }
                if (values.size() >= MAX_JOIN_FRAMES) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private void extractThreadNames(Object value, LinkedHashSet<String> target) {
        if (value == null || target == null || target.size() >= MAX_JOIN_THREADS) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : null;
                if (key == null || (!isThreadishField(key) && !isNameField(key))) {
                    continue;
                }
                extractThreadNames(entry.getValue(), target);
                if (target.size() >= MAX_JOIN_THREADS) {
                    return;
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                extractThreadNames(item, target);
                if (target.size() >= MAX_JOIN_THREADS) {
                    return;
                }
            }
            return;
        }
        String normalized = normalizeThreadName(String.valueOf(value));
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private void extractLockKeys(Object value, LinkedHashSet<String> target) {
        if (value == null || target == null || target.size() >= MAX_JOIN_LOCK_KEYS) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : null;
                if (key != null && (isLockishField(key) || isNameField(key))) {
                    extractLockKeys(entry.getValue(), target);
                }
                if (target.size() >= MAX_JOIN_LOCK_KEYS) {
                    return;
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                extractLockKeys(item, target);
                if (target.size() >= MAX_JOIN_LOCK_KEYS) {
                    return;
                }
            }
            return;
        }
        String normalized = normalizeLockKey(String.valueOf(value));
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private String normalizeThreadName(String threadName) {
        if (threadName == null) {
            return null;
        }
        String normalized = threadName.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizePoolName(String poolName) {
        if (poolName == null) {
            return null;
        }
        String normalized = poolName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (!looksLikePoolName(normalized)) {
            return null;
        }
        return normalized;
    }

    private String poolNameForThread(String threadName) {
        String normalized = normalizeThreadName(threadName);
        if (normalized == null) {
            return null;
        }
        Matcher matcher = TRAILING_THREAD_INDEX_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            String candidate = matcher.group(1);
            if (looksLikePoolName(candidate)) {
                return candidate;
            }
        }
        return looksLikePoolName(normalized) ? normalized : null;
    }

    private boolean looksLikePoolName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("pool")
            || lower.contains("exec")
            || lower.contains("worker")
            || lower.contains("executor")
            || lower.contains("scheduler");
    }

    private String normalizeMethodIdentity(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher matcher = THREAD_DUMP_METHOD_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            trimmed = matcher.group(1);
        }
        int parenIndex = trimmed.indexOf('(');
        if (parenIndex > 0) {
            trimmed = trimmed.substring(0, parenIndex);
        }
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace > 0) {
            trimmed = trimmed.substring(0, firstSpace);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeLockKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (HEX_LOCK_KEY_PATTERN.matcher(trimmed).matches()) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        String normalizedClassName = normalizeClassName(trimmed);
        if (normalizedClassName != null && (normalizedClassName.contains(".") || normalizedClassName.contains("$") || normalizedClassName.endsWith("[]"))) {
            return normalizedClassName;
        }
        return null;
    }

    private boolean isThreadishField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("thread")
            || normalized.contains("owner")
            || normalized.contains("notifier")
            || normalized.contains("locker");
    }

    private boolean isLockContentionEventName(String eventTypeName) {
        String normalized = eventTypeName == null ? "" : eventTypeName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("javamonitorblocked") || normalized.contains("monitorenter");
    }

    private boolean isLockishField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("monitor")
            || normalized.contains("address")
            || normalized.endsWith("class")
            || normalized.contains("objectclass");
    }

    private boolean isNameField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("name")
            || normalized.equals("javaname")
            || normalized.equals("osname")
            || normalized.equals("type")
            || normalized.equals("description");
    }

    private void putIfNotEmpty(Map<String, Object> target, String key, List<String> values) {
        if (target != null && key != null && values != null && !values.isEmpty()) {
            target.put(key, values);
        }
    }

    private String joinedExamples(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values.stream().limit(3).toList());
    }

    private String joinedClauses(List<String> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return "no concrete joins";
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        if (clauses.size() == 2) {
            return clauses.get(0) + " and " + clauses.get(1);
        }
        return String.join(", ", clauses.subList(0, clauses.size() - 1)) + ", and " + clauses.getLast();
    }

    private String humanDuration(long seconds) {
        if (seconds <= 0L) {
            return "0s";
        }
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes < 60L) {
            return remainingSeconds == 0L ? minutes + "m" : minutes + "m " + remainingSeconds + "s";
        }
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        return remainingMinutes == 0L ? hours + "h" : hours + "h " + remainingMinutes + "m";
    }

    private List<String> artifactPaths(ArtifactSignalProfile... profiles) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (profiles == null) {
            return List.of();
        }
        for (ArtifactSignalProfile profile : profiles) {
            if (profile != null && profile.sourcePath() != null && !profile.sourcePath().isBlank()) {
                paths.add(profile.sourcePath());
            }
        }
        return List.copyOf(paths);
    }

    private List<String> artifactPaths(ArtifactTimingProfile... profiles) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (profiles == null) {
            return List.of();
        }
        for (ArtifactTimingProfile profile : profiles) {
            if (profile != null && profile.sourcePath() != null && !profile.sourcePath().isBlank()) {
                paths.add(profile.sourcePath());
            }
        }
        return List.copyOf(paths);
    }

    private List<ArtifactType> artifactTypes(ArtifactSignalProfile... profiles) {
        LinkedHashSet<ArtifactType> types = new LinkedHashSet<>();
        if (profiles == null) {
            return List.of();
        }
        for (ArtifactSignalProfile profile : profiles) {
            if (profile != null && profile.artifactType() != null) {
                types.add(profile.artifactType());
            }
        }
        return List.copyOf(types);
    }

    private List<ArtifactType> artifactTypes(ArtifactTimingProfile... profiles) {
        LinkedHashSet<ArtifactType> types = new LinkedHashSet<>();
        if (profiles == null) {
            return List.of();
        }
        for (ArtifactTimingProfile profile : profiles) {
            if (profile != null && profile.artifactType() != null) {
                types.add(profile.artifactType());
            }
        }
        return List.copyOf(types);
    }

    private List<ArtifactType> uniqueArtifactTypes(List<ArtifactType> artifactTypes) {
        if (artifactTypes == null || artifactTypes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ArtifactType> unique = new LinkedHashSet<>();
        for (ArtifactType artifactType : artifactTypes) {
            if (artifactType != null) {
                unique.add(artifactType);
            }
        }
        return List.copyOf(unique);
    }

    private String joinedSignals(List<String> signalFamilies) {
        if (signalFamilies == null || signalFamilies.isEmpty()) {
            return "no shared signal families";
        }
        return String.join(", ", signalFamilies);
    }

    private String joinedLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "artifact";
        }
        return String.join(" and ", labels);
    }

    private String timedSnapshotPhrase(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "artifact snapshots";
        }
        if (labels.size() == 1) {
            return labels.getFirst() + " snapshot";
        }
        return joinedLabels(labels) + " snapshots";
    }

    private void mergeMetric(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || target.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value instanceof String text) {
            if (!text.isBlank()) {
                target.put(key, text);
            }
            return;
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric > 0.0d) {
                target.put(key, value);
            }
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            converted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(converted);
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Object item : list) {
            converted.add(mapValue(item));
        }
        return List.copyOf(converted.stream().filter(map -> !map.isEmpty()).toList());
    }

    private List<String> listOfMapsClassNames(Object value, String key, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object className = map.get(key);
            String normalized = normalizeClassName(stringValue(className));
            if (normalized != null && !normalized.isBlank()) {
                values.add(normalized);
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private String normalizeClassName(String className) {
        if (className == null) {
            return null;
        }
        String trimmed = className.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return switch (trimmed) {
            case "[B", "byte[]" -> "byte[]";
            case "[C", "char[]" -> "char[]";
            case "[I", "int[]" -> "int[]";
            case "[J", "long[]" -> "long[]";
            case "[S", "short[]" -> "short[]";
            case "[Z", "boolean[]" -> "boolean[]";
            case "[F", "float[]" -> "float[]";
            case "[D", "double[]" -> "double[]";
            default -> {
                if (trimmed.startsWith("[L") && trimmed.endsWith(";")) {
                    yield trimmed.substring(2, trimmed.length() - 1).replace('/', '.') + "[]";
                }
                yield trimmed;
            }
        };
    }

    private String stringValue(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private Instant instantValue(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
        }
        String normalized = normalizeOffsetTimestamp(text);
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeOffsetTimestamp(String text) {
        if (text == null) {
            return null;
        }
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return text;
        }
        if (text.matches(".*[+-]\\d{4}$")) {
            return text.substring(0, text.length() - 5)
                + text.substring(text.length() - 5, text.length() - 2)
                + ":"
                + text.substring(text.length() - 2);
        }
        return text;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long nestedLongValue(Map<String, Map<String, Long>> source, String outerKey, String innerKey) {
        if (source == null || source.isEmpty()) {
            return 0L;
        }
        Map<String, Long> nested = source.get(outerKey);
        if (nested == null || nested.isEmpty()) {
            return 0L;
        }
        return nested.getOrDefault(innerKey, 0L);
    }

    private double doubleValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private Double optionalDoubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private long firstPositiveLong(Map<String, Object> primary, String primaryKey, Map<String, Object> secondary, String secondaryKey) {
        long primaryValue = longValue(primary, primaryKey);
        return primaryValue > 0L ? primaryValue : longValue(secondary, secondaryKey);
    }

    private double firstPositiveDouble(
        Map<String, Object> primary,
        String primaryKey,
        Map<String, Object> secondary,
        String secondaryKey
    ) {
        double primaryValue = doubleValue(primary, primaryKey);
        return primaryValue > 0.0d ? primaryValue : doubleValue(secondary, secondaryKey);
    }

    private double firstPositiveDouble(
        Map<String, Object> first,
        String firstKey,
        Map<String, Object> second,
        String secondKey,
        Map<String, Object> third,
        String thirdKey
    ) {
        double firstValue = doubleValue(first, firstKey);
        if (firstValue > 0.0d) {
            return firstValue;
        }
        double secondValue = doubleValue(second, secondKey);
        return secondValue > 0.0d ? secondValue : doubleValue(third, thirdKey);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return (double) numerator / (double) denominator;
    }

    private void putIfPositiveLong(Map<String, Object> target, String key, long value) {
        if (value > 0L) {
            target.put(key, value);
        }
    }

    private void putIfPositiveDouble(Map<String, Object> target, String key, double value) {
        if (value > 0.0d) {
            target.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String sourcePath(ParsedArtifact parsedArtifact) {
        return parsedArtifact != null && parsedArtifact.metadata() != null ? parsedArtifact.metadata().sourcePath() : null;
    }

    private record SequentialGap(
        String direction,
        long gapSeconds,
        Instant primaryStartTime,
        Instant primaryEndTime,
        Instant companionStartTime,
        Instant companionEndTime,
        List<String> windowIds
    ) {
    }

    public record CrossArtifactSignalSummary(
        List<ArtifactSignalProfile> artifactSignals,
        List<SignalAlignment> strongestAlignments,
        CrossArtifactTimingSummary crossArtifactTiming
    ) {

        public CrossArtifactSignalSummary {
            artifactSignals = artifactSignals == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(artifactSignals));
            strongestAlignments = strongestAlignments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(strongestAlignments));
            crossArtifactTiming = crossArtifactTiming == null
                ? new CrossArtifactTimingSummary(List.of(), List.of())
                : crossArtifactTiming;
        }

        public boolean hasAlignment(String alignmentId) {
            return strongestAlignments.stream().anyMatch(alignment -> alignment.alignmentId().equals(alignmentId));
        }

        public SignalAlignment alignment(String alignmentId) {
            return strongestAlignments.stream()
                .filter(alignment -> alignment.alignmentId().equals(alignmentId))
                .findFirst()
                .orElse(null);
        }

        public Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            List<Map<String, Object>> sharedSignalFamilies = sharedSignalFamilies();
            if (!sharedSignalFamilies.isEmpty()) {
                canonical.put("sharedSignalFamilies", sharedSignalFamilies);
            }
            Map<String, Object> timingSummary = crossArtifactTiming.toCanonicalMap();
            if (!timingSummary.isEmpty()) {
                canonical.put("crossArtifactTiming", timingSummary);
            }
            List<Map<String, Object>> renderedAlignments = strongestAlignments.stream()
                .map(SignalAlignment::toCanonicalMap)
                .toList();
            if (!renderedAlignments.isEmpty()) {
                canonical.put("strongestAlignments", renderedAlignments);
            }
            List<Map<String, Object>> renderedArtifacts = artifactSignals.stream()
                .filter(ArtifactSignalProfile::hasUsableContent)
                .map(ArtifactSignalProfile::toCanonicalMap)
                .toList();
            if (!renderedArtifacts.isEmpty()) {
                canonical.put("perArtifactSignals", renderedArtifacts);
            }
            return canonical.isEmpty() ? Map.of() : Map.copyOf(canonical);
        }

        private List<Map<String, Object>> sharedSignalFamilies() {
            LinkedHashMap<String, LinkedHashSet<ArtifactType>> families = new LinkedHashMap<>();
            for (ArtifactSignalProfile artifactSignal : artifactSignals) {
                for (String signalFamily : artifactSignal.signalFamilies()) {
                    families.computeIfAbsent(signalFamily, ignored -> new LinkedHashSet<>()).add(artifactSignal.artifactType());
                }
            }

            List<Map<String, Object>> shared = new ArrayList<>();
            for (Map.Entry<String, LinkedHashSet<ArtifactType>> entry : families.entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("signalFamily", entry.getKey());
                item.put("artifactTypes", List.copyOf(entry.getValue()));
                shared.add(Map.copyOf(item));
            }
            return List.copyOf(shared);
        }
    }

    public record CrossArtifactTimingSummary(
        List<ArtifactTimingProfile> timingCoverage,
        List<TimingAlignment> timingAlignments
    ) {

        public CrossArtifactTimingSummary {
            timingCoverage = timingCoverage == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timingCoverage));
            timingAlignments = timingAlignments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timingAlignments));
        }

        public TimingAlignment alignment(String alignmentId) {
            return timingAlignments.stream()
                .filter(alignment -> alignment.alignmentId().equals(alignmentId))
                .findFirst()
                .orElse(null);
        }

        Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            List<Map<String, Object>> renderedCoverage = timingCoverage.stream()
                .filter(ArtifactTimingProfile::hasUsableContent)
                .map(ArtifactTimingProfile::toCanonicalMap)
                .toList();
            if (!renderedCoverage.isEmpty()) {
                canonical.put("timingCoverage", renderedCoverage);
            }
            List<Map<String, Object>> renderedAlignments = timingAlignments.stream()
                .map(TimingAlignment::toCanonicalMap)
                .toList();
            if (!renderedAlignments.isEmpty()) {
                canonical.put("timingAlignments", renderedAlignments);
            }
            return canonical.isEmpty() ? Map.of() : Map.copyOf(canonical);
        }
    }

    public record ArtifactTimingProfile(
        ArtifactType artifactType,
        String sourcePath,
        String timingStatus,
        String basis,
        List<Map<String, Object>> timingWindows,
        List<String> notes
    ) {

        public ArtifactTimingProfile {
            timingWindows = timingWindows == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timingWindows));
            notes = notes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(notes));
        }

        boolean hasUsableContent() {
            return timingStatus != null || !timingWindows.isEmpty() || !notes.isEmpty();
        }

        Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
            if (sourcePath != null && !sourcePath.isBlank()) {
                canonical.put("sourcePath", sourcePath);
            }
            if (timingStatus != null && !timingStatus.isBlank()) {
                canonical.put("timingStatus", timingStatus);
            }
            if (basis != null && !basis.isBlank()) {
                canonical.put("basis", basis);
            }
            if (!timingWindows.isEmpty()) {
                canonical.put("timingWindows", timingWindows);
            }
            if (!notes.isEmpty()) {
                canonical.put("notes", notes);
            }
            return Map.copyOf(canonical);
        }
    }

    public record ArtifactSignalProfile(
        ArtifactType artifactType,
        String sourcePath,
        List<String> signalFamilies,
        List<String> exactClasses,
        List<String> classFamilies,
        List<String> hotspotMethods,
        List<String> threadNames,
        List<String> poolNames,
        List<String> lockKeys,
        List<String> hotspotFrames,
        Map<String, Object> keyMetrics
    ) {

        public ArtifactSignalProfile {
            signalFamilies = signalFamilies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(signalFamilies));
            exactClasses = exactClasses == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(exactClasses));
            classFamilies = classFamilies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(classFamilies));
            hotspotMethods = hotspotMethods == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(hotspotMethods));
            threadNames = threadNames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(threadNames));
            poolNames = poolNames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(poolNames));
            lockKeys = lockKeys == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(lockKeys));
            hotspotFrames = hotspotFrames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(hotspotFrames));
            keyMetrics = keyMetrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keyMetrics));
        }

        boolean hasUsableContent() {
            return !(signalFamilies.isEmpty()
                && exactClasses.isEmpty()
                && classFamilies.isEmpty()
                && hotspotMethods.isEmpty()
                && threadNames.isEmpty()
                && poolNames.isEmpty()
                && lockKeys.isEmpty()
                && hotspotFrames.isEmpty()
                && keyMetrics.isEmpty());
        }

        Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("artifactType", artifactType != null ? artifactType.name() : null);
            if (sourcePath != null && !sourcePath.isBlank()) {
                canonical.put("sourcePath", sourcePath);
            }
            if (!signalFamilies.isEmpty()) {
                canonical.put("signalFamilies", signalFamilies);
            }
            if (!exactClasses.isEmpty()) {
                canonical.put("exactClasses", exactClasses);
            }
            if (!classFamilies.isEmpty()) {
                canonical.put("classFamilies", classFamilies);
            }
            if (!hotspotMethods.isEmpty()) {
                canonical.put("hotspotMethods", hotspotMethods);
            }
            if (!threadNames.isEmpty()) {
                canonical.put("threadNames", threadNames);
            }
            if (!poolNames.isEmpty()) {
                canonical.put("poolNames", poolNames);
            }
            if (!lockKeys.isEmpty()) {
                canonical.put("lockKeys", lockKeys);
            }
            if (!hotspotFrames.isEmpty()) {
                canonical.put("hotspotFrames", hotspotFrames);
            }
            if (!keyMetrics.isEmpty()) {
                canonical.put("keyMetrics", keyMetrics);
            }
            return Map.copyOf(canonical);
        }
    }

    public record SignalAlignment(
        String alignmentId,
        String label,
        String detail,
        List<String> artifactPaths,
        List<ArtifactType> artifactTypes,
        List<String> sharedSignalFamilies,
        List<String> sharedClasses,
        List<String> sharedClassFamilies,
        Map<String, Object> metrics,
        int score
    ) {

        public SignalAlignment {
            artifactPaths = artifactPaths == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
            artifactTypes = artifactTypes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(artifactTypes));
            sharedSignalFamilies = sharedSignalFamilies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sharedSignalFamilies));
            sharedClasses = sharedClasses == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sharedClasses));
            sharedClassFamilies = sharedClassFamilies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sharedClassFamilies));
            metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }

        Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("alignmentId", alignmentId);
            canonical.put("label", label);
            canonical.put("detail", detail);
            if (!artifactTypes.isEmpty()) {
                canonical.put("artifactTypes", artifactTypes.stream().map(ArtifactType::name).toList());
            }
            if (!artifactPaths.isEmpty()) {
                canonical.put("artifactPaths", artifactPaths);
            }
            if (!sharedSignalFamilies.isEmpty()) {
                canonical.put("sharedSignalFamilies", sharedSignalFamilies);
            }
            if (!sharedClasses.isEmpty()) {
                canonical.put("sharedClasses", sharedClasses);
            }
            if (!sharedClassFamilies.isEmpty()) {
                canonical.put("sharedClassFamilies", sharedClassFamilies);
            }
            if (!metrics.isEmpty()) {
                canonical.put("metrics", metrics);
            }
            return Map.copyOf(canonical);
        }
    }

    public record TimingAlignment(
        String alignmentId,
        String status,
        String label,
        String detail,
        List<String> artifactPaths,
        List<ArtifactType> artifactTypes,
        List<String> windowIds,
        Map<String, Object> metrics,
        int score
    ) {

        public TimingAlignment {
            artifactPaths = artifactPaths == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(artifactPaths));
            artifactTypes = artifactTypes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(artifactTypes));
            windowIds = windowIds == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(windowIds));
            metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }

        Map<String, Object> toCanonicalMap() {
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("alignmentId", alignmentId);
            canonical.put("status", status);
            canonical.put("label", label);
            canonical.put("detail", detail);
            if (!artifactTypes.isEmpty()) {
                canonical.put("artifactTypes", artifactTypes.stream().map(ArtifactType::name).toList());
            }
            if (!artifactPaths.isEmpty()) {
                canonical.put("artifactPaths", artifactPaths);
            }
            if (!windowIds.isEmpty()) {
                canonical.put("windowIds", windowIds);
            }
            if (!metrics.isEmpty()) {
                canonical.put("metrics", metrics);
            }
            return Map.copyOf(canonical);
        }
    }
}
