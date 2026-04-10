package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShareableReportRedactorTest {

    @Test
    void preservesJvmStyleIdentifiersInFollowUpCommands() {
        ShareableReportRedactor redactor = new ShareableReportRedactor();

        String redacted = redactor.redact(
            "jfr print --events jdk.JavaMonitorBlocked,jdk.GarbageCollection <recording.jfr>"
        );

        assertTrue(redacted.contains("jdk.JavaMonitorBlocked"));
        assertTrue(redacted.contains("jdk.GarbageCollection"));
    }

    @Test
    void preservesAiModelIdentifiersWithNumericVersionSuffixes() {
        ShareableReportRedactor redactor = new ShareableReportRedactor();

        String redacted = redactor.redact("Final Narrative Model: llama3.2 (family llama3)");

        assertTrue(redacted.contains("llama3.2"));
    }

    @Test
    void preservesNamedAiModelIdentifiersWithDotsAndHyphens() {
        ShareableReportRedactor redactor = new ShareableReportRedactor();

        String redacted = redactor.redact("Final Narrative Model: xai.grok-4-fast-non-reasoning");

        assertTrue(redacted.contains("xai.grok-4-fast-non-reasoning"));
    }

    @Test
    void preservesDecimalMetrics() {
        ShareableReportRedactor redactor = new ShareableReportRedactor();

        String redacted = redactor.redact("Pauses consumed 97.6% of the 10.5s window.");

        assertTrue(redacted.contains("97.6%"));
        assertTrue(redacted.contains("10.5s"));
    }

    @Test
    void preservesAgentTemplateIdentifiers() {
        ShareableReportRedactor redactor = new ShareableReportRedactor();

        String redacted = redactor.redact("Final Narrative Template: JfrAgent.analyze@v1");

        assertTrue(redacted.contains("JfrAgent.analyze@v1"));
    }
}
