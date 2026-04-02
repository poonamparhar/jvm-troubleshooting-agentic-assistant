package com.javaassistant.modelproviders;

import com.javaassistant.config.EnvConfig;
import dev.langchain4j.model.ollama.OllamaChatModel;

public class OllamaChatModelProvider {

    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL_NAME = "llama3.2";
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 16384;

    public static ConfiguredChatModel createChatModel() {
        // Get configuration from environment variables or use defaults
        String baseUrl = EnvConfig.getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
        String modelName = EnvConfig.getOrDefault("OLLAMA_MODEL_NAME", DEFAULT_MODEL_NAME);
        int contextWindowTokens = EnvConfig.getIntOrDefault("OLLAMA_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            // Configure the Ollama Chat Model
            return new ConfiguredChatModel(
                OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(0.7)
                    // .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
                    .logRequests(true)
                    .build(),
                "OLLAMA",
                "Ollama",
                modelName,
                ConfiguredChatModel.inferModelFamily(modelName),
                contextWindowTokens
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Ollama Chat Model", e);
        }
    }
}
