# Engineering Backlog: Multi-Agent JVM Troubleshooting

## Purpose
This backlog tracks the work needed to align the repository with its real product goal: a JVM troubleshooting system powered by multiple specialized AI agents.

The repository already has a strong deterministic grounding substrate. The backlog below focuses on turning that substrate into the support layer for a multi-agent runtime rather than treating it as the product endpoint.

Historical execution details remain in:
- `docs/implementation-status.json`
- `docs/next-steps-status.json`

## Current State Snapshot

### Already in place
- Canonical diagnostics types and report contract
- Deterministic parsers for GC, JFR, thread dump, `hs_err`, NMT, heap histogram, `pmap`, container memory, and OOM or restart-signal artifacts
- Deterministic assessors, typed comparison, and multi-artifact correlation
- Saved report bundles with console, JSON, Markdown, and HTML rendering
- Bundle catalog support
- Redaction support for shareable reports
- Java 25 verification and CI automation
- Specialist-agent orchestration for `analyze`, `compare`, `correlate`, and auto-generated `ask` context
- Specialist agent interfaces for GC, JFR, thread dump, `hs_err`, NMT, heap histogram, `pmap`, container memory, OOM or restart-signal logs, and correlation
- Registry-driven model-provider plumbing with dedicated and OpenAI-compatible provider support
- Saved report agent traceability plus supervisor traceability for single, compare, and correlate workflows
- Reference incident regression bundles for compare and correlate workflows

### Still incomplete
- The report contract now captures agent traceability, supervisor traceability, and provider/model-family/template traceability for AI-written narratives
- Reference incident regression now covers single-artifact, compare, and correlate workflows, but broader artifact-family bundles are still valuable
- Agent-level finding attribution still needs to extend beyond narrative-stage traceability

## Priority Workstreams

### Workstream 1: Make Multi-Agent Analysis the Primary Runtime Path
Status:
Initial milestone delivered

Goal:
Move the CLI from a structured-pipeline-first runtime to an specialist-agent runtime.

Tasks:
- reintroduce or add a supervisor or orchestration layer
- route `analyze`, `compare`, and `correlate` through specialist-agent execution when an AI provider is available
- keep deterministic parsing and evidence extraction at the front of the path
- keep deterministic parsing as internal grounding rather than a user-visible degraded analysis mode

Definition of done:
- supported artifact families invoke specialist agents by default when AI is available
- the CLI no longer treats AI as only a summarization layer
- deterministic parsing remains mandatory grounding for supported artifacts

### Workstream 2: Turn Deterministic Outputs into Reusable Agent-Grounding Packs
Status:
Initial milestone delivered

Goal:
Make parsed data and heuristics directly useful to agents.

Tasks:
- define artifact-level grounding packets that include parsed metrics, evidence IDs, typed summaries, comparison deltas, and chunked raw excerpts without prewritten findings or recommendations
- expose existing parsers, comparators, and helper logic as reusable tools or context builders for agents
- define prompt contracts so each specialist agent receives consistent agent-driven input

Definition of done:
- agents consume a stable, testable grounding shape instead of ad hoc raw strings
- deterministic extraction becomes a reusable support layer for the agent runtime

### Workstream 3: Expand Specialist-Agent Coverage
Status:
Initial milestone delivered

Goal:
Match the restored agent layer to the supported artifact surface.

Tasks:
- keep the expanded specialist surface maintained as new artifact support is added
- revisit whether comparison needs dedicated comparison agents or should remain artifact-specialist behavior with comparison prompts

Definition of done:
- every supported artifact family has a specialist-agent story
- no important artifact family is supported only by deterministic logic while the product goal is agent-first analysis

### Workstream 4: Build Multi-Agent Synthesis for Compare and Correlate
Status:
Initial milestone delivered

Goal:
Treat incident synthesis as its own agentic capability instead of only as deterministic plumbing.

Tasks:
- design a supervisor or correlation agent that merges artifact-level outputs
- use deterministic comparison and correlation results as grounding inputs to synthesis
- define how uncertainty, conflicting signals, and missing evidence should be represented
- align `compare` and `correlate` output with the saved report contract

Definition of done:
- `compare` and `correlate` produce multi-agent results
- synthesis is explicit and explainable rather than implicit in one large prompt

### Workstream 5: Extend the Report Contract for Agent Traceability
Status:
Initial milestone delivered

Goal:
Make agent-backed conclusions auditable.

Tasks:
- add report fields for agent attribution and synthesis traceability
- keep provider, model-family, template-version, and tool-usage metadata stable and useful beyond the current supervisor trace and narrative traceability
- keep the JSON contract stable enough for future UI or automation use
- ensure renderers can surface traceability without overwhelming users

Definition of done:
- reports explain both what was found and which specialist or synthesis step produced it
- downstream consumers can depend on the report contract rather than scraping console output

### Workstream 6: Add Agent Quality Gates and Regression Harnesses
Status:
Initial milestone delivered

Goal:
Prevent silent regressions in agent behavior.

Tasks:
- expand the initial reference incident bundles for compare and correlate into broader supported artifact-family coverage
- add checks for evidence coverage, severity discipline, and unsupported-claim drift
- track prompt or template versions where needed
- keep no-provider behavior and AI-unavailable user-facing suppression under test

Definition of done:
- agent quality is testable, not only parser determinism
- high-severity findings remain supported by diagnostic evidence under regression

### Workstream 7: Productize Packaging and User Workflow
Goal:
Make the multi-agent product easier to run and hand off internally.

Tasks:
- verify the packaged runtime path and startup workflow
- improve user guidance for analyze, compare, correlate, report, and ask flows
- keep report-bundle location and runtime identity clear
- preserve the catalog and saved-report workflow as reusable team assets

Definition of done:
- a new internal user can run the tool and understand the multi-agent workflow without reading source code
- report bundles and catalog entries support incident handoff and reuse

## Recommended Execution Order
1. Workstream 6: broaden artifact-family reference-incident coverage and future finding-attribution checks
2. Workstream 7: packaging and user workflow
3. Future work: agent-level finding attribution and deeper synthesis traceability

## Near-Term Acceptance Criteria
- The design and roadmap clearly describe specialist AI agents as the primary analysis layer.
- Deterministic parsing and structured data are described as grounding infrastructure.
- Supported artifact families have explicit specialist-agent coverage in the current runtime.
- The next implementation step is clearly identified as packaging, user workflow polish, and broader artifact-family evaluation rather than more deterministic-only expansion.

## Risks to Watch
- Leaving the runtime structured-only will keep the project misaligned with its stated goal.
- Letting agents operate on unbounded raw logs without grounding will erode trust.
- Failing to capture agent traceability will make outputs harder to audit and improve.
- Expanding artifact coverage without a matching specialist-agent story will deepen the architecture mismatch.
