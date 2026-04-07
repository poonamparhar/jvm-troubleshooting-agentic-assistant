package com.javaassistant.orchestration;

import com.javaassistant.context.ContextSelector;
import com.javaassistant.context.DiagnosticComputationService;
import com.javaassistant.context.DiagnosticContextRetriever;
import com.javaassistant.context.DiagnosticToolResult;
import com.javaassistant.context.IndexedArtifactDiagnosticContext;
import com.javaassistant.context.JfrSelector;
import com.javaassistant.diagnostics.AgentToolInvocation;
import com.javaassistant.diagnostics.ArtifactType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Thread-local tool runtime used by specialist agents to access curated retrieval and focused computation.
 */
public final class AgentToolRuntime {

    public static final String TOOL_FAMILY_RETRIEVAL = "RETRIEVAL";
    public static final String TOOL_FAMILY_COMPUTATION = "COMPUTATION";

    private static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();

    private AgentToolRuntime() {
    }

    public static <T> T withSession(Session session, Supplier<T> supplier) {
        if (session == null) {
            return supplier.get();
        }
        Session previous = CURRENT_SESSION.get();
        CURRENT_SESSION.set(session);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT_SESSION.remove();
            } else {
                CURRENT_SESSION.set(previous);
            }
        }
    }

    public static List<AgentToolInvocation> toolInvocations() {
        Session session = CURRENT_SESSION.get();
        return session != null ? session.toolInvocations() : List.of();
    }

    public static String retrieve(ArtifactType artifactType, String toolName, String artifactRef, String selectorQuery) {
        Session session = CURRENT_SESSION.get();
        if (session == null) {
            return inactiveSessionNotice();
        }
        return session.retrieve(artifactType, toolName, artifactRef, selectorQuery).renderForAgent();
    }

    public static String retrieveJfr(String toolName, String artifactRef, String selectorQuery) {
        Session session = CURRENT_SESSION.get();
        if (session == null) {
            return inactiveSessionNotice();
        }
        return session.retrieveJfr(toolName, artifactRef, selectorQuery).renderForAgent();
    }

    public static String compute(ArtifactType artifactType, String toolName, String artifactRef, String request) {
        Session session = CURRENT_SESSION.get();
        if (session == null) {
            return inactiveSessionNotice();
        }
        return session.compute(artifactType, toolName, artifactRef, request).renderForAgent();
    }

    private static String inactiveSessionNotice() {
        return """
            Artifact: (unknown)
            Slice: No active tool session [unavailable]
            Source: Tool runtime
            Truncated: false
            More available: false
            Content:
            No active diagnostic tool session is available for this agent invocation.
            """.strip();
    }

    public static Session createSession(
        String stageId,
        ToolBudget toolBudget,
        Map<String, IndexedArtifactDiagnosticContext> contextsByAlias
    ) {
        return new Session(
            stageId,
            toolBudget,
            contextsByAlias,
            new DiagnosticContextRetriever(),
            new DiagnosticComputationService()
        );
    }

    public record ToolBudget(int maxToolCalls, int maxRetrievalCalls, int maxRetrievalsPerArtifact) {
        public static ToolBudget analyze() {
            return new ToolBudget(12, 8, Integer.MAX_VALUE);
        }

        public static ToolBudget compare() {
            return new ToolBudget(16, 10, Integer.MAX_VALUE);
        }

        public static ToolBudget sequence() {
            return new ToolBudget(20, 12, 4);
        }

        public static ToolBudget correlate() {
            return new ToolBudget(24, 16, 6);
        }
    }

    public static final class Session {
        private final String stageId;
        private final ToolBudget toolBudget;
        private final Map<String, IndexedArtifactDiagnosticContext> contextsByAlias;
        private final DiagnosticContextRetriever retriever;
        private final DiagnosticComputationService computationService;
        private final List<AgentToolInvocation> toolInvocations = new ArrayList<>();
        private final Map<String, Integer> retrievalCallsByArtifact = new LinkedHashMap<>();
        private final Map<String, Set<String>> seenSliceIdsByArtifact = new LinkedHashMap<>();
        private int toolCalls;
        private int retrievalCalls;

        private Session(
            String stageId,
            ToolBudget toolBudget,
            Map<String, IndexedArtifactDiagnosticContext> contextsByAlias,
            DiagnosticContextRetriever retriever,
            DiagnosticComputationService computationService
        ) {
            this.stageId = stageId;
            this.toolBudget = toolBudget != null ? toolBudget : ToolBudget.analyze();
            this.contextsByAlias = contextsByAlias != null
                ? new LinkedHashMap<>(contextsByAlias)
                : Map.of();
            this.retriever = retriever;
            this.computationService = computationService;
            seedSeenSlicesFromStartingContext();
        }

        public List<AgentToolInvocation> toolInvocations() {
            return List.copyOf(toolInvocations);
        }

        private DiagnosticToolResult retrieve(
            ArtifactType preferredArtifactType,
            String toolName,
            String artifactRef,
            String selectorQuery
        ) {
            IndexedArtifactDiagnosticContext indexedContext = resolveContext(preferredArtifactType, artifactRef);
            if (indexedContext == null) {
                return budgetOrResolutionNotice(toolName, TOOL_FAMILY_RETRIEVAL, preferredArtifactType, artifactRef, selectorQuery, "No matching artifact context was available.");
            }
            if (budgetExceeded(toolName, TOOL_FAMILY_RETRIEVAL, indexedContext, selectorQuery)) {
                return budgetNotice(indexedContext, "retrieval");
            }
            DiagnosticToolResult result = indexedContext.diagnosticContext().artifactType() == ArtifactType.JFR
                ? retriever.retrieveJfr(indexedContext, JfrSelector.fromQuery(selectorQuery), seenSliceIds(indexedContext))
                : retriever.retrieve(indexedContext, ContextSelector.fromQuery(selectorQuery), seenSliceIds(indexedContext));
            markSeenSlice(indexedContext, result);
            recordToolInvocation(toolName, TOOL_FAMILY_RETRIEVAL, indexedContext, selectorQuery, result);
            toolCalls++;
            retrievalCalls++;
            retrievalCallsByArtifact.merge(artifactPath(indexedContext), 1, Integer::sum);
            return result;
        }

        private DiagnosticToolResult retrieveJfr(String toolName, String artifactRef, String selectorQuery) {
            IndexedArtifactDiagnosticContext indexedContext = resolveContext(ArtifactType.JFR, artifactRef);
            if (indexedContext == null) {
                return budgetOrResolutionNotice(toolName, TOOL_FAMILY_RETRIEVAL, ArtifactType.JFR, artifactRef, selectorQuery, "No matching JFR context was available.");
            }
            if (budgetExceeded(toolName, TOOL_FAMILY_RETRIEVAL, indexedContext, selectorQuery)) {
                return budgetNotice(indexedContext, "retrieval");
            }
            DiagnosticToolResult result = retriever.retrieveJfr(
                indexedContext,
                JfrSelector.fromQuery(selectorQuery),
                seenSliceIds(indexedContext)
            );
            markSeenSlice(indexedContext, result);
            recordToolInvocation(toolName, TOOL_FAMILY_RETRIEVAL, indexedContext, selectorQuery, result);
            toolCalls++;
            retrievalCalls++;
            retrievalCallsByArtifact.merge(artifactPath(indexedContext), 1, Integer::sum);
            return result;
        }

        private DiagnosticToolResult compute(
            ArtifactType preferredArtifactType,
            String toolName,
            String artifactRef,
            String request
        ) {
            IndexedArtifactDiagnosticContext indexedContext = resolveContext(preferredArtifactType, artifactRef);
            if (indexedContext == null) {
                return budgetOrResolutionNotice(toolName, TOOL_FAMILY_COMPUTATION, preferredArtifactType, artifactRef, request, "No matching artifact context was available.");
            }
            if (toolCalls >= toolBudget.maxToolCalls()) {
                return budgetNotice(indexedContext, "tool");
            }
            DiagnosticToolResult result = computationService.compute(indexedContext, request);
            recordToolInvocation(toolName, TOOL_FAMILY_COMPUTATION, indexedContext, request, result);
            toolCalls++;
            return result;
        }

        private boolean budgetExceeded(
            String toolName,
            String toolFamily,
            IndexedArtifactDiagnosticContext indexedContext,
            String request
        ) {
            if (toolCalls >= toolBudget.maxToolCalls()) {
                recordBudgetInvocation(toolName, toolFamily, indexedContext, request, "total-tool-budget-exhausted");
                return true;
            }
            if (TOOL_FAMILY_RETRIEVAL.equals(toolFamily) && retrievalCalls >= toolBudget.maxRetrievalCalls()) {
                recordBudgetInvocation(toolName, toolFamily, indexedContext, request, "retrieval-budget-exhausted");
                return true;
            }
            if (TOOL_FAMILY_RETRIEVAL.equals(toolFamily)
                && toolBudget.maxRetrievalsPerArtifact() < Integer.MAX_VALUE
                && retrievalCallsByArtifact.getOrDefault(artifactPath(indexedContext), 0) >= toolBudget.maxRetrievalsPerArtifact()) {
                recordBudgetInvocation(toolName, toolFamily, indexedContext, request, "per-artifact-retrieval-budget-exhausted");
                return true;
            }
            return false;
        }

        private DiagnosticToolResult budgetNotice(IndexedArtifactDiagnosticContext indexedContext, String budgetType) {
            return new DiagnosticToolResult(
                indexedContext != null ? indexedContext.diagnosticContext().artifactType() : null,
                artifactPath(indexedContext),
                "budget-exhausted",
                "notice",
                "Tool budget exhausted",
                "The " + budgetType + " budget is exhausted for stage " + stageId + ". If uncertainty remains, say so in the final analysis.",
                "Tool budget",
                false,
                false
            );
        }

        private DiagnosticToolResult budgetOrResolutionNotice(
            String toolName,
            String toolFamily,
            ArtifactType artifactType,
            String artifactRef,
            String request,
            String content
        ) {
            String detail = content;
            List<String> availableReferences = availableArtifactReferences(artifactType);
            if (!availableReferences.isEmpty()) {
                detail = detail + " Available artifact references: " + String.join("; ", availableReferences) + ".";
            }
            toolInvocations.add(new AgentToolInvocation(
                toolName,
                toolFamily,
                artifactType,
                artifactRef,
                request,
                "unresolved-context",
                "No matching artifact context",
                "agent-tool-runtime",
                false,
                false
            ));
            return new DiagnosticToolResult(
                artifactType,
                artifactRef,
                "unresolved-context",
                "notice",
                "No matching artifact context",
                detail,
                "Tool runtime",
                false,
                false
            );
        }

        private void recordBudgetInvocation(
            String toolName,
            String toolFamily,
            IndexedArtifactDiagnosticContext indexedContext,
            String request,
            String sliceId
        ) {
            toolInvocations.add(new AgentToolInvocation(
                toolName,
                toolFamily,
                indexedContext != null ? indexedContext.diagnosticContext().artifactType() : null,
                artifactPath(indexedContext),
                request,
                sliceId,
                "Tool budget exhausted",
                "agent-tool-budget",
                false,
                false
            ));
        }

        private void recordToolInvocation(
            String toolName,
            String toolFamily,
            IndexedArtifactDiagnosticContext indexedContext,
            String request,
            DiagnosticToolResult result
        ) {
            toolInvocations.add(new AgentToolInvocation(
                toolName,
                toolFamily,
                indexedContext != null ? indexedContext.diagnosticContext().artifactType() : null,
                artifactPath(indexedContext),
                request,
                result != null ? result.sliceId() : null,
                result != null ? result.label() : null,
                result != null ? result.traceability() : null,
                result != null && result.truncated(),
                result != null && result.moreAvailable()
            ));
        }

        private void seedSeenSlicesFromStartingContext() {
            for (IndexedArtifactDiagnosticContext context : contextsByAlias.values().stream().distinct().toList()) {
                if (context == null || context.diagnosticContext() == null) {
                    continue;
                }
                rememberVisibleSlices(context, context.diagnosticContext().structuredSlices());
                rememberVisibleSlices(context, context.diagnosticContext().representativeSlices());
            }
        }

        private void rememberVisibleSlices(
            IndexedArtifactDiagnosticContext indexedContext,
            List<com.javaassistant.context.ContextSlice> slices
        ) {
            if (indexedContext == null || slices == null || slices.isEmpty()) {
                return;
            }
            Set<String> seenSliceIds = seenSliceIdsByArtifact.computeIfAbsent(artifactSessionKey(indexedContext), key -> new LinkedHashSet<>());
            for (com.javaassistant.context.ContextSlice slice : slices) {
                if (slice != null && slice.sliceId() != null && !slice.sliceId().isBlank()) {
                    seenSliceIds.add(normalizeSliceId(slice.sliceId()));
                }
            }
        }

        private Set<String> seenSliceIds(IndexedArtifactDiagnosticContext indexedContext) {
            return Set.copyOf(seenSliceIdsByArtifact.computeIfAbsent(artifactSessionKey(indexedContext), key -> new LinkedHashSet<>()));
        }

        private void markSeenSlice(IndexedArtifactDiagnosticContext indexedContext, DiagnosticToolResult result) {
            if (indexedContext == null || result == null || !isTrackableSliceId(result.sliceId())) {
                return;
            }
            seenSliceIdsByArtifact
                .computeIfAbsent(artifactSessionKey(indexedContext), key -> new LinkedHashSet<>())
                .add(normalizeSliceId(result.sliceId()));
        }

        private boolean isTrackableSliceId(String sliceId) {
            if (sliceId == null || sliceId.isBlank()) {
                return false;
            }
            String normalizedSliceId = normalizeSliceId(sliceId);
            return !normalizedSliceId.contains("budget-exhausted")
                && !"unresolved-context".equals(normalizedSliceId)
                && !"unavailable".equals(normalizedSliceId);
        }

        private String artifactSessionKey(IndexedArtifactDiagnosticContext context) {
            String artifactPath = artifactPath(context);
            return artifactPath != null && !artifactPath.isBlank()
                ? artifactPath
                : "context@" + System.identityHashCode(context);
        }

        private String normalizeSliceId(String sliceId) {
            return sliceId.toLowerCase(Locale.ROOT);
        }

        private IndexedArtifactDiagnosticContext resolveContext(ArtifactType preferredArtifactType, String artifactRef) {
            if (contextsByAlias.isEmpty()) {
                return null;
            }

            boolean explicitArtifactRef = artifactRef != null && !artifactRef.isBlank();
            if (explicitArtifactRef) {
                IndexedArtifactDiagnosticContext direct = contextsByAlias.get(artifactRef);
                if (direct != null && matchesType(direct, preferredArtifactType)) {
                    return direct;
                }
                String normalizedRef = artifactRef.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, IndexedArtifactDiagnosticContext> entry : contextsByAlias.entrySet()) {
                    IndexedArtifactDiagnosticContext candidate = entry.getValue();
                    if (!matchesType(candidate, preferredArtifactType)) {
                        continue;
                    }
                    if (entry.getKey().toLowerCase(Locale.ROOT).equals(normalizedRef)
                        || artifactPath(candidate).toLowerCase(Locale.ROOT).equals(normalizedRef)
                        || displayName(candidate).toLowerCase(Locale.ROOT).equals(normalizedRef)) {
                        return candidate;
                    }
                }
                return null;
            }

            if (contextsByAlias.containsKey("current")) {
                IndexedArtifactDiagnosticContext current = contextsByAlias.get("current");
                if (matchesType(current, preferredArtifactType)) {
                    return current;
                }
            }
            if (contextsByAlias.containsKey("primary")) {
                IndexedArtifactDiagnosticContext primary = contextsByAlias.get("primary");
                if (matchesType(primary, preferredArtifactType)) {
                    return primary;
                }
            }

            List<IndexedArtifactDiagnosticContext> candidates = contextsByAlias.values().stream()
                .distinct()
                .filter(context -> matchesType(context, preferredArtifactType))
                .toList();
            if (candidates.size() == 1) {
                return candidates.getFirst();
            }
            if (!candidates.isEmpty()) {
                return candidates.getFirst();
            }
            return null;
        }

        private boolean matchesType(IndexedArtifactDiagnosticContext context, ArtifactType preferredArtifactType) {
            return preferredArtifactType == null
                || context == null
                || context.diagnosticContext() == null
                || context.diagnosticContext().artifactType() == preferredArtifactType;
        }

        private String artifactPath(IndexedArtifactDiagnosticContext context) {
            return context != null
                && context.inputArtifact() != null
                && context.inputArtifact().metadata() != null
                && context.inputArtifact().metadata().sourcePath() != null
                ? context.inputArtifact().metadata().sourcePath()
                : "(unknown)";
        }

        private String displayName(IndexedArtifactDiagnosticContext context) {
            return context != null
                && context.inputArtifact() != null
                && context.inputArtifact().metadata() != null
                && context.inputArtifact().metadata().displayName() != null
                ? context.inputArtifact().metadata().displayName()
                : "";
        }

        private List<String> availableArtifactReferences(ArtifactType preferredArtifactType) {
            LinkedHashMap<String, IndexedArtifactDiagnosticContext> canonicalAliases = new LinkedHashMap<>();
            for (Map.Entry<String, IndexedArtifactDiagnosticContext> entry : contextsByAlias.entrySet()) {
                IndexedArtifactDiagnosticContext candidate = entry.getValue();
                if (!matchesType(candidate, preferredArtifactType)) {
                    continue;
                }
                String alias = entry.getKey();
                if (isPreferredArtifactAlias(alias)) {
                    canonicalAliases.putIfAbsent(alias, candidate);
                }
            }
            if (canonicalAliases.isEmpty()) {
                for (Map.Entry<String, IndexedArtifactDiagnosticContext> entry : contextsByAlias.entrySet()) {
                    IndexedArtifactDiagnosticContext candidate = entry.getValue();
                    if (!matchesType(candidate, preferredArtifactType)) {
                        continue;
                    }
                    canonicalAliases.putIfAbsent(entry.getKey(), candidate);
                }
            }
            return canonicalAliases.entrySet().stream()
                .map(entry -> entry.getKey() + " -> " + entry.getValue().diagnosticContext().artifactType() + " | " + artifactPath(entry.getValue()))
                .distinct()
                .toList();
        }

        private boolean isPreferredArtifactAlias(String alias) {
            if (alias == null || alias.isBlank()) {
                return false;
            }
            return "primary".equals(alias)
                || "current".equals(alias)
                || "baseline".equals(alias)
                || alias.matches("artifact-\\d+");
        }
    }
}
