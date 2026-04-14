package com.javaassistant.diagnostics;

/**
 * Identifies which orchestrated workflow produced the saved report.
 */
public enum OrchestrationWorkflowType {
    SINGLE_ARTIFACT,
    COMPARE,
    SEQUENCE,
    CORRELATE
}
