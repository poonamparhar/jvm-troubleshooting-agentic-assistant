package com.example.data;

/**
 * Enumeration of supported diagnostic data types
 */
public enum DataType {
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
     * Determine data type from file contents
     */
    public static DataType fromContents(String content) {
        if (content == null || content.isEmpty()) {
            return GC_LOG; // Default if no content
        }
        var lowerContent = content.toLowerCase();

        if (lowerContent.contains("a fatal error has been detected")) {
            return HS_ERR_LOG;
        } else if (lowerContent.contains("full thread dump") || lowerContent.contains("java stack information")) {
            return THREAD_DUMP;
        } else if (lowerContent.contains("java profile") || lowerContent.contains("heap dump")) {
            return HEAP_DUMP;
        } else if (lowerContent.contains("gc(") || lowerContent.contains("[gc") || lowerContent.contains("full gc")) {
            return GC_LOG;
        } else if (lowerContent.contains("cpu time") || lowerContent.contains("heap size") || lowerContent.contains("metrics")) {
            return PERFORMANCE_METRICS;
        } else if (lowerContent.contains("native memory tracking") || lowerContent.contains("total:") ||
                   lowerContent.contains("-java heap") || lowerContent.contains("-class") ||
                   lowerContent.contains("-thread") || lowerContent.contains("-code") ||
                   lowerContent.contains("-gc")) {
            return NMT_MEMORY;
        } else if (lowerContent.contains("#instances") || lowerContent.contains("#bytes") ||
                   (lowerContent.contains("num") && lowerContent.contains("class name"))) {
            return HEAP_HISTOGRAM;
        } else if (lowerContent.contains("address") && lowerContent.contains("kbytes") && lowerContent.contains("rss")) {
            return PMAP_OUTPUT;
        } else {
            return GC_LOG; // Default to GC log if unsure
        }
    }
}
