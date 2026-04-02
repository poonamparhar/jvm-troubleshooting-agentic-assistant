# JVM Troubleshooting Agentic Assistant - Target Design

## 1. Product Goal
The main goal of this project is to build a JVM troubleshooting system whose core value comes from multiple specialized AI agents.

Each agent should focus on a specific diagnostic domain, for example:
- GC logs
- JFR recordings
- thread dumps
- `hs_err` crash logs
- Native Memory Tracking output
- heap histograms
- `pmap`
- container-memory snapshots
- kernel OOM and restart-signal logs
- cross-artifact correlation and synthesis

Deterministic parsing, typed diagnostics, structured evidence, comparison helpers, and saved reports are still important, but they exist to strengthen agent reasoning. They are grounding infrastructure for the agents, not the product's replacement for AI.

This project is not intended to be:
- an ungrounded raw-log chatbot
- a deterministic-only report generator with AI bolted on at the end

Current repo reality as of March 31, 2026:
- the deterministic grounding and report pipeline is already strong
- artifact-specific agent interfaces now cover GC, JFR, thread dump, `hs_err`, NMT, heap histogram, `pmap`, container memory, OOM or restart-signal logs, and multi-artifact correlation
- the CLI runtime now routes supported `analyze`, `compare`, `correlate`, and auto-generated `ask` context through specialist-agent orchestration
- saved reports now persist both agent traceability and supervisor traceability, and the test suite now includes initial golden compare or correlate incident evaluation bundles
- the main remaining gaps are provider or model execution traceability depth, broader golden-incident coverage, and stricter trust thresholds for the specialist-agent runtime

Related documents:
- `README.md`
- `docs/product-roadmap.md`
- `docs/engineering-backlog.md`
- `docs/next-steps-roadmap.md`
- `docs/report-contract.md`
- `docs/implementation-status.json`

## 2. Design Principles

### 2.1 Specialist Over Generic
The system should prefer artifact-specific agents over a single general-purpose troubleshooting prompt. A GC log should be analyzed by a GC specialist; a thread dump should be analyzed by a thread-dump specialist; multi-artifact bundles should be synthesized by a correlation or supervisor agent.

### 2.2 Structured Context Over Free-Form
Agents should reason over:
- parsed metrics
- evidence anchors
- typed summaries
- comparison deltas
- chunked raw excerpts when useful

They should not depend on blindly ingesting unbounded raw logs as their only context.

### 2.3 Reports Are Product Artifacts
The saved report bundle is still a first-class product artifact. It should capture what the agent team concluded, what evidence supported those conclusions, and what the user should do next.

### 2.4 Deterministic Logic Supports the Agents
Parsers, assessors, comparators, and correlators remain valuable because they:
- reduce hallucination risk
- expose evidence consistently
- give agents typed context quickly
- make output auditable and reusable

They should not hand specialist agents prewritten incident findings or user recommendations as if those conclusions were already decided. Their job is to prepare understandable, structured diagnostic context for the agents.

### 2.5 Degraded Mode Is Acceptable, But Not the Goal
If no AI provider is configured, deterministic parsing may still run internally as preparation, but user-facing troubleshooting analysis should wait until an AI specialist or synthesis agent is available. That safety net is for preparation, not as the intended primary product experience.

## 3. Target Architecture

### 3.1 Intended Runtime Flow

```text
User CLI Command
    |
    v
JVMTroubleshooter
    |
    +--> ArtifactLoader / ArtifactClassifier / discovery
    |
    +--> Grounding layer
    |       |
    |       +--> ArtifactParsingService
    |       +--> ArtifactAssessmentService
    |       +--> ArtifactComparisonService
    |       +--> MultiArtifactCorrelator
    |       +--> Evidence extraction / typed summaries / deltas / chunked context
    |
    +--> Analysis orchestrator / supervisor
            |
            +--> GCLogAgent
            +--> HSErrLogAgent
            +--> NMTAgent
            +--> HeapHistogramAgent
            +--> PmapAgent
            +--> JfrAgent
            +--> ThreadDumpAgent
            +--> ContainerMemoryAgent
            +--> OomSignalAgent
            +--> Correlation / synthesis agent
            |
            +--> artifact-informed analysis
            +--> cross-artifact synthesis
            +--> findings and recommendations
            +--> confidence and uncertainty notes
    |
    +--> AnalysisReportAssembler
    +--> ReportBundleService + renderers
    +--> report / catalog / ask workflows
```

### 3.2 Current Implementation Gap
The repository now has a specialist-agent runtime in place, including saved narrative traceability, provider/model-family/template execution traceability, supervisor traceability, and an initial golden-incident harness, but it still needs broader artifact-family evaluation and future finding-level attribution.

The next major architectural moves are:
- keep the deterministic grounding substrate
- keep the canonical report bundle
- extend saved traceability into future finding-level attribution
- deepen agent quality gates so the multi-agent runtime stays trustworthy as prompts and orchestration evolve

## 4. Core Components

### 4.1 CLI and Runtime Routing
Primary entry point:
- `src/main/java/com/javaassistant/JVMTroubleshooter.java`

Responsibilities:
- interactive command loop
- artifact loading and support-bundle discovery
- provider switching between OCI and Ollama
- routing into single-artifact, comparison, correlation, report, catalog, and follow-up-question flows

Design target:
- `analyze`, `compare`, and `correlate` should route into specialist-agent orchestration
- `report` and `catalog` should stay report-centric
- `ask` should use saved reports and specialist-agent outputs as the source of truth

### 4.2 Ingest and Discovery
Primary classes:
- `src/main/java/com/javaassistant/ingest/ArtifactLoader.java`
- `src/main/java/com/javaassistant/ingest/ArtifactDiscoveryResult.java`
- `src/main/java/com/javaassistant/detect/ArtifactClassifier.java`

Responsibilities:
- load raw artifact content or external-binary references
- classify artifact families
- discover supported and unsupported files in support bundles
- synthesize combined container-memory artifacts when raw cgroup files are present

### 4.3 Grounding Layer
Primary classes:
- `src/main/java/com/javaassistant/parse/ArtifactParsingService.java`
- `src/main/java/com/javaassistant/assessment/ArtifactAssessmentService.java`
- `src/main/java/com/javaassistant/compare/ArtifactComparisonService.java`
- `src/main/java/com/javaassistant/correlate/MultiArtifactCorrelator.java`

Grounding responsibilities:
- extract typed metrics, snippets, and evidence anchors
- compute deterministic heuristics and candidate signals
- produce comparison deltas and correlation summaries
- build the structured context packets that agents can trust and cite

The grounding layer should make agents better, faster, and safer. It should not replace them as the product's primary reasoning layer.

### 4.4 Specialist AI Agents
Existing agent interfaces:
- `src/main/java/com/javaassistant/agents/GCLogAgent.java`
- `src/main/java/com/javaassistant/agents/JfrAgent.java`
- `src/main/java/com/javaassistant/agents/ThreadDumpAgent.java`
- `src/main/java/com/javaassistant/agents/HSErrLogAgent.java`
- `src/main/java/com/javaassistant/agents/NMTAgent.java`
- `src/main/java/com/javaassistant/agents/HeapHistogramAgent.java`
- `src/main/java/com/javaassistant/agents/PmapAgent.java`
- `src/main/java/com/javaassistant/agents/ContainerMemoryAgent.java`
- `src/main/java/com/javaassistant/agents/OomSignalAgent.java`
- `src/main/java/com/javaassistant/agents/CorrelationAgent.java`

Specialist-agent responsibilities:
- interpret one artifact family deeply
- use structured evidence and tools first, then raw excerpts when needed
- produce structured-context-supported findings, likely causes, uncertainty notes, and next actions
- stay within the scope of their artifact specialty instead of drifting into generic troubleshooting advice

### 4.5 Orchestration and Synthesis
Design target:
- introduce or reintroduce a supervisor or orchestration layer that coordinates specialist agents
- fan out one support bundle into the right artifact-specific agents
- merge outputs into one incident-level synthesis
- choose when to run comparison or correlation specialists
- support parallel agent execution where artifact analyses are independent

The deterministic comparison and correlation services remain useful here as:
- tools for the agents
- grounding inputs for the supervisor
- fallback baselines for evaluation

### 4.6 Report Assembly and Persistence
Primary classes:
- `src/main/java/com/javaassistant/report/AnalysisReportAssembler.java`
- `src/main/java/com/javaassistant/report/AnalysisReportJsonCodec.java`
- `src/main/java/com/javaassistant/report/ReportBundleService.java`
- `src/main/java/com/javaassistant/render/*.java`

Responsibilities:
- persist the canonical `AnalysisReport`
- render console, JSON, Markdown, and HTML outputs
- preserve evidence references and follow-up actions
- support safe sharing through redaction

The report contract already captures narrative-stage agent traceability plus supervisor traceability. Future additions should extend that with:
- which provider or model family was used
- which prompt or template version produced the agent output
- which specialist agent endorsed future finding-level conclusions

### 4.7 Model Providers and Agent Infrastructure
Primary classes:
- `src/main/java/com/javaassistant/modelproviders/OCIChatModelProvider.java`
- `src/main/java/com/javaassistant/modelproviders/OllamaChatModelProvider.java`

Infrastructure expectations:
- use LangChain4j and `langchain4j-agentic` for specialist-agent definitions and orchestration
- support OCI and Ollama as interchangeable providers
- keep prompts and tool contracts versioned enough for regression testing

## 5. Command Intent

### 5.1 `load`
Loads:
- a single diagnostic artifact
- a raw container-memory directory that can be synthesized
- or a saved report bundle by `analysisId`

### 5.2 `analyze`
Target behavior:
- build a structured diagnostic context for one artifact or one support bundle
- invoke the relevant specialist agent or agents
- persist a structured incident report containing the AI analysis result

### 5.3 `compare`
Target behavior:
- build deterministic baseline/current deltas
- feed those deltas plus artifact excerpts into a comparison-capable specialist agent or synthesis layer
- persist a report that explains what changed and why it matters

### 5.4 `correlate`
Target behavior:
- run per-artifact specialists first
- invoke a correlation or supervisor agent to synthesize the incident across artifact families
- preserve contributing evidence paths and uncertainty when data is incomplete

### 5.5 `ask`
Target behavior:
- answer from saved reports, structured findings, and future agent traceability
- optionally route a follow-up question back to the relevant specialist agent
- avoid treating open-ended chat memory over raw logs as the source of truth

### 5.6 `report` and `catalog`
These remain report-centric product surfaces:
- `report` reads a saved bundle
- `catalog` lists prior incident analyses from canonical metadata

## 6. Trust and Output Model

### 6.1 Trust Model
- AI agents are the primary analysis layer
- deterministic parsing and typed evidence ground the agents
- high-severity findings must be backed by explicit evidence
- future report output should capture both extracted evidence and agent traceability
- unsupported artifacts should not be sent blindly to a generic agent without explicit user intent

### 6.2 Canonical Report Contract
The canonical machine-readable artifact remains `report.json`.

Why it still matters in a multi-agent product:
- it is the durable output of the agent team
- it supports cataloging, sharing, later Q&A, and future UI or service layers
- it gives downstream tooling one stable contract even if prompts or orchestration evolve

## 7. Privacy and Sharing
- `report.json` remains the canonical local analysis artifact
- external binary inputs such as `.jfr` recordings should still be referenced by path rather than embedded bytes
- shareable renderings should remain redacted by default
- agent-backed conclusions should be shareable without exposing unnecessary raw data

## 8. Build and Verification
- Java 25 is the supported build and test runtime
- Maven should fail fast on older runtimes
- verification should continue to use `bash scripts/verify-java25.sh`

## 9. Extension Strategy
When adding a new artifact family, the preferred sequence is:
1. detection and artifact-type registration
2. deterministic parser and evidence extraction
3. specialist agent definition
4. optional deterministic assessor or heuristics that help ground the agent
5. report-contract updates for new findings or traceability
6. comparison and correlation support where justified
7. samples, regression tests, and documentation

## 10. Immediate Design Priorities
1. Extend `AnalysisReport` to capture agent traceability without weakening the existing evidence contract.
2. Deepen supervisor behavior for compare, correlate, and saved-report follow-up.
3. Add golden incident bundles and stronger evaluation gates for agent quality, not just parser determinism.
