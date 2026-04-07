# Product Roadmap: Multi-Agent JVM Troubleshooting Assistant

## Product Goal
Build an internal-first JVM troubleshooting product whose primary value comes from multiple specialized AI agents.

The intended product is not:
- a generic prompt-only log chatbot
- a deterministic-only parser with AI limited to narrative polish

The intended product is:
- a multi-agent assistant
- where each agent specializes in a diagnostic artifact family or synthesis task
- and where deterministic parsing, typed evidence, and structured reports exist to support and audit agent reasoning

The near-term product should be a CLI that:
- ingests JVM diagnostic artifacts and support bundles
- routes them to the right specialist agents
- synthesizes agent-authored findings and next actions
- saves a reusable structured incident report

## Strategic Position

### Primary users
- SREs
- platform engineers
- JVM performance or reliability engineers

### Core product promise
Turn scattered JVM diagnostics into a multi-agent investigation within minutes.

### Product principles
- AI agents are the main analysis layer.
- Deterministic parsing and structured evidence ground the agents.
- Saved reports are reusable product artifacts, not transient console output.
- High-severity conclusions must remain traceable and auditable.

## Current Implementation Position

Status as of March 31, 2026:

### Foundational capabilities already implemented
- Canonical diagnostics types exist for artifacts, parsed outputs, evidence, findings, actions, reports, and correlation results.
- Deterministic parsers exist for GC logs, JFR recordings, thread dumps, `hs_err`, NMT, heap histograms, `pmap`, container-memory snapshots, and kernel OOM or restart-signal logs.
- Deterministic assessors, typed comparison, and multi-artifact correlation already extract a strong grounding layer.
- Saved report bundles exist with console, JSON, Markdown, and HTML rendering plus catalog support.
- Safe-redaction support and Java 25 verification are already in place.
- Registry-driven AI provider plumbing now exists for local, cloud, and OpenAI-compatible hosted providers.
- Specialist-agent orchestration is now the primary runtime path for `analyze`, `compare`, and `correlate`.
- Artifact-specific agent interfaces now cover GC, JFR, thread dump, `hs_err`, NMT, heap histogram, `pmap`, container memory, OOM or restart-signal logs, and multi-artifact correlation.
- Saved reports now persist both agent traceability and supervisor traceability, including compare and correlate workflow steps.
- Reference incident evaluation bundles now exercise compare and correlate agent workflows end to end as part of regression coverage.

### Major gap still open
- The report contract now includes agent traceability, supervisor traceability, and provider/model-family/template traceability for AI-written narratives.
- Reference incident coverage now includes single-artifact, compare, and correlate workflows, but broader artifact-family bundles and agent-level finding attribution are still future hardening work.
- Agent-level finding attribution and richer model-execution metadata remain future hardening work.

### What this means for the roadmap
The repo now has an agent-first specialist-agent core with saved supervisor traceability, so the roadmap should shift from runtime realignment to traceability hardening, broader evaluation, and user workflow polish.

## Product Decisions

### 1. Primary architecture
The product should use:
- specialist AI agents as primary analyzers
- deterministic parsing, typed evidence, and report bundles as grounding infrastructure

### 2. Trust model
- Agents must reason from evidence, not from unconstrained raw logs alone.
- High-severity findings must cite deterministic evidence and should eventually record which agent produced or endorsed them.
- Unsupported artifact families should not silently fall into a generic ungrounded AI flow.

### 3. Report contract
- `report.json` remains the canonical machine-readable artifact.
- The report should evolve to include agent traceability and synthesis attribution.
- Human-readable renderings remain important, but downstream tooling should depend on the JSON contract.

### 4. Degraded mode
If no AI provider is available, deterministic parsing may still run internally as grounding. The product should not present or save troubleshooting analysis for users until an AI specialist or synthesis agent provides the narrative.

### 5. Support-bundle behavior
- `analyze <dir>` should treat a directory as one incident input set.
- The runtime should fan the discovered artifacts out to the appropriate specialists.
- A correlation or supervisor agent should synthesize one incident-level result from artifact-level outputs.

## Roadmap to a Product-Ready Multi-Agent v1

### Phase 1: Grounding Foundation
Status: largely implemented

Goal:
Keep and harden the deterministic substrate that makes specialist-agent analysis safer and more reusable.

Already delivered:
- typed diagnostics domain
- parsers and assessors
- comparison and correlation helpers
- report bundle contract and rendering
- shareable redaction
- Java 25 verification

Remaining expectation:
- preserve this layer as the grounding substrate for agent reasoning rather than letting it become a substitute for agents

### Phase 2: Multi-Agent Runtime Realignment
Status: delivered

Goal:
Make specialist-agent analysis the primary runtime path for supported artifacts.

Deliverables:
- reintroduce a supervisor or orchestration layer in the CLI runtime
- route supported artifacts to artifact-specific agents
- feed agents bounded diagnostic starting context built from parsed evidence, summaries, and curated raw excerpts
- preserve deterministic report assembly after agent analysis

Success criteria:
- `analyze` invokes specialist agents for supported artifact types when an AI provider is configured
- deterministic parsing remains part of the path, but agent reasoning is the primary product output
- the product no longer describes AI as only a narrative add-on

### Phase 3: Expand Specialist Coverage to the Full Supported Artifact Surface
Status: delivered

Goal:
Match the current grounding surface with equivalent agent specialization.

Deliverables:
- JFR specialist agents
- thread-dump specialist agents
- container-memory specialist agents
- OOM or restart-signal specialist agents
- compare and correlate flows aligned with the expanded specialist set

Success criteria:
- every supported artifact family has a specialist AI path
- support bundles can be analyzed by a mix of artifact-specific agents and a synthesis layer

### Phase 4: Agent-Backed Comparison, Correlation, and Follow-Up Workflows
Status: initial delivery completed, deeper expansion planned

Goal:
Deepen supervisor synthesis, compare and correlate traceability, and saved-report user follow-up.

Deliverables:
- comparison-oriented specialist prompts or agents
- a correlation or supervisor agent for cross-artifact synthesis
- follow-up question handling that uses saved reports and agent-backed findings
- clear handling of uncertainty and missing evidence in multi-agent outputs

Success criteria:
- `compare` and `correlate` become multi-agent workflows, not only deterministic helper pipelines
- users can ask follow-up questions against saved agent-backed reports

### Phase 5: Report Traceability, Evaluation, and Safety
Status: traceability and release-threshold hardening delivered, further attribution depth planned

Goal:
Make agent outputs auditable and trustworthy enough for real incident use.

Deliverables:
- keep the report contract carrying provider, model-family, and template metadata for selected AI narratives
- keep prompt and agent-template version tracking stable as prompts evolve
- expand reference incident bundles beyond the initial compare and correlate harness
- keep evidence gates and AI-unavailable user-facing suppression under test

Success criteria:
- reports show which agent produced which conclusions
- regressions in agent quality become testable
- high-severity findings remain agent-driven even when the primary interpreter is an AI agent

### Phase 6: Productization and Team Workflow
Status: planned

Goal:
Turn the multi-agent core into an easier-to-adopt team product.

Deliverables:
- packaging and onboarding polish
- user walkthroughs and runtime identity improvements
- stronger catalog and incident-history workflows
- future UI or service layers built on canonical reports

Success criteria:
- a new internal team can run the tool, trust its outputs, and reuse prior analyses
- the product can evolve toward a shared incident workspace without losing grounding or traceability

## Product-Ready v1 Acceptance Criteria
The multi-agent v1 milestone should only be considered complete when:
- supported artifact families route through specialist AI agents when a provider is configured
- deterministic parsing and structured evidence clearly ground those agent outputs
- `compare` and `correlate` have an clear synthesis story, not only deterministic helpers
- the saved report contract includes enough traceability to explain what happened
- high-severity findings remain traceable and auditable
- shareable report formats stay safely redacted
- the Java 25 verification workflow remains reproducible for contributors

## Longer-Term Expansion
After the multi-agent v1 milestone, the product can grow into:
- broader collector coverage such as heap dumps and JVM flag inventories
- deeper retained-size or object-retention analysis
- richer Kubernetes and host-environment collectors
- incident workspaces, annotations, and multi-user collaboration
- external integrations if needed
