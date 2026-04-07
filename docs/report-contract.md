# Report Contract: Analysis Report Bundle

## Purpose
This document defines the canonical saved-report contract for the agent-driven JVM triage tool. Future automation and UI work must build on this contract rather than parsing console output.

## Canonical Bundle Layout
By default, each saved analysis bundle lives under:

`target/analysis-reports/<analysis-id>/`

The base bundle directory can be overridden at runtime with:
- `ANALYSIS_REPORT_DIR`
- `-Djtroubleshoot.reportDir=/path`
- legacy compatibility: `-Djvm.troubleshooter.reportDir=/path`

Current bundle contents:
- `report.json`: canonical machine-readable report
- `report.txt`: shareable text rendering with safe redaction
- `report.md`: shareable Markdown rendering with safe redaction
- `report.html`: shareable HTML rendering with safe redaction

`report.json` is the source of truth. The other files are renderings of the same `AnalysisReport`.

## Schema Versioning
- Top-level field: `schemaVersion`
- Current value: `1`
- Compatibility rule:
  - Readers must accept reports with a missing `schemaVersion` by treating them as schema version `1`
  - New backward-compatible additions may add optional fields under the same schema version
  - Future externally consumed incompatible field renames, removals, or semantic breaks must increment `schemaVersion`

## Required Top-Level Fields
- `schemaVersion`
- `analysisId`
- `createdAt`
- `incidentSummary`
- `overallSeverity`
- `confidence`
- `inputArtifacts`
- `parsedArtifacts`
- `evidence`
- `findings`
- `recommendedActions`
- `missingData`
- `followUpCommands`

## Optional Top-Level Fields
- `userNarrative`
- `agentTraceability`
- `supervisorTrace`
- `artifactInventory`
- `correlationResult`
- `artifactSummaries`
- `reportMetadata`

## Report Metadata Projection
`reportMetadata` is the canonical home for derived metadata that downstream tooling can use without scanning the full report body.

Current stable nested projections:
- `reportMetadata.agentParticipationSummary`
- `reportMetadata.shareableFormats`
- `reportMetadata.catalogSummary`

Current stable aggregate counters:
- `reportMetadata.agentTraceabilityCount`
- `reportMetadata.selectedAgentTraceabilityCount`
- `reportMetadata.agentQualityGateCount`
- `reportMetadata.hasSupervisorTrace`
- `reportMetadata.supervisorTraceStepCount`

`reportMetadata.agentParticipationSummary` is the canonical quick answer for whether the saved analysis actually involved an AI agent or an LLM-backed narrative path. Current fields:
- `analysisPath`
- `aiAgentAttempted`
- `aiAgentAttemptCount`
- `aiAgentSelectedForUserNarrative`
- `llmNarrativeSelectedForUserNarrative`
- `selectedNarrativeAgent`
- `selectedNarrativeSource`

`reportMetadata.catalogSummary` is the compact listing and indexing projection for saved bundles. Current fields:
- `analysisId`
- `createdAt`
- `overallSeverity`
- `confidence`
- `hasUserNarrative`
- `artifactTypes`
- `redactionProfile`
- `inputArtifactCount`
- `hasCorrelationResult`
- `aiAgentAttempted`
- `aiAgentSelectedForUserNarrative`
- `llmNarrativeSelectedForUserNarrative`
- `workflowType` when supervisor trace exists
- `userNarrativeAgent` when selected traceability exists
- `userNarrativeSource` when selected traceability exists

The read-only saved-incident catalog must be built from `report.json` and this summary projection. It must not scrape `report.txt`, `report.md`, or `report.html`.

## Stable Semantics
- `analysisId` identifies one saved analysis bundle
- `overallSeverity` is the highest-severity finding in the report
- `confidence` is the best supported overall confidence for the report
- `findings` are deterministic statements derived from structured parsing, rules, and correlation
- `evidence` anchors findings to specific artifacts, snippets, or extracted metrics
- `recommendedActions` are user actions tied to one or more finding IDs
- `missingData` explains why confidence is limited or what additional evidence would help
- `followUpCommands` suggests commands a user can run next
- `userNarrative` is the saved user-facing summary chosen for the report
- `agentTraceability` records which specialist, synthesis, or fallback path produced report narratives and how that path cleared initial quality gates
- `reportMetadata.agentParticipationSummary` records whether any AI agent ran, whether an AI agent was actually selected for the final narrative, and whether the final narrative path was LLM-backed
- `supervisorTrace` records the saved workflow type plus the supervisor's grounding, comparison or correlation, specialist-selection, and synthesis-selection steps
- `artifactInventory` lists the files discovered during bundle analysis and whether each one was included or skipped

## Supervisor Trace Projection
When present, `supervisorTrace` provides a machine-readable orchestration trail for the saved report.

Stable top-level fields:
- `workflowType`
- `artifactPaths`
- `steps`

Stable step fields:
- `stepId`
- `stepType`
- `stageId`
- `decision`
- `artifactType`
- `artifactPaths`
- `evidenceIds`
- `findingIds`
- `agentName`
- `narrativeSource`
- `selectedForUserNarrative`

The intended use of `supervisorTrace` is:
- explain which grounded workflow ran for `analyze`, `compare`, or `correlate`
- show which specialist or synthesis candidate the supervisor accepted at each important stage
- preserve cross-artifact attribution for compare and correlate flows without scraping human-readable renderings

## Artifact and Finding Identity
- Finding IDs must be deterministic for a given rule or correlation condition
- Evidence IDs must be deterministic for a given parsed artifact section or extracted metric
- Renderers may reorder presentation, but the canonical JSON field names and meanings must remain stable within a schema version

## Schema Evolution Procedure
When changing the canonical JSON contract:

1. Decide whether the change is additive or breaking.
2. Additive changes may:
   - add new optional top-level fields
   - add new optional nested fields under existing objects such as `reportMetadata`
   - add new optional keys inside stable projections such as `catalogSummary`
3. Breaking changes must increment `schemaVersion`. Examples:
   - removing a field
   - renaming a field
   - changing field semantics incompatibly
   - changing required-versus-optional expectations incompatibly
4. Every contract change must update:
   - `docs/report-contract.md`
   - checked-in example bundle fixtures under `src/test/resources/report-bundles/`
   - compatibility-style report-loading tests
   - snapshot or renderer stability tests when output shape changes
5. Readers must continue accepting schema-v1 bundles with a missing `schemaVersion` by treating them as version `1`.

## Privacy Model
- `report.json` is the canonical local bundle artifact for structured analysis results
- External binary inputs such as `.jfr` recordings are referenced by `sourcePath` and parsed metadata rather than embedding raw bytes into `report.json`
- Shareable renderings (`report.txt`, `report.md`, `report.html`) now apply the `internal-safe-v1` redaction profile by default
- That profile redacts hostnames and IPs, filesystem paths, explicit command-line fields, and obvious sensitive environment-style assignments
- The optional `reportMetadata.shareableFormats.redactionProfile` field records the active safe-sharing profile for downstream tooling
- Redaction behavior must not change the underlying canonical JSON semantics for schema version `1`

## Implementation Notes
Primary implementation touchpoints:
- `src/main/java/com/javaassistant/diagnostics/AnalysisReport.java`
- `src/main/java/com/javaassistant/report/AnalysisReportJsonCodec.java`
- `src/main/java/com/javaassistant/render/JsonReportRenderer.java`
- `src/test/resources/report-bundles/`
