package com.javaassistant.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.agents.GCTools;
import com.javaassistant.context.DiagnosticContextIndexer;
import com.javaassistant.diagnostics.ArtifactMetadata;
import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.Evidence;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.diagnostics.ParsedArtifact;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.parse.GcLogArtifactParser;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolRuntimeTest {

    private final ArtifactLoader loader = new ArtifactLoader(new ArtifactClassifier());
    private final GcLogArtifactParser gcParser = new GcLogArtifactParser();
    private final DiagnosticContextIndexer indexer = new DiagnosticContextIndexer();

    @Test
    void recordsRetrievalAndFocusedComputationInvocations() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            new AgentToolRuntime.ToolBudget(6, 4, Integer.MAX_VALUE),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String retrieval = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=45"));
        String computation = AgentToolRuntime.withSession(session, () -> tools.computeGcView("", "pause-percentiles"));

        assertTrue(retrieval.contains("Artifact:"));
        assertTrue(retrieval.contains("GC event 45"));
        assertTrue(computation.contains("GC pause percentile summary"));
        assertEquals(2, session.toolInvocations().size());
        assertEquals("fetchGcContext", session.toolInvocations().get(0).toolName());
        assertEquals("RETRIEVAL", session.toolInvocations().get(0).toolFamily());
        assertEquals(ArtifactType.GC_LOG, session.toolInvocations().get(0).artifactType());
        assertEquals("computeGcView", session.toolInvocations().get(1).toolName());
        assertEquals("COMPUTATION", session.toolInvocations().get(1).toolFamily());
    }

    @Test
    void enforcesAnalyzeRetrievalBudget() throws Exception {
        var artifact = loader.load(Path.of("samples/g1_21_smallheap_fullgcs.log"));
        var indexedContext = indexer.index(artifact, gcParser.parse(artifact));
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            new AgentToolRuntime.ToolBudget(6, 4, Integer.MAX_VALUE),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=24"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "gcId=45"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "pattern=Evacuation Failure"));
        AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "lines=640-650"));
        String fifthResponse = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", "section=summary"));

        assertTrue(fifthResponse.contains("Tool budget exhausted"));
        assertEquals(5, session.toolInvocations().size());
        assertEquals("retrieval-budget-exhausted", session.toolInvocations().getLast().sliceId());
    }

    @Test
    void blankRetrievalProgressesAcrossTheSessionInsteadOfRepeatingTheFirstOmittedSlice() {
        var indexedContext = syntheticIndexedContext(320, 15);
        AgentToolRuntime.Session session = AgentToolRuntime.createSession(
            "single-artifact-specialist-analysis",
            AgentToolRuntime.ToolBudget.analyze(),
            sessionContexts(indexedContext)
        );

        GCTools tools = new GCTools();
        String first = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", ""));
        String second = AgentToolRuntime.withSession(session, () -> tools.fetchGcContext("", ""));

        assertTrue(first.contains("Slice id: section13"));
        assertTrue(second.contains("Slice id: section14"));
    }

    private Map<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> sessionContexts(
        com.javaassistant.context.IndexedArtifactDiagnosticContext indexedContext
    ) {
        LinkedHashMap<String, com.javaassistant.context.IndexedArtifactDiagnosticContext> contexts = new LinkedHashMap<>();
        contexts.put("current", indexedContext);
        contexts.put("primary", indexedContext);
        return contexts;
    }

    private com.javaassistant.context.IndexedArtifactDiagnosticContext syntheticIndexedContext(int lineCount, int sectionCount) {
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            builder.append("synthetic-line-").append(lineNumber);
            if (lineNumber == lineCount) {
                builder.append(" late-clue");
            }
            if (lineNumber < lineCount) {
                builder.append('\n');
            }
        }
        InputArtifact artifact = new InputArtifact(
            ArtifactType.GC_LOG,
            new ArtifactMetadata(
                "/tmp/synthetic-gc.log",
                "synthetic-gc.log",
                builder.length(),
                LocalDateTime.of(2026, 4, 1, 12, 0),
                Map.of()
            ),
            builder.toString()
        );
        LinkedHashMap<String, Object> extractedData = new LinkedHashMap<>();
        for (int index = 1; index <= sectionCount; index++) {
            extractedData.put("section" + index, Map.of("value", "v".repeat(180)));
        }
        ParsedArtifact parsedArtifact = new ParsedArtifact(
            ArtifactType.GC_LOG,
            artifact.metadata(),
            "synthetic-parser",
            extractedData,
            List.of(new Evidence(
                "late-clue",
                artifact.metadata().sourcePath(),
                "Late clue",
                "A late clue is present near the file tail.",
                "synthetic-line-320 late-clue",
                List.of(320),
                Map.of("lineNumber", 320)
            )),
            List.of()
        );
        return indexer.index(artifact, parsedArtifact);
    }
}
