package com.javaassistant.ai;

import com.javaassistant.EnvConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for provider configuration resolution.
 */
final class ProviderConfigSupport {

    private ProviderConfigSupport() {
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    static String envFirstNonBlank(String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = normalize(EnvConfig.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String firstPresentEnvKey(String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            if (normalize(EnvConfig.get(key)) != null) {
                return key;
            }
        }
        return null;
    }

    static ProviderSetupStatus.Check requiredEnvCheck(String label, String... keys) {
        String presentKey = firstPresentEnvKey(keys);
        if (presentKey != null) {
            return ProviderSetupStatus.ready(label, "Configured via " + presentKey + ".");
        }
        return ProviderSetupStatus.missing(
            label,
            "Set " + renderKeyOptions(keys) + " in your shell environment or " + EnvConfig.supportedEnvFileDescription() + "."
        );
    }

    static ProviderSetupStatus.Check resolvedModelCheck(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderSetupStatus.missing(
                "Model selection",
                "Choose a model with `jtroubleshoot config set model <name>` or `--model <name>`."
            );
        }
        return ProviderSetupStatus.ready("Model selection", "Using " + modelName + ".");
    }

    static ProviderSetupStatus buildSetupStatus(
        String setupMode,
        List<ProviderSetupStatus.Check> checks,
        List<String> nextSteps
    ) {
        boolean ready = true;
        for (ProviderSetupStatus.Check check : checks) {
            if (check.status() == ProviderSetupStatus.Status.MISSING) {
                ready = false;
                break;
            }
        }
        return new ProviderSetupStatus(setupMode, ready, checks, nextSteps);
    }

    static List<String> genericNextSteps(
        List<String> requiredEnvironmentKeys,
        boolean needsModelSelection,
        boolean ready
    ) {
        List<String> steps = new ArrayList<>();
        if (ready) {
            steps.add("The selected provider looks ready. You can run `analyze`, `compare`, or `correlate`.");
            return List.copyOf(steps);
        }
        if (requiredEnvironmentKeys != null && !requiredEnvironmentKeys.isEmpty()) {
            steps.add(
                "If the required settings are not already in your shell environment, add them to `"
                    + EnvConfig.PREFERRED_ENV_FILE_NAME
                    + "`."
            );
        }
        if (needsModelSelection) {
            steps.add("Set a model with `jtroubleshoot config set model <name>` or pass `--model <name>`.");
        }
        steps.add("Run `jtroubleshoot status` to review the active provider setup.");
        return List.copyOf(steps);
    }

    static String renderKeyOptions(String... keys) {
        if (keys == null || keys.length == 0) {
            return "(environment variable)";
        }
        if (keys.length == 1) {
            return keys[0];
        }
        return String.join(", ", keys);
    }

    static String requiredEnvValue(String message, String... keys) {
        String value = envFirstNonBlank(keys);
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
