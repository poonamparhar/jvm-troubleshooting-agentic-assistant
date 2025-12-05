package com.example.modelproviders;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;


public class OllamaChatModelProvider {

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL_NAME = "llama3.2";

    public static ChatModel createChatModel() {
        // Get configuration from environment variables or use defaults
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_OLLAMA_BASE_URL;
        }

        String modelName = System.getenv("OLLAMA_MODEL_NAME");
        if (modelName == null || modelName.isEmpty()) {
            modelName = DEFAULT_MODEL_NAME;
        }

        try {
            // Configure the Ollama Chat Model
            ChatModel model = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(0.7)
                    // .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
                    .logRequests(true)
                    .build();
            return model;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Ollama Chat Model", e);
        }
    }
}
