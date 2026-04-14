package com.javaassistant.ingest;

import com.javaassistant.diagnostics.ArtifactType;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Centralized artifact classification heuristics for diagnostic ingest.
 */
final class ArtifactClassifier {

    private static final Pattern THREAD_HEADER_PATTERN = Pattern.compile(
        "^\"[^\"]+\".*\\bnid=\\S+.*$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTAINER_MEMORY_SECTION_PATTERN = Pattern.compile(
        "(?is)\\[(memory\\.current|memory\\.max|memory\\.high|memory\\.events|memory\\.stat|memory\\.pressure|"
            + "memory\\.usage_in_bytes|memory\\.limit_in_bytes|memory\\.soft_limit_in_bytes|memory\\.failcnt|"
            + "memory\\.oom_control|memory\\.pressure_level)]"
    );
    private static final Pattern KERNEL_OOM_PATTERN = Pattern.compile(
        "(?im)^(?:.*(?:oom-kill:|out of memory:|memory cgroup out of memory:).*)$|^(?:.*killed process \\d+ \\([^)]+\\).*)$"
    );

    ArtifactType classify(String content, String sourceName) {
        if (sourceName != null && sourceName.toLowerCase(Locale.ROOT).endsWith(".jfr")) {
            return ArtifactType.JFR;
        }

        if (content == null || content.isEmpty()) {
            return ArtifactType.UNKNOWN;
        }

        String lowerContent = content.toLowerCase();

        if (lowerContent.contains("a fatal error has been detected")
            || lowerContent.contains("there is insufficient memory for the java runtime environment")
            || lowerContent.contains("native memory allocation (malloc) failed")
            || (lowerContent.contains("#")
                && lowerContent.contains("possible reasons:")
                && lowerContent.contains("possible solutions:"))) {
            return ArtifactType.HS_ERR_LOG;
        }

        if (lowerContent.contains("full thread dump")
            || lowerContent.contains("found one java-level deadlock")
            || lowerContent.contains("java-level deadlock:")
            || (lowerContent.contains("java.lang.thread.state:") && THREAD_HEADER_PATTERN.matcher(content).find())) {
            return ArtifactType.THREAD_DUMP;
        }

        if (looksLikeOomSignal(content, lowerContent)) {
            return ArtifactType.OOM_SIGNAL;
        }

        if (looksLikeContainerMemorySnapshot(content, lowerContent)) {
            return ArtifactType.CONTAINER_MEMORY;
        }

        if (lowerContent.contains("native memory tracking")
            || (lowerContent.contains("total:") && lowerContent.contains("reserved=") && lowerContent.contains("committed="))
            || (lowerContent.contains("-java heap") && lowerContent.contains("-class"))) {
            return ArtifactType.NMT;
        }

        if ((lowerContent.contains("#instances") && lowerContent.contains("#bytes"))
            || (lowerContent.contains("num") && lowerContent.contains("#instances") && lowerContent.contains("class name"))) {
            return ArtifactType.HEAP_HISTOGRAM;
        }

        if ((lowerContent.contains("address") && lowerContent.contains("kbytes") && lowerContent.contains("rss"))
            || lowerContent.contains("total kb")
            || (lowerContent.contains("[ anon ]")
                && (lowerContent.contains("rw---")
                    || lowerContent.contains("r-x--")
                    || lowerContent.contains("-----")))) {
            return ArtifactType.PMAP;
        }

        if (lowerContent.contains("gc(")
            || lowerContent.contains("[gc")
            || lowerContent.contains("full gc")
            || lowerContent.contains("young generation")
            || lowerContent.contains("old generation")) {
            return ArtifactType.GC_LOG;
        }

        return ArtifactType.UNKNOWN;
    }

    private boolean looksLikeContainerMemorySnapshot(String content, String lowerContent) {
        if (!CONTAINER_MEMORY_SECTION_PATTERN.matcher(content).find()) {
            return false;
        }

        boolean hasV2Current = lowerContent.contains("[memory.current]");
        boolean hasV2Events = lowerContent.contains("[memory.events]");
        boolean hasV2Stat = lowerContent.contains("[memory.stat]");
        boolean hasV2Pressure = lowerContent.contains("[memory.pressure]");
        boolean hasV2Limit = lowerContent.contains("[memory.max]") || lowerContent.contains("[memory.high]");
        if (hasV2Current && hasV2Events && (hasV2Stat || hasV2Pressure || hasV2Limit)) {
            return true;
        }

        boolean hasV1Current = lowerContent.contains("[memory.usage_in_bytes]");
        boolean hasV1Stat = lowerContent.contains("[memory.stat]");
        boolean hasV1Limit = lowerContent.contains("[memory.limit_in_bytes]")
            || lowerContent.contains("[memory.soft_limit_in_bytes]");
        boolean hasV1Events = lowerContent.contains("[memory.failcnt]")
            || lowerContent.contains("[memory.oom_control]");

        return hasV1Current && hasV1Stat && (hasV1Limit || hasV1Events);
    }

    private boolean looksLikeOomSignal(String content, String lowerContent) {
        if (KERNEL_OOM_PATTERN.matcher(content).find()) {
            return true;
        }

        return lowerContent.contains("oomkilled")
            || (lowerContent.contains("crashloopbackoff")
                && lowerContent.contains("restart count")
                && lowerContent.contains("last state"));
    }
}
