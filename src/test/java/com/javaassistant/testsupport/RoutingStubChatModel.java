package com.javaassistant.testsupport;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic chat stub that routes by prompt content for agent-orchestration tests.
 */
public class RoutingStubChatModel implements ChatModel {
    private final List<String> prompts = new ArrayList<>();
    private final boolean blankJfrSpecialistResponses;
    private final boolean internalTermJfrSpecialistResponse;

    public RoutingStubChatModel() {
        this(false, false);
    }

    public RoutingStubChatModel(boolean blankJfrSpecialistResponses) {
        this(blankJfrSpecialistResponses, false);
    }

    public RoutingStubChatModel(boolean blankJfrSpecialistResponses, boolean internalTermJfrSpecialistResponse) {
        this.blankJfrSpecialistResponses = blankJfrSpecialistResponses;
        this.internalTermJfrSpecialistResponse = internalTermJfrSpecialistResponse;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String prompt = chatRequest.messages().stream()
            .map(Object::toString)
            .reduce("", (left, right) -> left + "\n" + right);
        prompts.add(prompt);

        String responseText;
        if (prompt.contains("Analyze the following multi-artifact JVM diagnostic data:")) {
            responseText = "Correlation synthesis narrative";
        } else if (blankJfrSpecialistResponses && prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = "";
        } else if (internalTermJfrSpecialistResponse && prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = "Summary: The packet suggests a hot path issue.\nKey metrics: method cpu is elevated.\nLikely issues: the packet indicates CPU concentration.\nRecommended actions: inspect the hot method.\nNext steps: capture another recording.";
        } else if (prompt.contains("Analyze the following Java Flight Recorder diagnostic data:")) {
            responseText = "JFR specialist narrative";
        } else if (prompt.contains("Analyze the following heap histogram diagnostic data:")) {
            responseText = "Heap histogram specialist narrative";
        } else if (prompt.contains("Analyze the following GC log diagnostic data:")) {
            responseText = "GC specialist narrative";
        } else if (prompt.contains("Analyze the following Native Memory Tracking diagnostic data:")) {
            responseText = "NMT specialist narrative";
        } else if (prompt.contains("Analyze the following pmap diagnostic data:")) {
            responseText = "Pmap specialist narrative";
        } else {
            responseText = "Fallback AI narrative";
        }

        return ChatResponse.builder()
            .aiMessage(AiMessage.aiMessage(responseText))
            .build();
    }

    public List<String> prompts() {
        return prompts;
    }
}
