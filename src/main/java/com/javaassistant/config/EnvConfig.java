package com.javaassistant.config;

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
 * Central helper for reading configuration from a .env file while still
 * respecting real OS environment variables. Values found in the .env file take
 * precedence, but if a key is absent we fall back to {@link System#getenv} so
 * the application remains flexible in production setups.
 */
public final class EnvConfig {

    private static final Map<String, String> DOTENV = loadDotEnv();

    private EnvConfig() {
    }

    public static String get(String key) {
        String value = DOTENV.get(key);
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
            // If .env cannot be read we log to stderr and continue with an empty map.
            System.err.printf("[EnvConfig] Failed to read .env file (%s): %s%n", envPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Path resolveEnvPath() {
        String explicitPath = System.getenv("ENV_FILE");
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path path = Paths.get(explicitPath);
            if (Files.exists(path)) {
                return path;
            }
        }

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path envPath = cwd.resolve(".env");
        if (Files.exists(envPath)) {
            return envPath;
        }

        // Fallback: check project root if running from within submodules (e.g., target/classes)
        Path projectRoot = cwd.getParent();
        if (projectRoot != null) {
            Path parentEnv = projectRoot.resolve(".env");
            if (Files.exists(parentEnv)) {
                return parentEnv;
            }
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
