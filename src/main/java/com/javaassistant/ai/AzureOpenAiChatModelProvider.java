package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import java.util.ArrayList;
import java.util.List;

public final class AzureOpenAiChatModelProvider implements ChatModelProviderFactory {

    public static final String ID = "azure-openai";
    public static final String TRACEABILITY_PROVIDER_ID = "AZURE_OPENAI";
    public static final AzureOpenAiChatModelProvider INSTANCE = new AzureOpenAiChatModelProvider();

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 131072;

    private AzureOpenAiChatModelProvider() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String traceabilityProviderId() {
        return TRACEABILITY_PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Azure OpenAI";
    }

    @Override
    public List<String> aliases() {
        return List.of("azure");
    }

    @Override
    public List<String> configurationKeys() {
        return List.of("AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_API_KEY");
    }

    @Override
    public List<String> configurationNotes() {
        return List.of(
            "Set the Azure deployment name with `jtroubleshoot config set model <deployment>` or `--model <deployment>`.",
            "The API key alias `AZURE_OPENAI_KEY` is also accepted."
        );
    }

    @Override
    public String setupModeDescription() {
        return "Endpoint, API key, and deployment";
    }

    @Override
    public ProviderSetupStatus setupStatus(String modelNameOverride) {
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        checks.add(ProviderConfigSupport.requiredEnvCheck("Endpoint", "AZURE_OPENAI_ENDPOINT"));
        checks.add(ProviderConfigSupport.requiredEnvCheck("API key", "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_KEY"));
        checks.add(ProviderConfigSupport.resolvedModelCheck(resolveModelName(modelNameOverride)));
        return ProviderConfigSupport.buildSetupStatus(
            setupModeDescription(),
            checks,
            ProviderConfigSupport.genericNextSteps(
                configurationKeys(),
                resolveModelName(modelNameOverride) == null,
                checks.stream().noneMatch(check -> check.status() == ProviderSetupStatus.Status.MISSING)
            )
        );
    }

    @Override
    public ConfiguredChatModel createChatModel(String modelNameOverride) {
        String endpoint = ProviderConfigSupport.requiredEnvValue(
            "Azure OpenAI endpoint is not configured. Set AZURE_OPENAI_ENDPOINT in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "AZURE_OPENAI_ENDPOINT"
        );
        String apiKey = ProviderConfigSupport.requiredEnvValue(
            "Azure OpenAI API key is not configured. Set AZURE_OPENAI_API_KEY in your environment or "
                + EnvConfig.supportedEnvFileDescription()
                + ".",
            "AZURE_OPENAI_API_KEY",
            "AZURE_OPENAI_KEY"
        );
        String deploymentName = resolveModelName(modelNameOverride);
        if (deploymentName == null) {
            throw new IllegalStateException(
                "Azure OpenAI deployment is not configured. Use `jtroubleshoot config set model <deployment>` or pass `--model <deployment>`."
            );
        }
        int contextWindowTokens = EnvConfig.getIntOrDefault("AZURE_OPENAI_CONTEXT_WINDOW_TOKENS", DEFAULT_CONTEXT_WINDOW_TOKENS);

        try {
            AzureOpenAiChatModel.Builder builder = AzureOpenAiChatModel.builder()
                .endpoint(endpoint)
                .apiKey(apiKey)
                .deploymentName(deploymentName)
                .temperature(0.7);

            String serviceVersion = ProviderConfigSupport.envFirstNonBlank("AZURE_OPENAI_SERVICE_VERSION");
            if (serviceVersion != null) {
                builder.serviceVersion(serviceVersion);
            }

            return new ConfiguredChatModel(
                builder.build(),
                traceabilityProviderId(),
                displayName(),
                deploymentName,
                ConfiguredChatModel.inferModelFamily(deploymentName),
                contextWindowTokens
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to initialize Azure OpenAI Chat Model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String resolveModelName(String modelNameOverride) {
        return ProviderConfigSupport.firstNonBlank(
            modelNameOverride,
            ProviderConfigSupport.envFirstNonBlank("AZURE_OPENAI_DEPLOYMENT", "AZURE_OPENAI_MODEL_NAME")
        );
    }
}
