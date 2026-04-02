package com.javaassistant;

import com.javaassistant.config.EnvConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Shared runtime metadata and configuration helpers for the CLI.
 */
final class ApplicationRuntimeSupport {

    static final String REPORT_DIRECTORY_SYSTEM_PROPERTY = "jvm.troubleshooter.reportDir";
    static final String REPORT_DIRECTORY_ENV_VAR = "ANALYSIS_REPORT_DIR";

    private static final String APPLICATION_PROPERTIES_RESOURCE = "/application.properties";
    private static final String DEFAULT_APPLICATION_NAME = "jvm-troubleshooting-agentic-assistant";
    private static final String DEFAULT_APPLICATION_VERSION = "development";

    private ApplicationRuntimeSupport() {
    }

    static Path resolveReportBundleDirectory() {
        String configuredDirectory = System.getProperty(REPORT_DIRECTORY_SYSTEM_PROPERTY);
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = EnvConfig.get(REPORT_DIRECTORY_ENV_VAR);
        }
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return defaultReportBundleDirectory();
        }
        return Paths.get(configuredDirectory).toAbsolutePath().normalize();
    }

    static Path defaultReportBundleDirectory() {
        return Path.of("target", "analysis-reports").toAbsolutePath().normalize();
    }

    static String applicationName() {
        String configuredName = loadApplicationProperties().getProperty("application.name");
        if (configuredName != null && !configuredName.isBlank()) {
            return configuredName;
        }
        return DEFAULT_APPLICATION_NAME;
    }

    static String applicationVersion() {
        String configuredVersion = loadApplicationProperties().getProperty("application.version");
        if (configuredVersion != null && !configuredVersion.isBlank() && !configuredVersion.contains("${")) {
            return configuredVersion;
        }

        Package applicationPackage = JVMTroubleshooter.class.getPackage();
        if (applicationPackage != null) {
            String implementationVersion = applicationPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion;
            }
        }

        return DEFAULT_APPLICATION_VERSION;
    }

    static String javaRuntimeDescription() {
        String runtimeVersion = firstNonBlank(
            System.getProperty("java.runtime.version"),
            System.getProperty("java.version")
        );
        String vendor = firstNonBlank(System.getProperty("java.vendor"));
        if (vendor == null) {
            vendor = "unknown vendor";
        }
        if (runtimeVersion == null) {
            runtimeVersion = "unknown";
        }
        return runtimeVersion + " (" + vendor + ")";
    }

    private static Properties loadApplicationProperties() {
        Properties properties = new Properties();
        try (InputStream inputStream = ApplicationRuntimeSupport.class.getResourceAsStream(APPLICATION_PROPERTIES_RESOURCE)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
            // Fall through to defaults when runtime metadata cannot be loaded.
        }
        return properties;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
