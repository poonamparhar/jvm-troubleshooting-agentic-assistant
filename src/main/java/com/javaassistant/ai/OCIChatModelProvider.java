package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import com.javaassistant.UserConfigStore;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OCIChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "oci";
    public static final String TRACEABILITY_PROVIDER_ID = "OCI";
    public static final OCIChatModelProvider INSTANCE = new OCIChatModelProvider();
    static final String COMPARTMENT_OCID_PREFIX = "ocid1.compartment.";

    public static final String DEFAULT_PROFILE = "DEFAULT";
    public static final String DEFAULT_MODEL_NAME = "xai.grok-4-fast-non-reasoning";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
    public static final String OCI_AUTHENTICATION_METHOD_FIELD = "ociAuthenticationMethod";

    private OCIChatModelProvider() {
    }

    private enum OciAuthenticationMethod {
        API_KEY("api_key", "API key"),
        SESSION_TOKEN("session_token", "session token");

        private final String configValue;
        private final String description;

        OciAuthenticationMethod(String configValue, String description) {
            this.configValue = configValue;
            this.description = description;
        }

        private String configValue() {
            return configValue;
        }

        private String description() {
            return description + " (" + configValue + ")";
        }

        private static OciAuthenticationMethod fromConfigValue(String configuredValue) {
            if (configuredValue == null || configuredValue.isBlank()) {
                return API_KEY;
            }

            String normalized = configuredValue.strip()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

            return switch (normalized) {
                case "api_key", "apikey", "configfileauthenticationdetailsprovider" -> API_KEY;
                case "session_token", "sessiontoken", "sessiontokenauthenticationdetailsprovider" -> SESSION_TOKEN;
                default -> throw new IllegalArgumentException(
                    "Invalid `" + OCI_AUTHENTICATION_METHOD_FIELD + "` value `" + configuredValue + "`. Use `api_key` or `session_token`."
                );
            };
        }
    }

    public static String resolveAuthenticationMethodConfigValue(String configuredValue) {
        return OciAuthenticationMethod.fromConfigValue(configuredValue).configValue();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String traceabilityProviderId() {
        return TRACEABILITY_PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "OCI Generative AI";
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("OCI_COMPARTMENT_ID");
    }

    @Override
    public List<String> configurationNotes() {
        return List.of(
            "Reads OCI auth and profile details from the OCI CLI or SDK config, typically `~/.oci/config`.",
            "Set `" + OCI_AUTHENTICATION_METHOD_FIELD + "` in `config.json` to `api_key` for API key auth or `session_token` for security token auth.",
            "`OCI_PROFILE` is optional when you do not want to use the default profile."
        );
    }

    @Override
    public String documentedDefaultModelName() {
        return DEFAULT_MODEL_NAME;
    }

    @Override
    public String setupModeDescription() {
        return "OCI config profile plus compartment id";
    }

    @Override
    public List<String> setupGuidance() {
        return List.of(
            "No env file is usually needed for OCI auth because the tool reads your OCI CLI or SDK config.",
            "Use `" + OCI_AUTHENTICATION_METHOD_FIELD + "` in `config.json` to choose `api_key` or `session_token` auth.",
            "If OCI_COMPARTMENT_ID is not already in your shell environment, add it to `" + EnvConfig.PREFERRED_ENV_FILE_NAME + "`.",
            "Run `jtroubleshoot status` to verify that the OCI config and compartment id are both visible."
        );
    }

    @Override
    public ProviderSetupStatus setupStatus(String modelNameOverride) {
        String profile = EnvConfig.getOrDefault("OCI_PROFILE", DEFAULT_PROFILE);
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        checks.add(compartmentIdCheck());
        try {
            String configuredValue = UserConfigStore.loadResolvedOciAuthenticationMethod();
            OciAuthenticationMethod authenticationMethod = OciAuthenticationMethod.fromConfigValue(configuredValue);
            checks.add(ProviderSetupStatus.ready(
                OCI_AUTHENTICATION_METHOD_FIELD,
                "Using " + authenticationMethod.description() + "."
            ));
        } catch (IllegalArgumentException exception) {
            checks.add(ProviderSetupStatus.missing(OCI_AUTHENTICATION_METHOD_FIELD, exception.getMessage()));
        } catch (IOException exception) {
            checks.add(ProviderSetupStatus.missing(
                OCI_AUTHENTICATION_METHOD_FIELD,
                "Could not read config.json to resolve `" + OCI_AUTHENTICATION_METHOD_FIELD + "`."
            ));
        }
        try {
            ConfigFileReader.parse("~/.oci/config", profile);
            checks.add(ProviderSetupStatus.ready(
                "OCI config",
                "Read `~/.oci/config` with profile " + profile + "."
            ));
        } catch (Exception exception) {
            checks.add(ProviderSetupStatus.missing(
                "OCI config",
                "Could not read `~/.oci/config` with profile " + profile + "."
            ));
        }
        checks.add(ProviderSetupStatus.ready("Model selection", "Using " + resolveModelName(modelNameOverride) + "."));
        return ProviderConfigSupport.buildSetupStatus(
            setupModeDescription(),
            checks,
            checks.stream().noneMatch(check -> check.status() == ProviderSetupStatus.Status.MISSING)
                ? List.of("The selected OCI provider looks ready. You can run `analyze`, `compare`, or `correlate`.")
                : List.of(
                    "Make sure your OCI CLI or SDK config is readable and that the selected profile exists.",
                    "If OCI_COMPARTMENT_ID is not already in your shell environment, add it to `" + EnvConfig.PREFERRED_ENV_FILE_NAME + "`.",
                    "Run `jtroubleshoot status` after updating the setup."
                )
        );
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        String profile = EnvConfig.getOrDefault("OCI_PROFILE", DEFAULT_PROFILE);
        String modelName = resolveModelName(modelNameOverride);
        int contextWindowTokens = EnvConfig.getIntOrDefault("OCI_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            String ociCompartmentId = validatedCompartmentId(EnvConfig.get("OCI_COMPARTMENT_ID"));

            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse("~/.oci/config", profile);
            AuthenticationDetailsProvider authProvider = createAuthenticationDetailsProvider(
                configFile,
                OciAuthenticationMethod.fromConfigValue(UserConfigStore.loadResolvedOciAuthenticationMethod())
            );

            // Configure the OCI Chat Model
            return new ConfiguredChatModel(
                OciGenAiChatModel.builder()
                    .compartmentId(ociCompartmentId)
                    .authProvider(authProvider)
                    .modelName(modelName)
                    .temperature(0.7)
                    .maxTokens(131072)
                    .build(),
                traceabilityProviderId(),
                displayName(),
                modelName,
                ConfiguredChatModel.inferModelFamily(modelName),
                contextWindowTokens
            );
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create OCI Chat Model (I/O error while reading OCI config)", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OCI Chat Model: " + e.getMessage(), e);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        if (modelNameOverride != null && !modelNameOverride.isBlank()) {
            return modelNameOverride.strip();
        }
        return EnvConfig.getOrDefault("OCI_MODEL_NAME", DEFAULT_MODEL_NAME);
    }

    private static AuthenticationDetailsProvider createAuthenticationDetailsProvider(
        ConfigFileReader.ConfigFile configFile,
        OciAuthenticationMethod authenticationMethod
    ) throws IOException {
        return switch (authenticationMethod) {
            case API_KEY -> new ConfigFileAuthenticationDetailsProvider(configFile);
            case SESSION_TOKEN -> new SessionTokenAuthenticationDetailsProvider(configFile);
        };
    }

    private static ProviderSetupStatus.Check compartmentIdCheck() {
        try {
            validatedCompartmentId(EnvConfig.get("OCI_COMPARTMENT_ID"));
            return ProviderSetupStatus.ready("Compartment id", "Configured via OCI_COMPARTMENT_ID.");
        } catch (IllegalArgumentException exception) {
            return ProviderSetupStatus.missing("Compartment id", exception.getMessage());
        }
    }

    static String validatedCompartmentId(String configuredValue) {
        String normalized = ProviderConfigSupport.normalize(configuredValue);
        if (normalized == null) {
            throw new IllegalArgumentException(
                "Set OCI_COMPARTMENT_ID in your shell environment or " + EnvConfig.supportedEnvFileDescription() + "."
            );
        }
        if (!normalized.startsWith(COMPARTMENT_OCID_PREFIX)) {
            throw new IllegalArgumentException(
                "OCI_COMPARTMENT_ID must be a compartment OCID starting with `" + COMPARTMENT_OCID_PREFIX + "`."
            );
        }
        return normalized;
    }
}
