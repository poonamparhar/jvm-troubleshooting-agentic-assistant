package com.javaassistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central helper for reading configuration from the preferred
 * {@code jtroubleshoot.env} file, with fallback to legacy
 * {@code jtroubleshoot-ai.env} and {@code .env}, while still respecting real
 * OS environment variables. When the launcher provides an application home,
 * lookup is anchored there so local setup files stay next to the command. When
 * no application home is defined, lookup falls back to the current working
 * directory. Values found in the env file take precedence, but if a key is
 * absent we fall back to {@link System#getenv} so the application remains
 * flexible in production setups.
 */
public final class EnvConfig {

    public static final String ENV_FILE_OVERRIDE_ENV_VAR = "ENV_FILE";
    public static final String PREFERRED_ENV_FILE_NAME = "jtroubleshoot.env";
    public static final String LEGACY_APP_ENV_FILE_NAME = "jtroubleshoot-ai.env";
    public static final String LEGACY_ENV_FILE_NAME = ".env";

    private EnvConfig() {
    }

    public static String get(String key) {
        String value = loadDotEnv().get(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value;
    }

    public static String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    public static Integer getInt(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static int getIntOrDefault(String key, int defaultValue) {
        Integer value = getInt(key);
        return value != null ? value : defaultValue;
    }

    public static String supportedEnvFileDescription() {
        return PREFERRED_ENV_FILE_NAME
            + " (or legacy "
            + LEGACY_APP_ENV_FILE_NAME
            + " / "
            + LEGACY_ENV_FILE_NAME
            + ")";
    }

    public static Path resolvedEnvPath() {
        return resolveEnvPath();
    }

    private static Map<String, String> loadDotEnv() {
        Path envPath = resolveEnvPath();
        if (envPath == null || !Files.exists(envPath)) {
            return Collections.emptyMap();
        }

        try (BufferedReader reader = Files.newBufferedReader(envPath, StandardCharsets.UTF_8)) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(EnvConfig::splitKeyValue)
                    .filter(entry -> entry[0] != null)
                    .collect(Collectors.toMap(entry -> entry[0], entry -> entry[1], (first, second) -> second, HashMap::new));
        } catch (IOException e) {
            // If the configured env file cannot be read we log to stderr and continue with an empty map.
            System.err.printf("[EnvConfig] Failed to read env file (%s): %s%n", envPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Path resolveEnvPath() {
        String explicitPath = System.getenv(ENV_FILE_OVERRIDE_ENV_VAR);
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path path = Paths.get(explicitPath);
            if (Files.exists(path)) {
                return path.toAbsolutePath().normalize();
            }
        }

        Path applicationHome = explicitApplicationHome();
        if (applicationHome != null) {
            Path preferredRuntimeEnv = ApplicationRuntimeSupport.defaultEnvFile();
            if (preferredRuntimeEnv != null && Files.exists(preferredRuntimeEnv)) {
                return preferredRuntimeEnv.toAbsolutePath().normalize();
            }
            Path appHomeEnv = firstExistingEnvPath(applicationHome);
            if (appHomeEnv != null) {
                return appHomeEnv;
            }
            return null;
        }

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path envPath = firstExistingEnvPath(cwd);
        if (envPath != null) {
            return envPath;
        }

        // Fallback: check project root if running from within submodules (e.g., target/classes)
        Path projectRoot = cwd.getParent();
        if (projectRoot != null) {
            Path parentEnv = firstExistingEnvPath(projectRoot);
            if (parentEnv != null) {
                return parentEnv;
            }
        }
        return null;
    }

    private static Path explicitApplicationHome() {
        String configuredHome = System.getProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY);
        if (configuredHome == null || configuredHome.isBlank()) {
            return null;
        }
        return Paths.get(configuredHome).toAbsolutePath().normalize();
    }

    private static Path firstExistingEnvPath(Path directory) {
        if (directory == null) {
            return null;
        }

        Path preferredEnv = directory.resolve(PREFERRED_ENV_FILE_NAME);
        if (Files.exists(preferredEnv)) {
            return preferredEnv;
        }

        Path legacyAppEnv = directory.resolve(LEGACY_APP_ENV_FILE_NAME);
        if (Files.exists(legacyAppEnv)) {
            return legacyAppEnv;
        }

        Path legacyEnv = directory.resolve(LEGACY_ENV_FILE_NAME);
        if (Files.exists(legacyEnv)) {
            return legacyEnv;
        }

        return null;
    }

    private static String[] splitKeyValue(String line) {
        int equalsIndex = line.indexOf('=');
        if (equalsIndex < 0) {
            return new String[]{null, null};
        }
        String key = line.substring(0, equalsIndex).trim();
        String value = line.substring(equalsIndex + 1).trim();

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        return new String[]{key, value};
    }
}
