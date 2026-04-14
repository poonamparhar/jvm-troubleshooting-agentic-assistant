package com.javaassistant.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfiguredChatModelTest {

    @Test
    void closeQuietlyClosesAutoCloseableChatModels() {
        TestCloseableChatModel chatModel = new TestCloseableChatModel();
        ConfiguredChatModel configured = ConfiguredChatModel.synthetic(chatModel);

        configured.closeQuietly();

        assertTrue(chatModel.closed);
    }

    @Test
    void closeQuietlyInvokesAVisibleCloseMethodWhenPresent() {
        TestReflectiveCloseChatModel chatModel = new TestReflectiveCloseChatModel();
        ConfiguredChatModel configured = ConfiguredChatModel.synthetic(chatModel);

        configured.closeQuietly();

        assertTrue(chatModel.closed);
    }

    private static final class TestCloseableChatModel implements ChatModel, AutoCloseable {
        private boolean closed;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            throw new UnsupportedOperationException("Not needed for this test.");
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return null;
        }

        @Override
        public Set supportedCapabilities() {
            return Set.of();
        }

        @Override
        public List listeners() {
            return List.of();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TestReflectiveCloseChatModel implements ChatModel {
        private boolean closed;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            throw new UnsupportedOperationException("Not needed for this test.");
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return null;
        }

        @Override
        public Set supportedCapabilities() {
            return Set.of();
        }

        @Override
        public List listeners() {
            return List.of();
        }

        public void close() {
            closed = true;
        }
    }
}
