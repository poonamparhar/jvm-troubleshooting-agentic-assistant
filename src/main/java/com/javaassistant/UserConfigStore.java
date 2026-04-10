package com.javaassistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and saves the user's persistent CLI defaults from config.json.
 */
public final class UserConfigStore {

    static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Pattern INTEGER_FIELD_PATTERN_TEMPLATE =
        Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");
    private static final Pattern STRING_FIELD_PATTERN_TEMPLATE =
        Pattern.compile("\"%s\"\\s*:\\s*(null|\"(?:\\\\.|[^\"])*\")");

    private final Path configFile;

    UserConfigStore(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    public static String loadResolvedOciAuthenticationMethod() throws IOException {
        return new UserConfigStore(ApplicationRuntimeSupport.resolveUserConfigFile()).load().ociAuthenticationMethod();
    }

    Path configFile() {
        return configFile;
    }

    boolean exists() {
        return Files.exists(configFile);
    }

    StoredConfig load() throws IOException {
        if (!exists()) {
            return StoredConfig.empty();
        }

        String json = Files.readString(configFile, StandardCharsets.UTF_8);
        return parse(json);
    }

    void save(StoredConfig config) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configFile, toJson(config != null ? config : StoredConfig.empty()), StandardCharsets.UTF_8);
    }

    private StoredConfig parse(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return StoredConfig.empty();
        }

        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IOException("Config file must contain a JSON object.");
        }

        Integer schemaVersion = extractIntegerField(trimmed, "schemaVersion");
        String provider = extractStringField(trimmed, "provider");
        String model = extractStringField(trimmed, "model");
        String ociAuthenticationMethod = extractStringField(trimmed, "ociAuthenticationMethod");

        return new StoredConfig(
            schemaVersion != null ? schemaVersion : CURRENT_SCHEMA_VERSION,
            normalize(provider),
            normalize(model),
            normalize(ociAuthenticationMethod)
        );
    }

    private String toJson(StoredConfig config) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION);
        if (config.provider() != null) {
            builder.append(",\n  \"provider\": \"").append(escapeJson(config.provider())).append("\"");
        }
        if (config.model() != null) {
            builder.append(",\n  \"model\": \"").append(escapeJson(config.model())).append("\"");
        }
        if (config.ociAuthenticationMethod() != null) {
            builder.append(",\n  \"ociAuthenticationMethod\": \"").append(escapeJson(config.ociAuthenticationMethod())).append("\"");
        }
        builder.append("\n}\n");
        return builder.toString();
    }

    private static Integer extractIntegerField(String json, String fieldName) throws IOException {
        Matcher matcher = pattern(INTEGER_FIELD_PATTERN_TEMPLATE, fieldName).matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (containsField(json, fieldName)) {
            throw new IOException("Config field '" + fieldName + "' must be an integer.");
        }
        return null;
    }

    private static String extractStringField(String json, String fieldName) throws IOException {
        Matcher matcher = pattern(STRING_FIELD_PATTERN_TEMPLATE, fieldName).matcher(json);
        if (matcher.find()) {
            String token = matcher.group(1);
            if ("null".equals(token)) {
                return null;
            }
            return unescapeJson(token.substring(1, token.length() - 1));
        }
        if (containsField(json, fieldName)) {
            throw new IOException("Config field '" + fieldName + "' must be a string or null.");
        }
        return null;
    }

    private static Pattern pattern(Pattern template, String fieldName) {
        return Pattern.compile(String.format(template.pattern(), Pattern.quote(fieldName)));
    }

    private static boolean containsField(String json, String fieldName) {
        return json.contains("\"" + fieldName + "\"");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String escapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String unescapeJson(String value) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                builder.append(current);
                continue;
            }

            if (index + 1 >= value.length()) {
                throw new IOException("Invalid JSON escape sequence in config file.");
            }

            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"', '\\', '/' -> builder.append(escaped);
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IOException("Invalid unicode escape in config file.");
                    }
                    String hex = value.substring(index + 1, index + 5);
                    try {
                        builder.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException exception) {
                        throw new IOException("Invalid unicode escape in config file.", exception);
                    }
                    index += 4;
                }
                default -> throw new IOException("Unsupported escape sequence '\\" + escaped + "' in config file.");
            }
        }
        return builder.toString();
    }

    record StoredConfig(int schemaVersion, String provider, String model, String ociAuthenticationMethod) {

        static StoredConfig empty() {
            return new StoredConfig(CURRENT_SCHEMA_VERSION, null, null, null);
        }

        boolean isEmpty() {
            return provider == null && model == null && ociAuthenticationMethod == null;
        }

        StoredConfig withProvider(String newProvider) {
            return new StoredConfig(schemaVersion, normalize(newProvider), model, ociAuthenticationMethod);
        }

        StoredConfig withModel(String newModel) {
            return new StoredConfig(schemaVersion, provider, normalize(newModel), ociAuthenticationMethod);
        }

        StoredConfig withOciAuthenticationMethod(String newOciAuthenticationMethod) {
            return new StoredConfig(schemaVersion, provider, model, normalize(newOciAuthenticationMethod));
        }

        StoredConfig clearModel() {
            return new StoredConfig(schemaVersion, provider, null, ociAuthenticationMethod);
        }
    }
}
