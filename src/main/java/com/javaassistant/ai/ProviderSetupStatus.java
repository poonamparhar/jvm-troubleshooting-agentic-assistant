package com.javaassistant.ai;

import java.util.List;

/**
 * User-facing readiness information for the currently selected AI provider.
 */
public record ProviderSetupStatus(
    String setupMode,
    boolean ready,
    List<Check> checks,
    List<String> nextSteps
) {

    public ProviderSetupStatus {
        checks = List.copyOf(checks != null ? checks : List.of());
        nextSteps = List.copyOf(nextSteps != null ? nextSteps : List.of());
    }

    public record Check(String label, Status status, String detail) { }

    public enum Status {
        READY,
        MISSING,
        INFO
    }

    public static Check ready(String label, String detail) {
        return new Check(label, Status.READY, detail);
    }

    public static Check missing(String label, String detail) {
        return new Check(label, Status.MISSING, detail);
    }

    public static Check info(String label, String detail) {
        return new Check(label, Status.INFO, detail);
    }
}
