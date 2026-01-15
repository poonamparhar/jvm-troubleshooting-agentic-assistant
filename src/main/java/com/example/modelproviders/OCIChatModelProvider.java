package com.example.modelproviders;

import dev.langchain4j.model.chat.ChatModel;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;

import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
// import dev.langchain4j.memory.chat.MessageWindowChatMemory;
// import dev.langchain4j.model.chat.ChatModel;
// import dev.langchain4j.service.AiServices;

import java.io.IOException;

public class OCIChatModelProvider {

    // private static String ociConfigPath;
    // private static String ociProfile;
    private static String ociCompartmentId;
    // private static String ociEndpoint;
    private static String modelName;

    // private static String configFile = "config.json";

    public static ChatModel createChatModel() {

        // Load configuration
        // Config config = Config.loadFromJson(configFile);
        // ociConfigPath = config.getOciConfigPath();
        // ociProfile = config.getOciProfile();
        ociCompartmentId = "ocid1.compartment.oc1..aaaaaaaaelntwstooo7apdllenrk4s45wrblsjzepulapqg4u4px6zwkwtsa";
        //"ocid1.tenancy.oc1..aaaaaaaajhbzef3dwuda6nytzggrlvjo6wt7dfcfexgqzguzhvtt2fc3h7ka";
        // ociEndpoint = "https://inference.generativeai.us-ashburn-1.oci.oraclecloud.com";
        modelName = "xai.grok-3";

        try {
            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse("~/.oci/config", "bmc_operator_access");
            // AuthenticationDetailsProvider provider = new SessionTokenAuthenticationDetailsProvider(configFile);

            // Configure the OCI Chat Model
            ChatModel model = OciGenAiChatModel.builder()
                    .compartmentId(ociCompartmentId)
                    //.authProvider(new ConfigFileAuthenticationDetailsProvider("DEFAULT"))
                    .authProvider(new SessionTokenAuthenticationDetailsProvider(configFile))
                    .modelName(modelName)
                    .temperature(0.7)
                    .maxTokens(20000)
                    .build();
            return model;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create OCI Chat Model", e);
        }
    }
}
