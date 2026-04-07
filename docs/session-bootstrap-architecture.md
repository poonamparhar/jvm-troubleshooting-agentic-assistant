# Session Bootstrap Architecture

This file is the fastest way to rehydrate context for a new session.

## Core Design Rules

- AI agents are the primary analysis layer. User-facing troubleshooting analysis is shown only when an AI agent-backed narrative passes the quality gate.
- Deterministic parsing, assessment, comparison, and correlation are supportive infrastructure. They ground and organize diagnostics for the agents, but they do not replace AI-authored troubleshooting guidance.
- Raw diagnostic artifacts remain the source of truth.
- Structured diagnostic context is the agent working context.
- Agents can expand context only through curated retrieval and computation tools, not arbitrary file reads.
- JFR is always parse-first from the `.jfr` file on disk. The model sees derived structures and retrieved derived slices, never raw binary bytes.
- `ask` works from the saved canonical `AnalysisReport`, not from raw artifacts.
- Saved AI defaults live in `./config.json` by default. The repo launcher pins that path to the project root, and direct `java -jar` usage falls back to the current working directory unless overridden.
- `mvn package` produces both the repo-local jar layout and a packaged `target/jtroubleshoot-<version>-dist.zip` distribution with `bin/jtroubleshoot`, `config.json`, `jtroubleshoot.env`, and runtime jars under `lib/jtroubleshoot/`.

## Current Package Ownership

```mermaid
flowchart TB
    subgraph Root["com.javaassistant root"]
        CLI[jtroubleshoot<br/>one-shot CLI + shell]
        RuntimeFactory[DiagnosticRuntimeFactory<br/>shared runtime wiring]
        RuntimeSupport[ApplicationRuntimeSupport<br/>RuntimeRoutingPolicy<br/>EnvConfig<br/>UserConfigStore]
    end

    subgraph Ingest["ingest"]
        Loader[ArtifactLoader]
        Classifier[ArtifactClassifier]
    end

    subgraph Parse["parse"]
        ParsingService[ArtifactParsingService]
        Parsers[Artifact parsers<br/>GC / JFR / Thread Dump / hs_err / NMT / Heap / pmap / Container / OOM]
    end

    subgraph Supportive["supportive deterministic services"]
        Assessment[assessment<br/>ArtifactAssessmentService<br/>artifact-specific assessors]
        Compare[compare<br/>ArtifactComparisonService<br/>artifact-specific comparators]
        Correlate[correlate<br/>MultiArtifactCorrelator]
    end

    subgraph AgentRuntime["agent runtime"]
        Agents[agents<br/>specialist agents + tool wrappers]
        Orchestration[orchestration<br/>DiagnosticAgentOrchestrator<br/>AgentDiagnosticContextBuilder<br/>AgentToolRuntime<br/>AgentQualityGateEvaluator]
        Context[context<br/>DiagnosticContextIndexer<br/>DiagnosticContextRetriever<br/>DiagnosticComputationService]
    end

    subgraph AI["ai"]
        Providers[ConfiguredChatModel<br/>ChatModelProviderRegistry<br/>dedicated providers + OpenAI-compatible presets]
        QnA[StructuredReportQuestionAnswerer]
    end

    subgraph Reporting["diagnostics + report"]
        Domain[diagnostics<br/>InputArtifact / ParsedArtifact / AnalysisReport / Traceability]
        Reports[report<br/>AnalysisReportAssembler<br/>renderers<br/>ReportBundleService]
    end

    CLI --> RuntimeFactory
    CLI --> RuntimeSupport
    CLI --> Loader
    CLI --> Reports
    CLI --> QnA
    CLI --> Providers

    Loader --> Classifier
    Loader --> Domain

    RuntimeFactory --> ParsingService
    RuntimeFactory --> Assessment
    RuntimeFactory --> Compare
    RuntimeFactory --> Correlate
    RuntimeFactory --> Orchestration

    ParsingService --> Parsers
    Parsers --> Domain
    Assessment --> Domain
    Compare --> Domain
    Correlate --> Domain

    Orchestration --> Agents
    Orchestration --> Context
    Orchestration --> Reports
    Providers --> Orchestration
    Agents --> Context
    Reports --> Domain
```

## Command Routing

```mermaid
flowchart LR
    User[User] --> Launcher[bin/jtroubleshoot<br/>or java -jar]
    Launcher --> CLI[jtroubleshoot]

    CLI --> Config[global option parsing<br/>--provider / --model<br/>+ config.json defaults]
    Config --> OneShot[one-shot commands]
    Config --> Shell[shell]
    Config --> ConfigCmd[config show / set / clear]

    OneShot --> AnalyzeCmd[analyze]
    OneShot --> CompareCmd[compare]
    OneShot --> CorrelateCmd[correlate]
    OneShot --> AskCmd[ask --analysis-id]
    OneShot --> ReportCmd[reports show / reports list]

    Shell --> OpenCmd[open / open-report]
    Shell --> ShellAsk[ask / show / context / clear]
    Shell --> ShellProvider[provider use]
    ConfigCmd --> RuntimeSupport[ApplicationRuntimeSupport + UserConfigStore]

    OpenCmd --> Loader[ArtifactLoader]
    OpenCmd --> BundleLoad[ReportBundleService.load]

    AnalyzeCmd --> Loader
    AnalyzeCmd --> AnalyzeOrch[DiagnosticAgentOrchestrator.analyze]

    CompareCmd --> Loader
    CompareCmd --> CompareOrch[DiagnosticAgentOrchestrator.compare]

    CorrelateCmd --> Loader
    CorrelateCmd --> CorrelateOrch[DiagnosticAgentOrchestrator.correlate]

    AskCmd --> ReportGate{AI agent-backed report available?}
    ReportGate -->|yes| QnA[StructuredReportQuestionAnswerer]
    ReportGate -->|no| AskBlocked[No answer shown]

    ReportCmd --> BundleRead[ReportBundleService.read/list]
    ShellProvider --> Providers[registry-backed AI providers]
```

- The CLI now resolves saved `config.json` defaults plus any `--provider` and `--model` overrides before command dispatch, and it initializes the selected AI model lazily only when an AI-backed command actually needs it.
- The provider layer is registry-driven. Dedicated factories handle Ollama, OpenAI, Anthropic, Google Gemini, Mistral, Azure OpenAI, and OCI, while the OpenAI-compatible provider implementation also backs preset hosted providers such as xAI, Groq, OpenRouter, Together AI, and Fireworks AI.

## Single-Artifact Analyze Flow

```mermaid
sequenceDiagram
    actor User
    participant CLI as JVMTroubleshooter
    participant Loader as ArtifactLoader
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

    User->>CLI: analyze <artifact>
    CLI->>Loader: load(path)
    Loader-->>CLI: InputArtifact
    CLI->>Orch: analyze(inputArtifact)

    Orch->>Parse: parse(full artifact)
    Parse-->>Orch: ParsedArtifact
    Orch->>Assess: evaluate(parsedArtifact)
    Assess-->>Orch: AssessmentResult
    Orch->>Index: index(inputArtifact, parsedArtifact)
    Index-->>Orch: IndexedArtifactDiagnosticContext
    Orch->>Builder: buildSingleArtifactContext(...)
    Builder-->>Orch: bounded diagnostic context text
    Orch->>Report: assemble base report
    Report-->>Orch: AnalysisReport

    Orch->>Agent: analyze(diagnostic context)
    opt Agent needs more detail
        Agent->>Tools: retrieve(...) / compute(...)
        Tools-->>Agent: DiagnosticToolResult
    end

    Agent-->>Orch: candidate narrative
    Orch->>Gate: evaluate quality + coverage
    Gate-->>Orch: accepted / rejected

    alt accepted
        Orch->>Report: attach user narrative + traceability
        Orch-->>CLI: final report
        CLI->>Save: save bundle
        CLI->>Render: render user output
        Render-->>User: troubleshooting analysis
    else rejected or unavailable
        Orch-->>CLI: report without user narrative
        CLI-->>User: no troubleshooting analysis shown
    end
```

## Compare And Correlate Design

```mermaid
flowchart TB
    subgraph Compare["compare"]
        B1[baseline artifact] --> BG[ground baseline]
        C1[current artifact] --> CG[ground current]
        BG --> CMP[ArtifactComparisonService]
        CG --> CMP
        BG --> BC[baseline indexed context]
        CG --> CC[current indexed context]
        BC --> CB[comparison context builder]
        CC --> CB
        CMP --> BaseReport1[comparison base report]
        CB --> SameTypeAgent[same-artifact specialist agent]
        SameTypeAgent --> ToolSession1[tool session]
        ToolSession1 --> Gate1[quality gate]
        Gate1 --> FinalCompare[final comparison report]
    end

    subgraph Correlate["correlate"]
        ASet[multiple supported artifacts] --> GroundAll[ground each artifact]
        GroundAll --> CorrDet[MultiArtifactCorrelator]
        GroundAll --> PerArtifactIndex[index each artifact]
        PerArtifactIndex --> SpecAgents[artifact-specific specialist agents]
        SpecAgents --> SpecObs[specialist observations]
        CorrDet --> CorrBase[multi-artifact base report]
        PerArtifactIndex --> CorrBuilder[correlation context builder]
        SpecObs --> CorrBuilder
        CorrBuilder --> SynthAgent[CorrelationAgent]
        SynthAgent --> ToolSession2[correlation tool session]
        ToolSession2 --> Gate2[quality gate]
        Gate2 --> FinalCorr[final correlation report]
    end
```

## Context-Building And Tooling Lifecycle

```mermaid
flowchart TB
    Raw[InputArtifact<br/>raw text or JFR path placeholder] --> Parse[ParsedArtifact<br/>extractedData + evidence + warnings]
    Raw --> Indexer[DiagnosticContextIndexer]
    Parse --> Indexer

    Indexer --> FullIndex[IndexedArtifactDiagnosticContext<br/>full structured blocks<br/>raw lines / sections<br/>all slices]
    Indexer --> StartCtx[ArtifactDiagnosticContext<br/>structured facts<br/>highlights<br/>bounded structured slices<br/>bounded representative slices<br/>coverage]

    StartCtx --> Builder[AgentDiagnosticContextBuilder]
    Builder --> Prompt[bounded diagnostic context text]

    FullIndex --> Session[AgentToolRuntime.Session]
    Prompt --> Agent[Specialist or synthesis agent]
    Agent -->|retrieve / compute| Session
    Session --> Retriever[DiagnosticContextRetriever]
    Session --> Compute[DiagnosticComputationService]
    Retriever --> ToolResult[DiagnosticToolResult<br/>content + traceability + truncated + moreAvailable]
    Compute --> ToolResult
    ToolResult --> Agent

    Coverage[ContextCoverage<br/>omitted blocks<br/>omitted slices<br/>parse gaps<br/>truncation markers] --> Agent
    Coverage --> Gate[coverage-aware quality gate]
```

## Saved Report And Ask Flow

```mermaid
flowchart LR
    Base[AnalysisReportAssembler] --> Report[AnalysisReport]
    AgentNarrative[accepted AI narrative] --> Report
    Traceability[agent traceability + supervisor trace] --> Report

    Report --> Save[ReportBundleService]
    Save --> Txt[report.txt]
    Save --> Json[report.json]
    Save --> Md[report.md]
    Save --> Html[report.html]

    Json --> Reload[open-report / reports show / reports list]
    Reload --> AskGate{hasAiAgentBackedUserNarrative?}
    AskGate -->|yes| Ask[StructuredReportQuestionAnswerer]
    AskGate -->|no| Blocked[Ask blocked]
    Ask --> LLM[active chat model]
    LLM --> UserAnswer[user answer based only on report]
```

## Supported Artifact Scope

```mermaid
flowchart LR
    Analyze[analyze] --> A1[GC_LOG]
    Analyze --> A2[JFR]
    Analyze --> A3[THREAD_DUMP]
    Analyze --> A4[HS_ERR_LOG]
    Analyze --> A5[NMT]
    Analyze --> A6[HEAP_HISTOGRAM]
    Analyze --> A7[PMAP]
    Analyze --> A8[CONTAINER_MEMORY]
    Analyze --> A9[OOM_SIGNAL]

    Compare[compare] --> C1[JFR]
    Compare --> C2[THREAD_DUMP]
    Compare --> C3[HEAP_HISTOGRAM]
    Compare --> C4[NMT]
    Compare --> C5[PMAP]

    Correlate[correlate] --> R1[any supported analyze artifact type]
```

## Fast Re-entry File Map

- CLI and command routing: `src/main/java/com/javaassistant/JVMTroubleshooter.java`
- Shared runtime wiring: `src/main/java/com/javaassistant/DiagnosticRuntimeFactory.java`
- Runtime policy and app metadata: `src/main/java/com/javaassistant/ApplicationRuntimeSupport.java`, `src/main/java/com/javaassistant/RuntimeRoutingPolicy.java`
- Artifact ingest: `src/main/java/com/javaassistant/ingest/`
- Parsing: `src/main/java/com/javaassistant/parse/`
- Supportive deterministic analysis: `src/main/java/com/javaassistant/assessment/`, `src/main/java/com/javaassistant/compare/`, `src/main/java/com/javaassistant/correlate/`
- Agent orchestration: `src/main/java/com/javaassistant/orchestration/`
- Context indexing and tooling: `src/main/java/com/javaassistant/context/`
- Agent prompts and tools: `src/main/java/com/javaassistant/agents/`
- Canonical report contract: `src/main/java/com/javaassistant/diagnostics/`
- Report persistence and rendering: `src/main/java/com/javaassistant/report/`
- Existing architecture doc: `docs/architecture-and-control-flow.md`
