package com.example.data;

import java.util.List;

/**
 * Represents an identified issue in the diagnostic data
 */
public record Issue(
    String description,
    Severity severity,
    String category,
    List<String> evidence,
    String recommendation
) {

    public Issue(String description, Severity severity) {
        this(description, severity, null, List.of(), null);
    }

    public Issue(String description, Severity severity, String category) {
        this(description, severity, category, List.of(), null);
    }

    public boolean hasEvidence() {
        return evidence != null && !evidence.isEmpty();
    }

    @Override
    public String toString() {
        return "Issue{" +
                "description='" + description + '\'' +
                ", severity=" + severity +
                ", category='" + category + '\'' +
                ", evidence=" + evidence +
                ", recommendation='" + recommendation + '\'' +
                '}';
    }
}
