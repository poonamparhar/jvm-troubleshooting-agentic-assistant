package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class OCIChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "oci";
    public static final String TRACEABILITY_PROVIDER_ID = "OCI";
    public static final OCIChatModelProvider INSTANCE = new OCIChatModelProvider();

    public static final String DEFAULT_PROFILE = "DEFAULT";
    public static final String DEFAULT_MODEL_NAME = "xai.grok-4-fast-non-reasoning";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;

    private OCIChatModelProvider() {
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
            "`OCI_PROFILE` is optional when you do not want to use the default profile."
        );
    }

    @Override
    public String documentedDefaultModelName() {
        return DEFAULT_MODEL_NAME;
    }

    @Override
    public String setupModeDescription() {
        return "OCI native config plus compartment id";
    }

    @Override
    public List<String> setupGuidance() {
        return List.of(
            "No env file is usually needed for OCI auth because the tool reads your OCI CLI or SDK config.",
            "If OCI_COMPARTMENT_ID is not already in your shell environment, add it to `" + EnvConfig.PREFERRED_ENV_FILE_NAME + "`.",
            "Run `jtroubleshoot status` to verify that the OCI config and compartment id are both visible."
        );
    }

    @Override
    public ProviderSetupStatus setupStatus(String modelNameOverride) {
        String profile = EnvConfig.getOrDefault("OCI_PROFILE", DEFAULT_PROFILE);
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        checks.add(ProviderConfigSupport.requiredEnvCheck("Compartment id", "OCI_COMPARTMENT_ID"));
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
            String ociCompartmentId = EnvConfig.get("OCI_COMPARTMENT_ID");
            if (ociCompartmentId == null || ociCompartmentId.isBlank()) {
                throw new IllegalStateException(
                    "OCI_COMPARTMENT_ID is not configured. Please set it in your environment or " + EnvConfig.supportedEnvFileDescription() + "."
                );
            }

            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse("~/.oci/config", profile);
            SessionTokenAuthenticationDetailsProvider authProvider = new SessionTokenAuthenticationDetailsProvider(configFile);

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
}
