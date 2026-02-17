package com.example.modelproviders;

import com.example.config.EnvConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

public class OllamaChatModelProvider {

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL_NAME = "llama3.2";

    public static ChatModel createChatModel() {
        // Get configuration from environment variables or use defaults
        String baseUrl = EnvConfig.getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
        String modelName = EnvConfig.getOrDefault("OLLAMA_MODEL_NAME", DEFAULT_MODEL_NAME);

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
