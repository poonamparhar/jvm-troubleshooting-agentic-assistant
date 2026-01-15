package com.example;

import com.example.agents.GCLogAgent;
import com.example.agents.HSErrLogAgent;

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
    // private static final ChatModel CHAT_MODEL = OllamaChatModelProvider.createChatModel();
    private static final ChatModel CHAT_MODEL = OCIChatModelProvider.createChatModel();
    private static final List<DiagnosticData> WORKING_SET = new ArrayList<>();
    private static final SupervisorAgent TROUBLESHOOTING_AGENT = createTroubleshootingAgent();

    // Build sub-agents for specialized tasks
    private static final GCLogAgent gcLogAgent = AgenticServices.agentBuilder(GCLogAgent.class)
                .chatModel(CHAT_MODEL)
                // .tools(new GCTools())
                .build();

    private static final HSErrLogAgent hsErrLogAgent = AgenticServices.agentBuilder(HSErrLogAgent.class)
                .chatModel(CHAT_MODEL)
                .build();

    private static SupervisorAgent createTroubleshootingAgent() {
        // // Build sub-agents for specialized tasks
        GCLogAgent gcLogAgent = AgenticServices.agentBuilder(GCLogAgent.class)
                .chatModel(CHAT_MODEL)
                // .tools(new GCTools())
                .build();

        HSErrLogAgent hsErrLogAgent = AgenticServices.agentBuilder(HSErrLogAgent.class)
                .chatModel(CHAT_MODEL)
                .build();

        // Build the Supervisor agent
        SupervisorAgent troubleshootingAgent = AgenticServices.supervisorBuilder()
                .chatModel(CHAT_MODEL)
                .subAgents(gcLogAgent, hsErrLogAgent)
                .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY)
                .responseStrategy(SupervisorResponseStrategy.LAST)
                .supervisorContext("\"You are a JVM troubleshooting supervisor, managing a GC logs expert and a hs_err log expert. " +
                                    "For analyzing GC Logs, use gcLogAgent. " +
                                    "For analyzing hs_err log files, use hsErrLogAgent. " +
                                    "Invoke only one agent for a specific data type analysis. " +
                                    "Respond in plain English, no markdown, no extra ticks, text, or blocks before or after the text response.")
                .maxAgentsInvocations(2)
                .build();

        return troubleshootingAgent;
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
        DiagnosticData currentDiagnosticData = null;

        System.out.println("JVM Troubleshooting Agentic Assistant");
        System.out.println("=====================================");
        System.out.println("Commands:");
        System.out.println("  use <file...>   - Set working set of diagnostic data files");
        System.out.println("  analyze [<file...>] - Analyze loaded diagnostic data or specified files");
        System.out.println("  ask <question>  - Ask a question about the loaded data");
        System.out.println("  status          - Show current status");
        System.out.println("  help            - Show this help");
        System.out.println("  quit            - Exit the application");
        System.out.println();

        while (true) {
            System.out.print("jvm-troubleshooter> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1] : "";

            try {
                switch (command) {
                    case "use":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify one or more file paths to use");
                        } else {
                            WORKING_SET.clear();
                            String[] files = argument.split("\\s+");
                            for (String f : files) {
                                try {
                                    DiagnosticData dd = loadDiagnosticData(f);
                                    WORKING_SET.add(dd);
                                } catch (Exception e) {
                                    System.out.println("Warning: " + e.getMessage());
                                }
                            }
                            if (!WORKING_SET.isEmpty()) {
                                System.out.println("Working set has " + WORKING_SET.size() + " file(s).");
                                for (int i = 0; i < WORKING_SET.size(); i++) {
                                    DiagnosticData dd = WORKING_SET.get(i);
                                    System.out.println("  [" + (i + 1) + "] " + dd.sourceFile() + " (" + dd.type().description() + ", " + dd.getContentSize() + " chars)");
                                }
                                // Backward compatibility: keep first in currentDiagnosticData
                                currentDiagnosticData = WORKING_SET.get(0);
                            }
                        }
                        break;

                    case "analyze":
                        DiagnosticData dataToAnalyze = null;
                        if (!argument.isEmpty()) {
                            // Analyze specific single file provided as argument
                            String[] files = argument.split("\\s+");
                            if (files.length > 1) {
                                System.out.println("Error: Analyze command accepts only one log file. Use 'use <file...>' to load multiple files first, then analyze individually.");
                                break;
                            }
                            String file = files[0].trim();
                            try {
                                dataToAnalyze = loadDiagnosticData(file);
                            } catch (Exception e) {
                                System.out.println("Error: " + e.getMessage());
                                break;
                            }
                        } else if (currentDiagnosticData != null) {
                            dataToAnalyze = currentDiagnosticData;
                        } else {
                            System.out.println("Error: No diagnostic file selected. Use 'use <file>' first or specify a single file with 'analyze <file>'.");
                            break;
                        }
                        analyzeSingleDiagnosticData(dataToAnalyze);
                        break;

                    case "ask":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a question to ask");
                        } else if (currentDiagnosticData == null) {
                            System.out.println("Error: No diagnostic data loaded. Use 'use <file...>' first.");
                        } else {
                            askQuestion(argument, currentDiagnosticData);
                        }
                        break;

                    case "status":
                        showStatus(currentDiagnosticData);
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
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new Exception("File not found: " + filePath);
        }

        String content = Files.readString(path);
        DataType dataType = DataType.fromContents(content);
        DiagnosticData diagnosticData = new DiagnosticData(dataType, content, filePath);

        System.out.println("Loaded " + dataType.description() + " from " + filePath);
        System.out.println("Content size: " + diagnosticData.getContentSize() + " characters");

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
        // Invoke Supervisor with a natural request
        // result = (String) TROUBLESHOOTING_AGENT.invoke(request);

        if (diagnosticData.type() == DataType.GC_LOG) {
            result = gcLogAgent.analyze(request);
        } else if (diagnosticData.type() == DataType.HS_ERR_LOG) {
            result = hsErrLogAgent.analyze(request);
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
    }

    /**
     * Shows the current status
     */
    private static void showStatus(DiagnosticData currentDiagnosticData) {
        System.out.println("Current Status:");
        System.out.println("- Working set files: " + WORKING_SET.size());
        if (!WORKING_SET.isEmpty()) {
            for (int i = 0; i < WORKING_SET.size(); i++) {
                DiagnosticData dd = WORKING_SET.get(i);
                System.out.println("  [" + (i + 1) + "] " + dd.sourceFile() + " (" + dd.type().description() + ", " + dd.getContentSize() + " chars)");
            }
        } else {
            System.out.println("  (none)");
        }
    }

    /**
     * Prints the help information
     */
    private static void printHelp() {
        System.out.println("JVM Troubleshooting Agentic Assistant Commands:");
        System.out.println("================================================");
        System.out.println("use <file...>   - Set working set of diagnostic data files");
        System.out.println("                 Supported: GC logs, thread dumps, HS_ERR logs, metrics, heap dumps");
        System.out.println("analyze [file] - Analyze the currently loaded diagnostic data or specified single log file");
        System.out.println("ask <question>  - Ask a question about the loaded diagnostic data");
        System.out.println("                 Example: ask What are the main performance issues?");
        System.out.println("status          - Show current application status");
        System.out.println("help            - Show this help information");
        System.out.println("quit            - Exit the application");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  use sample-gc.log samples/sample-hs_err-jdk21-oom.log");
        System.out.println("  analyze");
        System.out.println("  analyze sample-gc.log");
        System.out.println("  ask What memory issues do you see?");
        System.out.println("  ask How can I optimize garbage collection?");
    }




}
