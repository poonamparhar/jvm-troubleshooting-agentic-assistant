package com.example.modelproviders;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import dev.langchain4j.model.chat.ChatModel;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;

import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import java.io.IOException;

public class OCIChatModelProvider {

    // update the following variables as per your OCI setup
    private static String ociCompartmentId = "ocid1.compartment.oc1..aaaaaaaaelntwstooo7apdllenrk4s45wrblsjzepulapqg4u4px6zwkwtsa";
    private static String profile = "bmc_operator_access";
    private static String modelName = "xai.grok-3";

    public static ChatModel createChatModel() {
        try {
            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse("~/.oci/config", profile);
            ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                    .readTimeoutMillis(120000) // 2 minutes
                    .build();

            GenerativeAiInferenceClient generativeAiInferenceClient = GenerativeAiInferenceClient.builder()
                    .configuration(clientConfiguration).build(new SessionTokenAuthenticationDetailsProvider(configFile));

            // Configure the OCI Chat Model
            ChatModel model = OciGenAiChatModel.builder()
                    .compartmentId(ociCompartmentId)
                    //use SessionTokenAuthenticationDetailsProvider for token based auth
                    // or change to ConfigFileAuthenticationDetailsProvider for config file based auth
                    //.authProvider(new ConfigFileAuthenticationDetailsProvider("DEFAULT"))
                    .modelName(modelName)
                    .genAiClient(generativeAiInferenceClient)
                    .temperature(0.7)
                    .maxTokens(131072)
                    .build();
            return model;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create OCI Chat Model", e);
        }
    }
}
