package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating configured chat models for a named provider.
 */
public interface ChatModelProviderFactory {

    String id();

    String traceabilityProviderId();

    String displayName();

    default List<String> aliases() {
        return List.of();
    }

    default List<String> configurationKeys() {
        return List.of();
    }

    default List<String> configurationNotes() {
        return List.of();
    }

    default String documentedDefaultModelName() {
        return null;
    }

    default String setupModeDescription() {
        return configurationKeys().isEmpty()
            ? "No environment setup is normally required"
            : "Shell environment or `" + EnvConfig.PREFERRED_ENV_FILE_NAME + "`";
    }

    default List<String> setupGuidance() {
        List<String> guidance = new ArrayList<>();
        if (configurationKeys().isEmpty()) {
            guidance.add("No env file is normally required for this provider.");
        } else {
            guidance.add(
                "If the required settings are not already in your shell environment, add them to `"
                    + EnvConfig.PREFERRED_ENV_FILE_NAME
                    + "`."
            );
            guidance.add("Required environment: " + String.join(", ", configurationKeys()) + ".");
        }
        if (documentedDefaultModelName() == null) {
            guidance.add("Also set a model with `jtroubleshoot config set model <name>` or `--model <name>`.");
        }
        guidance.add("Run `jtroubleshoot status` to review the selected provider before analysis.");
        return List.copyOf(guidance);
    }

    default ProviderSetupStatus setupStatus(String modelNameOverride) {
        List<ProviderSetupStatus.Check> checks = new ArrayList<>();
        for (String key : configurationKeys()) {
            checks.add(ProviderConfigSupport.requiredEnvCheck(key, key));
        }
        String modelName = resolveModelName(modelNameOverride);
        checks.add(ProviderConfigSupport.resolvedModelCheck(modelName));
        boolean needsModelSelection = modelName == null || modelName.isBlank();
        return ProviderConfigSupport.buildSetupStatus(
            setupModeDescription(),
            checks,
            ProviderConfigSupport.genericNextSteps(configurationKeys(), needsModelSelection, !needsModelSelection && checks.stream()
                .noneMatch(check -> check.status() == ProviderSetupStatus.Status.MISSING))
        );
    }

    ConfiguredChatModel createChatModel(String modelNameOverride);

    String resolveModelName(String modelNameOverride);
}
