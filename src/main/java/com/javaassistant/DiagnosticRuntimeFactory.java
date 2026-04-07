package com.javaassistant;

import com.javaassistant.ai.ConfiguredChatModel;
import com.javaassistant.assessment.ArtifactAssessmentService;
import com.javaassistant.assessment.ContainerMemoryArtifactAssessor;
import com.javaassistant.assessment.GcLogArtifactAssessor;
import com.javaassistant.assessment.HeapHistogramArtifactAssessor;
import com.javaassistant.assessment.HsErrArtifactAssessor;
import com.javaassistant.assessment.JfrArtifactAssessor;
import com.javaassistant.assessment.NmtArtifactAssessor;
import com.javaassistant.assessment.OomSignalArtifactAssessor;
import com.javaassistant.assessment.PmapArtifactAssessor;
import com.javaassistant.assessment.ThreadDumpArtifactAssessor;
import com.javaassistant.compare.ArtifactComparisonService;
import com.javaassistant.compare.GcLogComparator;
import com.javaassistant.compare.HeapHistogramComparator;
import com.javaassistant.compare.JfrComparator;
import com.javaassistant.compare.NmtComparator;
import com.javaassistant.compare.PmapComparator;
import com.javaassistant.compare.ThreadDumpComparator;
import com.javaassistant.correlate.MultiArtifactCorrelator;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.orchestration.DiagnosticAgentOrchestrator;
import com.javaassistant.parse.ArtifactParsingService;
import com.javaassistant.parse.ContainerMemoryArtifactParser;
import com.javaassistant.parse.GcLogArtifactParser;
import com.javaassistant.parse.HeapHistogramArtifactParser;
import com.javaassistant.parse.HsErrArtifactParser;
import com.javaassistant.parse.JfrArtifactParser;
import com.javaassistant.parse.NmtArtifactParser;
import com.javaassistant.parse.OomSignalArtifactParser;
import com.javaassistant.parse.PmapArtifactParser;
import com.javaassistant.parse.ThreadDumpArtifactParser;
import com.javaassistant.report.AnalysisReportAssembler;
import java.util.List;

/**
 * Centralizes construction of the shared diagnostic runtime services so CLI and tests do not duplicate wiring.
 */
public final class DiagnosticRuntimeFactory {

    private DiagnosticRuntimeFactory() {
    }

    public static ArtifactLoader artifactLoader() {
        return new ArtifactLoader();
    }

    public static ArtifactParsingService parsingService() {
        return new ArtifactParsingService(List.of(
            new GcLogArtifactParser(),
            new JfrArtifactParser(),
            new ThreadDumpArtifactParser(),
            new HsErrArtifactParser(),
            new NmtArtifactParser(),
            new ContainerMemoryArtifactParser(),
            new OomSignalArtifactParser(),
            new HeapHistogramArtifactParser(),
            new PmapArtifactParser()
        ));
    }

    public static ArtifactAssessmentService assessmentService() {
        return new ArtifactAssessmentService(List.of(
            new GcLogArtifactAssessor(),
            new JfrArtifactAssessor(),
            new ThreadDumpArtifactAssessor(),
            new HsErrArtifactAssessor(),
            new NmtArtifactAssessor(),
            new ContainerMemoryArtifactAssessor(),
            new OomSignalArtifactAssessor(),
            new HeapHistogramArtifactAssessor(),
            new PmapArtifactAssessor()
        ));
    }

    public static ArtifactComparisonService comparisonService() {
        return new ArtifactComparisonService(List.of(
            new GcLogComparator(),
            new JfrComparator(),
            new ThreadDumpComparator(),
            new HeapHistogramComparator(),
            new NmtComparator(),
            new PmapComparator()
        ));
    }

    public static MultiArtifactCorrelator correlator() {
        return new MultiArtifactCorrelator();
    }

    public static AnalysisReportAssembler analysisReportAssembler() {
        return new AnalysisReportAssembler();
    }

    public static DiagnosticAgentOrchestrator diagnosticAgentOrchestrator(ConfiguredChatModel configuredChatModel) {
        return new DiagnosticAgentOrchestrator(
            parsingService(),
            assessmentService(),
            comparisonService(),
            correlator(),
            analysisReportAssembler(),
            configuredChatModel
        );
    }
}
