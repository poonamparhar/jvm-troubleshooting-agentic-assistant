package com.example.detect;

import com.example.model.ArtifactType;

/**
 * Centralized artifact classification heuristics for the evidence-first pipeline.
 */
public class ArtifactClassifier {

    public ArtifactType classify(String content) {
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
}
