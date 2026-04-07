# Architecture And Control Flow

This document reflects the current consolidated package layout and the active AI-first runtime flow.

For the fastest new-session re-entry, read `docs/session-bootstrap-architecture.md` first.

## Consolidated Architecture

```mermaid
flowchart LR
    User[User] --> Launcher[bin/jtroubleshoot<br/>repo launcher or packaged dist<br/>or java -jar]
    Launcher --> CLI[jtroubleshoot CLI<br/>one-shot router + shell]

    subgraph Root["com.javaassistant root"]
        RuntimeFactory[DiagnosticRuntimeFactory]
        RuntimeSupport[ApplicationRuntimeSupport<br/>RuntimeRoutingPolicy<br/>EnvConfig<br/>UserConfigStore]
    end

    subgraph AI["ai"]
        Providers[ConfiguredChatModel<br/>ChatModelProviderRegistry<br/>dedicated providers + OpenAI-compatible presets]
        QnA[StructuredReportQuestionAnswerer]
    end

    subgraph Ingest["ingest"]
        Loader[ArtifactLoader]
        Classifier[ArtifactClassifier]
    end

    subgraph Parse["parse"]
        ParsingService[ArtifactParsingService]
        Parsers[Artifact-specific parsers<br/>GC / JFR / thread dump / hs_err / NMT / heap / pmap / container / OOM]
    end

    subgraph Supportive["supportive deterministic services"]
        Assessment[assessment<br/>ArtifactAssessmentService + assessors]
        Comparison[compare<br/>ArtifactComparisonService + comparators]
        Correlation[correlate<br/>MultiArtifactCorrelator]
    end

    subgraph AgentRuntime["agent analysis runtime"]
        Context[context<br/>DiagnosticContextIndexer<br/>DiagnosticContextRetriever<br/>DiagnosticComputationService]
        Orchestration[orchestration<br/>DiagnosticAgentOrchestrator<br/>AgentDiagnosticContextBuilder<br/>AgentToolRuntime<br/>AgentQualityGateEvaluator]
        Agents[agents<br/>specialist agents + tool wrappers]
    end

    subgraph Reporting["diagnostics + report"]
        Domain[diagnostics<br/>InputArtifact / ParsedArtifact / AnalysisReport / Finding / Action / Traceability]
        Reports[report<br/>AnalysisReportAssembler<br/>renderers<br/>ReportBundleService]
    end

    CLI --> Providers
    CLI --> Loader
    CLI --> Reports
    CLI --> QnA
    CLI --> RuntimeFactory
    RuntimeSupport --> CLI

    Loader --> Classifier
    Loader --> Domain

    RuntimeFactory --> ParsingService
    RuntimeFactory --> Assessment
    RuntimeFactory --> Comparison
    RuntimeFactory --> Correlation
    RuntimeFactory --> Orchestration

    ParsingService --> Parsers
    Parsers --> Domain
    Assessment --> Domain
    Comparison --> Domain
    Correlation --> Domain

    Orchestration --> Context
    Orchestration --> Agents
    Orchestration --> Reports
    Providers --> Orchestration
    Agents --> Context
    Reports --> Domain
```

## CLI Invocation Flow

```mermaid
flowchart LR
    User[User] --> Launcher[bin/jtroubleshoot<br/>repo launcher or packaged dist<br/>or java -jar]
    Launcher --> Router[JVMTroubleshooter]

    Router --> Config[global option parsing<br/>--provider / --model<br/>+ saved config.json defaults]
    Config --> OneShot[One-shot commands<br/>analyze / compare / correlate / ask / reports / version / status]
    Config --> Shell[shell]
    Config --> ConfigCmd[config show / set / clear]

    OneShot --> Orch[DiagnosticAgentOrchestrator]
    OneShot --> Bundles[ReportBundleService]
    ConfigCmd --> RuntimeSupport

    Shell --> ContextOps[open / open-report / show / ask / context / clear]
    ContextOps --> Orch
    ContextOps --> Bundles
```

## Analyze Control Flow

```mermaid
sequenceDiagram
    actor User
    participant CLI as JVMTroubleshooter
    participant Loader as ArtifactLoader
    participant Factory as DiagnosticRuntimeFactory
    participant Providers as Provider selection + lazy model init
    participant Orch as DiagnosticAgentOrchestrator
    participant Parse as ArtifactParsingService
    participant Assess as ArtifactAssessmentService
    participant Index as DiagnosticContextIndexer
    participant Builder as AgentDiagnosticContextBuilder
    participant Agent as Specialist AI Agent
    participant Tools as AgentToolRuntime
    participant Gate as AgentQualityGateEvaluator
    participant Report as AnalysisReportAssembler
    participant Save as ReportBundleService
    participant Render as UserConsoleReportRenderer

    User->>CLI: jtroubleshoot analyze <diagnostic-file-or-dir>
    CLI->>RuntimeSupport: resolve config.json path
    CLI->>RuntimeSupport: load saved defaults if the command needs them
    CLI->>Providers: apply saved defaults + --provider / --model overrides
    CLI->>Loader: load / discover artifact
    Loader-->>CLI: InputArtifact or InputArtifact set
    CLI->>CLI: select analyze route (single / compare / sequence / correlate)
    CLI->>Providers: initialize selected provider only when analyze needs AI
    CLI->>Factory: diagnosticAgentOrchestrator(configured model)
    Factory-->>CLI: Orchestrator
    Note over CLI,Orch: Same-type compare or sequence routes use auto-ordering before baseline/current or progression analysis.
    CLI->>Orch: analyze / compareAutoOrdered / sequenceAutoOrdered / correlate

    Orch->>Parse: parse(full artifact content)
    Parse-->>Orch: ParsedArtifact
    Orch->>Assess: evaluate(parsed artifact)
    Assess-->>Orch: supportive assessment
    Orch->>Index: index(inputArtifact, parsedArtifact)
    Index-->>Orch: IndexedArtifactDiagnosticContext
    Orch->>Report: assemble base report
    Report-->>Orch: AnalysisReport
    Orch->>Builder: build starting diagnostic context
    Builder-->>Orch: bounded diagnostic context
    Orch->>Agent: analyze(diagnostic context)

    opt Additional artifact detail is needed
        Agent->>Tools: retrieval / computation request
        Tools-->>Agent: curated slices or derived metrics
    end

    Agent-->>Orch: AI troubleshooting narrative
    Orch->>Gate: validate narrative and coverage
    Gate-->>Orch: accepted / rejected

    alt AI narrative accepted
        Orch->>Report: attach user narrative + traceability
        Report-->>Orch: final AnalysisReport
        Orch-->>CLI: final report
        CLI->>Save: save report bundle
        CLI->>Render: render human-friendly output
        Render-->>User: troubleshooting analysis
    else AI narrative rejected or unavailable
        Orch-->>CLI: report without user narrative
        CLI->>Save: save report bundle
        CLI-->>User: no troubleshooting analysis shown
    end
```

## Notes

- The primary UX is now one-shot CLI commands. The interactive shell is explicit via `jtroubleshoot shell`.
- The packaged bundle now ships ready-to-edit `config.json` and `jtroubleshoot.env` files next to the launcher instead of relying on first-run setup commands.
- Global `--provider` and `--model` flags are resolved before command dispatch, and the actual chat model is created lazily only when an AI-backed command needs it.
- Persistent AI defaults live in `./config.json` by default. The repo launcher pins that to the project root via `-Djtroubleshoot.configFile`, while direct `java -jar` usage falls back to the current working directory unless a config-path override is supplied. Selection precedence is: CLI flags, then shell-session changes, then saved config defaults, then built-in defaults.
- Auto-routed same-type `analyze` comparisons and sequences keep the supplied order unless the runtime can safely infer a stronger older-to-newer order from parsed timestamps, filename hints, timestamped names, or filesystem modified times.
- `mvn package` now produces both the repo-local jar plus `target/jtroubleshoot-<version>-dist.zip`, where the packaged launcher lives under `bin/` and the runtime jars live under `lib/jtroubleshoot/`.
- The provider layer is now registry-driven. Dedicated provider factories cover Ollama, OpenAI, Anthropic, Google Gemini, Mistral, Azure OpenAI, OCI, and the more specialized hosted entries, while a reusable OpenAI-compatible provider implementation powers generic endpoints plus preset providers such as xAI, Groq, OpenRouter, Together AI, and Fireworks AI.
- `ingest` now owns both artifact loading and classification.
- `ai` now owns provider configuration and report-question answering.
- `report` now owns report assembly, rendering, and saved bundle persistence.
- `correlate` remains a separate package intentionally because multi-artifact synthesis is a distinct runtime concern from typed pairwise comparison.
- Raw diagnostics remain the source of truth; structured extraction and indexing exist to make that information more usable for the agents.
- Deterministic assessment remains supportive only and does not replace AI-authored troubleshooting guidance.
- JFR recordings are always parsed first; models see only derived structures and retrieved slices, never raw binary bytes.
