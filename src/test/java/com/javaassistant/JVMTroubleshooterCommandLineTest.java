package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaassistant.ai.ChatModelProviderRegistry;
import com.javaassistant.ai.OCIChatModelProvider;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class JVMTroubleshooterCommandLineTest {

    @Test
    void printsOneShotHelpWhenNoArgumentsAreProvided() {
        CommandResult result = runCommand();

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage:"));
        assertTrue(result.output().contains("[--provider <provider-id>] [--model <name>] analyze <artifact-or-dir> [more-artifacts-or-dirs ...] [--type <type>]"));
        assertTrue(result.output().contains("jtroubleshoot shell"));
        assertTrue(result.output().contains("jtroubleshoot provider list"));
        assertTrue(result.output().contains("Global options:"));
        assertTrue(result.output().contains("Examples:"));
        assertTrue(!result.output().contains("Notes:"));
        assertTrue(!result.output().contains("jtroubleshoot init"));
        assertTrue(!result.output().contains("jtroubleshoot quickstart"));
    }

    @Test
    void removedSetupCommandsAreNotShownInHelp() {
        CommandResult result = runCommand("help");

        assertEquals(0, result.exitCode());
        assertTrue(!result.output().contains("jtroubleshoot quickstart"));
        assertTrue(!result.output().contains("jtroubleshoot init"));
    }

    @Test
    void rejectsLoadInOneShotMode() {
        CommandResult result = runCommand("load", "samples/g1_21_smallheap_fullgcs.log");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("only available in the interactive shell"));
        assertTrue(result.output().contains("jtroubleshoot shell"));
    }

    @Test
    void printsShellHelpWithoutStartingTheShellLoop() {
        CommandResult result = runCommand("help", "shell");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Stateful shell commands:"));
        assertTrue(result.output().contains("open <artifact>"));
        assertTrue(!result.output().contains("open-report <analysis-id>"));
        assertTrue(result.output().contains("analyze [<artifact-or-dir> ...]              Analyze one artifact, auto-compare two, auto-trend same-type sequences, or auto-correlate mixed inputs"));
        assertTrue(!result.output().contains("show [<analysis-id>]"));
        assertTrue(!result.output().contains("reports show"));
        assertTrue(!result.output().contains("reports list"));
        assertTrue(!result.output().contains("\n  context"));
        assertTrue(!result.output().contains("doctor"));
        assertTrue(!result.output().contains("\n  init"));
    }

    @Test
    void oneShotHelpDoesNotListDoctor() {
        CommandResult result = runCommand("help");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("jtroubleshoot [--provider <provider-id>] [--model <name>] status"));
        assertTrue(!result.output().contains("doctor"));
        assertTrue(!result.output().contains("config clear"));
        assertTrue(!result.output().contains("ask --analysis-id"));
        assertTrue(!result.output().contains("reports show"));
        assertTrue(!result.output().contains("reports list"));
    }

    @Test
    void oneShotAskRequiresShellBackedActiveDiagnosticContext() {
        CommandResult result = runCommand("ask", "What is the main issue?");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("active diagnostic context"));
        assertTrue(result.output().contains("jtroubleshoot shell"));
        assertTrue(!result.output().contains("ask --analysis-id"));
    }

    @Test
    void reportsCommandIsRemoved() {
        CommandResult result = runCommand("reports", "show");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("'reports' is no longer supported"));
        assertTrue(result.output().contains("Open the saved report files directly"));
    }

    @Test
    void openReportCommandIsRemoved() {
        CommandResult result = runCommand("open-report", "20260328120000-sample.log");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("'open-report' is no longer supported"));
    }

    @Test
    void statusShowsDefaultProviderAndModel() {
        CommandResult result = runCommand("status");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("AI setup:"));
        assertTrue(result.output().contains("Provider: Ollama (ollama)"));
        assertTrue(result.output().contains("Model: llama3.2"));
        assertTrue(result.output().contains("Ready: yes"));
        assertTrue(result.output().contains("Saved AI defaults: (not set)"));
        assertTrue(result.output().contains("Next:"));
    }

    @Test
    void statusUsesSavedProviderAndModelDefaults() throws Exception {
        Path configFile = newConfigFile();
        Files.writeString(
            configFile,
            """
                {
                  "provider": "oci",
                  "model": "xai.grok-4"
                }
                """,
            StandardCharsets.UTF_8
        );

        CommandResult result = runCommand(configFile, "status");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Provider: OCI Generative AI (oci)"));
        assertTrue(result.output().contains("Model: xai.grok-4"));
        assertTrue(result.output().contains("OCI authentication: config_file (default)"));
        assertTrue(result.output().contains("Saved AI defaults: OCI Generative AI (oci) / xai.grok-4"));
    }

    @Test
    void statusAcceptsTopLevelProviderAndModelOverrides() throws Exception {
        Path configFile = newConfigFile();
        Files.writeString(
            configFile,
            """
                {
                  "provider": "oci",
                  "model": "xai.grok-4"
                }
                """,
            StandardCharsets.UTF_8
        );

        CommandResult result = runCommand(configFile, "--provider", "ollama", "--model", "llama3.2", "status");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Provider: Ollama (ollama)"));
        assertTrue(result.output().contains("Model: llama3.2"));
        assertTrue(result.output().contains("Saved AI defaults: OCI Generative AI (oci) / xai.grok-4"));
    }

    @Test
    void helpCanUseProviderOverrideWithoutInitializingTheProvider() {
        CommandResult result = runCommand("--provider", "oci", "help");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage:"));
        assertTrue(result.output().contains("--provider <provider-id>"));
    }

    @Test
    void helpAcceptsProviderAliasesWithoutInitializingTheProvider() {
        CommandResult result = runCommand("--provider", "claude", "help");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage:"));
        assertTrue(result.output().contains("--provider <provider-id>"));
    }

    @Test
    void rejectsMissingTopLevelProviderValue() {
        CommandResult result = runCommand("--provider");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Missing value for --provider"));
    }

    @Test
    void configShowDisplaysConfigPathAndUnsetDefaults() {
        CommandResult result = runCommand("config", "show");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Saved AI defaults:"));
        assertTrue(result.output().contains("File:"));
        assertTrue(result.output().contains("Defaults: (not set)"));
        assertTrue(result.output().contains("Built-in default: Ollama (ollama) / llama3.2"));
    }

    @Test
    void configShowDisplaysSavedOciAuthenticationMethod() throws Exception {
        Path configFile = newConfigFile();
        Files.writeString(
            configFile,
            """
                {
                  "provider": "oci",
                  "model": "xai.grok-4",
                  "ociAuthenticationMethod": "config_file"
                }
                """,
            StandardCharsets.UTF_8
        );

        CommandResult result = runCommand(configFile, "config", "show");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("OCI authentication: config_file"));
    }

    @Test
    void configSetProviderPersistsAcrossCommands() throws Exception {
        Path configFile = newConfigFile();
        String expectedModel = ChatModelProviderRegistry.provider(OCIChatModelProvider.ID).resolveModelName(null);

        CommandResult setResult = runCommand(configFile, "config", "set", "provider", "oci");

        assertEquals(0, setResult.exitCode());
        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"provider\": \"oci\""));
        assertTrue(savedJson.contains("\"model\": \"" + expectedModel + "\""));
        assertTrue(savedJson.contains("\"ociAuthenticationMethod\": \"config_file\""));
        assertTrue(!savedJson.contains("\"schemaVersion\""));
        assertTrue(setResult.output().contains("`ociAuthenticationMethod` `config_file` in config.json"));
        assertTrue(setResult.output().contains("edit " + configFile));

        CommandResult statusResult = runCommand(configFile, "status");
        assertEquals(0, statusResult.exitCode());
        assertTrue(statusResult.output().contains("Provider: OCI Generative AI (oci)"));
        assertTrue(statusResult.output().contains("Model: " + expectedModel));
        assertTrue(statusResult.output().contains("OCI authentication: config_file"));
    }

    @Test
    void configSetModelPersistsTheActiveProviderAndModel() throws Exception {
        Path configFile = newConfigFile();

        CommandResult setResult = runCommand(configFile, "--provider", "oci", "config", "set", "model", "xai.grok-4");

        assertEquals(0, setResult.exitCode());
        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"provider\": \"oci\""));
        assertTrue(savedJson.contains("\"model\": \"xai.grok-4\""));

        CommandResult statusResult = runCommand(configFile, "status");
        assertEquals(0, statusResult.exitCode());
        assertTrue(statusResult.output().contains("Provider: OCI Generative AI (oci)"));
        assertTrue(statusResult.output().contains("Model: xai.grok-4"));
    }

    @Test
    void configSetModelPrintsConciseConfirmation() throws Exception {
        Path configFile = newConfigFile();

        CommandResult result = runCommand(configFile, "--provider", "oci", "config", "set", "model", "xai.grok-4.1-fast-reasoning");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Saved model xai.grok-4.1-fast-reasoning in config.json. It will be used for subsequent commands."));
        assertTrue(!result.output().contains("Current session:"));
        assertTrue(!result.output().contains("Saved defaults:"));
        assertTrue(!result.output().contains("Config file:"));
    }

    @Test
    void configSetModelPreservesSavedOciAuthenticationMethod() throws Exception {
        Path configFile = newConfigFile();
        Files.writeString(
            configFile,
            """
                {
                  "provider": "oci",
                  "model": "xai.grok-4",
                  "ociAuthenticationMethod": "config_file"
                }
                """,
            StandardCharsets.UTF_8
        );

        CommandResult result = runCommand(configFile, "config", "set", "model", "xai.grok-4.1-fast-reasoning");

        assertEquals(0, result.exitCode());
        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"model\": \"xai.grok-4.1-fast-reasoning\""));
        assertTrue(savedJson.contains("\"ociAuthenticationMethod\": \"config_file\""));
    }

    @Test
    void configSetProviderOciPreservesCurrentOciModelSelection() throws Exception {
        Path configFile = newConfigFile();

        CommandResult result = runCommand(
            configFile,
            "--provider",
            "oci",
            "--model",
            "xai.grok-4.1-fast-reasoning",
            "config",
            "set",
            "provider",
            "oci"
        );

        assertEquals(0, result.exitCode());
        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"model\": \"xai.grok-4.1-fast-reasoning\""));
        assertTrue(savedJson.contains("\"ociAuthenticationMethod\": \"config_file\""));
    }

    @Test
    void statusShowsConfiguredOciAuthenticationMethod() throws Exception {
        Path configFile = newConfigFile();
        Files.writeString(
            configFile,
            """
                {
                  "provider": "oci",
                  "model": "xai.grok-4",
                  "ociAuthenticationMethod": "config_file"
                }
                """,
            StandardCharsets.UTF_8
        );

        CommandResult result = runCommand(configFile, "status");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("OCI authentication: config_file"));
    }

    @Test
    void configSetProviderWithoutBuiltInDefaultModelPromptsForExplicitModelSelection() throws Exception {
        Path configFile = newConfigFile();

        CommandResult setResult = runCommand(configFile, "config", "set", "provider", "openrouter");

        assertEquals(0, setResult.exitCode());
        assertTrue(setResult.output().contains("Saved provider OpenRouter (openrouter) in config.json."));
        assertTrue(setResult.output().contains("It will be used for subsequent commands."));
        assertTrue(setResult.output().contains("Next: set `OPENROUTER_API_KEY`"));
        assertTrue(setResult.output().contains("choose a model with `jtroubleshoot config set model <name>`"));

        CommandResult statusResult = runCommand(configFile, "status");
        assertEquals(0, statusResult.exitCode());
        assertTrue(statusResult.output().contains("Provider: OpenRouter (openrouter)"));
        assertTrue(!statusResult.output().contains("Model:"));
    }

    @Test
    void configClearIsRejected() {
        CommandResult result = runCommand("config", "clear", "model");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Use: config show | config set provider <provider-id> | config set model <name>"));
    }

    @Test
    void providerListShowsExpandedProviderCatalog() {
        CommandResult result = runCommand("provider", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Supported AI Providers:"));
        assertTrue(result.output().contains("Setup: Local Ollama runtime"));
        assertTrue(result.output().contains("Required environment: (none)"));
        assertTrue(result.output().contains("Required environment: OPENAI_API_KEY"));
        assertTrue(result.output().contains("Required environment: OCI_COMPARTMENT_ID"));
        assertTrue(result.output().contains("Note: Set the routed model with `jtroubleshoot config set model <provider/model>` or `--model <provider/model>`."));
        assertTrue(!result.output().contains("OPENAI_MODEL_NAME"));
        assertTrue(!result.output().contains("OLLAMA_MODEL_NAME"));
        assertTrue(!result.output().contains("OCI_MODEL_NAME"));
        assertTrue(!result.output().contains("OPENAI_BASE_URL"));
        assertTrue(result.output().contains("OpenAI (openai)"));
        assertTrue(result.output().contains("Anthropic (anthropic)"));
        assertTrue(result.output().contains("Google AI Gemini (google)"));
        assertTrue(result.output().contains("Azure OpenAI (azure-openai)"));
        assertTrue(result.output().contains("xAI (xai)"));
        assertTrue(result.output().contains("Groq (groq)"));
        assertTrue(result.output().contains("OpenRouter (openrouter)"));
        assertTrue(result.output().contains("Together AI (together)"));
        assertTrue(result.output().contains("Fireworks AI (fireworks)"));
        assertTrue(result.output().contains("OpenAI-Compatible (openai-compatible)"));
    }

    @Test
    void configSetProviderPrintsSetupGuidance() throws Exception {
        Path configFile = newConfigFile();

        CommandResult result = runCommand(configFile, "config", "set", "provider", "openai");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Saved provider OpenAI (openai) in config.json."));
        assertTrue(result.output().contains("It will be used for subsequent commands."));
        assertTrue(result.output().contains("Next: set `OPENAI_API_KEY`"));
        assertTrue(!result.output().contains("Current session:"));
        assertTrue(!result.output().contains("Saved defaults:"));
    }

    @Test
    void explainsCoverageRelatedNarrativeRejectionsInUserLanguage() {
        AnalysisReport report = rejectedAnalysisReport(
            new AgentQualityGateResult(
                "coverage-aware-confidence",
                AgentQualityGateStatus.FAILED,
                "The starting context reported omissions or truncation for 1 artifact(s), but the agent did not retrieve more context before concluding."
            )
        );

        assertEquals(
            "The thread-dump specialist did not use enough of the available diagnostic context to support a trustworthy conclusion.",
            JVMTroubleshooter.unavailableAgentAnalysisReason(report)
        );
        assertTrue(JVMTroubleshooter.unavailableAgentAnalysisNextStep(report).contains("stronger model or provider"));
    }

    @Test
    void explainsEmptyNarrativeRejectionsInUserLanguage() {
        AnalysisReport report = rejectedAnalysisReport(
            new AgentQualityGateResult(
                "response-not-empty",
                AgentQualityGateStatus.FAILED,
                "The narrative was empty, so the user-facing report should fall back."
            )
        );

        assertEquals(
            "The thread-dump specialist did not return a troubleshooting response.",
            JVMTroubleshooter.unavailableAgentAnalysisReason(report)
        );
        assertTrue(JVMTroubleshooter.unavailableAgentAnalysisNextStep(report).contains("reachable"));
    }

    @Test
    void explainsProviderInvocationFailuresInUserLanguage() {
        AnalysisReport report = rejectedAnalysisReport(
            new AgentQualityGateResult(
                "response-not-empty",
                AgentQualityGateStatus.FAILED,
                "The agent call failed before returning a response: OCI 401 unauthorized"
            )
        );

        assertEquals(
            "The thread-dump specialist could not get a response from the configured AI model. Details: OCI 401 unauthorized",
            JVMTroubleshooter.unavailableAgentAnalysisReason(report)
        );
        assertTrue(JVMTroubleshooter.unavailableAgentAnalysisNextStep(report).contains("access problem"));
    }

    private static CommandResult runCommand(String... args) {
        try {
            return runCommand(newConfigFile(), args);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static CommandResult runCommand(Path configFile, String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        String originalConfigFile = System.getProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY);
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);

        try {
            System.setProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY, configFile.toString());
            System.setOut(captureStream);
            System.setErr(captureStream);
            int exitCode = JVMTroubleshooter.runCommandLine(args);
            captureStream.flush();
            return new CommandResult(exitCode, outputBuffer.toString(StandardCharsets.UTF_8), configFile);
        } finally {
            if (originalConfigFile == null) {
                System.clearProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY);
            } else {
                System.setProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY, originalConfigFile);
            }
            System.setOut(originalOut);
            System.setErr(originalErr);
            captureStream.close();
        }
    }

    private static Path newConfigFile() throws Exception {
        Path tempDirectory = Files.createTempDirectory("jtroubleshoot-cli-config");
        return tempDirectory.resolve("config.json");
    }

    private static AnalysisReport rejectedAnalysisReport(AgentQualityGateResult failedGate) {
        return new AnalysisReport(
            AnalysisReport.CURRENT_SCHEMA_VERSION,
            "test-analysis",
            LocalDateTime.of(2026, 4, 2, 8, 0),
            "Test summary",
            null,
            List.of(
                new AgentTraceability(
                    "single-artifact-specialist-analysis",
                    "ThreadDumpAgent",
                    AgentNarrativeSource.SPECIALIST_AGENT,
                    ArtifactType.THREAD_DUMP,
                    List.of("samples/thread_dump_deadlock.txt"),
                    List.of("thread-dump-deadlock"),
                    false,
                    List.of(failedGate)
                )
            ),
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );
    }

    private record CommandResult(int exitCode, String output, Path configFile) { }
}
