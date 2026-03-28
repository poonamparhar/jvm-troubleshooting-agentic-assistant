package com.example.data;

import com.example.detect.ArtifactClassifier;
import com.example.model.ArtifactType;

/**
 * Enumeration of supported diagnostic data types
 */
public enum DataType {
    UNKNOWN("unknown", "Unknown Data Type"),
    GC_LOG("gc.log", "JVM Garbage Collection GC Log"),
    THREAD_DUMP("threaddump", "JVM Thread Dump"),
    HS_ERR_LOG("hs_err.log", "JVM Crash hs_err Log"),
    PERFORMANCE_METRICS("metrics", "JVM Performance Metrics"),
    HEAP_DUMP("heapdump", "JVM Heap Dump"),
    NMT_MEMORY("nmt", "JVM Native Memory Tracking Output"),
    HEAP_HISTOGRAM("histogram", "JVM Heap Histogram Output"),
    PMAP_OUTPUT("pmap", "Process Memory Map Output");

    private final String fileExtension;
    private final String description;
    private static final ArtifactClassifier CLASSIFIER = new ArtifactClassifier();

    DataType(String fileExtension, String description) {
        this.fileExtension = fileExtension;
        this.description = description;
    }

    public String fileExtension() {
        return fileExtension;
    }

    public String description() {
        return description;
    }

    /**
     * Maps the legacy input type to the canonical v1 artifact type.
     */
    public ArtifactType toArtifactType() {
        return switch (this) {
            case GC_LOG -> ArtifactType.GC_LOG;
            case HS_ERR_LOG -> ArtifactType.HS_ERR_LOG;
            case NMT_MEMORY -> ArtifactType.NMT;
            case HEAP_HISTOGRAM -> ArtifactType.HEAP_HISTOGRAM;
            case PMAP_OUTPUT -> ArtifactType.PMAP;
            default -> ArtifactType.UNKNOWN;
        };
    }

    public static DataType fromArtifactType(ArtifactType artifactType) {
        if (artifactType == null) {
            return UNKNOWN;
        }

        return switch (artifactType) {
            case GC_LOG -> GC_LOG;
            case HS_ERR_LOG -> HS_ERR_LOG;
            case NMT -> NMT_MEMORY;
            case HEAP_HISTOGRAM -> HEAP_HISTOGRAM;
            case PMAP -> PMAP_OUTPUT;
            default -> UNKNOWN;
        };
    }

    /**
     * Determine data type from file contents
     */
    public static DataType fromContents(String content) {
        return fromArtifactType(CLASSIFIER.classify(content));
    }
}
