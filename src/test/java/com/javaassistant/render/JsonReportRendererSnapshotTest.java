package com.javaassistant.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.javaassistant.diagnostics.ActionPriority;
import com.javaassistant.diagnostics.ActionType;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactInventoryStatus;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.FindingStatus;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.RecommendedAction;
import com.javaassistant.diagnostics.SeverityLevel;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReportRendererSnapshotTest {

    private final JsonReportRenderer renderer = new JsonReportRenderer();

    @Test
    void rendersDeterministicCanonicalJsonSnapshot() {
        AnalysisReport report = snapshotReport();

        String expected = """
            {
              "schemaVersion": 1,
              "analysisId": "snapshot-report",
              "createdAt": "2026-03-29T16:30",
              "incidentSummary": "Synthetic snapshot report for regression coverage.",
              "userNarrative": null,
              "agentTraceability": [],
              "supervisorTrace": null,
              "overallSeverity": "HIGH",
              "confidence": "MEDIUM",
              "inputArtifacts": [
                {
                  "type": "NMT",
                  "metadata": {
                    "sourcePath": "samples/snapshot.nmt",
                    "displayName": "snapshot.nmt",
                    "contentLength": 6,
                    "discoveredAt": "2026-03-29T16:30",
                    "attributes": {
                      "origin": "snapshot-test"
                    }
                  },
                  "content": "sample"
                }
              ],
              "parsedArtifacts": [
                {
                  "type": "NMT",
                  "metadata": {
                    "sourcePath": "samples/snapshot.nmt",
                    "displayName": "snapshot.nmt",
                    "contentLength": 6,
                    "discoveredAt": "2026-03-29T16:30",
                    "attributes": {
                      "origin": "snapshot-test"
                    }
                  },
                  "parserVersion": "parser-snapshot-v1",
                  "extractedData": {
                    "snapshotKind": "summary",
                    "collector": "G1"
                  },
                  "evidence": [
                    {
                      "id": "evidence-snapshot",
                      "artifactPath": "samples/snapshot.nmt",
                      "label": "Synthetic anchor",
                      "detail": "Synthetic detail",
                      "snippet": "Synthetic snippet",
                      "lineNumbers": [
                        10,
                        11
                      ],
                      "metrics": {
                        "committedKb": 2048,
                        "threads": 14
                      }
                    }
                  ],
                  "warnings": [
                    "collector inferred"
                  ]
                }
              ],
              "evidence": [
                {
                  "id": "evidence-snapshot",
                  "artifactPath": "samples/snapshot.nmt",
                  "label": "Synthetic anchor",
                  "detail": "Synthetic detail",
                  "snippet": "Synthetic snippet",
                  "lineNumbers": [
                    10,
                    11
                  ],
                  "metrics": {
                    "committedKb": 2048,
                    "threads": 14
                  }
                }
              ],
              "findings": [
                {
                  "id": "finding-snapshot",
                  "title": "Snapshot finding",
                  "summary": "Structured signal detected.",
                  "category": "memory",
                  "severity": "HIGH",
                  "confidence": "MEDIUM",
                  "status": "LIKELY",
                  "artifactPaths": [
                    "samples/snapshot.nmt"
                  ],
                  "evidenceIds": [
                    "evidence-snapshot"
                  ],
                  "rationale": "Matched synthetic threshold."
                }
              ],
              "recommendedActions": [
                {
                  "id": "action-snapshot",
                  "summary": "Capture supporting evidence",
                  "rationale": "Needed for confirmation.",
                  "actionType": "DATA_COLLECTION",
                  "priority": "HIGH",
                  "steps": [
                    "Collect GC log",
                    "Collect NMT diff"
                  ],
                  "relatedFindingIds": [
                    "finding-snapshot"
                  ]
                }
              ],
              "missingData": [
                "Need hs_err log"
              ],
              "followUpCommands": [
                "jcmd <pid> VM.native_memory summary"
              ],
              "artifactInventory": [
                {
                  "sourcePath": "samples/snapshot.nmt",
                  "displayName": "snapshot.nmt",
                  "artifactType": "NMT",
                  "status": "SUPPORTED",
                  "detail": "Included in structured analysis."
                }
              ],
              "artifactSummaries": [
                {
                  "type": "NMT",
                  "sourcePath": "samples/snapshot.nmt",
                  "parserVersion": "parser-snapshot-v1",
                  "snapshotKind": "summary"
                }
              ],
              "reportMetadata": {
                "inputArtifactCount": 1,
                "parsedArtifactCount": 1,
                "evidenceCount": 1,
                "findingCount": 1,
                "agentTraceabilityCount": 0,
                "selectedAgentTraceabilityCount": 0,
                "agentQualityGateCount": 0,
                "agentParticipationSummary": {
                  "analysisPath": "Deterministic findings only",
                  "aiAgentAttempted": false,
                  "aiAgentAttemptCount": 0,
                  "aiAgentSelectedForUserNarrative": false,
                  "llmNarrativeSelectedForUserNarrative": false,
                  "selectedNarrativeAgent": null,
                  "selectedNarrativeSource": null,
                  "selectedNarrativeProvider": null,
                  "selectedNarrativeProviderLabel": null,
                  "selectedNarrativeModel": null,
                  "selectedNarrativeModelFamily": null,
                  "selectedNarrativeTemplateId": null,
                  "selectedNarrativeTemplateVersion": null
                },
                "hasSupervisorTrace": false,
                "supervisorTraceStepCount": 0,
                "recommendedActionCount": 1,
                "missingDataCount": 1,
                "followUpCommandCount": 1,
                "artifactInventoryCount": 1,
                "hasCorrelationResult": false,
                "shareableFormats": {
                  "redactionProfile": "internal-safe-v1",
                  "redactedFormats": [
                    "text",
                    "markdown",
                    "html"
                  ],
                  "fullFidelityFormats": [
                    "json"
                  ]
                },
                "catalogSummary": {
                  "analysisId": "snapshot-report",
                  "createdAt": "2026-03-29T16:30",
                  "overallSeverity": "HIGH",
                  "confidence": "MEDIUM",
                  "hasUserNarrative": false,
                  "artifactTypes": [
                    "NMT"
                  ],
                  "redactionProfile": "internal-safe-v1",
                  "inputArtifactCount": 1,
                  "hasCorrelationResult": false,
                  "aiAgentAttempted": false,
                  "aiAgentSelectedForUserNarrative": false,
                  "llmNarrativeSelectedForUserNarrative": false
                }
              },
              "correlationResult": null
            }
            """.stripIndent().trim();

        assertEquals(expected, renderer.render(report));
    }

    private AnalysisReport snapshotReport() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 3, 29, 16, 30, 0);

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("origin", "snapshot-test");

        ArtifactMetadata metadata = new ArtifactMetadata(
            "samples/snapshot.nmt",
            "snapshot.nmt",
            6L,
            timestamp,
            attributes
        );

        Map<String, Object> evidenceMetrics = new LinkedHashMap<>();
        evidenceMetrics.put("committedKb", 2048);
        evidenceMetrics.put("threads", 14);

        Evidence evidence = new Evidence(
            "evidence-snapshot",
            "samples/snapshot.nmt",
            "Synthetic anchor",
            "Synthetic detail",
            "Synthetic snippet",
            List.of(10, 11),
            evidenceMetrics
        );

        Map<String, Object> extractedData = new LinkedHashMap<>();
        extractedData.put("snapshotKind", "summary");
        extractedData.put("collector", "G1");

        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.NMT,
            metadata,
            "parser-snapshot-v1",
            extractedData,
            List.of(evidence),
            List.of("collector inferred")
        );

        Finding finding = new Finding(
            "finding-snapshot",
            "Snapshot finding",
            "Structured signal detected.",
            "memory",
            SeverityLevel.HIGH,
            ConfidenceLevel.MEDIUM,
            FindingStatus.LIKELY,
            List.of("samples/snapshot.nmt"),
            List.of("evidence-snapshot"),
            "Matched synthetic threshold."
        );

        RecommendedAction action = new RecommendedAction(
            "action-snapshot",
            "Capture supporting evidence",
            "Needed for confirmation.",
            ActionType.DATA_COLLECTION,
            ActionPriority.HIGH,
            List.of("Collect GC log", "Collect NMT diff"),
            List.of("finding-snapshot")
        );

        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "snapshot-report",
            timestamp,
            "Synthetic snapshot report for regression coverage.",
            null,
            List.of(),
            null,
            SeverityLevel.HIGH,
            ConfidenceLevel.MEDIUM,
            List.of(new InputArtifact(ArtifactType.NMT, metadata, "sample")),
            List.of(parsedArtifact),
            List.of(evidence),
            List.of(finding),
            List.of(action),
            List.of("Need hs_err log"),
            List.of("jcmd <pid> VM.native_memory summary"),
            List.of(new ArtifactInventoryEntry(
                "samples/snapshot.nmt",
                "snapshot.nmt",
                ArtifactType.NMT,
                ArtifactInventoryStatus.SUPPORTED,
                "Included in structured analysis."
            )),
            null
        );
    }
}
