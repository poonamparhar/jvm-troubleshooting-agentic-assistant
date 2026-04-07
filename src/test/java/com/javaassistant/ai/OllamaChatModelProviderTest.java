package com.javaassistant.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import org.junit.jupiter.api.Test;

class OllamaChatModelProviderTest {

    @Test
    void compactLocalDetectionRecognizesDefaultLlama32Profile() {
        assertTrue(ModelProfileSupport.isCompactLocalModel("OLLAMA", "llama3.2", "llama3"));
        assertTrue(ModelProfileSupport.isCompactLocalModel("ollama", "qwen2.5:7b", "qwen2"));
        assertFalse(ModelProfileSupport.isCompactLocalModel("OCI", "xai.grok-4", "grok4"));
        assertFalse(ModelProfileSupport.isCompactLocalModel("OLLAMA", "qwen2.5:32b", "qwen2"));
    }

    @Test
    void compactLocalOllamaDefaultsCapOutputAndLowerTemperature() {
        ConfiguredChatModel configured = OllamaChatModelProvider.INSTANCE.createChatModel("llama3.2");

        try {
            assertEquals("llama3.2", configured.modelName());
            assertEquals("llama3", configured.modelFamily());
            assertEquals(16384, configured.approximateContextWindowTokens());

            ChatRequestParameters requestParameters = configured.chatModel().defaultRequestParameters();
            OllamaChatRequestParameters ollamaParameters = assertInstanceOf(OllamaChatRequestParameters.class, requestParameters);

            assertEquals(0.1d, ollamaParameters.temperature());
            assertEquals(700, ollamaParameters.maxOutputTokens());
        } finally {
            configured.closeQuietly();
        }
    }

    @Test
    void largerOllamaModelsKeepTheStandardGenerationCap() {
        ConfiguredChatModel configured = OllamaChatModelProvider.INSTANCE.createChatModel("qwen2.5:32b");

        try {
            ChatRequestParameters requestParameters = configured.chatModel().defaultRequestParameters();
            OllamaChatRequestParameters ollamaParameters = assertInstanceOf(OllamaChatRequestParameters.class, requestParameters);

            assertEquals(0.2d, ollamaParameters.temperature());
            assertEquals(1024, ollamaParameters.maxOutputTokens());
        } finally {
            configured.closeQuietly();
        }
    }
}
