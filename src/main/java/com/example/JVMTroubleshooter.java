package com.example;

import com.example.agents.GCLogAgent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.model.chat.ChatModel;

import com.example.data.DataType;
import com.example.data.DiagnosticData;
import com.example.modelproviders.OllamaChatModelProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Main command-line application for JVM troubleshooting
 */
public class JVMTroubleshooter {
    private static final ChatModel CHAT_MODEL = OllamaChatModelProvider.createChatModel();

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
        System.out.println("  load <file>     - Load diagnostic data file");
        System.out.println("  analyze         - Analyze loaded diagnostic data");
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
                    case "load":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a file path to load");
                        } else {
                            currentDiagnosticData = loadDiagnosticData(argument);
                        }
                        break;

                    case "analyze":
                        if (currentDiagnosticData == null) {
                            System.out.println("Error: No diagnostic data loaded. Use 'load <file>' first.");
                        } else {
                            analyzeDiagnosticData(currentDiagnosticData);
                        }
                        break;

                    case "ask":
                        if (argument.isEmpty()) {
                            System.out.println("Error: Please specify a question to ask");
                        } else if (currentDiagnosticData == null) {
                            System.out.println("Error: No diagnostic data loaded. Use 'load <file>' first.");
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
        DataType dataType = DataType.fromFileName(filePath);
        DiagnosticData diagnosticData = new DiagnosticData(dataType, content, filePath);

        System.out.println("Loaded " + dataType.description() + " from " + filePath);
        System.out.println("Content size: " + diagnosticData.getContentSize() + " characters");

        return diagnosticData;
    }

    /**
     * Analyzes the loaded diagnostic data
     */
    private static void analyzeDiagnosticData(DiagnosticData diagnosticData) {
        System.out.println("Analyzing " + diagnosticData.type().description() + "...");

        // 1. Build sub-agents for specialized tasks
        GCLogAgent gcLogAgent = AgenticServices.agentBuilder(GCLogAgent.class)
                    .chatModel(CHAT_MODEL)
                    // .tools(new GCTools())
                    .build();

         // 2. Build the Supervisor agent
        SupervisorAgent troubleshootingAgent = AgenticServices.supervisorBuilder()
                .chatModel(CHAT_MODEL)
                .subAgents(gcLogAgent)
                .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY)
                .responseStrategy(SupervisorResponseStrategy.LAST)
                .supervisorContext("You are an expert in troubleshooting HotSpot JVM issues. Use only the gcLogAgent for analyzing GC Logs. Respond in plain English, no markdown, no extra text before or after the response.")
                .maxAgentsInvocations(2)
                .build();

        // 3. Invoke Supervisor with a natural request
        String result = (String) troubleshootingAgent.invoke(
                "Analyze the provided diagnostic data" +
                " and provide a summary of findings and recommendations." +
                diagnosticData
        );

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
        // System.out.println("- Configuration: " + (ociService != null ? "Loaded" : "Not loaded"));
        System.out.println("- Diagnostic Data: " + (currentDiagnosticData != null ?
            currentDiagnosticData.type().description() + " (" + currentDiagnosticData.sourceFile() + ")" :
            "None loaded"));
        // System.out.println("- AI Service: " + (ociService != null ? "Available" : "Unavailable"));
    }

    /**
     * Prints the help information
     */
    private static void printHelp() {
        System.out.println("JVM Troubleshooting Agentic Assistant Commands:");
        System.out.println("================================================");
        System.out.println("load <file>     - Load diagnostic data file");
        System.out.println("                 Supported: GC logs, thread dumps, HS_ERR logs, metrics, heap dumps");
        System.out.println("analyze         - Analyze the currently loaded diagnostic data");
        System.out.println("ask <question>  - Ask a question about the loaded diagnostic data");
        System.out.println("                 Example: ask What are the main performance issues?");
        System.out.println("status          - Show current application status");
        System.out.println("help            - Show this help information");
        System.out.println("quit            - Exit the application");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  load sample-gc.log");
        System.out.println("  analyze");
        System.out.println("  ask What memory issues do you see?");
        System.out.println("  ask How can I optimize garbage collection?");
    }




}
