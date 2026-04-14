package com.javaassistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Shared runtime metadata and configuration helpers for the CLI.
 */
final class ApplicationRuntimeSupport {

    static final String REPORT_DIRECTORY_SYSTEM_PROPERTY = "jtroubleshoot.reportDir";
    static final String LEGACY_REPORT_DIRECTORY_SYSTEM_PROPERTY = "jvm.troubleshooter.reportDir";
    static final String REPORT_DIRECTORY_ENV_VAR = "ANALYSIS_REPORT_DIR";
    static final String CONFIG_FILE_SYSTEM_PROPERTY = "jtroubleshoot.configFile";
    static final String LEGACY_CONFIG_FILE_SYSTEM_PROPERTY = "jvm.troubleshooter.configFile";
    static final String CONFIG_FILE_ENV_VAR = "JTROUBLESHOOT_CONFIG_FILE";
    static final String APPLICATION_HOME_SYSTEM_PROPERTY = "jtroubleshoot.home";

    private static final String APPLICATION_PROPERTIES_RESOURCE = "/application.properties";
    private static final String DEFAULT_APPLICATION_NAME = "jtroubleshoot";
    private static final String DEFAULT_APPLICATION_VERSION = "development";
    private static final String DEFAULT_CONFIG_FILE = "config.json";
    private static final String DEFAULT_ENV_FILE = "jtroubleshoot.env";
    private static final String DEFAULT_DIST_CONFIG_DIRECTORY = "conf";
    private static final String DEFAULT_DIST_REPORT_DIRECTORY = "reports";

    private ApplicationRuntimeSupport() {
    }

    static Path resolveReportBundleDirectory() {
        String configuredDirectory = firstNonBlank(
            System.getProperty(REPORT_DIRECTORY_SYSTEM_PROPERTY),
            System.getProperty(LEGACY_REPORT_DIRECTORY_SYSTEM_PROPERTY)
        );
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = EnvConfig.get(REPORT_DIRECTORY_ENV_VAR);
        }
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return defaultReportBundleDirectory();
        }
        return Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    static Path defaultReportBundleDirectory() {
        Path applicationHome = resolveApplicationHome();
        if (looksLikeSourceCheckout(applicationHome)) {
            return applicationHome.resolve("target").resolve("analysis-reports").toAbsolutePath().normalize();
        }
        return applicationHome.resolve(DEFAULT_DIST_REPORT_DIRECTORY).toAbsolutePath().normalize();
    }

    static Path resolveUserConfigFile() {
        String configuredFile = firstNonBlank(
            System.getProperty(CONFIG_FILE_SYSTEM_PROPERTY),
            System.getProperty(LEGACY_CONFIG_FILE_SYSTEM_PROPERTY)
        );
        if (configuredFile == null || configuredFile.isBlank()) {
            configuredFile = EnvConfig.get(CONFIG_FILE_ENV_VAR);
        }
        if (configuredFile == null || configuredFile.isBlank()) {
            return defaultUserConfigFile();
        }
        return Path.of(configuredFile).toAbsolutePath().normalize();
    }

    static Path defaultUserConfigFile() {
        Path applicationHome = resolveApplicationHome();
        if (looksLikeSourceCheckout(applicationHome)) {
            return applicationHome.resolve(DEFAULT_CONFIG_FILE).toAbsolutePath().normalize();
        }
        return applicationHome.resolve(DEFAULT_DIST_CONFIG_DIRECTORY).resolve(DEFAULT_CONFIG_FILE).toAbsolutePath().normalize();
    }

    static Path defaultEnvFile() {
        Path applicationHome = resolveApplicationHome();
        if (looksLikeSourceCheckout(applicationHome)) {
            return applicationHome.resolve(DEFAULT_ENV_FILE).toAbsolutePath().normalize();
        }
        return applicationHome.resolve(DEFAULT_DIST_CONFIG_DIRECTORY).resolve(DEFAULT_ENV_FILE).toAbsolutePath().normalize();
    }

    static Path resolveApplicationHome() {
        String configuredHome = System.getProperty(APPLICATION_HOME_SYSTEM_PROPERTY);
        if (configuredHome == null || configuredHome.isBlank()) {
            return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }
        return Path.of(configuredHome).toAbsolutePath().normalize();
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

    private static boolean looksLikeSourceCheckout(Path applicationHome) {
        if (applicationHome == null) {
            return false;
        }
        return Files.exists(applicationHome.resolve("pom.xml"))
            && Files.isDirectory(applicationHome.resolve("src"));
    }
}
