package com.example.modelproviders;

import com.example.config.EnvConfig;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import java.io.IOException;

public class OCIChatModelProvider {

    // update the following variables as per your OCI setup
    private static final String profile = EnvConfig.getOrDefault("OCI_PROFILE", "bmc_operator_access");
    private static final String modelName = EnvConfig.getOrDefault("OCI_MODEL_NAME", "xai.grok-4-1-fast-reasoning");

    public static ChatModel createChatModel() {
        try {
            String ociCompartmentId = EnvConfig.get("OCI_COMPARTMENT_ID");
            if (ociCompartmentId == null || ociCompartmentId.isBlank()) {
                throw new IllegalStateException("OCI_COMPARTMENT_ID is not configured. Please set it in your .env file.");
            }

            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse("~/.oci/config", profile);
            ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                    .readTimeoutMillis(120000) // 2 minutes
                    .build();

            SessionTokenAuthenticationDetailsProvider authProvider = new SessionTokenAuthenticationDetailsProvider(configFile);

            GenerativeAiInferenceClient generativeAiInferenceClient = GenerativeAiInferenceClient.builder()
                    .configuration(clientConfiguration)
                    .build(authProvider);

            // Configure the OCI Chat Model
            ChatModel model = OciGenAiChatModel.builder()
                    .compartmentId(ociCompartmentId)
                    .authProvider(authProvider)
                    .modelName(modelName)
                    // .genAiClient(generativeAiInferenceClient)
                    .temperature(0.7)
                    .maxTokens(131072)
                    .build();
            return model;
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create OCI Chat Model (I/O error while reading OCI config)", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OCI Chat Model: " + e.getMessage(), e);
        }
    }
}
