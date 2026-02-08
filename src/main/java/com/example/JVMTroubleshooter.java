package com.example;

import com.example.agents.GCLogAgent;
import com.example.agents.HSErrLogAgent;
import com.example.agents.NMTAgent;
import com.example.agents.NMTTools;
import com.example.agents.HeapHistogramAgent;
import com.example.agents.HeapHistogramTools;
import com.example.agents.CorrelationAgent;
import com.example.agents.CorrelationTools;
import com.example.agents.PmapAgent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.model.chat.ChatModel;

import com.example.data.DataType;
import com.example.data.DiagnosticData;
import com.example.modelproviders.OCIChatModelProvider;
import com.example.modelproviders.OllamaChatModelProvider;

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

    /**
     * Simple class to hold parsed command and arguments
     */
    private static class ParsedCommand {
        final String command;
        final String argument;

        ParsedCommand(String command, String argument) {
            this.command = command;
            this.argument = argument;
        }
    }

    public enum Provider {
        OCI, OLLAMA
    }

    private static Provider currentProvider = Provider.OLLAMA;
    private static ChatModel currentChatModel = OllamaChatModelProvider.createChatModel();
    private static DiagnosticData loadedData = null;
    private static final List<String> conversationHistory = new ArrayList<>();
    private static SupervisorAgent troubleshootingAgent;

    // Build sub-agents for specialized tasks - now created via factory method
    private static GCLogAgent gcLogAgent;
    private static HSErrLogAgent hsErrLogAgent;
    private static NMTAgent nmtAgent;
    private static HeapHistogramAgent heapHistogramAgent;
    private static CorrelationAgent correlationAgent;
    private static PmapAgent pmapAgent;

    static {
        createAgents();
    }

    private static SupervisorAgent createTroubleshootingAgent() {
        // Build the Supervisor agent using the existing agent instances
        SupervisorAgent troubleshootingAgent = AgenticServices.supervisorBuilder()
                .chatModel(currentChatModel)
                .subAgents(gcLogAgent, hsErrLogAgent, nmtAgent, heapHistogramAgent, pmapAgent)
                .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .supervisorContext("""
                    You are a JVM troubleshooting supervisor. You have access to specialized agents for different types of JVM diagnostic data.

                    For analysis requests:
                    - Route GC log analysis to the appropriate agent
                    - Route crash log analysis to the appropriate agent
                    - Route NMT memory analysis to the appropriate agent
                    - Route heap histogram analysis to the appropriate agent
                    - Route PMAP analysis to the appropriate agent

                    For correlation requests:
                    - Analyze each file with the appropriate domain agent
                    - Synthesize findings across all files
                    - Provide unified insights and recommendations

                    Always provide clear, actionable analysis in plain English.
                    """)
                .maxAgentsInvocations(3)
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
                .tools(new CorrelationTools())
                .build();

        pmapAgent = AgenticServices.agentBuilder(PmapAgent.class)
                .chatModel(currentChatModel)
                // .tools(new PmapTools())
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
            currentProvider = newProvider;
            if (newProvider == Provider.OCI) {
                currentChatModel = OCIChatModelProvider.createChatModel();
            } else {
                currentChatModel = OllamaChatModelProvider.createChatModel();
            }
            recreateAgents();
            System.out.println("Successfully switched to " + newProvider + " provider");
        } catch (Exception e) {
            System.err.println("Error switching provider: " + e.getMessage());
            // Revert on failure
            currentProvider = (newProvider == Provider.OCI) ? Provider.OLLAMA : Provider.OCI;
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
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
            } else {
                // Regular character
                currentToken.append(c);
            }
        }

        // Add final token if any
        if (currentToken.length() > 0) {
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
        System.out.println("  load <file> [--type <type>] - Load a single diagnostic data file");
        System.out.println("  analyze [<file>]     - Analyze loaded diagnostic data or specified single file");
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
                                conversationHistory.clear();
                                System.out.println("Loaded file: " + loadedData.sourceFile() + " (" + loadedData.type().description() + ", " + loadedData.getContentSize() + " chars)");
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                            }
                        }
                        break;

                    case "analyze":
                        DiagnosticData dataToAnalyze = null;
                        if (!argument.isEmpty()) {
                            // Analyze specific single file provided as argument
                            String[] files = argument.split("\\s+");
                            if (files.length > 1) {
                                System.out.println("Error: Analyze command accepts only one log file.");
                                break;
                            }
                            String file = files[0].trim();
                            try {
                                dataToAnalyze = loadDiagnosticData(file);
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
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
                System.err.println("Error processing command: " + e.getMessage());
                e.printStackTrace();
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
     * Truncates content to prevent context window overflow (limit to ~50,000 tokens)
     */
    private static String truncateContentForContextWindow(String content, String filePath) {
        final int MAX_TOKENS = 50000;
        final int CHARS_PER_TOKEN_ESTIMATE = 4; // Rough estimate: 4 characters per token

        // Estimate token count
        int estimatedTokens = content.length() / CHARS_PER_TOKEN_ESTIMATE;

        if (estimatedTokens <= MAX_TOKENS) {
            return content; // No truncation needed
        }

        // Calculate truncation points to keep beginning and end
        int charsToKeep = MAX_TOKENS * CHARS_PER_TOKEN_ESTIMATE;
        int keepAtStart = charsToKeep / 3; // 1/3 at start
        int keepAtEnd = charsToKeep / 3;   // 1/3 at end
        int truncatePoint = content.length() - keepAtEnd;

        if (truncatePoint <= keepAtStart) {
            // File is too small to truncate meaningfully, keep first part
            String truncated = content.substring(0, Math.min(charsToKeep, content.length()));
            return truncated + "\n\n[CONTENT TRUNCATED - File too large for full analysis]";
        }

        // Keep beginning and end, truncate middle
        String startPart = content.substring(0, keepAtStart);
        String endPart = content.substring(truncatePoint);

        String truncatedContent = startPart +
            "\n\n[... CONTENT TRUNCATED DUE TO SIZE - " +
            String.format("%,d", estimatedTokens) + " estimated tokens, showing first and last portions only ...]\n\n" +
            endPart;

        System.out.println("Warning: Large file detected (" + String.format("%,d", estimatedTokens) + " tokens). Content truncated to first and last portions for analysis.");
        return truncatedContent;
    }

    /**
     * Loads diagnostic data from the specified file path with optional type override
     */
    private static DiagnosticData loadDiagnosticData(String filePath, DataType overrideType, Scanner scanner) throws Exception {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new Exception("File not found: " + filePath);
        }

        String content = Files.readString(path);

        // Truncate large files to prevent context window overflow (limit to ~50,000 tokens)
        content = truncateContentForContextWindow(content, filePath);

        DataType dataType = overrideType != null ? overrideType : DataType.fromContents(content);

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
        }

        DiagnosticData diagnosticData = new DiagnosticData(dataType, content, filePath);

        return diagnosticData;
    }

    /**
     * Analyzes a single diagnostic data item
     */
    private static void analyzeSingleDiagnosticData(DiagnosticData diagnosticData) {
        System.out.println("Analyzing " + diagnosticData.type().description() + " from " + diagnosticData.sourceFile() + "...");

        String request = "Analyze the following " + diagnosticData.type().description() +
                    " and provide a summary of findings and recommendations.\n\n";
        request +=  "Content: \n" + diagnosticData.content() + "\n\n\n\n";

        String result = "";

        // SupervisorAgent routing (commented out - using direct agent calls instead):
        // result = (String) troubleshootingAgent.invoke(request);

        // Direct agent invocation based on data type
        if (diagnosticData.type() == DataType.GC_LOG) {
            result = gcLogAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.HS_ERR_LOG) {
            result = hsErrLogAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.NMT_MEMORY) {
            result = nmtAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.HEAP_HISTOGRAM) {
            result = heapHistogramAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.PMAP_OUTPUT) {
            result = pmapAgent.analyze(request);
        } else {
            result = "Unsupported data type for analysis: " + diagnosticData.type().description();
        }

        System.out.println("======= Analysis complete. ========");
        System.out.println(result);
    }




    /**
     * Asks a question about the loaded diagnostic data
     */
    private static void askQuestion(String question, DiagnosticData diagnosticData) {
        System.out.println("Question: " + question);
        System.out.println("Context: " + diagnosticData.type().description() + " from " + diagnosticData.sourceFile());

        // Build conversation history for prompt
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = 0; i < conversationHistory.size(); i += 2) {
            if (i + 1 < conversationHistory.size()) {
                historyBuilder.append(conversationHistory.get(i)).append("\n");
                historyBuilder.append(conversationHistory.get(i + 1)).append("\n");
            }
        }

        String request = "Answer only the following question about this " + diagnosticData.type().description() + " based on the conversation history and log:\n";
        if (historyBuilder.length() > 0) {
            request += "Conversation history:\n" + historyBuilder.toString() + "\n";
        }
        request += "Question: " + question + "\n\n" +
                   "Log content:\n" + diagnosticData.content() + "\n\n" +
                   "Do not show the entire analysis. Be concise and to the point.\n";

        String result = "";
        /*
        SupervisorAgent routing for Q&A (commented out - using direct agent calls instead):
        result = (String) troubleshootingAgent.invoke(request);
        */
        // Direct agent invocation based on data type
        if (diagnosticData.type() == DataType.GC_LOG) {
            result = gcLogAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.HS_ERR_LOG) {
            result = hsErrLogAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.NMT_MEMORY) {
            result = nmtAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.HEAP_HISTOGRAM) {
            result = heapHistogramAgent.analyze(request);
        } else {
            result = "Unsupported data type for questions: " + diagnosticData.type().description();
        }

        // Append to history
        conversationHistory.add("User: " + question);
        conversationHistory.add("Assistant: " + result);

        System.out.println("======= Response =======\n" + result);
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
            """ + combined.toString();
        String result = "";
        /*
        SupervisorAgent routing for correlation (commented out - using direct agent call instead):
        result = (String) troubleshootingAgent.invoke(correlationRequest);
        */
        // Direct correlation agent invocation
        result = correlationAgent.analyze(correlationRequest);

        System.out.println("======= Correlation complete. ========");
        System.out.println(result);
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
    }

    /**
     * Prints the help information
     */
    private static void printHelp() {
        System.out.println("JVM Troubleshooting Agentic Assistant Commands:");
        System.out.println("================================================");
        System.out.println("load <file>     - Load a single diagnostic data file");
        System.out.println("                 Supported: GC logs, hs_err logs, NMT memory, heap histograms, pmap output");
        System.out.println("analyze [<file>] - Analyze the loaded diagnostic data or specified single log file");
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
        System.out.println("  compare baseline.nmt current.nmt");
        System.out.println("  correlate gc.log nmt.txt pmap.txt");
        System.out.println("  config set provider ollama");
        System.out.println("  ask What memory issues do you see?");
        System.out.println("  ask How can I optimize garbage collection?");
    }




}
