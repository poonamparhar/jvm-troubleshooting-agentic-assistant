# Engineering Backlog: Evidence-First JVM Triage v1

## Purpose
This backlog turns the product roadmap into an implementation sequence for the current codebase. It is optimized for getting from the existing demo CLI to an internal-first, evidence-backed triage tool with deterministic parsing, structured reports, and bounded AI synthesis.

## Current State Assessment

### What the current prototype already gives us
- Interactive CLI shell with command parsing and provider switching in `JVMTroubleshooter`
- Basic artifact detection via content heuristics in `DataType`
- A small shared input model in `DiagnosticData`
- Per-artifact AI interfaces for GC, `hs_err`, NMT, heap histogram, and `pmap`
- Narrow parsing helpers for NMT, heap histogram, and `pmap`
- Sample corpus in `samples/` that can seed golden tests

### Main gaps relative to the roadmap
- The analysis flow is still prompt-first, not parser-first
- Findings are emitted as ad hoc plain text, not as a stable report model
- Compare mode relies on stitched marker strings instead of typed comparisons
- Correlation is LLM prose over raw combined content instead of normalized cross-artifact findings
- There is no canonical JSON report contract for automation or a later UI
- There are no automated golden tests in the current Maven project

## Codebase Mapping

### Keep, but narrow or extract
- `src/main/java/com/example/JVMTroubleshooter.java`
  - Keep as the CLI entrypoint, but move file loading, detection, orchestration, and rendering out of it.
- `src/main/java/com/example/data/DataType.java`
  - Keep the detection intent, but evolve it into artifact classification plus file metadata helpers.
- `src/main/java/com/example/data/DiagnosticData.java`
  - Keep only as a temporary bridge. Replace with `InputArtifact` and parsed artifact types.
- `src/main/java/com/example/data/Issue.java`
  - Reuse concepts, but likely replace with richer `Finding` and `Evidence`.
- `src/main/java/com/example/data/Recommendation.java`
  - Reuse concepts, but align with report output and action categorization.

### Replace as the primary analysis path
- `src/main/java/com/example/agents/GCLogAgent.java`
- `src/main/java/com/example/agents/HSErrLogAgent.java`
- `src/main/java/com/example/agents/NMTAgent.java`
- `src/main/java/com/example/agents/HeapHistogramAgent.java`
- `src/main/java/com/example/agents/PmapAgent.java`
- `src/main/java/com/example/agents/CorrelationAgent.java`
  - These should stop being the primary analyzers for supported artifacts. They can survive only as bounded synthesis and follow-up layers fed by structured findings.

### Harvest and promote into deterministic parsers
- `src/main/java/com/example/agents/NMTTools.java`
- `src/main/java/com/example/agents/HeapHistogramTools.java`
- `src/main/java/com/example/agents/PmapTools.java`
  - The logic here should move into parser packages with typed outputs and unit tests.

## Target v1 Architecture

### Suggested packages
- `com.example.cli`
- `com.example.ingest`
- `com.example.detect`
- `com.example.parse`
- `com.example.rules`
- `com.example.correlate`
- `com.example.report`
- `com.example.render`
- `com.example.ai`
- `com.example.model`

### Core v1 flow
1. Discover input files from a file or directory.
2. Classify each artifact and capture metadata.
3. Parse each supported artifact into typed structured output.
4. Run artifact-specific rules to emit findings and evidence.
5. Correlate normalized findings across artifacts.
6. Build a canonical `AnalysisReport`.
7. Render console summary plus `json`, `markdown`, and `html` reports.
8. Optionally call AI to produce an operator-friendly narrative from the structured report.

## Backlog

### Epic 1: Introduce a canonical domain model
Goal: establish stable types so parsing, rules, correlation, rendering, and AI all speak the same language.

Stories:
- Add `ArtifactType`, `InputArtifact`, `ArtifactMetadata`, `ParsedArtifact`, `Evidence`, `Finding`, `RecommendedAction`, `AnalysisReport`, and `CorrelationResult`.
- Define `ConfidenceLevel`, `FindingStatus`, and `ActionType` enums.
- Add a canonical JSON-serializable report shape.
- Mark `DiagnosticData` as transitional and route new code around it.

Definition of done:
- All new pipeline code depends on the new model package.
- Every finding can reference at least one evidence item.
- A sample report object can be serialized deterministically.

Suggested first files:
- `src/main/java/com/example/model/ArtifactType.java`
- `src/main/java/com/example/model/InputArtifact.java`
- `src/main/java/com/example/model/Evidence.java`
- `src/main/java/com/example/model/Finding.java`
- `src/main/java/com/example/model/AnalysisReport.java`

### Epic 2: Split ingestion and detection out of the CLI
Goal: remove file loading and type detection logic from the monolithic CLI entrypoint.

Stories:
- Extract command handling from `JVMTroubleshooter` into a small CLI layer.
- Add `ArtifactLoader` that reads files and directories.
- Add support-bundle discovery for `analyze <file-or-dir>`.
- Replace direct `DataType.fromContents(...)` calls with a classifier service.
- Preserve user type override, but move it into the ingest layer.

Definition of done:
- The CLI no longer owns low-level file reading or type detection.
- A directory input returns a list of discovered artifacts with metadata.
- Unsupported files generate explicit warnings instead of falling through to AI.

Current code touchpoints:
- `src/main/java/com/example/JVMTroubleshooter.java`
- `src/main/java/com/example/data/DataType.java`
- `src/main/java/com/example/data/DiagnosticData.java`

### Epic 3: Build deterministic parsers for all v1 artifacts
Goal: make structured extraction the primary source of truth.

Stories:
- GC parser:
  - collector detection
  - pause extraction
  - throughput summary
  - full GC count
  - heap pressure indicators
- `hs_err` parser:
  - fatal error type
  - problematic frame
  - JVM version
  - command line flags
  - memory and system context
- NMT parser:
  - top-level totals
  - category totals
  - metadata/metaspace details
  - thread stack details
  - diff parsing
- Heap histogram parser:
  - top consumers
  - total bytes/instances
  - diff parsing for compare mode
- `pmap` parser:
  - totals
  - breakdowns
  - large mappings
  - anonymous/file-backed/shared buckets

Definition of done:
- Each artifact type has a typed parser result.
- Each parser has fixture-based tests using `samples/`.
- Existing tool logic is either moved or deleted from the agent package.

Current code touchpoints:
- `src/main/java/com/example/agents/NMTTools.java`
- `src/main/java/com/example/agents/HeapHistogramTools.java`
- `src/main/java/com/example/agents/PmapTools.java`
- `src/main/java/com/example/agents/GCLogAgent.java`
- `src/main/java/com/example/agents/HSErrLogAgent.java`

### Epic 4: Add rule engines per artifact
Goal: convert parsed metrics into deterministic findings, severity, confidence, and recommended actions.

Stories:
- Define rule interfaces for single-artifact analysis.
- Create GC rules for long pauses, low throughput, frequent full GC, and heap pressure.
- Create `hs_err` rules for native OOM, crash signatures, suspect frames, and missing-data flags.
- Create NMT rules for metaspace pressure, thread stack pressure, code cache pressure, and native allocation growth.
- Create heap histogram rules for leak suspects and abnormal top-consumer patterns.
- Create `pmap` rules for anon growth, large mappings, and native-memory mismatch hints.

Definition of done:
- Findings are deterministic for the same input.
- Every rule-produced finding carries evidence and rationale.
- Recommendations distinguish immediate action from further evidence collection.

### Epic 5: Replace stitched compare mode with typed comparisons
Goal: make comparisons first-class instead of marker-based prompt tricks.

Stories:
- Introduce `ComparisonInput` and typed diff results.
- Replace marker injection in compare flows with parser-level diff support.
- Support compare for NMT, heap histogram, and `pmap` first.
- Emit compare findings in the same report schema as single-file analysis.

Definition of done:
- Compare mode does not concatenate marker strings for supported formats.
- Diff results can be rendered in console and JSON outputs.

Current code touchpoints:
- `src/main/java/com/example/JVMTroubleshooter.java`
- `src/main/java/com/example/agents/NMTAgent.java`
- `src/main/java/com/example/agents/HeapHistogramAgent.java`
- `src/main/java/com/example/agents/PmapAgent.java`

### Epic 6: Build a normalized correlator
Goal: create cross-artifact findings from structured signals instead of raw combined prompts.

Stories:
- Define normalized dimensions for heap pressure, native pressure, metaspace pressure, crash context, and confidence.
- Implement correlation rules described in the roadmap.
- Emit correlation findings into `CorrelationResult` and merge them into the final report.
- Add missing-data findings when a strong conclusion cannot be made.

Definition of done:
- Correlation depends on parsed artifacts and artifact-level findings, not raw strings.
- Cross-artifact findings include evidence references back to source artifacts.

Current code touchpoints:
- `src/main/java/com/example/agents/CorrelationAgent.java`
- Future parser and rule packages

### Epic 7: Make AI a bounded synthesis layer
Goal: keep AI helpful without letting it invent primary facts.

Stories:
- Replace artifact-specific analyzer agents with one summarizer prompt over `AnalysisReport`.
- Keep provider abstraction, but move it under an `ai` package.
- Add explicit guardrails in the summarizer prompt: no fabricated metrics, no unsupported findings.
- Allow deterministic mode to run with no configured AI provider.

Definition of done:
- Core report generation works with AI disabled.
- AI output is derived from structured findings only.
- Provider switching affects narrative generation, not metric extraction.

Current code touchpoints:
- `src/main/java/com/example/modelproviders/OCIChatModelProvider.java`
- `src/main/java/com/example/modelproviders/OllamaChatModelProvider.java`
- `src/main/java/com/example/agents/*.java`
- `src/main/java/com/example/JVMTroubleshooter.java`

### Epic 8: Introduce canonical report generation and rendering
Goal: produce trustworthy, shareable artifacts from a stable report model.

Stories:
- Create a report assembler that combines artifact findings, correlation findings, confidence, and missing-data guidance.
- Add JSON renderer as the source-of-truth output.
- Add Markdown and HTML renderers for sharing.
- Add a concise console renderer for incident use.
- Add stable analysis IDs and timestamped report directories.

Definition of done:
- `analyze` emits a concise summary plus a saved report location.
- JSON output is stable enough for tests and future UI consumption.
- Markdown and HTML include findings, evidence, actions, and missing data.

### Epic 9: Redesign the CLI around product workflows
Goal: align the command surface with the roadmap.

Stories:
- Support:
  - `analyze <file-or-dir>`
  - `compare <baseline> <current>`
  - `report <analysis-id or input> --format text|markdown|html|json`
  - `ask <question>` against a saved/generated report
- Keep provider switching, but make it optional for deterministic runs.
- Persist report bundles so later commands can refer to analysis IDs.

Definition of done:
- The main UX is report-driven, not chat-memory-driven.
- `ask` consumes structured report context instead of raw log bodies.

Current code touchpoints:
- `src/main/java/com/example/JVMTroubleshooter.java`

### Epic 10: Add tests and trust gates
Goal: make results repeatable and safe enough for internal incident use.

Stories:
- Add JUnit and Maven Surefire configuration.
- Add parser fixture tests per artifact.
- Add correlation tests for mixed support bundles.
- Add regression tests for unknown files and partial/corrupt input.
- Add snapshot-style tests for canonical JSON output.
- Add a no-provider integration path to verify deterministic behavior.

Definition of done:
- CI can run parser, rule, correlator, and renderer tests.
- Same input yields materially stable JSON output.
- High-severity findings always include evidence.

Current code touchpoints:
- `pom.xml`
- `samples/`

## Recommended Sequence

### Sprint 1
- Epic 1: canonical domain model
- Epic 2: ingest/detection extraction
- Test scaffolding from Epic 10

### Sprint 2
- Epic 3: NMT, heap histogram, and `pmap` parsers
- Epic 5: typed compare for those same artifacts

### Sprint 3
- Epic 3: GC and `hs_err` parsers
- Epic 4: first rule engines for all supported artifact types

### Sprint 4
- Epic 6: normalized correlator
- Epic 8: JSON report assembler and console renderer

### Sprint 5
- Epic 7: bounded AI synthesis
- Epic 9: final CLI workflow cleanup
- Remaining trust and export work from Epic 10

## First Concrete Implementation Slice
If we want the fastest path to visible progress, the best first slice is:

1. Add the new report/domain model.
2. Extract `analyze <file>` into a small pipeline service.
3. Implement one fully deterministic artifact path end to end, preferably NMT.
4. Emit a JSON report plus concise console summary for NMT.
5. Add fixture tests for that path.

That slice proves the architecture before we repeat it across GC, `hs_err`, heap histogram, and `pmap`.

## Suggested Near-Term Task List
- Create `model` package and report types.
- Create `ingest` package and move file loading out of `JVMTroubleshooter`.
- Create `parse.nmt` package by lifting logic from `NMTTools`.
- Create `rules.nmt` package for metaspace/thread/code-cache findings.
- Add `report` package with JSON serialization and summary rendering.
- Add JUnit dependencies and first parser/rule tests.

## Risks to Watch
- Keeping the current agent interfaces around too long will slow the architecture shift.
- Compare mode will remain brittle until marker-based prompting is removed.
- Large-file truncation in the CLI is acceptable for LLM prompts, but it is the wrong behavior for deterministic parsers and should move behind parser-aware limits.
- `DataType` currently includes out-of-scope types like thread dumps and heap dumps; keep the enum or classifier aligned with actual v1 support so the product does not overpromise.
