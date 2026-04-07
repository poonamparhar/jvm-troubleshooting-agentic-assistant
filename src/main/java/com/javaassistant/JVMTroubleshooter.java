package com.javaassistant;

import com.javaassistant.compare.ArtifactComparisonService;
import com.javaassistant.ingest.ArtifactLoader;
import com.javaassistant.ingest.ArtifactDiscoveryResult;
import com.javaassistant.ai.ChatModelProviderFactory;
import com.javaassistant.ai.ChatModelProviderRegistry;
import com.javaassistant.diagnostics.AgentNarrativeSource;
import com.javaassistant.diagnostics.AgentQualityGateResult;
import com.javaassistant.diagnostics.AgentQualityGateStatus;
import com.javaassistant.diagnostics.AgentTraceability;
import com.javaassistant.diagnostics.AnalysisReport;
import com.javaassistant.diagnostics.ArtifactInventoryEntry;
import com.javaassistant.diagnostics.ArtifactType;
import com.javaassistant.diagnostics.InputArtifact;
import com.javaassistant.orchestration.DiagnosticAgentOrchestrator;
import com.javaassistant.report.ReportBundleService;
import com.javaassistant.ai.ConfiguredChatModel;
import com.javaassistant.ai.ProviderSetupStatus;
import com.javaassistant.report.UserConsoleReportRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

/**
 * Main command-line application for JVM troubleshooting
 */
public class JVMTroubleshooter {

    private record AskRequest(String question) { }
    private record ArtifactTargetRequest(String filePath, ArtifactType overrideType) { }
    private record AnalyzeTargetRequest(List<String> inputPaths, ArtifactType overrideType) { }
    private record ResolvedAnalyzeInputs(List<InputArtifact> artifacts, List<ArtifactInventoryEntry> artifactInventory) { }
    private record RuntimeAiSelection(String providerId, String modelOverride) { }
    private record CommandLineInvocation(RuntimeAiSelection runtimeAiSelection, List<String> commandTokens) { }
    private record AgentAnalysisRejectionSummary(String reason, String nextStep) { }
    private enum AskRouteMode {
        SINGLE_ARTIFACT,
        COMPARE_PAIR,
        SEQUENCE_SET,
        CORRELATE_SET,
        UNSUPPORTED
    }
    private record AskRoute(AskRouteMode mode, String message) {
        private boolean supported() {
            return mode != AskRouteMode.UNSUPPORTED;
        }
    }

    private static final String DEFAULT_PROVIDER_ID = ChatModelProviderRegistry.DEFAULT_PROVIDER_ID;

    private static String currentProviderId = DEFAULT_PROVIDER_ID;
    private static String currentModelOverride;
    private static ConfiguredChatModel currentConfiguredChatModel;
    private static Path savedConfigFile;
    private static boolean savedConfigExists;
    private static String savedProviderId;
    private static String savedModelOverride;
    private static InputArtifact loadedArtifact = null;
    private static List<InputArtifact> activeDiagnosticArtifacts = List.of();
    private static final ArtifactLoader artifactLoader = DiagnosticRuntimeFactory.artifactLoader();
    private static ReportBundleService reportBundleService = new ReportBundleService(ApplicationRuntimeSupport.resolveReportBundleDirectory());
    private static final ArtifactComparisonService comparisonService = DiagnosticRuntimeFactory.comparisonService();
    private static final UserConsoleReportRenderer userConsoleReportRenderer = new UserConsoleReportRenderer();

    private static void switchProvider(String newProviderId) {
        if (Objects.equals(currentProviderId, newProviderId) && currentModelOverride == null) {
            System.out.println("Provider already set to: " + renderProviderForDisplay(newProviderId));
            return;
        }

        currentProviderId = newProviderId;
        currentModelOverride = null;
        currentConfiguredChatModel = null;
        System.out.println(
            "Switched to " + renderProviderForDisplay(newProviderId) + " for this shell session. Saved defaults were not changed."
        );
        printProviderSetupHint(newProviderId);
    }

    private static void resetCommandLineState() {
        currentProviderId = DEFAULT_PROVIDER_ID;
        currentModelOverride = null;
        currentConfiguredChatModel = null;
        reportBundleService = new ReportBundleService(ApplicationRuntimeSupport.resolveReportBundleDirectory());
        savedConfigFile = ApplicationRuntimeSupport.resolveUserConfigFile();
        savedConfigExists = Files.exists(savedConfigFile);
        savedProviderId = null;
        savedModelOverride = null;
        loadedArtifact = null;
        activeDiagnosticArtifacts = List.of();
    }

    private static RuntimeAiSelection defaultRuntimeAiSelection() {
        return new RuntimeAiSelection(DEFAULT_PROVIDER_ID, null);
    }

    private static RuntimeAiSelection emptyRuntimeAiSelection() {
        return new RuntimeAiSelection(null, null);
    }

    private static RuntimeAiSelection savedRuntimeAiSelection() {
        return new RuntimeAiSelection(savedProviderId != null ? savedProviderId : DEFAULT_PROVIDER_ID, savedModelOverride);
    }

    private static void applyRuntimeAiSelection(RuntimeAiSelection selection) {
        RuntimeAiSelection effectiveSelection = selection != null ? selection : defaultRuntimeAiSelection();
        String selectedProviderId = effectiveSelection.providerId() != null ? effectiveSelection.providerId() : DEFAULT_PROVIDER_ID;
        String selectedModelOverride = normalizeModelOverride(effectiveSelection.modelOverride());
        if (!Objects.equals(currentProviderId, selectedProviderId) || !Objects.equals(currentModelOverride, selectedModelOverride)) {
            currentProviderId = selectedProviderId;
            currentModelOverride = selectedModelOverride;
            currentConfiguredChatModel = null;
        }
    }

    private static void applyRuntimeAiOverrides(RuntimeAiSelection overrides) {
        if (overrides == null) {
            return;
        }

        String overrideProviderId = overrides.providerId();
        String overrideModel = normalizeModelOverride(overrides.modelOverride());
        if (overrideProviderId == null && overrideModel == null) {
            return;
        }

        String selectedProviderId = overrideProviderId != null ? overrideProviderId : currentProviderId;
        String selectedModelOverride = overrideModel;

        if (!Objects.equals(currentProviderId, selectedProviderId) || !Objects.equals(currentModelOverride, selectedModelOverride)) {
            currentProviderId = selectedProviderId;
            currentModelOverride = selectedModelOverride;
            currentConfiguredChatModel = null;
        }
    }

    private static String normalizeModelOverride(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return null;
        }
        return modelOverride.strip();
    }

    private static ConfiguredChatModel resolveConfiguredChatModel() {
        if (currentConfiguredChatModel == null) {
            currentConfiguredChatModel = createConfiguredChatModel(currentProviderId, currentModelOverride);
        }
        return currentConfiguredChatModel;
    }

    private static ConfiguredChatModel createConfiguredChatModel(String providerId, String modelOverride) {
        ChatModelProviderFactory provider = ChatModelProviderRegistry.provider(providerId);
        return provider.createChatModel(modelOverride);
    }

    private static DiagnosticAgentOrchestrator diagnosticAgentOrchestrator() {
        return DiagnosticRuntimeFactory.diagnosticAgentOrchestrator(resolveConfiguredChatModel());
    }

    private static String resolvedConfiguredModelName() {
        if (currentConfiguredChatModel != null && currentConfiguredChatModel.modelName() != null && !currentConfiguredChatModel.modelName().isBlank()) {
            return currentConfiguredChatModel.modelName();
        }
        return resolveModelName(currentProviderId, currentModelOverride);
    }

    private static String resolveModelName(String providerId, String modelOverride) {
        String effectiveProviderId = providerId != null ? providerId : DEFAULT_PROVIDER_ID;
        return ChatModelProviderRegistry.provider(effectiveProviderId).resolveModelName(modelOverride);
    }

    private static List<String> tokenizeInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return List.of();
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

        return List.copyOf(tokens);
    }

    private static String joinTokens(List<String> tokens) {
        return String.join(" ", tokens);
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            exitCode = runCommandLine(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            exitCode = 1;
        }

        // Some AI provider SDKs keep non-daemon worker threads alive after a one-shot command.
        // Exit explicitly so the CLI always returns control to the terminal.
        System.exit(exitCode);
    }

    private static void releaseCommandLineResources() {
        if (currentConfiguredChatModel == null) {
            return;
        }
        currentConfiguredChatModel.closeQuietly();
        currentConfiguredChatModel = null;
    }

    static int runCommandLine(String[] args) {
        resetCommandLineState();
        try {
            List<String> rawTokens = args == null ? List.of() : List.of(args);
            CommandLineInvocation invocation;
            try {
                invocation = parseCommandLineInvocation(rawTokens);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
                return 1;
            }
            List<String> tokens = invocation.commandTokens();
            if (tokens.isEmpty()) {
                printCommandLineHelp();
                return 0;
            }
            try {
                initializeRuntimeAiSelection(tokens, invocation.runtimeAiSelection());
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
                return 1;
            }
            return executeCommandLine(tokens);
        } finally {
            releaseCommandLineResources();
        }
    }

    private static CommandLineInvocation parseCommandLineInvocation(List<String> rawTokens) throws Exception {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return new CommandLineInvocation(emptyRuntimeAiSelection(), List.of());
        }

        String providerId = null;
        String modelOverride = null;
        int index = 0;
        while (index < rawTokens.size()) {
            String token = rawTokens.get(index);
            if (!"--provider".equals(token) && !"--model".equals(token)) {
                break;
            }
            if (index + 1 >= rawTokens.size()) {
                throw new Exception("Missing value for " + token);
            }
            if ("--provider".equals(token)) {
                providerId = parseProviderId(rawTokens.get(index + 1));
            } else {
                modelOverride = rawTokens.get(index + 1);
            }
            index += 2;
        }

        return new CommandLineInvocation(
            new RuntimeAiSelection(providerId, modelOverride),
            List.copyOf(rawTokens.subList(index, rawTokens.size()))
        );
    }

    private static void initializeRuntimeAiSelection(List<String> tokens, RuntimeAiSelection commandLineOverrides) throws Exception {
        applyRuntimeAiSelection(defaultRuntimeAiSelection());

        if (shouldLoadSavedConfig(tokens)) {
            reloadSavedConfig();
            applyRuntimeAiSelection(savedRuntimeAiSelection());
        }

        applyRuntimeAiOverrides(commandLineOverrides);
    }

    private static boolean shouldLoadSavedConfig(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        String command = tokens.get(0).toLowerCase(Locale.ROOT);
        return !"help".equals(command) && !"-h".equals(command) && !"--help".equals(command);
    }

    private static void reloadSavedConfig() throws Exception {
        UserConfigStore configStore = userConfigStore();
        savedConfigFile = configStore.configFile();
        savedConfigExists = configStore.exists();

        UserConfigStore.StoredConfig storedConfig;
        try {
            storedConfig = configStore.load();
        } catch (Exception exception) {
            throw new Exception("Failed to read config file " + savedConfigFile + ": " + exception.getMessage(), exception);
        }

        savedProviderId = parseConfiguredProviderId(storedConfig.provider());
        savedModelOverride = normalizeModelOverride(storedConfig.model());
    }

    private static String parseConfiguredProviderId(String providerName) throws Exception {
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        try {
            return parseProviderId(providerName);
        } catch (Exception exception) {
            throw new Exception("Invalid provider '" + providerName + "' in config file " + savedConfigFile + ".");
        }
    }

    private static UserConfigStore userConfigStore() {
        return new UserConfigStore(ApplicationRuntimeSupport.resolveUserConfigFile());
    }

    private static int executeCommandLine(List<String> tokens) {
        String command = tokens.get(0).toLowerCase();
        List<String> arguments = tokens.subList(1, tokens.size());

        try {
            return switch (command) {
                case "help", "-h", "--help" -> handleCommandLineHelp(arguments);
                case "shell" -> {
                    startInteractiveMode();
                    yield 0;
                }
                case "analyze" -> handleAnalyzeCommand(arguments, null, false);
                case "compare" -> handleCompareCommand(arguments);
                case "correlate" -> handleCorrelateCommand(arguments);
                case "ask" -> handleAskCommand(arguments, false);
                case "status" -> {
                    showStatus(null);
                    yield 0;
                }
                case "version" -> {
                    printVersion();
                    yield 0;
                }
                case "open", "load" -> {
                    reportUnsupportedOneShotStatefulCommand(command);
                    yield 1;
                }
                case "open-report" -> {
                    reportRemovedCommand(command);
                    yield 1;
                }
                case "provider" -> {
                    if (arguments.size() == 1 && "list".equalsIgnoreCase(arguments.get(0))) {
                        printSupportedProviders();
                        yield 0;
                    }
                    reportShellOnlyConfigurationCommand(command);
                    yield 1;
                }
                case "config" -> handleConfigCommand(arguments);
                case "reports", "report", "catalog" -> {
                    reportRemovedCommand(command);
                    yield 1;
                }
                default -> {
                    System.out.println("Unknown command: " + command);
                    System.out.println();
                    printCommandLineHelp();
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int handleCommandLineHelp(List<String> arguments) {
        if (!arguments.isEmpty() && "shell".equalsIgnoreCase(arguments.get(0))) {
            printShellHelp();
            return 0;
        }
        printCommandLineHelp();
        return 0;
    }

    /**
     * Starts the interactive command prompt for JVM troubleshooting.
     */
    private static void startInteractiveMode() {
        Scanner scanner = new Scanner(System.in);
        printShellWelcome();

        while (true) {
            System.out.print(shellPrompt());
            if (!scanner.hasNextLine()) {
                System.out.println();
                break;
            }

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = tokenizeInput(input);
            if (tokens.isEmpty()) {
                continue;
            }

            if (!executeShellCommand(tokens, scanner)) {
                break;
            }
        }

        scanner.close();
    }

    private static void printShellWelcome() {
        System.out.println("jtroubleshoot shell");
        System.out.println("===================");
        System.out.println("Open or analyze diagnostic artifacts, then ask follow-up questions against the active context.");
        System.out.println("Type 'help' to see shell commands. For one-shot usage, run 'jtroubleshoot help'.");
        System.out.println();
    }

    private static String shellPrompt() {
        if (hasActiveDiagnosticContext()) {
            return "[context:" + abbreviatePromptLabel(activeDiagnosticContextLabel()) + "] > ";
        }
        if (loadedArtifact != null) {
            return "[artifact:" + abbreviatePromptLabel(promptDisplayName(artifactSourcePath(loadedArtifact))) + "] > ";
        }
        return "[jtroubleshoot] > ";
    }

    private static String promptDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return "(unknown)";
        }
        int unixSeparator = value.lastIndexOf('/');
        int windowsSeparator = value.lastIndexOf('\\');
        int separatorIndex = Math.max(unixSeparator, windowsSeparator);
        return separatorIndex >= 0 ? value.substring(separatorIndex + 1) : value;
    }

    private static String abbreviatePromptLabel(String value) {
        if (value == null || value.length() <= 40) {
            return value;
        }
        return value.substring(0, 37) + "...";
    }

    private static void setActiveDiagnosticContext(List<InputArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            activeDiagnosticArtifacts = List.of();
            return;
        }
        activeDiagnosticArtifacts = List.copyOf(artifacts);
    }

    private static void clearActiveDiagnosticContext() {
        activeDiagnosticArtifacts = List.of();
    }

    private static boolean hasActiveDiagnosticContext() {
        return activeDiagnosticArtifacts != null && !activeDiagnosticArtifacts.isEmpty();
    }

    private static String activeDiagnosticContextLabel() {
        if (!hasActiveDiagnosticContext()) {
            return "none";
        }
        if (activeDiagnosticArtifacts.size() == 1) {
            return promptDisplayName(artifactSourcePath(activeDiagnosticArtifacts.getFirst()));
        }

        ArtifactType firstType = activeDiagnosticArtifacts.getFirst().type();
        boolean sameType = activeDiagnosticArtifacts.stream().allMatch(artifact -> artifact.type() == firstType);
        if (sameType && firstType != null) {
            return activeDiagnosticArtifacts.size() + " " + firstType.description() + " artifacts";
        }
        return activeDiagnosticArtifacts.size() + " diagnostic artifacts";
    }

    private static boolean executeShellCommand(List<String> tokens, Scanner scanner) {
        String command = tokens.get(0).toLowerCase();
        List<String> arguments = tokens.subList(1, tokens.size());

        try {
            switch (command) {
                case "help":
                    printShellHelp();
                    break;
                case "open":
                    handleOpenCommand(arguments, scanner);
                    break;
                case "open-report":
                    reportRemovedShellCommand(command);
                    break;
                case "analyze":
                    handleAnalyzeCommand(arguments, scanner, true);
                    break;
                case "compare":
                    handleCompareCommand(arguments);
                    break;
                case "correlate":
                    handleCorrelateCommand(arguments);
                    break;
                case "ask":
                    handleAskCommand(arguments, true);
                    break;
                case "provider":
                    handleProviderCommand(arguments);
                    break;
                case "config":
                    handleConfigCommand(arguments);
                    break;
                case "status":
                    showStatus(loadedArtifact);
                    break;
                case "clear":
                    clearActiveContext();
                    break;
                case "show", "reports", "report", "catalog", "context":
                    reportRemovedShellCommand(command);
                    break;
                case "load":
                    handleLegacyLoadAlias(arguments, scanner);
                    break;
                case "quit":
                case "exit":
                    System.out.println("Goodbye!");
                    return false;
                default:
                    System.out.println("Unknown shell command: " + command + ". Type 'help' for available commands.");
                    break;
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }

        return true;
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

    private static boolean rejectLegacyFlag(String command, List<String> arguments) {
        if (!containsLegacyFlag(arguments)) {
            return false;
        }

        System.out.println("Error: --legacy is no longer supported for '" + command + "'.");
        System.out.println("       This CLI now runs through the AI specialist-agent workflow only.");
        if ("ask".equals(command)) {
            System.out.println("       Start 'jtroubleshoot shell', open or analyze diagnostic artifacts, then run 'ask <question>'.");
        } else {
            System.out.println("       Remove --legacy and rerun the command.");
        }
        return true;
    }

    private static boolean containsLegacyFlag(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return false;
        }
        for (String token : arguments) {
            if ("--legacy".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static int handleOpenCommand(List<String> arguments, Scanner scanner) throws Exception {
        ArtifactTargetRequest request = parseArtifactTargetRequest(
            arguments,
            "open <artifact> [--type <type>]"
        );
        loadedArtifact = loadInputArtifact(request.filePath(), request.overrideType(), scanner);
        setActiveDiagnosticContext(List.of(loadedArtifact));
        System.out.println(
            "Opened artifact: "
                + artifactSourcePath(loadedArtifact)
                + " ("
                + loadedArtifact.type().description()
                + ", "
                + artifactContentLength(loadedArtifact)
                + " chars)"
        );
        return 0;
    }

    private static int handleAnalyzeCommand(List<String> arguments, Scanner scanner, boolean allowCurrentContext) throws Exception {
        if (rejectLegacyFlag("analyze", arguments)) {
            return 1;
        }

        if (arguments.isEmpty()) {
            if (!allowCurrentContext) {
                throw new Exception(
                    "Analyze requires a diagnostic file or directory. Use 'jtroubleshoot shell' for a stateful session."
                );
            }
            if (loadedArtifact == null) {
                throw new Exception("No artifact is open. Use 'open <artifact>' first or pass a path to analyze.");
            }
            analyzeSingleArtifact(loadedArtifact);
            return 0;
        }

        AnalyzeTargetRequest request = parseAnalyzeTargetRequest(
            arguments,
            "analyze <artifact-or-dir> [more-artifacts-or-dirs ...] [--type <type>]"
        );
        analyzeTarget(request, scanner);
        return 0;
    }

    private static void analyzeTarget(AnalyzeTargetRequest request, Scanner scanner) throws Exception {
        if (request.inputPaths().size() == 1) {
            analyzeInputPath(request.inputPaths().getFirst(), request.overrideType(), scanner);
            return;
        }

        if (request.overrideType() != null) {
            throw new Exception("--type is only supported when analyzing a single diagnostic file.");
        }

        ResolvedAnalyzeInputs resolvedInputs = resolveAnalyzeInputs(request.inputPaths(), scanner);
        executeAnalyzeRoute(resolvedInputs.artifacts(), resolvedInputs.artifactInventory());
    }

    private static void analyzeInputPath(String filePath, ArtifactType overrideType, Scanner scanner) throws Exception {
        Path path = resolveAnalyzeInputPath(filePath);
        if (Files.isDirectory(path)) {
            ArtifactDiscoveryResult discovery = artifactLoader.discoverWithInventory(path);
            emitDirectoryDiscoverySummary(path, discovery);

            if (discovery.supportedArtifacts().isEmpty()) {
                throw new Exception(noSupportedArtifactsMessage(path, discovery));
            }

            executeAnalyzeRoute(
                discovery.supportedArtifacts(),
                discovery.inventoryEntries()
            );
            return;
        }

        InputArtifact artifact = loadInputArtifact(filePath, overrideType, scanner);
        if (artifact.type() == ArtifactType.UNKNOWN && overrideType == null && scanner == null) {
            throw new Exception(unknownArtifactTypeMessage(filePath));
        }
        analyzeSingleArtifact(artifact);
    }

    private static Path resolveAnalyzeInputPath(String filePath) throws Exception {
        if (reportBundleService.exists(filePath)) {
            throw new Exception(
                "analyze only accepts raw diagnostic files or directories. Open saved report files directly from the report directory if you need to review them."
            );
        }

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new Exception("File not found: " + filePath);
        }

        if (Files.isDirectory(path) && Files.exists(path.resolve("report.json"))) {
            throw new Exception(
                "analyze only accepts raw diagnostic files or directories. Open saved report files directly from the report directory if you need to review them."
            );
        }
        return path;
    }

    private static ResolvedAnalyzeInputs resolveAnalyzeInputs(List<String> inputPaths, Scanner scanner) throws Exception {
        Map<String, InputArtifact> artifactsBySourcePath = new LinkedHashMap<>();
        Map<String, ArtifactInventoryEntry> inventoryByKey = new LinkedHashMap<>();

        for (String inputPath : inputPaths) {
            Path path = resolveAnalyzeInputPath(inputPath);
            if (Files.isDirectory(path)) {
                ArtifactDiscoveryResult discovery = artifactLoader.discoverWithInventory(path);
                emitDirectoryDiscoverySummary(path, discovery);
                addArtifacts(artifactsBySourcePath, discovery.supportedArtifacts());
                addArtifactInventory(inventoryByKey, discovery.inventoryEntries());
                continue;
            }

            InputArtifact artifact = loadInputArtifact(inputPath, null, scanner);
            if (artifact.type() == ArtifactType.UNKNOWN && scanner == null) {
                throw new Exception(unknownArtifactTypeMessage(inputPath));
            }
            addArtifacts(artifactsBySourcePath, List.of(artifact));
            addArtifactInventory(inventoryByKey, List.of(toSupportedInventoryEntry(artifact)));
        }

        if (artifactsBySourcePath.isEmpty()) {
            throw new Exception("No supported diagnostic artifacts were found in the supplied inputs.");
        }

        return new ResolvedAnalyzeInputs(
            List.copyOf(artifactsBySourcePath.values()),
            List.copyOf(inventoryByKey.values())
        );
    }

    private static int handleCompareCommand(List<String> arguments) throws Exception {
        if (rejectLegacyFlag("compare", arguments)) {
            return 1;
        }
        if (arguments.size() != 2) {
            throw new Exception("Use: compare <baseline-file> <current-file>");
        }

        Path baselinePath = Paths.get(arguments.get(0));
        Path currentPath = Paths.get(arguments.get(1));
        if (!Files.exists(baselinePath) || !Files.exists(currentPath)) {
            throw new Exception("One or both comparison files were not found.");
        }
        runStructuredComparison(baselinePath, currentPath);
        return 0;
    }

    private static int handleCorrelateCommand(List<String> arguments) throws Exception {
        if (rejectLegacyFlag("correlate", arguments)) {
            return 1;
        }
        if (arguments.size() < 2) {
            throw new Exception("Use: correlate <artifact1> <artifact2> [artifact3 ...]");
        }

        List<InputArtifact> artifacts = new ArrayList<>();
        for (String filePath : arguments) {
            artifacts.add(loadInputArtifact(filePath));
        }
        correlateArtifacts(artifacts);
        return 0;
    }

    private static int handleAskCommand(List<String> arguments, boolean allowContextFallback) throws Exception {
        if (rejectLegacyFlag("ask", arguments)) {
            return 1;
        }

        AskRequest askRequest = parseAskRequest(arguments, allowContextFallback);
        if (askRequest.question().isBlank()) {
            throw new Exception("Please specify a question to ask.");
        }
        askQuestionAgainstActiveContext(askRequest.question());
        return 0;
    }

    private static void handleProviderCommand(List<String> arguments) throws Exception {
        if (arguments.isEmpty() || "show".equalsIgnoreCase(arguments.get(0))) {
            printProviderStatus();
            return;
        }
        if (arguments.size() == 1 && "list".equalsIgnoreCase(arguments.get(0))) {
            printSupportedProviders();
            return;
        }
        if (arguments.size() == 2 && "use".equalsIgnoreCase(arguments.get(0))) {
            switchProvider(parseProviderId(arguments.get(1)));
            return;
        }
        throw new Exception("Use: provider [show|list] or provider use <provider-id>");
    }

    private static int handleConfigCommand(List<String> arguments) throws Exception {
        if (arguments.isEmpty() || "show".equalsIgnoreCase(arguments.get(0))) {
            printSavedConfig();
            return 0;
        }

        String subcommand = arguments.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "set" -> handleConfigSet(arguments.subList(1, arguments.size()));
            default -> throw new Exception("Use: config show | config set provider <provider-id> | config set model <name>");
        };
    }

    private static int handleConfigSet(List<String> arguments) throws Exception {
        if (arguments.size() != 2) {
            throw new Exception("Use: config set provider <provider-id> | config set model <name>");
        }

        String target = arguments.get(0).toLowerCase(Locale.ROOT);
        String value = arguments.get(1);
        UserConfigStore.StoredConfig currentConfig = currentStoredConfig();

        return switch (target) {
            case "provider" -> {
                String providerId = parseProviderId(value);
                int result = saveAndReportConfig(
                    currentConfig.withProvider(toStoredProviderId(providerId)).clearModel(),
                    new RuntimeAiSelection(providerId, null),
                    providerConfigSaveHeadline(providerId)
                );
                printProviderSetupHint(providerId);
                yield result;
            }
            case "model" -> saveAndReportConfig(
                currentConfig.withProvider(toStoredProviderId(currentProviderId)).withModel(value),
                new RuntimeAiSelection(currentProviderId, normalizeModelOverride(value)),
                "Saved model " + value + " in " + savedConfigFile.getFileName() + ". It will be used for subsequent commands."
            );
            default -> throw new Exception("Use: config set provider <provider-id> | config set model <name>");
        };
    }

    private static int saveAndReportConfig(
        UserConfigStore.StoredConfig config,
        RuntimeAiSelection currentSessionSelection,
        String headline
    ) throws Exception {
        saveStoredConfig(config);
        applyRuntimeAiSelection(currentSessionSelection);
        System.out.println(headline);
        System.out.println();
        return 0;
    }

    private static void saveStoredConfig(UserConfigStore.StoredConfig config) throws Exception {
        UserConfigStore configStore = userConfigStore();
        try {
            configStore.save(config);
        } catch (Exception exception) {
            throw new Exception("Failed to save config file " + configStore.configFile() + ": " + exception.getMessage(), exception);
        }
        reloadSavedConfig();
    }

    private static UserConfigStore.StoredConfig currentStoredConfig() {
        return new UserConfigStore.StoredConfig(
            UserConfigStore.CURRENT_SCHEMA_VERSION,
            toStoredProviderId(savedProviderId),
            savedModelOverride
        );
    }

    private static String toStoredProviderId(String providerId) {
        return ChatModelProviderRegistry.canonicalProviderId(providerId);
    }

    private static String providerConfigSaveHeadline(String providerId) {
        return "Saved provider " + renderProviderForDisplay(providerId) + " in " + savedConfigFile.getFileName() + ". It will be used for subsequent commands.";
    }

    private static void handleLegacyLoadAlias(List<String> arguments, Scanner scanner) throws Exception {
        if (arguments.isEmpty()) {
            throw new Exception("Use 'open <artifact>'.");
        }
        if ("--analysis-id".equals(arguments.get(0))) {
            throw new Exception(
                "`load --analysis-id` is no longer supported. Open the saved report files directly from the report directory if you need to review them."
            );
        }
        System.out.println("`load` is deprecated. Using 'open' instead.");
        handleOpenCommand(arguments, scanner);
    }

    private static void clearActiveContext() {
        loadedArtifact = null;
        clearActiveDiagnosticContext();
        System.out.println("Cleared the active diagnostic context.");
    }

    private static void reportUnsupportedOneShotStatefulCommand(String command) {
        System.out.println("Error: '" + command + "' is only available in the interactive shell.");
        System.out.println("       Use 'jtroubleshoot shell' for stateful context commands.");
    }

    private static void reportShellOnlyConfigurationCommand(String command) {
        System.out.println("Error: '" + command + "' is only available in the interactive shell.");
        System.out.println("       Use '--provider <provider-id>' and optional '--model <name>' for one-shot overrides.");
        System.out.println("       Use 'config show' or 'config set' to save defaults for future runs.");
        System.out.println("       Run 'jtroubleshoot provider list' to see supported provider ids.");
        System.out.println("       Start 'jtroubleshoot shell' to change providers for the current shell session.");
    }

    private static void reportRemovedCommand(String command) {
        System.out.println("Error: '" + command + "' is no longer supported.");
        System.out.println("       Saved reports remain on disk under the report directory shown by 'status' and 'version'.");
        System.out.println("       Open the saved report files directly if you want to review them.");
    }

    private static void reportRemovedShellCommand(String command) {
        System.out.println("Error: '" + command + "' is no longer supported.");
        System.out.println("       Use 'status' for runtime details, open saved report files directly from the report directory if needed, and use 'clear' to reset the shell state.");
    }

    private static ArtifactTargetRequest parseArtifactTargetRequest(List<String> arguments, String usage) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new Exception("Use: " + usage);
        }
        if (arguments.size() == 1) {
            return new ArtifactTargetRequest(arguments.get(0), null);
        }
        if (arguments.size() == 3 && "--type".equals(arguments.get(1))) {
            try {
                return new ArtifactTargetRequest(arguments.get(0), ArtifactType.fromExternalName(arguments.get(2)));
            } catch (IllegalArgumentException e) {
                throw new Exception("Invalid artifact type: " + arguments.get(2));
            }
        }
        throw new Exception("Use: " + usage);
    }

    private static AnalyzeTargetRequest parseAnalyzeTargetRequest(List<String> arguments, String usage) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new Exception("Use: " + usage);
        }

        List<String> inputPaths = new ArrayList<>();
        ArtifactType overrideType = null;

        for (int index = 0; index < arguments.size(); index++) {
            String token = arguments.get(index);
            if ("--type".equals(token)) {
                if (overrideType != null || index != arguments.size() - 2 || inputPaths.isEmpty()) {
                    throw new Exception("Use: " + usage);
                }
                if (inputPaths.size() != 1) {
                    throw new Exception("--type is only supported when analyzing a single diagnostic file.");
                }
                try {
                    overrideType = ArtifactType.fromExternalName(arguments.get(index + 1));
                } catch (IllegalArgumentException e) {
                    throw new Exception("Invalid artifact type: " + arguments.get(index + 1));
                }
                index++;
                continue;
            }
            inputPaths.add(token);
        }

        if (inputPaths.isEmpty()) {
            throw new Exception("Use: " + usage);
        }
        return new AnalyzeTargetRequest(List.copyOf(inputPaths), overrideType);
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

    private static void executeAnalyzeRoute(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory
    ) throws Exception {
        RuntimeRoutingPolicy.AnalyzeCommandRoute route = RuntimeRoutingPolicy.selectAnalyzeCommandRoute(
            artifacts,
            comparisonService::supports
        );
        if (!route.supported()) {
            throw new Exception(route.message());
        }

        switch (route.mode()) {
            case SINGLE_ARTIFACT -> analyzeSingleArtifact(artifacts.getFirst(), artifactInventory);
            case COMPARE_PAIR -> compareArtifactsAutoOrdered(
                artifacts,
                artifactInventory,
                "Comparing 2 " + artifacts.getFirst().type().description() + " artifacts..."
            );
            case SEQUENCE_SET -> analyzeArtifactSequenceAutoOrdered(
                artifacts,
                artifactInventory,
                "Analyzing " + artifacts.size() + " " + artifacts.getFirst().type().description() + " artifacts as a diagnostic sequence..."
            );
            case CORRELATE_SET -> correlateArtifacts(
                artifacts,
                artifactInventory,
                "Correlating " + artifacts.size() + " diagnostic artifacts..."
            );
            case UNSUPPORTED -> throw new Exception(route.message());
        }
    }

    private static AskRoute selectAskRoute(List<InputArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return new AskRoute(
                AskRouteMode.UNSUPPORTED,
                "No diagnostic artifact is active. Open a diagnostic artifact or run analyze first."
            );
        }

        if (!artifacts.stream().allMatch(RuntimeRoutingPolicy::supportsStructuredAnalysis)) {
            return new AskRoute(
                AskRouteMode.UNSUPPORTED,
                "One or more active artifacts are not supported by the AI troubleshooting pipeline."
            );
        }

        if (artifacts.size() == 1) {
            return new AskRoute(AskRouteMode.SINGLE_ARTIFACT, null);
        }

        ArtifactType firstType = artifacts.getFirst().type();
        boolean sameTypeSet = artifacts.stream().allMatch(artifact -> artifact.type() == firstType);
        boolean comparisonAvailable = firstType != null
            && RuntimeRoutingPolicy.supportsStructuredComparison(firstType)
            && comparisonService.supports(firstType);
        if (sameTypeSet && artifacts.size() == 2 && comparisonAvailable) {
            return new AskRoute(AskRouteMode.COMPARE_PAIR, null);
        }
        if (sameTypeSet && artifacts.size() > 2 && comparisonAvailable) {
            return new AskRoute(AskRouteMode.SEQUENCE_SET, null);
        }

        if (RuntimeRoutingPolicy.supportsStructuredCorrelation(artifacts)) {
            return new AskRoute(AskRouteMode.CORRELATE_SET, null);
        }

        return new AskRoute(
            AskRouteMode.UNSUPPORTED,
            "The active diagnostic context cannot be routed to an AI question-answering path."
        );
    }

    /**
     * Analyzes a single canonical input artifact.
     */
    private static void analyzeSingleArtifact(InputArtifact artifact) {
        analyzeSingleArtifact(artifact, List.of());
    }

    private static void analyzeSingleArtifact(
        InputArtifact artifact,
        List<ArtifactInventoryEntry> artifactInventory
    ) {
        System.out.println("Analyzing " + artifact.type().description() + " from " + artifactSourcePath(artifact) + "...");
        loadedArtifact = artifact;
        setActiveDiagnosticContext(List.of(artifact));

        if (!RuntimeRoutingPolicy.supportsStructuredAnalysis(artifact)) {
            reportUnsupportedStructuredAnalysis(artifact);
            return;
        }

        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().analyze(artifact),
                artifactInventory
            );
            presentAcceptedReport("\n======= JVM Troubleshooting Analysis ========", report);
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent analysis", e);
        }
    }

    private static void runStructuredComparison(Path baselinePath, Path currentPath) {
        try {
            InputArtifact baselineArtifact = artifactLoader.load(baselinePath);
            InputArtifact currentArtifact = artifactLoader.load(currentPath);
            compareArtifacts(baselineArtifact, currentArtifact, List.of(), null);
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
            System.out.println("       If this file is a supported artifact, rerun 'analyze <path> --type <type>' or use 'open <path> --type <type>' in the shell.");
        } else {
            System.out.println("       This runtime does not fall back to ungrounded raw artifact chat.");
        }
        System.out.println("       Supported analysis types: GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL.");
    }

    private static void reportUnsupportedStructuredComparison(ArtifactType artifactType) {
        String typeDescription = artifactType != null ? artifactType.description() : "unknown";
        System.out.println("Error: AI specialist-agent comparison is not available for " + typeDescription + " artifacts.");
        System.out.println("       Supported comparison types: GC_LOG, JFR, THREAD_DUMP, HEAP_HISTOGRAM, NMT, PMAP.");
    }

    private static void reportUnsupportedStructuredQuestioning(String detail) {
        System.out.println("Error: Ask could not use the current diagnostic context.");
        if (detail != null && !detail.isBlank()) {
            System.out.println("       " + detail);
        }
        System.out.println("       Open a diagnostic artifact or run analyze in 'jtroubleshoot shell', then ask your question again.");
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

    private static void askQuestionAgainstActiveContext(String question) {
        if (!hasActiveDiagnosticContext()) {
            reportUnavailableQuestioningContext();
            return;
        }

        AskRoute route = selectAskRoute(activeDiagnosticArtifacts);
        if (!route.supported()) {
            reportUnsupportedStructuredQuestioning(route.message());
            return;
        }

        String result;
        try {
            result = switch (route.mode()) {
                case SINGLE_ARTIFACT -> diagnosticAgentOrchestrator().answerSingleArtifactQuestion(activeDiagnosticArtifacts.getFirst(), question);
                case COMPARE_PAIR -> diagnosticAgentOrchestrator().answerComparisonQuestionAutoOrdered(activeDiagnosticArtifacts, question);
                case SEQUENCE_SET -> diagnosticAgentOrchestrator().answerSequenceQuestionAutoOrdered(activeDiagnosticArtifacts, question);
                case CORRELATE_SET -> diagnosticAgentOrchestrator().answerCorrelationQuestion(activeDiagnosticArtifacts, question);
                case UNSUPPORTED -> null;
            };
        } catch (Exception exception) {
            reportStructuredPathFailure("AI follow-up question", exception);
            return;
        }

        if (result == null || result.isBlank()) {
            reportUnavailableQuestionAnswering();
            return;
        }

        System.out.println("Question: " + question);
        System.out.println("Context: " + activeDiagnosticContextLabel());
        System.out.println("======= Response =======\n\n" + result.strip() + "\n");
    }

    /**
     * Correlates multiple diagnostic artifacts.
     */
    private static void correlateArtifacts(List<InputArtifact> artifacts) {
        correlateArtifacts(artifacts, List.of(), "Correlating " + artifacts.size() + " diagnostic files...");
    }

    private static void correlateArtifacts(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory,
        String introMessage
    ) {
        if (introMessage != null && !introMessage.isBlank()) {
            System.out.println(introMessage);
        }
        setActiveDiagnosticContext(artifacts);

        if (!RuntimeRoutingPolicy.supportsStructuredCorrelation(artifacts)) {
            reportUnsupportedStructuredCorrelation(artifacts);
            return;
        }

        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().correlate(artifacts),
                artifactInventory
            );
            loadedArtifact = null;
            if (!presentAcceptedReport("======= JVM Multi-Artifact Analysis ========", report)) {
                return;
            }
            System.out.println();
            return;
        } catch (Exception e) {
            reportStructuredPathFailure("AI multi-artifact correlation", e);
        }
    }

    private static void compareArtifacts(
        InputArtifact baselineArtifact,
        InputArtifact currentArtifact,
        List<ArtifactInventoryEntry> artifactInventory,
        String introMessage
    ) {
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

        if (introMessage != null && !introMessage.isBlank()) {
            System.out.println(introMessage);
        }

        if (!RuntimeRoutingPolicy.supportsStructuredComparison(baselineType) || !comparisonService.supports(baselineType)) {
            reportUnsupportedStructuredComparison(baselineType);
            return;
        }

        setActiveDiagnosticContext(List.of(baselineArtifact, currentArtifact));
        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().compare(baselineArtifact, currentArtifact),
                artifactInventory
            );
            loadedArtifact = null;
            if (!presentAcceptedReport("======= JVM Comparison Analysis ========", report)) {
                return;
            }
            System.out.println();
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent comparison", e);
        }
    }

    private static void compareArtifactsAutoOrdered(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory,
        String introMessage
    ) {
        if (artifacts == null || artifacts.size() != 2) {
            System.out.println("Error: Auto comparison requires exactly two same-type comparable artifacts.");
            return;
        }

        ArtifactType artifactType = artifacts.getFirst().type();
        boolean sameType = artifacts.stream().allMatch(artifact -> artifact.type() == artifactType);
        if (!sameType) {
            System.out.println("Error: Auto comparison requires artifacts of the same type.");
            return;
        }

        if (introMessage != null && !introMessage.isBlank()) {
            System.out.println(introMessage);
        }

        if (!RuntimeRoutingPolicy.supportsStructuredComparison(artifactType) || !comparisonService.supports(artifactType)) {
            reportUnsupportedStructuredComparison(artifactType);
            return;
        }

        setActiveDiagnosticContext(artifacts);
        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().compareAutoOrdered(artifacts),
                artifactInventory
            );
            loadedArtifact = null;
            if (!presentAcceptedReport("======= JVM Comparison Analysis ========", report)) {
                return;
            }
            System.out.println();
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent comparison", e);
        }
    }

    private static void analyzeArtifactSequence(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory,
        String introMessage
    ) {
        if (artifacts == null || artifacts.size() < 3) {
            System.out.println("Error: Sequence analysis requires at least three same-type comparable artifacts.");
            return;
        }

        ArtifactType artifactType = artifacts.getFirst().type();
        boolean sameType = artifacts.stream().allMatch(artifact -> artifact.type() == artifactType);
        if (!sameType) {
            System.out.println("Error: Sequence analysis requires artifacts of the same type.");
            return;
        }

        if (introMessage != null && !introMessage.isBlank()) {
            System.out.println(introMessage);
        }

        if (!RuntimeRoutingPolicy.supportsStructuredComparison(artifactType) || !comparisonService.supports(artifactType)) {
            reportUnsupportedStructuredComparison(artifactType);
            return;
        }

        setActiveDiagnosticContext(artifacts);
        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().sequence(artifacts),
                artifactInventory
            );
            loadedArtifact = null;
            if (!presentAcceptedReport("======= JVM Trend Analysis ========", report)) {
                return;
            }
            System.out.println();
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent sequence analysis", e);
        }
    }

    private static void analyzeArtifactSequenceAutoOrdered(
        List<InputArtifact> artifacts,
        List<ArtifactInventoryEntry> artifactInventory,
        String introMessage
    ) {
        if (artifacts == null || artifacts.size() < 3) {
            System.out.println("Error: Sequence analysis requires at least three same-type comparable artifacts.");
            return;
        }

        ArtifactType artifactType = artifacts.getFirst().type();
        boolean sameType = artifacts.stream().allMatch(artifact -> artifact.type() == artifactType);
        if (!sameType) {
            System.out.println("Error: Sequence analysis requires artifacts of the same type.");
            return;
        }

        if (introMessage != null && !introMessage.isBlank()) {
            System.out.println(introMessage);
        }

        if (!RuntimeRoutingPolicy.supportsStructuredComparison(artifactType) || !comparisonService.supports(artifactType)) {
            reportUnsupportedStructuredComparison(artifactType);
            return;
        }

        setActiveDiagnosticContext(artifacts);
        try {
            var report = withArtifactInventory(
                diagnosticAgentOrchestrator().sequenceAutoOrdered(artifacts),
                artifactInventory
            );
            loadedArtifact = null;
            if (!presentAcceptedReport("======= JVM Trend Analysis ========", report)) {
                return;
            }
            System.out.println();
        } catch (Exception e) {
            reportStructuredPathFailure("AI specialist-agent sequence analysis", e);
        }
    }

    private static AnalysisReport withArtifactInventory(
        AnalysisReport report,
        List<ArtifactInventoryEntry> artifactInventory
    ) {
        if (report == null || artifactInventory == null || artifactInventory.isEmpty()) {
            return report;
        }
        return report.withArtifactInventory(artifactInventory);
    }

    private static void addArtifacts(
        Map<String, InputArtifact> artifactsBySourcePath,
        List<InputArtifact> artifacts
    ) {
        if (artifacts == null || artifacts.isEmpty()) {
            return;
        }
        for (InputArtifact artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            artifactsBySourcePath.putIfAbsent(artifactSourcePath(artifact), artifact);
        }
    }

    private static void addArtifactInventory(
        Map<String, ArtifactInventoryEntry> inventoryByKey,
        List<ArtifactInventoryEntry> artifactInventory
    ) {
        if (artifactInventory == null || artifactInventory.isEmpty()) {
            return;
        }
        for (ArtifactInventoryEntry entry : artifactInventory) {
            if (entry == null) {
                continue;
            }
            String inventoryKey = entry.status() + "|" + entry.sourcePath();
            inventoryByKey.putIfAbsent(inventoryKey, entry);
        }
    }

    private static ArtifactInventoryEntry toSupportedInventoryEntry(InputArtifact artifact) {
        if (artifact == null || artifact.metadata() == null) {
            return new ArtifactInventoryEntry(
                artifactSourcePath(artifact),
                artifactSourcePath(artifact),
                artifact != null ? artifact.type() : ArtifactType.UNKNOWN,
                com.javaassistant.diagnostics.ArtifactInventoryStatus.SUPPORTED,
                "Included in structured analysis."
            );
        }

        return new ArtifactInventoryEntry(
            artifact.metadata().sourcePath(),
            artifact.metadata().displayName(),
            artifact.type(),
            com.javaassistant.diagnostics.ArtifactInventoryStatus.SUPPORTED,
            "Included in structured analysis."
        );
    }

    private static String unknownArtifactTypeMessage(String filePath) {
        return "Unable to determine the artifact type for "
            + filePath
            + ". Re-run with --type <type>. Supported types: GC_LOG, JFR, THREAD_DUMP, HS_ERR_LOG, NMT, HEAP_HISTOGRAM, PMAP, CONTAINER_MEMORY, OOM_SIGNAL.";
    }

    private static void reportStructuredPathFailure(String operation, Exception exception) {
        System.err.println("[Error] " + operation + " failed: " + exception.getMessage());
        System.err.println("[Error] The ungrounded raw prompt compatibility path is intentionally disabled.");
    }

    private static AskRequest parseAskRequest(List<String> arguments, boolean allowContextFallback) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            return new AskRequest("");
        }
        if ("--analysis-id".equals(arguments.get(0))) {
            throw new Exception(
                "ask no longer uses saved reports. Start 'jtroubleshoot shell', open or analyze diagnostic artifacts, then run 'ask <question>'."
            );
        }
        if (!allowContextFallback) {
            throw new Exception(
                "ask uses the active diagnostic context in 'jtroubleshoot shell'. Start the shell, open or analyze diagnostic artifacts, then run 'ask <question>'."
            );
        }
        return new AskRequest(joinTokens(arguments));
    }

    private static String parseProviderId(String providerName) throws Exception {
        String canonicalProviderId = ChatModelProviderRegistry.canonicalProviderId(providerName);
        if (canonicalProviderId == null) {
            throw new Exception(
                "Invalid provider '" + providerName + "'. Valid options: " + ChatModelProviderRegistry.supportedProviderIdList()
            );
        }
        return canonicalProviderId;
    }

    private static boolean canPresentUserFacingAnalysis(AnalysisReport report) {
        return report != null && report.hasAiAgentBackedUserNarrative();
    }

    private static Path saveAcceptedAgentReport(AnalysisReport report) throws Exception {
        if (!canPresentUserFacingAnalysis(report)) {
            reportUnavailableAgentAnalysis(report);
            return null;
        }
        return reportBundleService.save(report);
    }

    private static boolean presentAcceptedReport(String heading, AnalysisReport report) throws Exception {
        Path savedBundle = saveAcceptedAgentReport(report);
        if (savedBundle == null) {
            return false;
        }
        System.out.println(heading + "\n\n" + userConsoleReportRenderer.render(report) + "\n");
        System.out.println("Saved report bundle: " + savedBundle);
        return true;
    }

    private static void reportUnavailableAgentAnalysis(AnalysisReport report) {
        System.out.println("AI agent analysis is unavailable right now. No troubleshooting analysis was shown or saved.");
        AgentAnalysisRejectionSummary rejectionSummary = unavailableAgentAnalysisSummary(report);
        if (rejectionSummary != null) {
            System.out.println("       Why it was held back: " + rejectionSummary.reason());
        }
        String modelName = resolvedConfiguredModelName();
        if (currentProviderId != null && modelName != null && !modelName.isBlank()) {
            System.out.println("       Active AI setup: " + formatAiSelection(currentProviderId, currentModelOverride));
        } else if (currentProviderId != null) {
            System.out.println("       Active AI setup: " + renderProviderForDisplay(currentProviderId));
        }
        if (rejectionSummary != null) {
            System.out.println("       Suggested next step: " + rejectionSummary.nextStep());
        } else {
            System.out.println("       Suggested next step: Verify provider/model availability and retry the command.");
        }
    }

    static String unavailableAgentAnalysisReason(AnalysisReport report) {
        AgentAnalysisRejectionSummary summary = unavailableAgentAnalysisSummary(report);
        return summary != null ? summary.reason() : null;
    }

    static String unavailableAgentAnalysisNextStep(AnalysisReport report) {
        AgentAnalysisRejectionSummary summary = unavailableAgentAnalysisSummary(report);
        return summary != null ? summary.nextStep() : null;
    }

    private static AgentAnalysisRejectionSummary unavailableAgentAnalysisSummary(AnalysisReport report) {
        AgentTraceability failedTraceability = mostRelevantFailedAiTraceability(report);
        if (failedTraceability == null) {
            return new AgentAnalysisRejectionSummary(
                "No AI troubleshooting response was available from the selected provider and model.",
                "Verify that the selected provider and model are reachable, then retry the analysis."
            );
        }

        AgentQualityGateResult failedGate = primaryFailedGate(failedTraceability);
        String specialistLabel = specialistLabel(failedTraceability);
        if (failedGate == null) {
            return new AgentAnalysisRejectionSummary(
                "The " + specialistLabel + " did not produce an acceptable troubleshooting response.",
                "Retry the analysis. If this repeats, verify provider availability and inspect the agent prompt or acceptance rules."
            );
        }

        return switch (failedGate.gateId()) {
            case "response-not-empty" -> responseNotEmptyRejectionSummary(specialistLabel, failedGate);
            case "coverage-aware-confidence" -> new AgentAnalysisRejectionSummary(
                "The " + specialistLabel + " did not use enough of the available diagnostic context to support a trustworthy conclusion.",
                "Retry with a stronger model or provider. If this keeps happening, increase the first-pass context or improve the agent's retrieval behavior."
            );
            case "user-language-only" -> new AgentAnalysisRejectionSummary(
                "The " + specialistLabel + " answered with internal system wording instead of direct troubleshooting guidance.",
                "Retry the analysis. If it repeats, the agent prompt needs tightening so the response stays user-facing."
            );
            case "model-execution-traceability" -> new AgentAnalysisRejectionSummary(
                "The AI response could not be verified with complete provider and model details, so it was not shown.",
                "Retry after fixing provider or model configuration capture, then rerun the analysis."
            );
            default -> new AgentAnalysisRejectionSummary(
                fallbackGateReason(failedTraceability, failedGate),
                "Retry the analysis after verifying provider/model availability. If it repeats, inspect the saved traceability for the rejected response."
            );
        };
    }

    private static AgentAnalysisRejectionSummary responseNotEmptyRejectionSummary(
        String specialistLabel,
        AgentQualityGateResult failedGate
    ) {
        String detail = failedGate != null ? failedGate.detail() : null;
        if (detail != null && detail.startsWith("The agent call failed before returning a response:")) {
            String failureDetail = detail.substring("The agent call failed before returning a response:".length()).trim();
            return new AgentAnalysisRejectionSummary(
                "The " + specialistLabel + " could not get a response from the configured AI model. Details: " + failureDetail,
                "Fix the provider/model access problem shown above, then retry the analysis."
            );
        }
        if (detail != null && detail.startsWith("The AI model returned an empty response")) {
            return new AgentAnalysisRejectionSummary(
                "The " + specialistLabel + " received an empty response from the AI model.",
                "Retry the analysis. If it repeats, try a different provider/model or inspect the model-side logs."
            );
        }
        return new AgentAnalysisRejectionSummary(
            "The " + specialistLabel + " did not return a troubleshooting response.",
            "Verify that the selected provider and model are reachable, then retry the analysis."
        );
    }

    private static AgentTraceability mostRelevantFailedAiTraceability(AnalysisReport report) {
        if (report == null || report.agentTraceability().isEmpty()) {
            return null;
        }
        AgentTraceability mostRecent = null;
        for (AgentTraceability traceability : report.agentTraceability()) {
            if (traceability != null && isAiNarrativeSource(traceability.narrativeSource())) {
                mostRecent = traceability;
            }
        }
        return mostRecent;
    }

    private static AgentQualityGateResult primaryFailedGate(AgentTraceability traceability) {
        if (traceability == null || traceability.qualityGates().isEmpty()) {
            return null;
        }
        List<String> priorityOrder = List.of(
            "response-not-empty",
            "coverage-aware-confidence",
            "user-language-only",
            "model-execution-traceability"
        );
        for (String gateId : priorityOrder) {
            for (AgentQualityGateResult result : traceability.qualityGates()) {
                if (result.status() == AgentQualityGateStatus.FAILED && gateId.equals(result.gateId())) {
                    return result;
                }
            }
        }
        for (AgentQualityGateResult result : traceability.qualityGates()) {
            if (result.status() == AgentQualityGateStatus.FAILED) {
                return result;
            }
        }
        return null;
    }

    private static boolean isAiNarrativeSource(AgentNarrativeSource narrativeSource) {
        return narrativeSource == AgentNarrativeSource.SPECIALIST_AGENT || narrativeSource == AgentNarrativeSource.SYNTHESIS_AGENT;
    }

    private static String specialistLabel(AgentTraceability traceability) {
        if (traceability == null || traceability.artifactType() == null) {
            return "AI specialist";
        }
        return switch (traceability.artifactType()) {
            case GC_LOG -> "GC specialist";
            case JFR -> "JFR specialist";
            case THREAD_DUMP -> "thread-dump specialist";
            case HS_ERR_LOG -> "crash-log specialist";
            case NMT -> "native-memory specialist";
            case HEAP_HISTOGRAM -> "heap-histogram specialist";
            case PMAP -> "pmap specialist";
            case CONTAINER_MEMORY -> "container-memory specialist";
            case OOM_SIGNAL -> "OOM-signal specialist";
            default -> "AI specialist";
        };
    }

    private static String fallbackGateReason(AgentTraceability traceability, AgentQualityGateResult failedGate) {
        String specialistLabel = specialistLabel(traceability);
        if (failedGate == null || failedGate.detail() == null || failedGate.detail().isBlank()) {
            return "The " + specialistLabel + " did not produce an acceptable troubleshooting response.";
        }
        return "The " + specialistLabel + " was held back because " + normalizeGateDetail(failedGate.detail());
    }

    private static String normalizeGateDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "the response did not pass the acceptance checks.";
        }
        String normalized = detail.strip();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("The ")) {
            normalized = Character.toLowerCase(normalized.charAt(4)) + normalized.substring(5);
        }
        return normalized + ".";
    }

    private static void reportUnavailableQuestioningContext() {
        System.out.println("AI follow-up requires an active diagnostic context.");
        System.out.println("       Start 'jtroubleshoot shell', open a diagnostic artifact or run analyze, then retry 'ask'.");
    }

    private static void reportUnavailableQuestionAnswering() {
        System.out.println("AI follow-up assistance is unavailable right now. No answer was generated.");
        System.out.println("       Verify provider/model availability and retry the question.");
    }

    private static String formatAiSelection(String providerId, String modelOverride) {
        String effectiveProviderId = providerId != null ? providerId : DEFAULT_PROVIDER_ID;
        String providerLabel = renderProviderForDisplay(effectiveProviderId);
        String modelName = resolveModelName(effectiveProviderId, modelOverride);
        return modelName != null && !modelName.isBlank()
            ? providerLabel + " / " + modelName
            : providerLabel;
    }

    private static String currentAiSelectionDescription() {
        return formatAiSelection(currentProviderId, currentModelOverride);
    }

    private static ProviderSetupStatus currentProviderSetupStatus() {
        return ChatModelProviderRegistry.provider(currentProviderId).setupStatus(currentModelOverride);
    }

    private static String savedDefaultsDescription() {
        if (savedProviderId == null && savedModelOverride == null) {
            return "(not set)";
        }
        return formatAiSelection(savedProviderId != null ? savedProviderId : DEFAULT_PROVIDER_ID, savedModelOverride);
    }

    private static String renderProviderForDisplay(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "(not set)";
        }
        ChatModelProviderFactory provider = ChatModelProviderRegistry.provider(providerId);
        return provider.displayName() + " (" + provider.id() + ")";
    }

    private static void printSavedConfig() {
        System.out.println("Saved AI defaults:");
        System.out.println("  File: " + describeFile(savedConfigFile, fileState(savedConfigFile)));
        System.out.println("  Defaults: " + savedDefaultsDescription());
        if (savedProviderId == null && savedModelOverride == null) {
            System.out.println("  Built-in default: " + formatAiSelection(DEFAULT_PROVIDER_ID, null));
        }
        System.out.println();
    }

    private static String describeResolvedEnvFile() {
        Path envPath = preferredEnvFilePath();
        return describeFile(envPath, fileState(envPath));
    }

    private static Path preferredEnvFilePath() {
        String explicitPath = System.getenv(EnvConfig.ENV_FILE_OVERRIDE_ENV_VAR);
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Path.of(explicitPath).toAbsolutePath().normalize();
        }
        Path resolvedPath = EnvConfig.resolvedEnvPath();
        if (resolvedPath != null) {
            return resolvedPath.toAbsolutePath().normalize();
        }
        return ApplicationRuntimeSupport.defaultEnvFile();
    }

    private static String fileState(Path path) {
        return Files.exists(path) ? "present" : "missing";
    }

    private static String describeFile(Path path, String state) {
        return path + " (" + state + ")";
    }

    private static String requiredEnvironmentDescription(ChatModelProviderFactory provider) {
        return provider.configurationKeys().isEmpty() ? "(none)" : String.join(", ", provider.configurationKeys());
    }

    private static String readinessDescription(ProviderSetupStatus status) {
        return status.ready() ? "ready" : "needs setup";
    }

    private static void printProviderSetupHint(String providerId) {
        ChatModelProviderFactory provider = ChatModelProviderRegistry.provider(providerId);
        List<String> nextSteps = new ArrayList<>();
        if (!provider.configurationKeys().isEmpty()) {
            nextSteps.add("set " + formatEnvironmentKeys(provider.configurationKeys()) + " in " + preferredEnvFilePath() + " or your shell");
        }
        if (provider.documentedDefaultModelName() == null) {
            nextSteps.add("choose a model with `jtroubleshoot config set model <name>`");
        }
        if (nextSteps.isEmpty()) {
            return;
        }
        System.out.println("Next: " + String.join("; ", nextSteps) + ".");
        System.out.println();
    }

    private static String formatEnvironmentKeys(List<String> keys) {
        return keys.stream()
            .map(key -> "`" + key + "`")
            .reduce((left, right) -> left + ", " + right)
            .orElse("(none)");
    }

    /**
     * Shows the current status
     */
    private static void showStatus(InputArtifact loadedArtifact) {
        ProviderSetupStatus setupStatus = currentProviderSetupStatus();
        System.out.println("AI setup:");
        System.out.println("  Provider: " + renderProviderForDisplay(currentProviderId));
        printConfiguredModel("  Model: ");
        System.out.println("  Ready: " + (setupStatus.ready() ? "yes" : "no"));
        System.out.println("  Config: " + describeFile(savedConfigFile, fileState(savedConfigFile)));
        System.out.println("  Env: " + describeResolvedEnvFile());
        System.out.println("  Reports: " + reportBundleService.baseDirectory());
        System.out.println("  Saved AI defaults: " + savedDefaultsDescription());

        List<String> missingSetup = setupStatus.checks().stream()
            .filter(check -> check.status() == ProviderSetupStatus.Status.MISSING)
            .map(ProviderSetupStatus.Check::label)
            .toList();
        if (!missingSetup.isEmpty()) {
            System.out.println("  Needs: " + String.join(", ", missingSetup));
        }

        if (hasActiveDiagnosticContext() || loadedArtifact != null) {
            System.out.println();
            System.out.println("Active diagnostic context:");
            if (hasActiveDiagnosticContext()) {
                System.out.println("  Diagnostics: " + activeDiagnosticContextLabel());
            }
            if (loadedArtifact != null) {
                System.out.println(
                    "  Open artifact: " + artifactSourcePath(loadedArtifact)
                        + " ("
                        + loadedArtifact.type().description()
                        + ", "
                        + artifactContentLength(loadedArtifact)
                        + " chars)"
                );
            }
        }

        List<String> nextSteps = recommendedStatusNextSteps(setupStatus);
        if (!nextSteps.isEmpty()) {
            System.out.println();
            System.out.println("Next:");
            for (String nextStep : nextSteps) {
                System.out.println("  - " + nextStep);
            }
        }
    }

    private static List<String> recommendedStatusNextSteps(ProviderSetupStatus setupStatus) {
        if (setupStatus.ready()) {
            return List.of(
                "Run `jtroubleshoot analyze <artifact-or-dir>`.",
                "Start `jtroubleshoot shell` if you want to ask follow-up questions against active diagnostic data.",
                "Saved report bundles are written to the report directory shown above."
            );
        }

        List<String> nextSteps = new ArrayList<>();
        if (!savedConfigExists) {
            nextSteps.add("Save defaults with `jtroubleshoot config set provider <provider-id>` if you want them persisted.");
        }

        for (ProviderSetupStatus.Check check : setupStatus.checks()) {
            if (check.status() != ProviderSetupStatus.Status.MISSING) {
                continue;
            }
            if ("Model selection".equals(check.label())) {
                nextSteps.add("Set a model with `jtroubleshoot config set model <name>`.");
            } else {
                nextSteps.add("Set `" + check.label() + "` in " + preferredEnvFilePath() + " or your shell environment.");
            }
        }

        nextSteps.add("Run `jtroubleshoot analyze <artifact-or-dir>` after the setup is complete.");
        return List.copyOf(nextSteps);
    }

    private static void printVersion() {
        ProviderSetupStatus setupStatus = currentProviderSetupStatus();
        System.out.println("Application Version:");
        System.out.println("  Name: " + ApplicationRuntimeSupport.applicationName());
        System.out.println("  Version: " + ApplicationRuntimeSupport.applicationVersion());
        System.out.println("  Report schema version: " + AnalysisReport.CURRENT_SCHEMA_VERSION);
        System.out.println("  Java runtime: " + ApplicationRuntimeSupport.javaRuntimeDescription());
        System.out.println("  Provider: " + renderProviderForDisplay(currentProviderId));
        printConfiguredModel("  Model: ");
        System.out.println("  AI readiness: " + readinessDescription(setupStatus));
        System.out.println("  AI config file: " + savedConfigFile);
        System.out.println("  AI env file: " + describeResolvedEnvFile());
        System.out.println("  Saved AI defaults: " + savedDefaultsDescription());
        System.out.println("  Report bundle directory: " + reportBundleService.baseDirectory());
        System.out.println("  AI config file overrides:");
        System.out.println("    -D" + ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY + "=<path>");
        System.out.println("    -D" + ApplicationRuntimeSupport.LEGACY_CONFIG_FILE_SYSTEM_PROPERTY + "=<path> (legacy)");
        System.out.println("    " + ApplicationRuntimeSupport.CONFIG_FILE_ENV_VAR + "=<path>");
        System.out.println("  Report directory overrides:");
        System.out.println("    -D" + ApplicationRuntimeSupport.REPORT_DIRECTORY_SYSTEM_PROPERTY + "=<path>");
        System.out.println("    -D" + ApplicationRuntimeSupport.LEGACY_REPORT_DIRECTORY_SYSTEM_PROPERTY + "=<path> (legacy)");
        System.out.println("    " + ApplicationRuntimeSupport.REPORT_DIRECTORY_ENV_VAR + "=<path>");
    }

    private static void printConfiguredModel(String prefix) {
        String modelName = resolvedConfiguredModelName();
        if (modelName != null && !modelName.isBlank()) {
            System.out.println(prefix + modelName);
        }
    }

    private static void printProviderStatus() {
        ProviderSetupStatus setupStatus = currentProviderSetupStatus();
        ChatModelProviderFactory provider = ChatModelProviderRegistry.provider(currentProviderId);
        System.out.println("AI Provider:");
        System.out.println("  Provider: " + renderProviderForDisplay(currentProviderId));
        printConfiguredModel("  Model: ");
        System.out.println("  Setup mode: " + setupStatus.setupMode());
        System.out.println("  Required environment: " + requiredEnvironmentDescription(provider));
        System.out.println("  AI readiness: " + readinessDescription(setupStatus));
        System.out.println("  Saved AI defaults: " + savedDefaultsDescription());
        System.out.println();
    }

    private static void printSupportedProviders() {
        System.out.println("Supported AI Providers:");
        for (ChatModelProviderFactory provider : ChatModelProviderRegistry.providers()) {
            String modelName = provider.documentedDefaultModelName();
            System.out.println("- " + provider.displayName() + " (" + provider.id() + ")");
            System.out.println("  Setup: " + provider.setupModeDescription());
            if (modelName != null && !modelName.isBlank()) {
                System.out.println("  Default model: " + modelName);
            }
            System.out.println("  Required environment: " + requiredEnvironmentDescription(provider));
            for (String note : provider.configurationNotes()) {
                System.out.println("  Note: " + note);
            }
        }
        System.out.println();
    }

    private static void printCommandLineHelp() {
        System.out.println("jtroubleshoot");
        System.out.println("=============");
        System.out.println("AI-assisted JVM troubleshooting from the command line.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] analyze <artifact-or-dir> [more-artifacts-or-dirs ...] [--type <type>]");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] compare <baseline-file> <current-file>");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] correlate <artifact1> <artifact2> [artifact3 ...]");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] shell");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] version");
        System.out.println("  jtroubleshoot [--provider <provider-id>] [--model <name>] status");
        System.out.println("  jtroubleshoot provider list");
        System.out.println("  jtroubleshoot config show");
        System.out.println("  jtroubleshoot config set provider <provider-id>");
        System.out.println("  jtroubleshoot config set model <name>");
        System.out.println("  jtroubleshoot help");
        System.out.println();
        System.out.println("Global options:");
        System.out.println("  --provider <provider-id>  Temporarily override the saved or default provider");
        System.out.println("  --model <name>           Temporarily override the model for this command or shell session");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  jtroubleshoot provider list");
        System.out.println("  jtroubleshoot config show");
        System.out.println("  jtroubleshoot config set provider openai");
        System.out.println("  jtroubleshoot config set model gpt-4o-mini");
        System.out.println("  jtroubleshoot analyze samples/g1_21_smallheap_fullgcs.log");
        System.out.println("  jtroubleshoot analyze baseline-gc.log current-gc.log");
        System.out.println("  jtroubleshoot analyze gc-1.log gc-2.log gc-3.log");
        System.out.println("  jtroubleshoot analyze gc.log thread-dump.txt heap-histo.txt");
        System.out.println("  jtroubleshoot --provider anthropic analyze samples/g1_21_smallheap_fullgcs.log");
        System.out.println("  jtroubleshoot --provider ollama --model llama3.2 analyze samples/g1_21_smallheap_fullgcs.log");
        System.out.println("  jtroubleshoot analyze samples/single_process_data");
        System.out.println("  jtroubleshoot compare baseline.nmt current.nmt");
        System.out.println("  jtroubleshoot correlate gc.log nmt.txt pmap.txt");
        System.out.println("  jtroubleshoot shell");
        System.out.println();
        System.out.println("Run 'jtroubleshoot help shell' to see interactive shell commands, including follow-up questions against active diagnostic data.");
    }

    private static void printShellHelp() {
        System.out.println("jtroubleshoot shell");
        System.out.println("===================");
        System.out.println("Stateful shell commands:");
        System.out.println("  open <artifact> [--type <type>]              Open a diagnostic artifact as the active context");
        System.out.println("  analyze [<artifact-or-dir> ...]              Analyze one artifact, auto-compare two, auto-trend same-type sequences, or auto-correlate mixed inputs");
        System.out.println("  ask <question>                               Ask a follow-up question against the active context");
        System.out.println("  compare <baseline-file> <current-file>");
        System.out.println("  correlate <artifact1> <artifact2> [artifact3 ...]");
        System.out.println("  provider [show|list] | provider use <provider-id>  Change the provider for this shell session only");
        System.out.println("  config show");
        System.out.println("  config set provider <provider-id>");
        System.out.println("  config set model <name>");
        System.out.println("  clear");
        System.out.println("  help");
        System.out.println("  exit");
        System.out.println();
        System.out.println("Legacy alias still accepted: load.");
    }




}
