package com.example;

import com.example.agents.*;
import com.example.ai.StructuredReportSummarizer;
import com.example.data.*;
import com.example.detect.ArtifactClassifier;
import com.example.ingest.ArtifactLoader;
import com.example.parse.ArtifactParsingService;
import com.example.parse.GcLogArtifactParser;
import com.example.parse.HeapHistogramArtifactParser;
import com.example.parse.HsErrArtifactParser;
import com.example.parse.NmtArtifactParser;
import com.example.parse.PmapArtifactParser;
import com.example.pipeline.SingleArtifactAnalysisPipeline;
import com.example.render.ConsoleReportRenderer;
import com.example.report.AnalysisReportAssembler;
import com.example.report.ReportBundleService;
import com.example.modelproviders.OCIChatModelProvider;
import com.example.modelproviders.OllamaChatModelProvider;
import com.example.rules.ArtifactRuleEngineService;
import com.example.rules.GcLogArtifactRuleEngine;
import com.example.rules.HeapHistogramArtifactRuleEngine;
import com.example.rules.HsErrArtifactRuleEngine;
import com.example.rules.NmtArtifactRuleEngine;
import com.example.rules.PmapArtifactRuleEngine;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.model.chat.ChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Main command-line application for JVM troubleshooting
 */
public class JVMTroubleshooter {

    private record ParsedCommand(String command, String argument) { }

    public enum Provider {
        OCI, OLLAMA
    }

    private static Provider currentProvider;
    private static ChatModel currentChatModel;
    private static DiagnosticData loadedData = null;
    private static SupervisorAgent troubleshootingAgent;
    private static final ArtifactLoader artifactLoader = new ArtifactLoader(new ArtifactClassifier());
    private static final ReportBundleService reportBundleService = new ReportBundleService(Path.of("target", "analysis-reports"));
    private static String latestAnalysisId;
    private static final ArtifactParsingService parsingService = new ArtifactParsingService(List.of(
        new GcLogArtifactParser(),
        new HsErrArtifactParser(),
        new NmtArtifactParser(),
        new HeapHistogramArtifactParser(),
        new PmapArtifactParser()
    ));
    private static final ArtifactRuleEngineService ruleEngineService = new ArtifactRuleEngineService(List.of(
        new GcLogArtifactRuleEngine(),
        new HsErrArtifactRuleEngine(),
        new NmtArtifactRuleEngine(),
        new HeapHistogramArtifactRuleEngine(),
        new PmapArtifactRuleEngine()
    ));
    private static final SingleArtifactAnalysisPipeline singleArtifactAnalysisPipeline = new SingleArtifactAnalysisPipeline(
        parsingService,
        ruleEngineService,
        new AnalysisReportAssembler(),
        new StructuredReportSummarizer(),
        new ConsoleReportRenderer()
    );

    // Build sub-agents for specialized tasks - now created via factory method
    private static GCLogAgent gcLogAgent;
    private static HSErrLogAgent hsErrLogAgent;
    private static NMTAgent nmtAgent;
    private static HeapHistogramAgent heapHistogramAgent;
    private static CorrelationAgent correlationAgent;
    private static PmapAgent pmapAgent;

    static {
        initializeDefaultProvider();
        createAgents();
    }

    private static void initializeDefaultProvider() {
        try {
            currentChatModel = OCIChatModelProvider.createChatModel();
            currentProvider = Provider.OCI;
        } catch (RuntimeException e) {
            System.err.println("[Warning] Failed to initialize default OCI provider: " + e.getMessage());
            System.err.println("[Warning] Falling back to OLLAMA provider. Fix OCI configuration and run 'config set provider oci' to retry.");
            currentChatModel = OllamaChatModelProvider.createChatModel();
            currentProvider = Provider.OLLAMA;
        }
    }

    private static SupervisorAgent createTroubleshootingAgent() {
        // Build the Supervisor agent using the existing agent instances
        troubleshootingAgent = AgenticServices.supervisorBuilder()
                .chatModel(currentChatModel)
                .subAgents(gcLogAgent, hsErrLogAgent, nmtAgent, heapHistogramAgent, pmapAgent)
                .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .supervisorContext("""
                    You are the JVM troubleshooting supervisor.

                    1. For every diagnostic request: determine its data type, then immediately call the matching specialist agent to perform the primary analysis before drafting any response yourself.
                    2. Required routing (inspect file names, headers, and signature markers):
                       - GC logs (gc.log, unified logging, pause summaries) -> GC log specialist
                       - Crash logs / hs_err_pid crash reports -> HotSpot crash specialist
                       - Native Memory Tracking (NMT) snapshots/diffs -> NMT specialist
                       - Heap histograms (jmap -histo, jcmd GC.class_histogram) -> Heap histogram specialist
                       - PMAP / process memory maps -> PMAP specialist
                    3. After the domain expert responds, do not call the same specialist again for the current request. Instead, optionally ask follow-up questions yourself if gaps remain, then synthesize a concise supervisor summary that cites the agent’s findings and next steps.
                    4. When correlating multiple files, orchestrate each appropriate specialist, reconcile conflicting signals, and produce a unified set of prioritized recommendations.
                    5. Final answers must be plain English (no JSON).
                    """)
                .maxAgentsInvocations(2)
                .build();

        return troubleshootingAgent;
    }

    private static void createAgents() {
        gcLogAgent = AgenticServices.agentBuilder(GCLogAgent.class)
                .chatModel(currentChatModel)
                // .tools(new GCTools())
                .build();

        hsErrLogAgent = AgenticServices.agentBuilder(HSErrLogAgent.class)
                .chatModel(currentChatModel)
                .build();

        nmtAgent = AgenticServices.agentBuilder(NMTAgent.class)
                .chatModel(currentChatModel)
                .tools(new NMTTools())
                .build();

        heapHistogramAgent = AgenticServices.agentBuilder(HeapHistogramAgent.class)
                .chatModel(currentChatModel)
                .tools(new HeapHistogramTools())
                .build();

        correlationAgent = AgenticServices.agentBuilder(CorrelationAgent.class)
                .chatModel(currentChatModel)
                .build();

        pmapAgent = AgenticServices.agentBuilder(PmapAgent.class)
                .chatModel(currentChatModel)
                .tools(new PmapTools())
                .build();

        // create supervisor agent with new sub-agents
        troubleshootingAgent = createTroubleshootingAgent();
    }

    private static void recreateAgents() {
        createAgents();
    }

    private static void switchProvider(Provider newProvider) {
        if (currentProvider == newProvider) {
            System.out.println("Provider already set to: " + newProvider);
            return;
        }

        try {
            ChatModel newChatModel = (newProvider == Provider.OCI)
                    ? OCIChatModelProvider.createChatModel()
                    : OllamaChatModelProvider.createChatModel();

            currentProvider = newProvider;
            currentChatModel = newChatModel;
            recreateAgents();
            System.out.println("Successfully switched to " + newProvider + " provider");
        } catch (Exception e) {
            System.err.println("[Error] Unable to switch provider to " + newProvider + ": " + e.getMessage());
            System.err.println("          Please verify your configuration and try again.");
        }
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

        String command = tokens.getFirst();
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
        System.out.println("  load <file> [--type <type>] - Load a single diagnostic data file");
        System.out.println("  analyze [<file-or-dir>] - Analyze loaded diagnostic data or specified file/directory");
        System.out.println("  report [<analysis-id>] [--format <text|json|markdown|html>] - Show a saved report");
        System.out.println("  compare <file1> <file2> - Compare two data files for  analysis");
        System.out.println("  correlate <files...> - Correlate multiple diagnostic files across types");
        System.out.println("  ask <question>      - Ask a question about the loaded data");
        System.out.println("  config set provider <oci|ollama> - Switch AI model provider");
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
                            System.out.println("Error: Please specify one file path to load");
                        } else {
                            String[] argParts = argument.split("\\s+");
                            String file = argParts[0].trim();
                            DataType overrideType = null;
                            if (argParts.length > 2 && "--type".equals(argParts[1])) {
                                try {
                                    overrideType = DataType.valueOf(argParts[2].toUpperCase());
                                } catch (IllegalArgumentException e) {
                                    System.out.println("Error: Invalid data type: " + argParts[2]);
                                    break;
                                }
                            } else if (argParts.length > 1) {
                                System.out.println("Error: Invalid syntax. Use: load <file> [--type <type>]");
                                break;
                            }
                            try {
                                loadedData = loadDiagnosticData(file, overrideType, scanner);
                                System.out.println("Loaded file: " + loadedData.sourceFile() + " (" + loadedData.type().description() + ", " + loadedData.getContentSize() + " chars)");
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                            }
                        }
                        break;

                    case "analyze":
                        DiagnosticData dataToAnalyze;
                        if (!argument.isEmpty()) {
                            try {
                                dataToAnalyze = loadAnalyzeTarget(argument.trim());
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                                break;
                            }
                            if (dataToAnalyze == null) {
                                break;
                            }
                        } else if (loadedData != null) {
                            dataToAnalyze = loadedData;
                        } else {
                            System.out.println("Error: No diagnostic file loaded. Use 'load <file>' first or specify a single file with 'analyze <file>'.");
                            break;
                        }
                        analyzeSingleDiagnosticData(dataToAnalyze);
                        break;

                    case "compare":
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
                            String baselineContent = Files.readString(baselinePath);
                            String currentContent = Files.readString(currentPath);
                            DataType baselineType = DataType.fromContents(baselineContent);
                            DataType currentType = DataType.fromContents(currentContent);
                            if (baselineType != currentType) {
                                System.out.println("Error: Files must be of the same type for comparison. Baseline: " + baselineType + ", Current: " + currentType);
                                break;
                            }
                            String marker = baselineType == DataType.HEAP_HISTOGRAM ? "=== CURRENT HISTOGRAM ===" :
                                           (baselineType == DataType.PMAP_OUTPUT ? "=== COMPARISON PMAP ===" : "=== CURRENT DATA ===");
                            String combinedContent = baselineContent + "\n" + marker + "\n" + currentContent;
                            DiagnosticData comparisonData = new DiagnosticData(baselineType, combinedContent,
                                                                             "comparison: " + baselineFile + " vs " + currentFile);
                            analyzeSingleDiagnosticData(comparisonData);
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

                    case "correlate":
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
                            List<DiagnosticData> dataList = new ArrayList<>();
                            for (String file : correlateFiles) {
                                dataList.add(loadDiagnosticData(file.trim()));
                            }
                            correlateDiagnosticData(dataList);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                        break;

                    case "ask":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a question to ask");
                        } else if (loadedData == null) {
                            System.out.println("Error: No diagnostic data loaded. Use 'load <file>' first.");
                        } else {
                            askQuestion(argument, loadedData);
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
                        showStatus(loadedData);
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
     * Loads diagnostic data from the specified file path
     */
    private static DiagnosticData loadDiagnosticData(String filePath) throws Exception {
        return loadDiagnosticData(filePath, null, null);
    }

    /**
     * Loads diagnostic data from the specified file path with optional type override
     */
    private static DiagnosticData loadDiagnosticData(String filePath, DataType overrideType, Scanner scanner) throws Exception {
        Path path = Paths.get(filePath);
        DiagnosticData diagnosticData = DiagnosticData.fromInputArtifact(
            artifactLoader.load(path, overrideType != null ? overrideType.toArtifactType() : null)
        );
        DataType dataType = diagnosticData.type();

        if (dataType == DataType.UNKNOWN && scanner != null) {
            System.out.println("Unable to automatically detect data type for file: " + filePath);
            System.out.println("Supported types: GC_LOG, HS_ERR_LOG, NMT_MEMORY, HEAP_HISTOGRAM, PMAP_OUTPUT");
            System.out.print("Please specify the data type: ");
            String typeInput = scanner.nextLine().trim().toUpperCase();
            try {
                dataType = DataType.valueOf(typeInput);
                if (dataType == DataType.UNKNOWN) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new Exception("Invalid data type specified: " + typeInput);
            }
            diagnosticData = DiagnosticData.fromInputArtifact(
                artifactLoader.load(path, dataType.toArtifactType())
            );
        }

        emitTruncationWarning(diagnosticData);
        return diagnosticData;
    }

    private static void emitTruncationWarning(DiagnosticData diagnosticData) {
        Object truncated = diagnosticData.getMetadata("truncated");
        Object originalLength = diagnosticData.getMetadata("originalContentLength");
        if ("true".equals(String.valueOf(truncated)) && originalLength != null) {
            int estimatedTokens = Integer.parseInt(String.valueOf(originalLength)) / 4;
            System.out.println("Warning: Large file detected (" + String.format("%,d", estimatedTokens) + " tokens). Content truncated to first and last portions for analysis.");
        }
    }

    private static DiagnosticData loadAnalyzeTarget(String target) throws Exception {
        Path path = Paths.get(target);
        if (!Files.exists(path)) {
            throw new Exception("File not found: " + target);
        }

        if (Files.isDirectory(path)) {
            List<DiagnosticData> discoveredData = artifactLoader.discover(path).stream()
                .map(DiagnosticData::fromInputArtifact)
                .toList();

            if (discoveredData.isEmpty()) {
                throw new Exception("No supported diagnostic artifacts found in directory: " + target);
            }

            System.out.println("Discovered " + discoveredData.size() + " supported artifact(s) in " + target);
            discoveredData.forEach(JVMTroubleshooter::emitTruncationWarning);

            if (discoveredData.size() == 1) {
                return discoveredData.getFirst();
            }

            correlateDiagnosticData(discoveredData);
            return null;
        }

        return loadDiagnosticData(target);
    }

    /**
     * Analyzes a single diagnostic data item
     */
    private static void analyzeSingleDiagnosticData(DiagnosticData diagnosticData) {
        System.out.println("Analyzing " + diagnosticData.type().description() + " from " + diagnosticData.sourceFile() + "...");

        if (shouldUseStructuredPipeline(diagnosticData)) {
            try {
                var report = singleArtifactAnalysisPipeline.analyze(diagnosticData.toInputArtifact(), currentChatModel);
                Path savedBundle = reportBundleService.save(report);
                latestAnalysisId = report.analysisId();
                System.out.println("\n======= Structured Analysis ========\n\n" + singleArtifactAnalysisPipeline.render(report) + "\n");
                System.out.println("Saved report bundle: " + savedBundle);
                return;
            } catch (RuntimeException e) {
                System.err.println("[Warning] Structured analysis failed, falling back to legacy agent flow: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[Warning] Structured report persistence failed, falling back to legacy agent flow: " + e.getMessage());
            }
        }

        String request = "Analyze the following " + diagnosticData.type().description() +
                    " and provide a summary of findings and recommendations.\n\n";
        request +=  "Content: \n" + diagnosticData.content() + "\n\n\n\n";

        String result;

        try {
            // SupervisorAgent routing
            // result = troubleshootingAgent.invoke(request);

            // Direct agent invocation based on data type
            switch (diagnosticData.type()) {
                case GC_LOG -> result = gcLogAgent.analyze(request);
                case HS_ERR_LOG -> result = hsErrLogAgent.analyze(request);
                case NMT_MEMORY -> result = nmtAgent.analyze(request);
                case HEAP_HISTOGRAM -> result = heapHistogramAgent.analyze(request);
                case PMAP_OUTPUT -> result = pmapAgent.analyze(request);
                default -> result = "Unsupported data type for analysis: " + diagnosticData.type().description();
            }
        } catch (RuntimeException e) {
            System.err.println("[Error] Unable to analyze diagnostic data: " + e.getMessage());
            return;
        }

        System.out.println("\n======= Analysis ======== \n\n" + result + "\n");
    }

    private static boolean shouldUseStructuredPipeline(DiagnosticData diagnosticData) {
        if (diagnosticData == null || diagnosticData.type() == null) {
            return false;
        }

        if (diagnosticData.sourceFile() != null && diagnosticData.sourceFile().startsWith("comparison:")) {
            return false;
        }

        return switch (diagnosticData.type()) {
            case GC_LOG, HS_ERR_LOG, NMT_MEMORY, HEAP_HISTOGRAM, PMAP_OUTPUT -> true;
            default -> false;
        };
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

        String rendered = reportBundleService.readReport(analysisId, format);
        System.out.println("======= Saved Report (" + format + ") =======\n");
        System.out.println(rendered);
        System.out.println();
    }




    /**
     * Asks a question about the loaded diagnostic data
     */
    private static void askQuestion(String question, DiagnosticData diagnosticData) {
        System.out.println("Question: " + question);
        System.out.println("Context: " + diagnosticData.type().description() + " from " + diagnosticData.sourceFile());

        String request = "Answer only the following question about this " + diagnosticData.type().description() + " using any prior conversation context maintained in your chat memory:\n";
        request += "Question: " + question + "\n\n" +
                   "Log content:\n" + diagnosticData.content() + "\n\n" +
                   "Do not show the entire analysis. Be concise and to the point.\n";

        String result;

        try {
            // result = troubleshootingAgent.invoke(request);

            // Direct agent invocation based on data type
            switch (diagnosticData.type()) {
                case GC_LOG -> result = gcLogAgent.analyze(request);
                case HS_ERR_LOG -> result = hsErrLogAgent.analyze(request);
                case NMT_MEMORY -> result = nmtAgent.analyze(request);
                case HEAP_HISTOGRAM -> result = heapHistogramAgent.analyze(request);
                default -> result = "Unsupported data type for questions: " + diagnosticData.type().description();
            }
        } catch (RuntimeException e) {
            System.err.println("[Error] Unable to answer question: " + e.getMessage());
            return;
        }

        System.out.println("======= Response =======\n\n" + result + "\n");
    }

    /**
     * Correlates multiple diagnostic data items
     */
    private static void correlateDiagnosticData(List<DiagnosticData> dataList) {
        System.out.println("Correlating " + dataList.size() + " diagnostic files...");

        StringBuilder combined = new StringBuilder();
        for (DiagnosticData data : dataList) {
            combined.append("=== FILE: ").append(data.sourceFile()).append(" TYPE: ").append(data.type().description()).append(" ===\n");
            combined.append(data.content()).append("\n\n");
        }

        String correlationRequest = """
            Correlate the following diagnostic files across different JVM diagnostic data types.
            Analyze each file according to its type and provide findings.
            Then synthesize a unified view across all files, identifying relationships, patterns, and prioritized recommendations.
            Return a single consolidated response.

            FILES:
            """ + combined;

        String result;
        try {
            // String result = troubleshootingAgent.invoke(correlationRequest);

            // Direct correlation agent invocation
            result = correlationAgent.analyze(correlationRequest);
        } catch (RuntimeException e) {
            System.err.println("[Error] Unable to correlate diagnostic data: " + e.getMessage());
            return;
        }

        System.out.println("======= Correlation Analysis ========\n\n" + result + "\n");
    }

    /**
     * Shows the current status
     */
    private static void showStatus(DiagnosticData loadedData) {
        System.out.println("Current Status:");
        System.out.println("  Provider: " + currentProvider);
        if (loadedData != null) {
            System.out.println("  " + loadedData.sourceFile() + " (" + loadedData.type().description() + ", " + loadedData.getContentSize() + " chars)");
        } else {
            System.out.println("  Loaded data: (none)");
        }
        if (latestAnalysisId != null) {
            System.out.println("  Latest analysis: " + latestAnalysisId);
        }
    }

    /**
     * Prints the help information
     */
    private static void printHelp() {
        System.out.println("JVM Troubleshooting Agentic Assistant Commands:");
        System.out.println("================================================");
        System.out.println("load <file>     - Load a single diagnostic data file");
        System.out.println("                 Supported: GC logs, hs_err logs, NMT memory, heap histograms, pmap output");
        System.out.println("analyze [<file-or-dir>] - Analyze the loaded diagnostic data or specified file/directory");
        System.out.println("report [<analysis-id>] [--format <text|json|markdown|html>] - Show a saved structured report");
        System.out.println("compare <file1> <file2> - Compare two files of the same type for analysis");
        System.out.println("correlate <file1> <file2> ... - Correlate multiple files of different types for integrated analysis");
        System.out.println("ask <question>  - Ask a question about the loaded data");
        System.out.println("                 Example: ask What are the main performance issues?");
        System.out.println("config set provider <oci|ollama> - Switch AI model provider");
        System.out.println("status          - Show current application status");
        System.out.println("help            - Show this help information");
        System.out.println("quit            - Exit the application");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  load sample-gc.log");
        System.out.println("  load unknown-file.txt --type GC_LOG");
        System.out.println("  analyze");
        System.out.println("  analyze sample-hs_err.log");
        System.out.println("  report --format json");
        System.out.println("  report 20260328120000-sample.log --format markdown");
        System.out.println("  compare baseline.nmt current.nmt");
        System.out.println("  correlate gc.log nmt.txt pmap.txt");
        System.out.println("  config set provider ollama");
        System.out.println("  ask What memory issues do you see?");
        System.out.println("  ask How can I optimize garbage collection?");
    }




}
