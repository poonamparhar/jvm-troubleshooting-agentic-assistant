package com.example.data;

import java.util.List;

/**
 * Represents a recommendation for resolving an issue
 */
public record Recommendation(
    String description,
    Priority priority,
    String rationale,
    List<String> actions,
    String category
) {

    public Recommendation(String description, Priority priority) {
        this(description, priority, null, List.of(), null);
    }

    public static Recommendation high(String description) {
        return new Recommendation(description, Priority.HIGH);
    }

    public static Recommendation urgent(String description) {
        return new Recommendation(description, Priority.URGENT);
    }

    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    @Override
    public String toString() {
        return "Recommendation{" +
                "description='" + description + '\'' +
                ", priority=" + priority +
                ", rationale='" + rationale + '\'' +
                ", actions=" + actions +
                ", category='" + category + '\'' +
                '}';
    }
}
