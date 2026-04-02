package com.javaassistant;

import com.javaassistant.ai.StructuredReportQuestionAnswerer;
import com.javaassistant.compare.ArtifactComparisonService;
import com.javaassistant.compare.HeapHistogramComparator;
import com.javaassistant.compare.JfrComparator;
import com.javaassistant.compare.NmtComparator;
import com.javaassistant.compare.PmapComparator;
import com.javaassistant.compare.ThreadDumpComparator;
import com.javaassistant.correlate.MultiArtifactCorrelator;
import com.javaassistant.detect.ArtifactClassifier;
import com.javaassistant.ingest.ArtifactDiscoveryResult;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
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
import com.javaassistant.orchestration.DiagnosticAgentOrchestrator;
import com.javaassistant.render.UserConsoleReportRenderer;
import com.javaassistant.report.AnalysisReportAssembler;
import com.javaassistant.report.ReportCatalogEntry;
import com.javaassistant.report.ReportCatalogResult;
import com.javaassistant.report.ReportBundleService;
import com.javaassistant.modelproviders.ConfiguredChatModel;
import com.javaassistant.modelproviders.OCIChatModelProvider;
import com.javaassistant.modelproviders.OllamaChatModelProvider;
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
import dev.langchain4j.model.chat.ChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.Scanner;

/**
 * Main command-line application for JVM troubleshooting
 */
public class JVMTroubleshooter {

    private record ParsedCommand(String command, String argument) { }
    private record AskRequest(String analysisId, String question) { }
    private record LoadRequest(String filePath, String analysisId, ArtifactType overrideType) { }
    private record CatalogRequest(com.javaassistant.diagnostics.SeverityLevel severity, ArtifactType artifactType) { }

    public enum Provider {
        OCI, OLLAMA
    }

    private static Provider currentProvider;
    private static ConfiguredChatModel currentConfiguredChatModel;
    private static ChatModel currentChatModel;
    private static InputArtifact loadedArtifact = null;
    private static final ArtifactLoader artifactLoader = new ArtifactLoader(new ArtifactClassifier());
    private static final ReportBundleService reportBundleService = new ReportBundleService(ApplicationRuntimeSupport.resolveReportBundleDirectory());
    private static final StructuredReportQuestionAnswerer structuredReportQuestionAnswerer = new StructuredReportQuestionAnswerer();
    private static String latestAnalysisId;
    private static AnalysisReport latestAnalysisReport;
    private static final ArtifactParsingService parsingService = new ArtifactParsingService(List.of(
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
    private static final ArtifactAssessmentService assessmentService = new ArtifactAssessmentService(List.of(
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
    private static final ArtifactComparisonService comparisonService = new ArtifactComparisonService(List.of(
        new JfrComparator(),
        new ThreadDumpComparator(),
        new HeapHistogramComparator(),
        new NmtComparator(),
        new PmapComparator()
    ));
    private static final MultiArtifactCorrelator multiArtifactCorrelator = new MultiArtifactCorrelator();
    private static final AnalysisReportAssembler analysisReportAssembler = new AnalysisReportAssembler();
    private static final UserConsoleReportRenderer userConsoleReportRenderer = new UserConsoleReportRenderer();

    static {
        initializeDefaultProvider();
    }

    private static void initializeDefaultProvider() {
        try {
            currentConfiguredChatModel = OllamaChatModelProvider.createChatModel();
            currentChatModel = currentConfiguredChatModel.chatModel();
            currentProvider = Provider.OLLAMA;
        } catch (RuntimeException e) {
            System.err.println("[Warning] Failed to initialize default OLLAMA provider: " + e.getMessage());
            System.err.println("[Warning] Falling back to OCI provider. Fix Ollama configuration and run 'config set provider ollama' to retry.");
            currentConfiguredChatModel = OCIChatModelProvider.createChatModel();
            currentChatModel = currentConfiguredChatModel.chatModel();
            currentProvider = Provider.OCI;
        }
    }

    private static void switchProvider(Provider newProvider) {
        if (currentProvider == newProvider) {
            System.out.println("Provider already set to: " + newProvider);
            return;
        }

        try {
            ConfiguredChatModel newConfiguredChatModel = (newProvider == Provider.OCI)
                    ? OCIChatModelProvider.createChatModel()
                    : OllamaChatModelProvider.createChatModel();

            currentProvider = newProvider;
            currentConfiguredChatModel = newConfiguredChatModel;
            currentChatModel = newConfiguredChatModel.chatModel();
            System.out.println("Successfully switched to " + newProvider + " provider");
        } catch (Exception e) {
            System.err.println("[Error] Unable to switch provider to " + newProvider + ": " + e.getMessage());
            System.err.println("          Please verify your configuration and try again.");
        }
    }

    private static DiagnosticAgentOrchestrator diagnosticAgentOrchestrator() {
        return new DiagnosticAgentOrchestrator(
            parsingService,
            assessmentService,
            comparisonService,
            multiArtifactCorrelator,
            analysisReportAssembler,
            currentConfiguredChatModel
        );
    }

    /**
     * Parses command input, properly handling quoted strings
     */
    private static ParsedCommand parseCommand(String input) {
        if (input == null || input.trim().isEmpty()) {
            return new ParsedCommand("", "");
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '\0';

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (!inQuotes && (c == '"' || c == '\'')) {
                // Start of quoted string
                inQuotes = true;
                quoteChar = c;
            } else if (inQuotes && c == quoteChar) {
                // End of quoted string
                inQuotes = false;
                quoteChar = '\0';
            } else if (!inQuotes && Character.isWhitespace(c)) {
                // Whitespace outside quotes - end current token
                if (!currentToken.isEmpty()) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
            } else {
                // Regular character
                currentToken.append(c);
            }
        }

        // Add final token if any
        if (!currentToken.isEmpty()) {
            tokens.add(currentToken.toString());
        }

        if (tokens.isEmpty()) {
            return new ParsedCommand("", "");
        }

        String command = tokens.get(0);
        String argument = tokens.size() > 1 ?
            tokens.subList(1, tokens.size()).stream().reduce((a, b) -> a + " " + b).orElse("") :
            "";

        return new ParsedCommand(command, argument);
    }

    public static void main(String[] args) {
        try {
            // Start interactive command prompt
            startInteractiveMode();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Starts the interactive command prompt for JVM troubleshooting
     */
    private static void startInteractiveMode() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("JVM Troubleshooting Agentic Assistant");
        System.out.println("=====================================");
        System.out.println("Commands:");
        System.out.println("  load <file-or-dir> [--type <type>] - Load a diagnostic file or a raw container-memory directory");
        System.out.println("  load --analysis-id <id> - Load a saved analysis report bundle as active context");
        System.out.println("  analyze [<file-or-dir>] - Analyze with AI specialist agents using the loaded artifact or specified file/directory");
        System.out.println("  report [<analysis-id>] [--format <text|json|markdown|html>] - Show a saved report");
        System.out.println("  catalog [--severity <level>] [--artifact-type <type>] - List saved report bundles");
        System.out.println("  compare <file1> <file2> - Compare two supported artifact files with AI specialist agents");
        System.out.println("  correlate <files...> - Correlate multiple supported diagnostic files through AI multi-agent synthesis");
        System.out.println("  ask [--analysis-id <id>] <question> - Ask a question about the loaded artifact or a saved analysis report");
        System.out.println("  config set provider <oci|ollama> - Switch AI model provider");
        System.out.println("  version             - Show application, runtime, and report-bundle info");
        System.out.println("  status              - Show current status");
        System.out.println("  help                - Show this help");
        System.out.println("  quit                - Exit the application");
        System.out.println();

        while (true) {
            System.out.print("jvm-troubleshooter> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            // Parse command and arguments, handling quoted strings
            ParsedCommand parsedCommand = parseCommand(input);
            String command = parsedCommand.command.toLowerCase();
            String argument = parsedCommand.argument;

            try {
                switch (command) {
                    case "load":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a file path or --analysis-id <id> to load");
                        } else {
                            try {
                                LoadRequest loadRequest = parseLoadRequest(argument);
                                if (loadRequest.analysisId() != null) {
                                    AnalysisReport report = loadSavedReport(loadRequest.analysisId());
                                    loadedArtifact = null;
                                    System.out.println("Loaded saved analysis report: " + report.analysisId());
                                    System.out.println("Summary: " + report.incidentSummary());
                                } else {
                                    AnalysisReport report = tryLoadStructuredContext(loadRequest.filePath());
                                    if (report != null) {
                                        loadedArtifact = null;
                                        System.out.println("Loaded saved analysis report: " + report.analysisId());
                                        System.out.println("Summary: " + report.incidentSummary());
                                    } else {
                                        loadedArtifact = loadInputArtifact(loadRequest.filePath(), loadRequest.overrideType(), scanner);
                                        if (!RuntimeRoutingPolicy.reportMatchesLoadedData(latestAnalysisReport, loadedArtifact)) {
                                            latestAnalysisReport = null;
                                            latestAnalysisId = null;
                                        }
                                        System.out.println(
                                            "Loaded file: "
                                                + artifactSourcePath(loadedArtifact)
                                                + " ("
                                                + loadedArtifact.type().description()
                                                + ", "
                                                + artifactContentLength(loadedArtifact)
                                                + " chars)"
                                        );
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                            }
                        }
                        break;

                    case "analyze":
                        if (rejectLegacyFlag("analyze", argument)) {
                            break;
                        }
                        InputArtifact artifactToAnalyze;
                        if (!argument.isEmpty()) {
                            try {
                                artifactToAnalyze = loadAnalyzeTarget(argument.trim());
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                                break;
                            }
                            if (artifactToAnalyze == null) {
                                break;
                            }
                        } else if (loadedArtifact != null) {
                            artifactToAnalyze = loadedArtifact;
                        } else {
                            System.out.println("Error: No diagnostic file loaded. Use 'load <file>' first or specify a single file with 'analyze <file>'.");
                            break;
                        }
                        analyzeSingleArtifact(artifactToAnalyze);
                        break;

                    case "compare":
                        if (rejectLegacyFlag("compare", argument)) {
                            break;
                        }
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify two files to compare: compare <baseline_file> <current_file>");
                            break;
                        }
                        String[] compareFiles = argument.split("\\s+");
                        if (compareFiles.length != 2) {
                            System.out.println("Error: Compare command requires exactly two files: compare <baseline_file> <current_file>");
                            break;
                        }
                        String baselineFile = compareFiles[0].trim();
                        String currentFile = compareFiles[1].trim();
                        try {
                            Path baselinePath = Paths.get(baselineFile);
                            Path currentPath = Paths.get(currentFile);
                            if (!Files.exists(baselinePath) || !Files.exists(currentPath)) {
                                System.out.println("Error: One or both files not found");
                                break;
                            }
                            runStructuredComparison(baselinePath, currentPath);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;

                    case "report":
                        try {
                            showSavedReport(argument);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;

                    case "catalog":
                        try {
                            showCatalog(argument);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;

                    case "correlate":
                        if (rejectLegacyFlag("correlate", argument)) {
                            break;
                        }
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify at least two files to correlate: correlate <file1> <file2> [file3 ...]");
                            break;
                        }
                        String[] correlateFiles = argument.split("\\s+");
                        if (correlateFiles.length < 2) {
                            System.out.println("Error: Correlate requires at least two files.");
                            break;
                        }
                        try {
                            List<InputArtifact> artifacts = new ArrayList<>();
                            for (String file : correlateFiles) {
                                artifacts.add(loadInputArtifact(file.trim()));
                            }
                            correlateArtifacts(artifacts);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;

                    case "ask":
                        if (rejectLegacyFlag("ask", argument)) {
                            break;
                        }
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a question to ask");
                        } else {
                            AskRequest askRequest = parseAskRequest(argument);
                            if (askRequest.question().isBlank()) {
                                System.out.println("Error: Please specify a question to ask");
                            } else if (askRequest.analysisId() != null) {
                                askQuestion(askRequest.question(), loadSavedReport(askRequest.analysisId()));
                            } else {
                                AnalysisReport questionContext = resolveStructuredQuestionContext();
                                if (questionContext != null) {
                                    askQuestion(askRequest.question(), questionContext);
                                } else if (loadedArtifact != null && RuntimeRoutingPolicy.supportsStructuredAnalysis(loadedArtifact)) {
                                    AnalysisReport generatedReport = ensureStructuredReportForQuestioning(loadedArtifact);
                                    if (generatedReport != null) {
                                        askQuestion(askRequest.question(), generatedReport);
                                    }
                                } else if (loadedArtifact == null) {
                                    AnalysisReport savedReport = tryLoadLatestSavedReport();
                                    if (savedReport != null) {
                                        askQuestion(askRequest.question(), savedReport);
                                    } else {
                                        System.out.println("Error: No diagnostic artifact or saved analysis report loaded. Use 'load <file>', run 'analyze', or pass --analysis-id.");
                                    }
                                } else {
                                    reportUnsupportedStructuredQuestioning(loadedArtifact);
                                }
                            }
                        }
                        break;

                    case "config":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify config command: config set provider <oci|ollama>");
                            break;
                        }
                        String[] configParts = argument.split("\\s+", 3);
                        if (configParts.length < 3 || !"set".equals(configParts[0]) || !"provider".equals(configParts[1])) {
                            System.out.println("Error: Invalid config command. Use: config set provider <oci|ollama>");
                            break;
                        }
                        String providerStr = configParts[2].toUpperCase();
                        try {
                            Provider newProvider = Provider.valueOf(providerStr);
                            switchProvider(newProvider);
                        } catch (IllegalArgumentException e) {
                            System.out.println("Error: Invalid provider '" + configParts[2] + "'. Valid options: oci, ollama");
                        }
                        break;

                    case "status":
                            showStatus(loadedArtifact);
                        break;

                    case "version":
                        printVersion();
                        break;

                    case "help":
                        printHelp();
                        break;

                    case "quit":
                    case "exit":
                        System.out.println("Goodbye!");
                        scanner.close();
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Unknown command: " + command + ". Type 'help' for available commands.");
                        break;
                }
            } catch (Exception e) {
                System.err.println("[Error] Failed to process command: " + e.getMessage());
            }
        }
    }

    /**
     * Loads a canonical input artifact from the specified file path.
     */
    private static InputArtifact loadInputArtifact(String filePath) throws Exception {
        return loadInputArtifact(filePath, null, null);
    }

    /**
     * Loads a canonical input artifact from the specified file path with optional type override.
     */
    private static InputArtifact loadInputArtifact(String filePath, ArtifactType overrideType, Scanner scanner) throws Exception {
        Path path = Paths.get(filePath);
        InputArtifact artifact = artifactLoader.load(path, overrideType);
        ArtifactType artifactType = artifact.type();

        if (artifactType == ArtifactType.UNKNOWN && scanner != null) {
            System.out.println("Unable to automatically detect artifact type for file: " + filePath);
            System.out.println("Supported types: GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL");
            System.out.println("Legacy aliases also accepted: NMT_MEMORY, PMAP_OUTPUT");
            System.out.print("Please specify the artifact type: ");
            String typeInput = scanner.nextLine().trim().toUpperCase();
            try {
                artifactType = ArtifactType.fromExternalName(typeInput);
            } catch (IllegalArgumentException e) {
                throw new Exception("Invalid artifact type specified: " + typeInput);
            }
            artifact = artifactLoader.load(path, artifactType);
        }

        return artifact;
    }

    private static String artifactSourcePath(InputArtifact artifact) {
        if (artifact == null || artifact.metadata() == null) {
            return "(unknown)";
        }
        if (artifact.metadata().sourcePath() != null && !artifact.metadata().sourcePath().isBlank()) {
            return artifact.metadata().sourcePath();
        }
        if (artifact.metadata().displayName() != null && !artifact.metadata().displayName().isBlank()) {
            return artifact.metadata().displayName();
        }
        return "(unknown)";
    }

    private static long artifactContentLength(InputArtifact artifact) {
        if (artifact == null) {
            return 0L;
        }
        if (artifact.metadata() != null && artifact.metadata().contentLength() > 0L) {
            return artifact.metadata().contentLength();
        }
        return artifact.content() != null ? artifact.content().length() : 0L;
    }

    private static boolean rejectLegacyFlag(String command, String argument) {
        if (!containsLegacyFlag(argument)) {
            return false;
        }

        System.out.println("Error: --legacy is no longer supported for '" + command + "'.");
        System.out.println("       This CLI now runs through the AI specialist-agent workflow only.");
        if ("ask".equals(command)) {
            System.out.println("       Run 'analyze <file>' first or use 'ask --analysis-id <id> <question>'.");
        } else {
            System.out.println("       Remove --legacy and rerun the command.");
        }
        return true;
    }

    private static boolean containsLegacyFlag(String argument) {
        if (argument == null || argument.isBlank()) {
            return false;
        }
        for (String token : argument.trim().split("\\s+")) {
            if ("--legacy".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static InputArtifact loadAnalyzeTarget(String target) throws Exception {
        AnalysisReport structuredContext = tryLoadStructuredContext(target);
        if (structuredContext != null) {
            loadedArtifact = null;
            System.out.println("Loaded saved analysis report: " + structuredContext.analysisId());
            System.out.println("Use 'report' to view it or 'ask <question>' to query it.");
            return null;
        }

        Path path = Paths.get(target);
        if (!Files.exists(path)) {
            throw new Exception("File not found: " + target);
        }

        if (Files.isDirectory(path)) {
            analyzeDirectory(path);
            return null;
        }

        return loadInputArtifact(target);
    }

    private static void analyzeDirectory(Path path) throws Exception {
        ArtifactDiscoveryResult discovery = artifactLoader.discoverWithInventory(path);
        emitDirectoryDiscoverySummary(path, discovery);

        if (discovery.supportedArtifacts().isEmpty()) {
            throw new Exception(noSupportedArtifactsMessage(path, discovery));
        }

        if (discovery.supportedArtifacts().size() == 1) {
            analyzeDiscoveredSingleArtifact(
                discovery.supportedArtifacts().get(0),
                discovery.inventoryEntries()
            );
            return;
        }

        analyzeDiscoveredArtifactSet(
            discovery.supportedArtifacts(),
            discovery.inventoryEntries()
        );
    }

    private static void emitDirectoryDiscoverySummary(Path path, ArtifactDiscoveryResult discovery) {
        System.out.println(
            "Discovered "
                + discovery.supportedArtifacts().size()
                + " supported artifact(s) and "
                + discovery.unsupportedEntries().size()
                + " unsupported file(s) in "
                + path
                + "."
        );

        for (ArtifactInventoryEntry unsupportedEntry : discovery.unsupportedEntries()) {
            System.out.println("Skipped unsupported file: " + unsupportedEntry.displayName() + " (" + unsupportedEntry.detail() + ")");
        }
    }

    private static String noSupportedArtifactsMessage(Path path, ArtifactDiscoveryResult discovery) {
        List<String> skippedFiles = discovery.unsupportedEntries().stream()
            .map(ArtifactInventoryEntry::displayName)
            .limit(5)
            .toList();

        if (skippedFiles.isEmpty()) {
            return "No supported diagnostic artifacts found in directory: " + path;
        }

        String suffix = String.join(", ", skippedFiles);
        if (discovery.unsupportedEntries().size() > skippedFiles.size()) {
            suffix += ", ...";
        }

        return "No supported diagnostic artifacts found in directory: " + path + ". Skipped unsupported files: " + suffix;
    }

    private static void analyzeDiscoveredSingleArtifact(
        InputArtifact artifact,
        List<ArtifactInventoryEntry> artifactInventory
    ) {
        String description = artifact.type().description();
        String sourcePath = artifactSourcePath(artifact);

        System.out.println("Analyzing " + description + " from " + sourcePath + "...");
        loadedArtifact = artifact;
        latestAnalysisId = null;
        latestAnalysisReport = null;

        if (!RuntimeRoutingPolicy.supportsStructuredAnalysis(artifact)) {
            reportUnsupportedStructuredAnalysis(artifact);
            return;
        }

        try {
            var report = diagnosticAgentOrchestrator().analyze(artifact).withArtifactInventory(artifactInventory);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return;
            }
            System.out.println("\n======= JVM Troubleshooting Analysis ========\n\n" + userConsoleReportRenderer.render(report) + "\n");
            System.out.println("Saved report bundle: " + savedBundle);
            return;
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent analysis", e);
        }
    }

    private static void analyzeDiscoveredArtifactSet(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory
    ) {
        System.out.println("Correlating " + artifacts.size() + " supported artifact(s) from the directory support bundle...");
        loadedArtifact = null;
        latestAnalysisId = null;
        latestAnalysisReport = null;

        if (!RuntimeRoutingPolicy.supportsStructuredCorrelation(artifacts)) {
            reportUnsupportedStructuredCorrelation(artifacts);
            return;
        }

        try {
            var report = diagnosticAgentOrchestrator().correlate(artifacts).withArtifactInventory(artifactInventory);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return;
            }
            System.out.println("======= JVM Multi-Artifact Analysis ========\n\n" + userConsoleReportRenderer.render(report) + "\n");
            System.out.println("Saved report bundle: " + savedBundle + "\n");
            return;
        } catch (Exception e) {
            reportStructuredPathFailure("AI multi-artifact directory correlation", e);
        }
    }

    /**
     * Analyzes a single canonical input artifact.
     */
    private static void analyzeSingleArtifact(InputArtifact artifact) {
        System.out.println("Analyzing " + artifact.type().description() + " from " + artifactSourcePath(artifact) + "...");
        loadedArtifact = artifact;
        latestAnalysisId = null;
        latestAnalysisReport = null;

        if (!RuntimeRoutingPolicy.supportsStructuredAnalysis(artifact)) {
            reportUnsupportedStructuredAnalysis(artifact);
            return;
        }

        try {
            var report = diagnosticAgentOrchestrator().analyze(artifact);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return;
            }
            System.out.println("\n======= JVM Troubleshooting Analysis ========\n\n" + userConsoleReportRenderer.render(report) + "\n");
            System.out.println("Saved report bundle: " + savedBundle);
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent analysis", e);
        }
    }

    private static void runStructuredComparison(Path baselinePath, Path currentPath) {
        try {
            InputArtifact baselineArtifact = artifactLoader.load(baselinePath);
            InputArtifact currentArtifact = artifactLoader.load(currentPath);
            ArtifactType baselineType = baselineArtifact.type();
            ArtifactType currentType = currentArtifact.type();

            if (baselineType != currentType) {
                System.out.println(
                    "Error: Files must be of the same type for comparison. Baseline: "
                        + baselineType
                        + ", Current: "
                        + currentType
                );
                return;
            }

            if (!RuntimeRoutingPolicy.supportsStructuredComparison(baselineType) || !comparisonService.supports(baselineType)) {
                reportUnsupportedStructuredComparison(baselineType);
                return;
            }

            latestAnalysisId = null;
            latestAnalysisReport = null;
            var report = diagnosticAgentOrchestrator().compare(baselineArtifact, currentArtifact);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return;
            }
            loadedArtifact = null;
            System.out.println("======= JVM Comparison Analysis ========\n\n" + userConsoleReportRenderer.render(report) + "\n");
            System.out.println("Saved report bundle: " + savedBundle + "\n");
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent comparison", e);
        }
    }

    private static void reportUnsupportedStructuredAnalysis(InputArtifact artifact) {
        System.out.println(
            "Error: AI specialist-agent analysis is not available for "
                + artifact.type().description()
                + " from "
                + artifactSourcePath(artifact)
                + "."
        );
        if (artifact.type() == ArtifactType.UNKNOWN) {
            System.out.println("       If this file is a supported artifact, reload it with 'load <path> --type <type>' and try again.");
        } else {
            System.out.println("       This runtime does not fall back to ungrounded raw artifact chat.");
        }
        System.out.println("       Supported analysis types: GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL.");
    }

    private static void reportUnsupportedStructuredComparison(ArtifactType artifactType) {
        String typeDescription = artifactType != null ? artifactType.description() : "unknown";
        System.out.println("Error: AI specialist-agent comparison is not available for " + typeDescription + " artifacts.");
        System.out.println("       Supported comparison types: JFR, THREAD_DUMP, HEAP_HISTOGRAM, NMT, PMAP.");
    }

    private static void reportUnsupportedStructuredQuestioning(InputArtifact artifact) {
        System.out.println(
            "Error: Ask requires a saved AI agent-backed report or a loaded artifact that supports AI specialist-agent analysis."
        );
        if (artifact != null) {
            System.out.println(
                "       Current artifact: "
                    + artifactSourcePath(artifact)
                    + " ("
                    + artifact.type().description()
                    + ")"
            );
        }
        System.out.println("       Run 'analyze <file>' first, or use 'ask --analysis-id <id> <question>'.");
    }

    private static void reportUnsupportedStructuredCorrelation(List<InputArtifact> artifacts) {
        System.out.println("Error: Correlation requires at least two artifacts supported by the AI analysis pipeline.");
        if (artifacts != null && !artifacts.isEmpty()) {
            List<String> unsupportedInputs = artifacts.stream()
                .filter(artifact -> !RuntimeRoutingPolicy.supportsStructuredAnalysis(artifact))
                .map(artifact -> artifactSourcePath(artifact) + " (" + artifact.type().description() + ")")
                .toList();
            if (!unsupportedInputs.isEmpty()) {
                System.out.println("       Unsupported inputs: " + String.join(", ", unsupportedInputs));
            }
        }
        System.out.println("       Supported correlation types: GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL.");
    }

    private static void showSavedReport(String argument) throws Exception {
        String analysisId = latestAnalysisId;
        String format = "text";

        if (argument != null && !argument.isBlank()) {
            String[] parts = argument.split("\\s+");
            for (int index = 0; index < parts.length; index++) {
                if ("--format".equals(parts[index])) {
                    if (index + 1 >= parts.length) {
                        throw new Exception("Missing value for --format");
                    }
                    format = parts[index + 1];
                    index++;
                } else if (analysisId == null || analysisId.equals(latestAnalysisId)) {
                    analysisId = parts[index];
                }
            }
        }

        if (analysisId == null || analysisId.isBlank()) {
            throw new Exception("No saved analysis is available yet. Run 'analyze' first or provide an analysis ID.");
        }

        AnalysisReport report = loadSavedReport(analysisId);
        String rendered = isTextFormat(format)
            ? userConsoleReportRenderer.render(report)
            : reportBundleService.readReport(analysisId, format);
        latestAnalysisId = analysisId;
        latestAnalysisReport = report;
        System.out.println("======= Saved Report (" + format + ") =======\n");
        System.out.println(rendered);
        System.out.println();
    }

    private static void showCatalog(String argument) throws Exception {
        CatalogRequest request = parseCatalogRequest(argument);
        ReportCatalogResult catalog = reportBundleService.listCatalogEntries(request.severity(), request.artifactType());

        System.out.println("======= Saved Incident Catalog =======");
        System.out.println("Base directory: " + reportBundleService.baseDirectory());
        if (request.severity() != null || request.artifactType() != null) {
            System.out.println(
                "Filters: severity="
                    + (request.severity() != null ? request.severity() : "any")
                    + ", artifactType="
                    + (request.artifactType() != null ? request.artifactType() : "any")
            );
        }

        if (catalog.entries().isEmpty()) {
            System.out.println("No saved report bundles matched the current filters.");
        } else {
            for (ReportCatalogEntry entry : catalog.entries()) {
                StringJoiner artifactTypes = new StringJoiner(", ");
                entry.artifactTypes().forEach(type -> artifactTypes.add(type.name()));
                System.out.println(
                    "- "
                        + entry.analysisId()
                        + " | "
                        + entry.createdAt()
                        + " | "
                        + entry.overallSeverity()
                        + "/"
                        + entry.confidence()
                        + " | artifacts="
                        + (entry.artifactTypes().isEmpty() ? "(none)" : artifactTypes)
                        + " | correlation="
                        + (entry.hasCorrelationResult() ? "yes" : "no")
                        + " | redaction="
                        + entry.redactionProfile()
                );
            }
        }

        if (!catalog.skippedBundles().isEmpty()) {
            System.out.println("Skipped unreadable bundles:");
            for (String skippedBundle : catalog.skippedBundles()) {
                System.out.println("- " + skippedBundle);
            }
        }

        System.out.println();
    }




    /**
     * Asks a question about the latest saved analysis report context.
     */
    private static void askQuestion(String question, AnalysisReport report) {
        if (!canPresentUserFacingAnalysis(report)) {
            reportUnavailableQuestioningContext();
            return;
        }
        if (currentChatModel == null) {
            reportUnavailableQuestionAnswering();
            return;
        }

        String result;
        try {
            result = structuredReportQuestionAnswerer.answer(report, question, currentChatModel);
        } catch (RuntimeException exception) {
            reportUnavailableQuestionAnswering();
            return;
        }

        System.out.println("Question: " + question);
        System.out.println("Context: analysis report " + report.analysisId());
        System.out.println("======= Response =======\n\n" + result + "\n");
    }

    /**
     * Correlates multiple diagnostic artifacts.
     */
    private static void correlateArtifacts(List<InputArtifact> artifacts) {
        System.out.println("Correlating " + artifacts.size() + " diagnostic files...");
        latestAnalysisId = null;
        latestAnalysisReport = null;

        if (!RuntimeRoutingPolicy.supportsStructuredCorrelation(artifacts)) {
            reportUnsupportedStructuredCorrelation(artifacts);
            return;
        }

        try {
            var report = diagnosticAgentOrchestrator().correlate(artifacts);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return;
            }
            loadedArtifact = null;
            System.out.println("======= JVM Multi-Artifact Analysis ========\n\n" + userConsoleReportRenderer.render(report) + "\n");
            System.out.println("Saved report bundle: " + savedBundle + "\n");
            return;
        } catch (Exception e) {
            reportStructuredPathFailure("AI multi-artifact correlation", e);
        }
    }

    private static boolean isTextFormat(String format) {
        if (format == null || format.isBlank()) {
            return true;
        }
        return "text".equalsIgnoreCase(format) || "txt".equalsIgnoreCase(format);
    }

    private static AnalysisReport resolveStructuredQuestionContext() {
        if (canPresentUserFacingAnalysis(latestAnalysisReport)
            && RuntimeRoutingPolicy.reportMatchesLoadedData(latestAnalysisReport, loadedArtifact)) {
            return latestAnalysisReport;
        }
        if (loadedArtifact == null) {
            return tryLoadLatestSavedReport();
        }
        return null;
    }

    private static AnalysisReport ensureStructuredReportForQuestioning(InputArtifact artifact) {
        if (!RuntimeRoutingPolicy.supportsStructuredAnalysis(artifact)) {
            return null;
        }

        System.out.println("No saved analysis report is loaded for this file. Generating one now...");
        latestAnalysisId = null;
        latestAnalysisReport = null;
        try {
            var report = diagnosticAgentOrchestrator().analyze(artifact);
            Path savedBundle = saveAcceptedAgentReport(report);
            if (savedBundle == null) {
                return null;
            }
            System.out.println("Saved report bundle: " + savedBundle);
            return report;
        } catch (Exception e) {
            reportStructuredPathFailure("AI question context generation", e);
            return null;
        }
    }

    private static void reportStructuredPathFailure(String operation, Exception exception) {
        System.err.println("[Error] " + operation + " failed: " + exception.getMessage());
        System.err.println("[Error] The ungrounded raw prompt compatibility path is intentionally disabled.");
    }

    private static AskRequest parseAskRequest(String argument) throws Exception {
        if (argument == null || argument.isBlank()) {
            return new AskRequest(null, "");
        }
        if (!argument.startsWith("--analysis-id")) {
            return new AskRequest(null, argument.trim());
        }

        String[] parts = argument.split("\\s+", 3);
        if (parts.length < 3) {
            throw new Exception("Invalid ask syntax. Use: ask [--analysis-id <id>] <question>");
        }
        return new AskRequest(parts[1], parts[2].trim());
    }

    private static CatalogRequest parseCatalogRequest(String argument) throws Exception {
        if (argument == null || argument.isBlank()) {
            return new CatalogRequest(null, null);
        }

        com.javaassistant.diagnostics.SeverityLevel severity = null;
        ArtifactType artifactType = null;
        String[] parts = argument.trim().split("\\s+");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if ("--severity".equals(part)) {
                if (index + 1 >= parts.length) {
                    throw new Exception("Missing value for --severity");
                }
                try {
                    severity = com.javaassistant.diagnostics.SeverityLevel.valueOf(parts[++index].toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new Exception("Invalid severity level: " + parts[index]);
                }
            } else if ("--artifact-type".equals(part) || "--type".equals(part)) {
                if (index + 1 >= parts.length) {
                    throw new Exception("Missing value for " + part);
                }
                try {
                    artifactType = ArtifactType.valueOf(parts[++index].toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new Exception("Invalid artifact type: " + parts[index]);
                }
            } else {
                throw new Exception("Invalid catalog syntax. Use: catalog [--severity <level>] [--artifact-type <type>]");
            }
        }

        return new CatalogRequest(severity, artifactType);
    }

    private static LoadRequest parseLoadRequest(String argument) throws Exception {
        if (argument == null || argument.isBlank()) {
            throw new Exception("Invalid load syntax. Use: load <file> [--type <type>] or load --analysis-id <id>");
        }

        String[] parts = argument.split("\\s+");
        if ("--analysis-id".equals(parts[0])) {
            if (parts.length != 2) {
                throw new Exception("Invalid load syntax. Use: load --analysis-id <id>");
            }
            return new LoadRequest(null, parts[1], null);
        }

        if (parts.length == 1) {
            return new LoadRequest(parts[0], null, null);
        }

        if (parts.length == 3 && "--type".equals(parts[1])) {
            try {
                return new LoadRequest(parts[0], null, ArtifactType.fromExternalName(parts[2]));
            } catch (IllegalArgumentException e) {
                throw new Exception("Invalid artifact type: " + parts[2]);
            }
        }

        throw new Exception("Invalid load syntax. Use: load <file> [--type <type>] or load --analysis-id <id>");
    }

    private static AnalysisReport tryLoadLatestSavedReport() {
        if (latestAnalysisId == null || latestAnalysisId.isBlank()) {
            return null;
        }
        try {
            latestAnalysisReport = loadSavedReport(latestAnalysisId);
            return latestAnalysisReport;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AnalysisReport loadSavedReport(String analysisId) throws Exception {
        AnalysisReport report = reportBundleService.load(analysisId);
        if (!canPresentUserFacingAnalysis(report)) {
            throw new Exception(
                "Saved analysis "
                    + analysisId
                    + " does not contain AI agent-backed troubleshooting analysis. Re-run analyze with an available AI provider."
            );
        }
        latestAnalysisId = analysisId;
        latestAnalysisReport = report;
        return report;
    }

    private static AnalysisReport tryLoadStructuredContext(String target) throws Exception {
        if (reportBundleService.exists(target)) {
            return loadSavedReport(target);
        }

        Path path = Paths.get(target);
        if (Files.isDirectory(path) && Files.exists(path.resolve("report.json"))) {
            return loadSavedReport(path.getFileName().toString());
        }
        return null;
    }

    private static boolean canPresentUserFacingAnalysis(AnalysisReport report) {
        return report != null && report.hasAiAgentBackedUserNarrative();
    }

    private static Path saveAcceptedAgentReport(AnalysisReport report) throws Exception {
        if (!canPresentUserFacingAnalysis(report)) {
            reportUnavailableAgentAnalysis(report);
            return null;
        }
        Path savedBundle = reportBundleService.save(report);
        latestAnalysisId = report.analysisId();
        latestAnalysisReport = report;
        return savedBundle;
    }

    private static void reportUnavailableAgentAnalysis(AnalysisReport report) {
        System.out.println("AI agent analysis is unavailable right now. No troubleshooting analysis was shown or saved.");
        if (report != null && report.aiAgentAttempted()) {
            System.out.println("       The AI agent was attempted, but no agent-backed troubleshooting narrative passed the acceptance checks.");
        } else {
            System.out.println("       No AI agent-backed troubleshooting narrative was available for this run.");
        }
        System.out.println("       Verify provider/model availability and retry the command.");
    }

    private static void reportUnavailableQuestioningContext() {
        System.out.println("AI follow-up requires a saved AI agent-backed troubleshooting analysis.");
        System.out.println("       Run 'analyze' again when an AI provider is available, then retry 'ask'.");
    }

    private static void reportUnavailableQuestionAnswering() {
        System.out.println("AI follow-up assistance is unavailable right now. No answer was generated.");
        System.out.println("       Verify provider/model availability and retry the question.");
    }

    /**
     * Shows the current status
     */
    private static void showStatus(InputArtifact loadedArtifact) {
        System.out.println("Current Status:");
        System.out.println("  Application: " + ApplicationRuntimeSupport.applicationName() + " " + ApplicationRuntimeSupport.applicationVersion());
        System.out.println("  Java runtime: " + ApplicationRuntimeSupport.javaRuntimeDescription());
        System.out.println("  Provider: " + currentProvider);
        if (currentConfiguredChatModel != null) {
            System.out.println("  Model: " + currentConfiguredChatModel.modelName());
        }
        System.out.println("  Analysis mode: AI specialist-agent runtime");
        System.out.println("  Report bundle directory: " + reportBundleService.baseDirectory());
        if (latestAnalysisReport != null) {
            System.out.println("  Report context: " + latestAnalysisReport.analysisId());
        } else if (loadedArtifact != null) {
            System.out.println(
                "  " + artifactSourcePath(loadedArtifact)
                    + " ("
                    + loadedArtifact.type().description()
                    + ", "
                    + artifactContentLength(loadedArtifact)
                    + " chars)"
            );
        } else {
            System.out.println("  Loaded context: (none)");
        }
        if (latestAnalysisId != null) {
            System.out.println("  Latest analysis: " + latestAnalysisId);
        }
    }

    private static void printVersion() {
        System.out.println("Application Version:");
        System.out.println("  Name: " + ApplicationRuntimeSupport.applicationName());
        System.out.println("  Version: " + ApplicationRuntimeSupport.applicationVersion());
        System.out.println("  Report schema version: " + AnalysisReport.CURRENT_SCHEMA_VERSION);
        System.out.println("  Java runtime: " + ApplicationRuntimeSupport.javaRuntimeDescription());
        System.out.println("  Provider: " + currentProvider);
        if (currentConfiguredChatModel != null) {
            System.out.println("  Model: " + currentConfiguredChatModel.modelName());
        }
        System.out.println("  Report bundle directory: " + reportBundleService.baseDirectory());
        System.out.println("  Report directory overrides:");
        System.out.println("    -D" + ApplicationRuntimeSupport.REPORT_DIRECTORY_SYSTEM_PROPERTY + "=<path>");
        System.out.println("    " + ApplicationRuntimeSupport.REPORT_DIRECTORY_ENV_VAR + "=<path>");
    }

    /**
     * Prints the help information
     */
    private static void printHelp() {
        System.out.println("JVM Troubleshooting Agentic Assistant Commands:");
        System.out.println("================================================");
        System.out.println("load <file-or-dir> - Load a single diagnostic artifact file or a raw container-memory directory");
        System.out.println("load --analysis-id <id> - Load a saved analysis report bundle as active context");
        System.out.println("                 Supported: GC logs, JFR recordings, thread dumps, hs_err logs, NMT memory, heap histograms, pmap output, container memory snapshots, kernel OOM and restart-signal logs");
        System.out.println("                 Raw cgroup directories containing memory.current, memory.events, and related files are synthesized into container-memory artifacts.");
        System.out.println("analyze [<file-or-dir>] - Analyze the loaded diagnostic artifact or specified file/directory with AI specialist agents");
        System.out.println("report [<analysis-id>] [--format <text|json|markdown|html>] - Show a saved analysis report");
        System.out.println("catalog [--severity <level>] [--artifact-type <type>] - List saved report bundles");
        System.out.println("compare <file1> <file2> - Compare two files of the same type through AI specialist-agent analysis");
        System.out.println("correlate <file1> <file2> ... - Correlate multiple files of different types through AI multi-agent synthesis");
        System.out.println("ask [--analysis-id <id>] <question> - Ask a question about the loaded data or a saved analysis report");
        System.out.println("                 Unsupported artifact families do not fall back to ungrounded raw prompt chat.");
        System.out.println("                 Example: ask What are the main performance issues?");
        System.out.println("config set provider <oci|ollama> - Switch AI model provider");
        System.out.println("version         - Show application version, runtime, provider, and report-bundle location");
        System.out.println("status          - Show current application status");
        System.out.println("help            - Show this help information");
        System.out.println("quit            - Exit the application");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  load sample-gc.log");
        System.out.println("  load recording.jfr");
        System.out.println("  load /sys/fs/cgroup --type CONTAINER_MEMORY");
        System.out.println("  load --analysis-id 20260328120000-sample.log");
        System.out.println("  load unknown-file.txt --type GC_LOG");
        System.out.println("  analyze");
        System.out.println("  analyze recording.jfr");
        System.out.println("  analyze sample-hs_err.log");
        System.out.println("  analyze samples/container_memory_pressure_snapshot.txt");
        System.out.println("  analyze samples/kernel_oom_kill.log");
        System.out.println("  analyze samples/pod_oomkilled_describe.txt");
        System.out.println("  report --format json");
        System.out.println("  report 20260328120000-sample.log --format markdown");
        System.out.println("  catalog");
        System.out.println("  catalog --severity HIGH --artifact-type THREAD_DUMP");
        System.out.println("  compare baseline.nmt current.nmt");
        System.out.println("  correlate gc.log nmt.txt pmap.txt");
        System.out.println("  config set provider ollama");
        System.out.println("  version");
        System.out.println("  ask What memory issues do you see?");
        System.out.println("  ask --analysis-id 20260328120000-sample.log What changed between snapshots?");
        System.out.println("  ask How can I optimize garbage collection?");
    }




}
