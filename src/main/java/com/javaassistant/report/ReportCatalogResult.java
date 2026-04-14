package com.javaassistant.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only saved-report catalog result with skipped-bundle diagnostics.
 */
public record ReportCatalogResult(
    List<ReportCatalogEntry> entries,
    List<String> skippedBundles
) {

    public ReportCatalogResult {
        entries = entries == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(entries));
        skippedBundles = skippedBundles == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(skippedBundles));
    }
}
