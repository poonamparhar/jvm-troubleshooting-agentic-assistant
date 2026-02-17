package com.example.data;

/**
 * Enumeration of recommendation priority levels
 */
public enum Priority {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    URGENT("Urgent", 4);

    private final String displayName;
    private final int level;

    Priority(String displayName, int level) {
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
     * Parse priority from string
     */
    public static Priority fromString(String value) {
        if (value == null) return LOW;

        return switch (value.toUpperCase()) {
            case "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "URGENT" -> URGENT;
            default -> LOW;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
