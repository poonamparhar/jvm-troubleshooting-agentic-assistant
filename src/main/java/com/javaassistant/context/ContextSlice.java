package com.javaassistant.context;

/**
 * One bounded raw or derived slice that can be shown to an agent.
 */
public record ContextSlice(
    String sliceId,
    String kind,
    String label,
    String content,
    String traceability,
    boolean truncated
) {
}
