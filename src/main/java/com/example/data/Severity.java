package com.example.data;

/**
 * Enumeration of issue severity levels
 */
public enum Severity {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    CRITICAL("Critical", 4);

    private final String displayName;
    private final int level;

    Severity(String displayName, int level) {
        this.displayName = displayName;
        this.level = level;
    }

    public String displayName() {
        return displayName;
    }

    public int level() {
        return level;
    }

    /**
     * Parse severity from string
     */
    public static Severity fromString(String value) {
        if (value == null) return LOW;

        return switch (value.toUpperCase()) {
            case "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "CRITICAL" -> CRITICAL;
            default -> LOW;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
