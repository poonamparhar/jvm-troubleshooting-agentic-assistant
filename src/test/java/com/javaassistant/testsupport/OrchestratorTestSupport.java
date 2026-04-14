package com.javaassistant.testsupport;

import com.javaassistant.DiagnosticRuntimeFactory;
import com.javaassistant.orchestration.DiagnosticAgentOrchestrator;
import com.javaassistant.ai.ConfiguredChatModel;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Shared factory for end-to-end orchestrator tests.
 */
public final class OrchestratorTestSupport {

    private OrchestratorTestSupport() {
    }

    public static DiagnosticAgentOrchestrator createOrchestrator(ChatModel chatModel) {
        return createOrchestrator(chatModel != null ? ConfiguredChatModel.synthetic(chatModel) : null);
    }

    public static DiagnosticAgentOrchestrator createOrchestrator(ConfiguredChatModel configuredChatModel) {
        return DiagnosticRuntimeFactory.diagnosticAgentOrchestrator(configuredChatModel);
    }
}
