package com.javaassistant.diagnostics;

import java.util.Locale;

/**
 * Canonical artifact types supported by the evidence-first pipeline.
 */
public enum ArtifactType {
    UNKNOWN("Unknown artifact type"),
    GC_LOG("JVM Garbage Collection GC Log"),
    JFR("Java Flight Recorder (JFR) Recording"),
    THREAD_DUMP("JVM Thread Dump"),
    HS_ERR_LOG("JVM Crash hs_err Log"),
    NMT("JVM Native Memory Tracking Output"),
    HEAP_HISTOGRAM("JVM Heap Histogram Output"),
    PMAP("Process Memory Map Output"),
    CONTAINER_MEMORY("Container cgroup Memory Snapshot"),
    OOM_SIGNAL("Kernel OOM / Restart Signal Log");

    private final String description;

    ArtifactType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public static ArtifactType fromExternalName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Artifact type cannot be blank.");
        }

        return switch (normalize(value)) {
            case "GC_LOG" -> GC_LOG;
            case "JFR" -> JFR;
            case "THREAD_DUMP" -> THREAD_DUMP;
            case "HS_ERR_LOG", "HS_ERR" -> HS_ERR_LOG;
            case "NMT", "NMT_MEMORY" -> NMT;
            case "HEAP_HISTOGRAM" -> HEAP_HISTOGRAM;
            case "PMAP", "PMAP_OUTPUT" -> PMAP;
            case "CONTAINER_MEMORY" -> CONTAINER_MEMORY;
            case "OOM_SIGNAL" -> OOM_SIGNAL;
            default -> throw new IllegalArgumentException("Unsupported artifact type: " + value);
        };
    }

    private static String normalize(String value) {
        return value.trim()
            .toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace('.', '_');
    }
}
