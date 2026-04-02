package com.javaassistant.modelproviders;

import com.javaassistant.config.EnvConfig;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import java.io.IOException;

public class OCIChatModelProvider {

    // update the following variables as per your OCI setup
    private static final String profile = EnvConfig.getOrDefault("OCI_PROFILE", "DEFAULT");
    private static final String modelName = EnvConfig.getOrDefault("OCI_MODEL_NAME", "xai.grok-4");
    private static final int contextWindowTokens = EnvConfig.getIntOrDefault("OCI_CONTEXT_WINDOW_TOKENS", 32768);

    public static ConfiguredChatModel createChatModel() {
        try {
            String ociCompartmentId = EnvConfig.get("OCI_COMPARTMENT_ID");
            if (ociCompartmentId == null || ociCompartmentId.isBlank()) {
                throw new IllegalStateException("OCI_COMPARTMENT_ID is not configured. Please set it in your .env file.");
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
                "OCI",
                "OCI Generative AI",
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
}
