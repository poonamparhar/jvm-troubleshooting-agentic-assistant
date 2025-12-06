package com.example.data;

/**
 * Enumeration of supported diagnostic data types
 */
public enum DataType {
    GC_LOG("gc.log", "JVM Garbage Collection GC Log"),
    THREAD_DUMP("threaddump", "JVM Thread Dump"),
    HS_ERR_LOG("hs_err.log", "JVM Crash hs_err Log"),
    PERFORMANCE_METRICS("metrics", "JVM Performance Metrics"),
    HEAP_DUMP("heapdump", "JVM Heap Dump");

    private final String fileExtension;
    private final String description;

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
     * Determine data type from file name
     */
    public static DataType fromFileName(String fileName) {
        var lowerFileName = fileName.toLowerCase();

        if (lowerFileName.contains("gc") || lowerFileName.contains("garbage")) {
            return GC_LOG;
        } else if (lowerFileName.contains("thread") || (lowerFileName.contains("dump") && !lowerFileName.contains("heap"))) {
            return THREAD_DUMP;
        } else if (lowerFileName.contains("hs_err") || lowerFileName.contains("error") || lowerFileName.contains("crash")) {
            return HS_ERR_LOG;
        } else if (lowerFileName.contains("metrics") || lowerFileName.contains("perf")) {
            return PERFORMANCE_METRICS;
        } else if (lowerFileName.contains("heap")) {
            return HEAP_DUMP;
        } else {
            return GC_LOG; // Default to GC log if unsure
        }
    }
}
