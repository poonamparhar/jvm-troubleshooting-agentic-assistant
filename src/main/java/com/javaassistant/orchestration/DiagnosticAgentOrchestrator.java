package com.javaassistant.orchestration;

import com.javaassistant.agents.ContainerMemoryAgent;
import com.javaassistant.agents.CorrelationAgent;
import com.javaassistant.agents.GCLogAgent;
import com.javaassistant.agents.HSErrLogAgent;
import com.javaassistant.agents.HeapHistogramAgent;
import com.javaassistant.agents.JfrAgent;
import com.javaassistant.agents.NMTAgent;
import com.javaassistant.agents.OomSignalAgent;
import com.javaassistant.agents.PmapAgent;
import com.javaassistant.agents.ThreadDumpAgent;
import com.javaassistant.assessment.ArtifactAssessmentService;
import com.javaassistant.assessment.AssessmentResult;
import com.javaassistant.compare.ArtifactComparisonService;
import com.javaassistant.context.ContextCoverage;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.context.IndexedArtifactDiagnosticContext;
import com.javaassistant.correlate.MultiArtifactCorrelator;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentToolInvocation;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.ConfidenceLevel;
import com.javaassistant.diagnostics.CorrelationResult;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.Finding;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ModelExecutionTraceability;
import com.javaassistant.diagnostics.OrchestrationWorkflowType;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.diagnostics.SeverityLevel;
import com.javaassistant.diagnostics.SupervisorTrace;
import com.javaassistant.diagnostics.SupervisorTraceStep;
import com.javaassistant.diagnostics.SupervisorTraceStepType;
import com.javaassistant.modelproviders.ConfiguredChatModel;
import com.javaassistant.orchestration.AgentDiagnosticContextBuilder.ArtifactGrounding;
import com.javaassistant.orchestration.AgentDiagnosticContextBuilder.SpecialistObservation;
import com.javaassistant.parse.ArtifactParsingService;
import com.javaassistant.report.AnalysisReportAssembler;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates deterministic evidence with specialist-agent execution so agents become the primary runtime path.
 */
public class DiagnosticAgentOrchestrator {

    private static final String AGENT_TEMPLATE_VERSION = "v1";

    private final ArtifactParsingService parsingService;
    private final ArtifactAssessmentService assessmentService;
    private final ArtifactComparisonService comparisonService;
    private final MultiArtifactCorrelator correlator;
    private final AnalysisReportAssembler reportAssembler;
    private final ConfiguredChatModel configuredChatModel;
    private final ChatModel chatModel;
    private final DiagnosticContextIndexer contextIndexer;
    private final AgentDiagnosticContextBuilder contextBuilder;
    private final AgentQualityGateEvaluator qualityGateEvaluator;

    public DiagnosticAgentOrchestrator(
        ArtifactParsingService parsingService,
        ArtifactAssessmentService assessmentService,
        ArtifactComparisonService comparisonService,
        MultiArtifactCorrelator correlator,
        AnalysisReportAssembler reportAssembler,
        ChatModel chatModel
    ) {
        this(
            parsingService,
            assessmentService,
            comparisonService,
            correlator,
            reportAssembler,
            chatModel != null ? ConfiguredChatModel.synthetic(chatModel) : null
        );
    }

    public DiagnosticAgentOrchestrator(
        ArtifactParsingService parsingService,
        ArtifactAssessmentService assessmentService,
        ArtifactComparisonService comparisonService,
        MultiArtifactCorrelator correlator,
        AnalysisReportAssembler reportAssembler,
        ConfiguredChatModel configuredChatModel
    ) {
        this.parsingService = parsingService;
        this.assessmentService = assessmentService;
        this.comparisonService = comparisonService;
        this.correlator = correlator;
        this.reportAssembler = reportAssembler;
        this.configuredChatModel = configuredChatModel;
        this.chatModel = configuredChatModel != null ? configuredChatModel.chatModel() : null;
        this.contextIndexer = new DiagnosticContextIndexer(
            DiagnosticContextIndexer.StartingContextBudget.forApproximateContextWindowTokens(
                configuredChatModel != null ? configuredChatModel.approximateContextWindowTokens() : null
            )
        );
        this.contextBuilder = new AgentDiagnosticContextBuilder(contextIndexer);
        this.qualityGateEvaluator = new AgentQualityGateEvaluator();
    }

    public AnalysisReport analyze(InputArtifact artifact) {
        ArtifactGrounding grounding = ground(artifact);
        IndexedArtifactDiagnosticContext indexedContext = indexContext(grounding);
        AnalysisReport baseReport = reportAssembler.assemble(
            grounding.inputArtifact(),
            grounding.parsedArtifact(),
            grounding.assessmentResult()
        );
        NarrativeSelection selection = buildSingleArtifactNarrative(grounding, indexedContext);
        return applyNarrative(baseReport, selection, singleArtifactTrace(grounding, selection));
    }

    public AnalysisReport compare(InputArtifact baseline, InputArtifact current) {
        ArtifactGrounding baselineGrounding = ground(baseline);
        ArtifactGrounding currentGrounding = ground(current);
        IndexedArtifactDiagnosticContext baselineContext = indexContext(baselineGrounding);
        IndexedArtifactDiagnosticContext currentContext = indexContext(currentGrounding);
        AssessmentResult comparisonEvaluation = comparisonService.compare(
            baselineGrounding.inputArtifact(),
            baselineGrounding.parsedArtifact(),
            currentGrounding.inputArtifact(),
            currentGrounding.parsedArtifact()
        );
        AnalysisReport baseReport = reportAssembler.assembleComparison(
            List.of(baselineGrounding.inputArtifact(), currentGrounding.inputArtifact()),
            List.of(baselineGrounding.parsedArtifact(), currentGrounding.parsedArtifact()),
            comparisonEvaluation
        );
        NarrativeSelection selection = buildComparisonNarrative(
            baselineGrounding,
            baselineContext,
            currentGrounding,
            currentContext,
            comparisonEvaluation
        );
        return applyNarrative(
            baseReport,
            selection,
            comparisonTrace(baselineGrounding, currentGrounding, comparisonEvaluation, selection)
        );
    }

    public AnalysisReport correlate(List<InputArtifact> artifacts) {
        List<ArtifactGrounding> groundings = artifacts.stream().map(this::ground).toList();
        List<IndexedArtifactDiagnosticContext> indexedContexts = groundings.stream().map(this::indexContext).toList();
        List<ParsedArtifact> parsedArtifacts = groundings.stream().map(ArtifactGrounding::parsedArtifact).toList();
        List<AssessmentResult> evaluations = groundings.stream().map(ArtifactGrounding::assessmentResult).toList();
        CorrelationResult correlationResult = correlator.correlate(parsedArtifacts, evaluations);
        AnalysisReport baseReport = reportAssembler.assemble(artifacts, parsedArtifacts, evaluations, correlationResult);

        List<AgentTraceability> traceability = new ArrayList<>();
        List<SpecialistObservation> observations = new ArrayList<>();
        List<NarrativeSelection> observationSelections = new ArrayList<>();
        for (int index = 0; index < groundings.size(); index++) {
            ArtifactGrounding grounding = groundings.get(index);
            IndexedArtifactDiagnosticContext indexedContext = indexedContexts.get(index);
            NarrativeSelection observationSelection = buildCorrelationObservation(grounding, indexedContext);
            observationSelections.add(observationSelection);
            traceability.addAll(observationSelection.traceability());
            if (hasText(observationSelection.narrative())) {
                NarrativeCandidate selectedCandidate = observationSelection.selectedCandidate();
                observations.add(new SpecialistObservation(
                    grounding.inputArtifact().type(),
                    selectedCandidate != null ? selectedCandidate.agentName() : agentName(grounding.inputArtifact().type()),
                    sourcePath(grounding.inputArtifact()),
                    observationSelection.narrative()
                ));
            }
        }

        String startingContext = contextBuilder.buildCorrelationContext(groundings, indexedContexts, observations);
        AgentToolRuntime.Session correlationToolSession = AgentToolRuntime.createSession(
            "correlation",
            AgentToolRuntime.ToolBudget.correlate(),
            correlationContexts(indexedContexts)
        );
        NarrativeSelection synthesisSelection = selectNarrative(
            "correlation-synthesis",
            null,
            artifactPaths(artifacts),
            evidenceIds(baseReport.evidence(), baseReport.findings()),
            signalPhrases(baseReport.findings(), baseReport.evidence()),
            baseReport.missingData(),
            indexedContexts.stream().map(indexedContext -> indexedContext.diagnosticContext().coverage()).toList(),
            true,
            primaryCandidate(
                "CorrelationAgent",
                AgentNarrativeSource.SYNTHESIS_AGENT,
                correlationToolSession,
                () -> AgentToolRuntime.withSession(
                    correlationToolSession,
                    () -> buildAgent(CorrelationAgent.class).analyze(startingContext)
                )
            ),
            List.of()
        );

        traceability.addAll(synthesisSelection.traceability());
        return applyNarrative(
            baseReport,
            new NarrativeSelection(
                synthesisSelection.narrative(),
                traceability,
                synthesisSelection.selectedCandidate(),
                synthesisSelection.selectedForUserNarrative()
            ),
            correlationTrace(groundings, correlationResult, baseReport, observationSelections, synthesisSelection)
        );
    }

    private ArtifactGrounding ground(InputArtifact artifact) {
        ParsedArtifact parsedArtifact = parsingService.parse(artifact);
        AssessmentResult assessmentResult = assessmentService.evaluate(parsedArtifact);
        return new ArtifactGrounding(artifact, parsedArtifact, assessmentResult);
    }

    private IndexedArtifactDiagnosticContext indexContext(ArtifactGrounding grounding) {
        return contextIndexer.index(grounding.inputArtifact(), grounding.parsedArtifact());
    }

    private AnalysisReport applyNarrative(AnalysisReport baseReport, NarrativeSelection selection, SupervisorTrace supervisorTrace) {
        List<AgentTraceability> traceability = selection != null ? selection.traceability() : List.of();
        if (selection == null || !hasText(selection.narrative())) {
            AnalysisReport report = traceability.isEmpty() ? baseReport : baseReport.withAgentTraceability(traceability);
            return supervisorTrace == null ? report : report.withSupervisorTrace(supervisorTrace);
        }
        return baseReport.withUserNarrativeAndTraceability(selection.narrative().strip(), traceability)
            .withSupervisorTrace(supervisorTrace);
    }

    private NarrativeSelection buildSingleArtifactNarrative(
        ArtifactGrounding grounding,
        IndexedArtifactDiagnosticContext indexedContext
    ) {
        ArtifactType artifactType = grounding.inputArtifact().type();
        String startingContext = contextBuilder.buildSingleArtifactContext(grounding, indexedContext);
        return selectNarrative(
            "single-artifact-specialist-analysis",
            artifactType,
            artifactPaths(List.of(grounding.inputArtifact())),
            evidenceIds(grounding.parsedArtifact().evidence(), grounding.assessmentResult().findings()),
            signalPhrases(grounding.assessmentResult().findings(), grounding.parsedArtifact().evidence()),
            grounding.assessmentResult().missingData(),
            List.of(indexedContext.diagnosticContext().coverage()),
            true,
            primarySpecialistCandidate(
                artifactType,
                startingContext,
                singleArtifactToolSession(indexedContext, AgentToolRuntime.ToolBudget.analyze())
            ),
            List.of()
        );
    }

    private NarrativeSelection buildComparisonNarrative(
        ArtifactGrounding baselineGrounding,
        IndexedArtifactDiagnosticContext baselineContext,
        ArtifactGrounding currentGrounding,
        IndexedArtifactDiagnosticContext currentContext,
        AssessmentResult comparisonEvaluation
    ) {
        ArtifactType artifactType = currentGrounding.inputArtifact().type();
        String startingContext = contextBuilder.buildComparisonContext(
            baselineGrounding,
            baselineContext,
            currentGrounding,
            currentContext,
            comparisonEvaluation
        );
        List<Evidence> evidence = new ArrayList<>(baselineGrounding.parsedArtifact().evidence());
        evidence.addAll(currentGrounding.parsedArtifact().evidence());
        return selectNarrative(
            "comparison-specialist-analysis",
            artifactType,
            artifactPaths(List.of(baselineGrounding.inputArtifact(), currentGrounding.inputArtifact())),
            evidenceIds(evidence, comparisonEvaluation.findings()),
            signalPhrases(comparisonEvaluation.findings(), evidence),
            comparisonEvaluation.missingData(),
            List.of(baselineContext.diagnosticContext().coverage(), currentContext.diagnosticContext().coverage()),
            true,
            primarySpecialistCandidate(
                artifactType,
                startingContext,
                comparisonToolSession(baselineContext, currentContext)
            ),
            List.of()
        );
    }

    private NarrativeSelection buildCorrelationObservation(
        ArtifactGrounding grounding,
        IndexedArtifactDiagnosticContext indexedContext
    ) {
        ArtifactType artifactType = grounding.inputArtifact().type();
        String startingContext = contextBuilder.buildSingleArtifactContext(grounding, indexedContext);
        return selectNarrative(
            "correlation-specialist-observation",
            artifactType,
            artifactPaths(List.of(grounding.inputArtifact())),
            evidenceIds(grounding.parsedArtifact().evidence(), grounding.assessmentResult().findings()),
            signalPhrases(grounding.assessmentResult().findings(), grounding.parsedArtifact().evidence()),
            grounding.assessmentResult().missingData(),
            List.of(indexedContext.diagnosticContext().coverage()),
            false,
            primarySpecialistCandidate(
                artifactType,
                startingContext,
                singleArtifactToolSession(indexedContext, AgentToolRuntime.ToolBudget.analyze())
            ),
            List.of()
        );
    }

    private NarrativeSelection selectNarrative(
        String stageId,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        List<String> signalPhrases,
        List<String> missingData,
        List<ContextCoverage> coverageMetadata,
        boolean selectedForUserNarrative,
        NarrativeCandidate primaryCandidate,
        List<NarrativeCandidate> fallbackCandidates
    ) {
        List<AgentTraceability> traceability = new ArrayList<>();

        if (primaryCandidate != null) {
            List<AgentQualityGateResult> qualityGates = qualityGateEvaluator.evaluate(
                primaryCandidate.narrative(),
                signalPhrases,
                evidenceIds,
                missingData,
                coverageMetadata,
                primaryCandidate.toolInvocations(),
                primaryCandidate.modelExecutionTraceability()
            );
            boolean accepted = hasText(primaryCandidate.narrative()) && qualityGateEvaluator.passesBlockingGates(qualityGates);
            traceability.add(traceability(
                stageId,
                primaryCandidate.agentName(),
                primaryCandidate.narrativeSource(),
                artifactType,
                artifactPaths,
                evidenceIds,
                selectedForUserNarrative && accepted,
                qualityGates,
                primaryCandidate.toolInvocations(),
                primaryCandidate.modelExecutionTraceability()
            ));
            if (accepted) {
                return new NarrativeSelection(
                    primaryCandidate.narrative().strip(),
                    traceability,
                    primaryCandidate,
                    selectedForUserNarrative
                );
            }
        }

        for (NarrativeCandidate fallbackCandidate : fallbackCandidates) {
            if (!hasText(fallbackCandidate.narrative())) {
                continue;
            }
            List<AgentQualityGateResult> qualityGates = qualityGateEvaluator.evaluate(
                fallbackCandidate.narrative(),
                signalPhrases,
                evidenceIds,
                missingData,
                coverageMetadata,
                fallbackCandidate.toolInvocations(),
                fallbackCandidate.modelExecutionTraceability()
            );
            boolean accepted = qualityGateEvaluator.passesBlockingGates(qualityGates);
            traceability.add(traceability(
                stageId,
                fallbackCandidate.agentName(),
                fallbackCandidate.narrativeSource(),
                artifactType,
                artifactPaths,
                evidenceIds,
                selectedForUserNarrative && accepted,
                qualityGates,
                fallbackCandidate.toolInvocations(),
                fallbackCandidate.modelExecutionTraceability()
            ));
            if (accepted) {
                return new NarrativeSelection(
                    fallbackCandidate.narrative().strip(),
                    traceability,
                    fallbackCandidate,
                    selectedForUserNarrative
                );
            }
        }

        return new NarrativeSelection(null, traceability, null, false);
    }

    private NarrativeCandidate primarySpecialistCandidate(
        ArtifactType artifactType,
        String startingContext,
        AgentToolRuntime.Session toolSession
    ) {
        if (artifactType == null || chatModel == null) {
            return null;
        }
        return new NarrativeCandidate(
            invokeSpecialistAgent(artifactType, startingContext, toolSession),
            agentName(artifactType),
            AgentNarrativeSource.SPECIALIST_AGENT,
            toolSession != null ? toolSession.toolInvocations() : List.of(),
            modelExecutionTraceability(agentName(artifactType), AgentNarrativeSource.SPECIALIST_AGENT)
        );
    }

    private NarrativeCandidate primaryCandidate(
        String agentName,
        AgentNarrativeSource narrativeSource,
        AgentToolRuntime.Session toolSession,
        NarrativeInvocation invocation
    ) {
        if (chatModel == null) {
            return null;
        }
        return new NarrativeCandidate(
            invokeAgent(invocation),
            agentName,
            narrativeSource,
            toolSession != null ? toolSession.toolInvocations() : List.of(),
            modelExecutionTraceability(agentName, narrativeSource)
        );
    }

    private String invokeSpecialistAgent(
        ArtifactType artifactType,
        String startingContext,
        AgentToolRuntime.Session toolSession
    ) {
        if (artifactType == null) {
            return null;
        }
        return switch (artifactType) {
            case GC_LOG -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(GCLogAgent.class).analyze(startingContext)));
            case JFR -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(JfrAgent.class).analyze(startingContext)));
            case THREAD_DUMP -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(ThreadDumpAgent.class).analyze(startingContext)));
            case HS_ERR_LOG -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(HSErrLogAgent.class).analyze(startingContext)));
            case NMT -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(NMTAgent.class).analyze(startingContext)));
            case HEAP_HISTOGRAM -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(HeapHistogramAgent.class).analyze(startingContext)));
            case PMAP -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(PmapAgent.class).analyze(startingContext)));
            case CONTAINER_MEMORY -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(ContainerMemoryAgent.class).analyze(startingContext)));
            case OOM_SIGNAL -> invokeAgent(() -> AgentToolRuntime.withSession(toolSession, () -> buildAgent(OomSignalAgent.class).analyze(startingContext)));
            default -> null;
        };
    }

    private String invokeAgent(NarrativeInvocation invocation) {
        if (chatModel == null) {
            return null;
        }
        try {
            String response = invocation.get();
            return hasText(response) ? response.strip() : null;
        } catch (RuntimeException ignored) {
            // Leave the report without a user narrative if the agent path is unavailable.
            return null;
        }
    }

    private AgentToolRuntime.Session singleArtifactToolSession(
        IndexedArtifactDiagnosticContext indexedContext,
        AgentToolRuntime.ToolBudget toolBudget
    ) {
        return AgentToolRuntime.createSession(
            "single-artifact",
            toolBudget,
            sessionContexts(indexedContext)
        );
    }

    private AgentToolRuntime.Session comparisonToolSession(
        IndexedArtifactDiagnosticContext baselineContext,
        IndexedArtifactDiagnosticContext currentContext
    ) {
        LinkedHashMap<String, IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        putContextAliases(contexts, "baseline", baselineContext);
        putContextAliases(contexts, "current", currentContext);
        contexts.putIfAbsent("primary", currentContext);
        return AgentToolRuntime.createSession("comparison", AgentToolRuntime.ToolBudget.compare(), contexts);
    }

    private Map<String, IndexedArtifactDiagnosticContext> sessionContexts(IndexedArtifactDiagnosticContext indexedContext) {
        LinkedHashMap<String, IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        putContextAliases(contexts, "current", indexedContext);
        contexts.putIfAbsent("primary", indexedContext);
        return contexts;
    }

    private Map<String, IndexedArtifactDiagnosticContext> correlationContexts(List<IndexedArtifactDiagnosticContext> indexedContexts) {
        LinkedHashMap<String, IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        for (int index = 0; index < indexedContexts.size(); index++) {
            putContextAliases(contexts, "artifact-" + (index + 1), indexedContexts.get(index));
        }
        if (!indexedContexts.isEmpty()) {
            contexts.putIfAbsent("primary", indexedContexts.getFirst());
        }
        return contexts;
    }

    private void putContextAliases(
        Map<String, IndexedArtifactDiagnosticContext> contexts,
        String alias,
        IndexedArtifactDiagnosticContext indexedContext
    ) {
        if (indexedContext == null) {
            return;
        }
        contexts.put(alias, indexedContext);
        String sourcePath = indexedContext.inputArtifact() != null && indexedContext.inputArtifact().metadata() != null
            ? indexedContext.inputArtifact().metadata().sourcePath()
            : null;
        if (hasText(sourcePath)) {
            contexts.putIfAbsent(sourcePath, indexedContext);
        }
        String displayName = indexedContext.inputArtifact() != null && indexedContext.inputArtifact().metadata() != null
            ? indexedContext.inputArtifact().metadata().displayName()
            : null;
        if (hasText(displayName)) {
            contexts.putIfAbsent(displayName, indexedContext);
        }
    }

    private SupervisorTrace singleArtifactTrace(ArtifactGrounding grounding, NarrativeSelection selection) {
        List<String> artifactPaths = artifactPaths(List.of(grounding.inputArtifact()));
        List<String> evidenceIds = evidenceIds(grounding.parsedArtifact().evidence(), grounding.assessmentResult().findings());
        List<String> findingIds = findingIds(grounding.assessmentResult().findings());
        return new SupervisorTrace(
            OrchestrationWorkflowType.SINGLE_ARTIFACT,
            artifactPaths,
            List.of(
                groundingStep("artifact-grounding", "single artifact", grounding),
                selectionStep(
                    "single-artifact-specialist-analysis",
                    SupervisorTraceStepType.SPECIALIST_SELECTION,
                    "single-artifact-specialist-analysis",
                    grounding.inputArtifact().type(),
                    artifactPaths,
                    evidenceIds,
                    findingIds,
                    selection,
                    "single-artifact analysis"
                )
            )
        );
    }

    private SupervisorTrace comparisonTrace(
        ArtifactGrounding baselineGrounding,
        ArtifactGrounding currentGrounding,
        AssessmentResult comparisonEvaluation,
        NarrativeSelection selection
    ) {
        List<InputArtifact> artifacts = List.of(baselineGrounding.inputArtifact(), currentGrounding.inputArtifact());
        List<Evidence> evidence = new ArrayList<>(baselineGrounding.parsedArtifact().evidence());
        evidence.addAll(currentGrounding.parsedArtifact().evidence());
        List<String> comparisonArtifactPaths = artifactPaths(artifacts);
        List<String> comparisonEvidenceIds = evidenceIds(evidence, comparisonEvaluation.findings());
        List<String> comparisonFindingIds = findingIds(comparisonEvaluation.findings());

        List<SupervisorTraceStep> steps = new ArrayList<>();
        steps.add(groundingStep("baseline-grounding", "baseline artifact", baselineGrounding));
        steps.add(groundingStep("current-grounding", "current artifact", currentGrounding));
        steps.add(new SupervisorTraceStep(
            "comparison-evaluation",
            SupervisorTraceStepType.COMPARISON_EVALUATION,
            null,
            "Deterministic comparison merged the baseline and current artifacts into %d finding(s)."
                .formatted(comparisonFindingIds.size()),
            currentGrounding.inputArtifact().type(),
            comparisonArtifactPaths,
            comparisonEvidenceIds,
            comparisonFindingIds,
            null,
            null,
            false
        ));
        steps.add(selectionStep(
            "comparison-specialist-analysis",
            SupervisorTraceStepType.SPECIALIST_SELECTION,
            "comparison-specialist-analysis",
            currentGrounding.inputArtifact().type(),
            comparisonArtifactPaths,
            comparisonEvidenceIds,
            comparisonFindingIds,
            selection,
            "artifact comparison"
        ));

        return new SupervisorTrace(OrchestrationWorkflowType.COMPARE, comparisonArtifactPaths, steps);
    }

    private SupervisorTrace correlationTrace(
        List<ArtifactGrounding> groundings,
        CorrelationResult correlationResult,
        AnalysisReport baseReport,
        List<NarrativeSelection> observationSelections,
        NarrativeSelection synthesisSelection
    ) {
        List<InputArtifact> artifacts = groundings.stream().map(ArtifactGrounding::inputArtifact).toList();
        List<String> allArtifactPaths = artifactPaths(artifacts);
        List<SupervisorTraceStep> steps = new ArrayList<>();

        for (int index = 0; index < groundings.size(); index++) {
            steps.add(groundingStep("grounding-" + (index + 1), "incident artifact", groundings.get(index)));
        }

        List<String> correlationFindingIds = correlationResult != null ? findingIds(correlationResult.findings()) : List.of();
        List<String> correlationEvidenceIds = correlationResult != null
            ? evidenceIds(baseReport.evidence(), correlationResult.findings())
            : List.of();
        steps.add(new SupervisorTraceStep(
            "correlation-evaluation",
            SupervisorTraceStepType.CORRELATION_EVALUATION,
            null,
            "Deterministic correlation synthesized %d cross-artifact finding(s) from %d grounded artifact(s)."
                .formatted(correlationFindingIds.size(), groundings.size()),
            null,
            allArtifactPaths,
            correlationEvidenceIds,
            correlationFindingIds,
            null,
            null,
            false
        ));

        for (int index = 0; index < groundings.size(); index++) {
            ArtifactGrounding grounding = groundings.get(index);
            NarrativeSelection observationSelection = observationSelections.get(index);
            steps.add(selectionStep(
                "correlation-observation-" + (index + 1),
                SupervisorTraceStepType.SPECIALIST_SELECTION,
                "correlation-specialist-observation",
                grounding.inputArtifact().type(),
                artifactPaths(List.of(grounding.inputArtifact())),
                evidenceIds(grounding.parsedArtifact().evidence(), grounding.assessmentResult().findings()),
                findingIds(grounding.assessmentResult().findings()),
                observationSelection,
                "supporting specialist observation"
            ));
        }

        steps.add(selectionStep(
            "correlation-synthesis",
            SupervisorTraceStepType.SYNTHESIS_SELECTION,
            "correlation-synthesis",
            null,
            allArtifactPaths,
            evidenceIds(baseReport.evidence(), baseReport.findings()),
            findingIds(baseReport.findings()),
            synthesisSelection,
            "incident synthesis"
        ));

        return new SupervisorTrace(OrchestrationWorkflowType.CORRELATE, allArtifactPaths, steps);
    }

    private SupervisorTraceStep groundingStep(String stepId, String role, ArtifactGrounding grounding) {
        AssessmentResult assessmentResult = grounding.assessmentResult();
        List<String> findingIds = findingIds(assessmentResult.findings());
        return new SupervisorTraceStep(
            stepId,
            SupervisorTraceStepType.ARTIFACT_GROUNDING,
            null,
            "Supervisor grounded the %s as %s and carried %d deterministic finding(s)."
                .formatted(role, grounding.inputArtifact().type(), findingIds.size()),
            grounding.inputArtifact().type(),
            artifactPaths(List.of(grounding.inputArtifact())),
            evidenceIds(grounding.parsedArtifact().evidence(), assessmentResult.findings()),
            findingIds,
            null,
            null,
            false
        );
    }

    private SupervisorTraceStep selectionStep(
        String stepId,
        SupervisorTraceStepType stepType,
        String stageId,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        List<String> findingIds,
        NarrativeSelection selection,
        String subject
    ) {
        NarrativeCandidate selectedCandidate = selection != null ? selection.selectedCandidate() : null;
        String decision;
        if (selectedCandidate != null) {
            decision = "Supervisor selected %s via %s for %s."
                .formatted(selectedCandidate.agentName(), selectedCandidate.narrativeSource(), subject);
        } else {
            decision = "Supervisor did not accept a narrative candidate for %s.".formatted(subject);
        }
        return new SupervisorTraceStep(
            stepId,
            stepType,
            stageId,
            decision,
            artifactType,
            artifactPaths,
            evidenceIds,
            findingIds,
            selectedCandidate != null ? selectedCandidate.agentName() : null,
            selectedCandidate != null ? selectedCandidate.narrativeSource() : null,
            selection != null && selection.selectedForUserNarrative(),
            selectedCandidate != null ? selectedCandidate.toolInvocations() : List.of(),
            selectedCandidate != null ? selectedCandidate.modelExecutionTraceability() : null
        );
    }

    private List<String> artifactPaths(List<InputArtifact> artifacts) {
        return artifacts.stream()
            .map(this::sourcePath)
            .filter(this::hasText)
            .distinct()
            .toList();
    }

    private List<String> evidenceIds(List<Evidence> evidence, List<Finding> findings) {
        List<String> ids = new ArrayList<>();
        evidence.stream()
            .map(Evidence::id)
            .filter(this::hasText)
            .forEach(ids::add);
        findings.stream()
            .flatMap(finding -> finding.evidenceIds().stream())
            .filter(this::hasText)
            .forEach(ids::add);
        return ids.stream().distinct().toList();
    }

    private List<String> findingIds(List<Finding> findings) {
        return findings.stream()
            .map(Finding::id)
            .filter(this::hasText)
            .distinct()
            .toList();
    }

    private List<String> signalPhrases(List<Finding> findings, List<Evidence> evidence) {
        List<String> phrases = new ArrayList<>();
        findings.stream()
            .map(Finding::title)
            .filter(this::hasText)
            .forEach(phrases::add);
        evidence.stream()
            .map(Evidence::label)
            .filter(this::hasText)
            .forEach(phrases::add);
        evidence.stream()
            .map(Evidence::id)
            .filter(this::hasText)
            .forEach(phrases::add);
        return phrases.stream().distinct().limit(12).toList();
    }

    private String sourcePath(InputArtifact artifact) {
        return artifact != null && artifact.metadata() != null ? artifact.metadata().sourcePath() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AgentTraceability traceability(
        String stageId,
        String agentName,
        AgentNarrativeSource narrativeSource,
        ArtifactType artifactType,
        List<String> artifactPaths,
        List<String> evidenceIds,
        boolean selectedForUserNarrative,
        List<AgentQualityGateResult> qualityGates,
        List<AgentToolInvocation> toolInvocations,
        ModelExecutionTraceability modelExecutionTraceability
    ) {
        return new AgentTraceability(
            stageId,
            agentName,
            narrativeSource,
            artifactType,
            artifactPaths,
            evidenceIds,
            selectedForUserNarrative,
            qualityGates,
            toolInvocations,
            modelExecutionTraceability
        );
    }

    private ModelExecutionTraceability modelExecutionTraceability(String agentName, AgentNarrativeSource narrativeSource) {
        if (configuredChatModel == null || narrativeSource == AgentNarrativeSource.DETERMINISTIC_FALLBACK) {
            return null;
        }
        String templateId = switch (narrativeSource) {
            case SPECIALIST_AGENT, SYNTHESIS_AGENT -> hasText(agentName) ? agentName + ".analyze" : null;
            case FALLBACK_SUMMARIZER, DETERMINISTIC_FALLBACK -> null;
        };
        String templateVersion = AGENT_TEMPLATE_VERSION;
        return hasText(templateId) ? configuredChatModel.executionTraceability(templateId, templateVersion) : null;
    }

    private String agentName(ArtifactType artifactType) {
        return switch (artifactType) {
            case GC_LOG -> "GCLogAgent";
            case JFR -> "JfrAgent";
            case THREAD_DUMP -> "ThreadDumpAgent";
            case HS_ERR_LOG -> "HSErrLogAgent";
            case NMT -> "NMTAgent";
            case HEAP_HISTOGRAM -> "HeapHistogramAgent";
            case PMAP -> "PmapAgent";
            case CONTAINER_MEMORY -> "ContainerMemoryAgent";
            case OOM_SIGNAL -> "OomSignalAgent";
            default -> "UnknownSpecialistAgent";
        };
    }

    private <T> T buildAgent(Class<T> agentType) {
        return AgenticServices.agentBuilder(agentType)
            .chatModel(chatModel)
            .build();
    }

    private record NarrativeCandidate(
        String narrative,
        String agentName,
        AgentNarrativeSource narrativeSource,
        List<AgentToolInvocation> toolInvocations,
        ModelExecutionTraceability modelExecutionTraceability
    ) { }

    private record NarrativeSelection(
        String narrative,
        List<AgentTraceability> traceability,
        NarrativeCandidate selectedCandidate,
        boolean selectedForUserNarrative
    ) { }

    @FunctionalInterface
    private interface NarrativeInvocation {
        String get();
    }
}
