package com.javaassistant.diagnostics;

/**
 * Identifies how a user-facing narrative was produced.
 */
public enum AgentNarrativeSource {
    SPECIALIST_AGENT,
    SYNTHESIS_AGENT,
    FALLBACK_SUMMARIZER,
    DETERMINISTIC_FALLBACK
}
