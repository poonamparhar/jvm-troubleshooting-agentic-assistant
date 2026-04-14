package com.javaassistant.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a deterministic safe-sharing profile to report renderings while leaving canonical JSON untouched.
 */
final class ShareableReportRedactor {

    static final String PROFILE_NAME = "internal-safe-v1";

    private static final Pattern EXPLICIT_COMMAND_PATTERN = Pattern.compile(
        "(?im)\\b(java command|java_command|command line|sun\\.java\\.command|launcher command|vm arguments)\\b(\\s*[:=]\\s*)([^\\n]+)"
    );
    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9_])([A-Z][A-Z0-9_]{2,})(\\s*=\\s*)([^\\s,;]+)"
    );
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9])/(?:[^\\s/]+/)*[^\\s)\\],;:]+"
    );
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9])[A-Za-z]:\\\\(?:[^\\s\\\\]+\\\\)*[^\\s)\\],;:]+"
    );
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("\\b[a-zA-Z0-9][a-zA-Z0-9-]*(?:\\.[a-zA-Z0-9-]+){1,}\\b");

    private static final List<String> FILE_LIKE_SUFFIXES = List.of(
        "cfg", "conf", "csv", "gz", "histogram", "html", "jar", "java", "json", "log", "md",
        "out", "properties", "sh", "so", "tar", "tmp", "txt", "war", "xml", "yaml", "yml"
    );

    private final Map<String, String> commandTokens = new LinkedHashMap<>();
    private final Map<String, String> environmentTokens = new LinkedHashMap<>();
    private final Map<String, String> pathTokens = new LinkedHashMap<>();
    private final Map<String, String> hostTokens = new LinkedHashMap<>();

    String profileName() {
        return PROFILE_NAME;
    }

    String shareabilityNotice() {
        return "Shareable rendering uses "
            + PROFILE_NAME
            + " redaction. report.json remains the canonical local analysis artifact; external binary inputs such as .jfr recordings are referenced by path rather than embedded bytes.";
    }

    String redact(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }

        String redacted = value;
        redacted = replaceExplicitCommandLines(redacted);
        redacted = replaceEnvironmentAssignments(redacted);
        redacted = replacePattern(redacted, UNIX_PATH_PATTERN, pathTokens, "path", token -> true);
        redacted = replacePattern(redacted, WINDOWS_PATH_PATTERN, pathTokens, "path", token -> true);
        redacted = replacePattern(redacted, IPV4_PATTERN, hostTokens, "host", token -> true);
        redacted = replacePattern(redacted, HOSTNAME_PATTERN, hostTokens, "host", this::shouldRedactHostname);
        return redacted;
    }

    private String replaceExplicitCommandLines(String text) {
        Matcher matcher = EXPLICIT_COMMAND_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                + matcher.group(2)
                + tokenFor(commandTokens, matcher.group(3), "command");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceEnvironmentAssignments(String text) {
        Matcher matcher = ENV_ASSIGNMENT_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!shouldRedactEnvironmentKey(key)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = key
                + matcher.group(2)
                + tokenFor(environmentTokens, key + "=" + matcher.group(3), "env");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replacePattern(
        String text,
        Pattern pattern,
        Map<String, String> tokens,
        String tokenKind,
        java.util.function.Predicate<String> shouldRedact
    ) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = shouldRedact.test(token)
                ? tokenFor(tokens, token, tokenKind)
                : token;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean shouldRedactEnvironmentKey(String key) {
        String upperKey = key.toUpperCase(Locale.ROOT);
        return upperKey.contains("TOKEN")
            || upperKey.contains("SECRET")
            || upperKey.contains("PASSWORD")
            || upperKey.contains("PASS")
            || upperKey.contains("PWD")
            || upperKey.contains("KEY")
            || upperKey.contains("CREDENTIAL")
            || upperKey.contains("COOKIE")
            || upperKey.contains("SESSION")
            || upperKey.contains("AUTH")
            || upperKey.contains("HOME")
            || upperKey.contains("USER")
            || upperKey.contains("USERNAME")
            || upperKey.contains("LOGNAME")
            || upperKey.contains("HOST")
            || upperKey.contains("PATH")
            || upperKey.contains("JAVA")
            || upperKey.startsWith("OCI_")
            || upperKey.startsWith("AWS_")
            || upperKey.startsWith("AZURE_")
            || upperKey.startsWith("GCP_")
            || upperKey.contains("KUBECONFIG");
    }

    private boolean shouldRedactHostname(String token) {
        String lowerToken = token.toLowerCase(Locale.ROOT);
        if (lowerToken.startsWith("redacted-")) {
            return false;
        }
        if (looksLikeMetricToken(token)) {
            return false;
        }
        if (!containsAlphabetic(token)) {
            return false;
        }
        if (looksLikeModelIdentifier(token)) {
            return false;
        }
        if (looksLikeNamedAiModelIdentifier(token)) {
            return false;
        }
        if (looksLikeTemplateIdentifier(token)) {
            return false;
        }
        if (looksLikeJvmIdentifier(token)) {
            return false;
        }
        if (!token.contains(".")) {
            return false;
        }
        int suffixIndex = lowerToken.lastIndexOf('.');
        if (suffixIndex > 0) {
            String suffix = lowerToken.substring(suffixIndex + 1);
            if (FILE_LIKE_SUFFIXES.contains(suffix)) {
                long dotCount = token.chars().filter(character -> character == '.').count();
                return dotCount >= 3;
            }
        }
        return true;
    }

    private boolean containsAlphabetic(String token) {
        return token != null && token.chars().anyMatch(Character::isLetter);
    }

    private boolean looksLikeMetricToken(String token) {
        return token != null && token.matches("\\d+(?:\\.\\d+)+(?:[a-zA-Z%]+)?");
    }

    private boolean looksLikeJvmIdentifier(String token) {
        return token.matches("(?:[a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$-]*");
    }

    private boolean looksLikeModelIdentifier(String token) {
        return token.matches("[A-Za-z][A-Za-z0-9-]*\\.[0-9]+");
    }

    private boolean looksLikeNamedAiModelIdentifier(String token) {
        if (token == null || !token.contains(".") || !containsAlphabetic(token)) {
            return false;
        }

        String lowerToken = token.toLowerCase(Locale.ROOT);
        return lowerToken.contains("grok")
            || lowerToken.contains("gpt")
            || lowerToken.contains("claude")
            || lowerToken.contains("gemini")
            || lowerToken.contains("llama")
            || lowerToken.contains("mistral")
            || lowerToken.contains("qwen")
            || lowerToken.contains("deepseek")
            || lowerToken.contains("phi");
    }

    private boolean looksLikeTemplateIdentifier(String token) {
        return token.matches("[A-Z][A-Za-z0-9_$-]*\\.[a-z][A-Za-z0-9_$-]*");
    }

    private String tokenFor(Map<String, String> tokens, String rawValue, String tokenKind) {
        return tokens.computeIfAbsent(
            rawValue,
            ignored -> "[redacted-" + tokenKind + "-" + (tokens.size() + 1) + "]"
        );
    }
}
