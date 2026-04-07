# Next-Steps Roadmap: Multi-Agent Realignment

## Purpose
This document defines the next execution phase after clarifying the project's true goal: a multi-agent JVM troubleshooting assistant.

The earlier deterministic post-v1 feature expansions are already captured in:
- `docs/next-steps-status.json`

This roadmap now focuses on the next major milestone after landing the initial multi-agent runtime, saved supervisor traceability, and the first reference-incident trust-gate pass:
- deepen traceability beyond narrative-stage and supervisor-stage traceability
- broaden quality gates around agent behavior
- keep deterministic parsing, structured evidence, and saved reports as the grounding layer

## Planning Assumptions
- The repository already has a strong deterministic substrate: parsers, assessors, comparison, correlation, and report bundles.
- Artifact-specific agent interfaces now cover GC, JFR, thread dump, `hs_err`, NMT, heap histogram, `pmap`, container memory, OOM or restart-signal logs, and multi-artifact correlation.
- The current runtime already routes supported `analyze`, `compare`, and `correlate` flows through specialist-agent orchestration.
- The product should not fall back to an ungrounded generic log-chat experience for supported artifacts.
- `report.json` remains the canonical persisted artifact.

## Execution Principles
- Keep specialist agents as the primary interpreters.
- Keep deterministic parsing and evidence extraction mandatory for supported artifacts.
- Prefer bounded diagnostic starting context over long ad hoc raw-log prompts.
- Keep saved reports and traceability as reusable product artifacts.
- Add evaluation gates for agent quality, not just parser determinism.

## Ordered Workstreams

### 1. Deepen Multi-Agent Synthesis and Traceability
Goal:
Make the multi-agent runtime easier to explain, audit, and extend.

Deliverables:
- keep saved traceability stable across provider, model-family, and template metadata while extending it into future finding attribution
- deepen compare and correlate supervisor behavior into future agent-level finding attribution
- define how saved-report follow-up should reference specialist-agent output

Exit criteria:
- saved reports capture which agent or synthesis step produced the result and enough execution metadata to reproduce it
- compare and correlate remain explainable as the orchestration gets richer

### 2. Add Agent Evaluation and Trust Gates
Goal:
Protect agent quality as the runtime evolves.

Deliverables:
- expand the initial reference incident bundles for compare and correlate into broader supported artifact-family coverage
- add checks for unsupported-claim drift, evidence coverage, and severity discipline
- keep no-provider behavior and AI-unavailable user-facing suppression under regression
- version prompts or templates where needed for reproducibility

Exit criteria:
- the project has automated feedback on agent quality, not only parser determinism

### 3. Productize Packaging and User Workflow
Goal:
Make the multi-agent product easier to run, understand, and hand off internally.

Deliverables:
- document the packaged runtime path
- refine user guidance for multi-agent analyze, compare, correlate, report, catalog, and ask flows
- keep runtime identity and saved-bundle location obvious
- preserve reuse of prior analyses through catalog and saved reports

Exit criteria:
- a new user can run the tool and understand the multi-agent workflow without reading the source

### 4. Add Multi-Agent Synthesis for Compare and Correlate
Goal:
Make comparison and incident synthesis explicitly agentic and agent-driven.

Deliverables:
- design comparison-oriented synthesis behavior
- design cross-artifact supervisor or correlation behavior
- preserve uncertainty and missing-data reporting
- keep deterministic comparators and correlators as grounding inputs

Exit criteria:
- `compare` and `correlate` produce explainable multi-agent outputs

### 5. Extend the Report Contract for Agent Traceability
Goal:
Make saved reports suitable for agent-backed operation and later audit.

Deliverables:
- add agent attribution fields to the report contract
- record synthesis traceability at the incident level
- decide how much provider or model metadata should be retained
- expose the new traceability safely in renderers

Exit criteria:
- reports show what was concluded, what evidence supported it, and which agent or synthesis step was responsible

## Decision Gates
- After workstream 1, decide how much agent or synthesis traceability belongs in the public report contract versus internal debug traces.
- After workstream 2, decide what evidence-coverage or unsupported-claim thresholds should block release.
- After workstream 3, decide what packaging or onboarding path is required for broader internal adoption.
- After workstream 5, decide what downstream UI or service consumers should rely on the new traceability fields.

## Recommended Near-Term Sequence
1. Finish workstream 1 before broadening the public report contract.
2. Finish workstream 2 before broader internal adoption.
3. Finish workstream 3 before expanding packaging or onboarding claims.
4. Land workstream 4 before expanding product messaging around compare and correlate.
5. Land workstream 5 before building any UI or service layer on top of reports.

## What Success Looks Like
- The repo has one clear story: specialist agents are primary, deterministic parsing is grounding.
- Supported artifact families have explicit specialist-agent coverage.
- The saved report contract remains reusable while becoming agent-aware.
- Compare and correlate become multi-agent workflows instead of purely deterministic helpers.
- The project becomes easier to trust because agent behavior is both agent-driven and testable.
