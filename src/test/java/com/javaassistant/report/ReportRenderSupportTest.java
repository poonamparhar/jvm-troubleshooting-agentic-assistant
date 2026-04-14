package com.javaassistant.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.SeverityLevel;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportRenderSupportTest {

    @Test
    void prefersLaterEvidenceWhenComparisonReportsContainDuplicateEvidenceIds() {
        Evidence baselineEvidence = new Evidence(
            "duplicate-evidence",
            "samples/thread_dump_baseline.txt",
            "Baseline evidence",
            "baseline",
            "baseline",
            List.of(10),
            java.util.Map.of()
        );
        Evidence currentEvidence = new Evidence(
            "duplicate-evidence",
            "samples/thread_dump_deadlock.txt",
            "Current evidence",
            "current",
            "current",
            List.of(20),
            java.util.Map.of()
        );
        Finding finding = new Finding(
            "finding-1",
            "Test finding",
            "summary",
            "compare.test",
            SeverityLevel.MEDIUM,
            ConfidenceLevel.HIGH,
            FindingStatus.CONFIRMED,
            List.of("samples/thread_dump_baseline.txt", "samples/thread_dump_deadlock.txt"),
            List.of("duplicate-evidence"),
            "rationale"
        );
        AnalysisReport report = new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "analysis-1",
            LocalDateTime.now(),
            "summary",
            null,
            List.of(),
            null,
            SeverityLevel.MEDIUM,
            ConfidenceLevel.HIGH,
            List.of(),
            List.of(),
            List.of(baselineEvidence, currentEvidence),
            List.of(finding),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        var evidenceIndex = ReportRenderSupport.evidenceIndex(report);

        assertEquals("samples/thread_dump_deadlock.txt", evidenceIndex.get("duplicate-evidence").artifactPath());
    }
}
