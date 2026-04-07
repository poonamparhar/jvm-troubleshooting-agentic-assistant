package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApplicationRuntimeSupportTest {

    @AfterEach
    void clearRuntimeOverrides() {
        System.clearProperty(ApplicationRuntimeSupport.REPORT_DIRECTORY_SYSTEM_PROPERTY);
        System.clearProperty(ApplicationRuntimeSupport.LEGACY_REPORT_DIRECTORY_SYSTEM_PROPERTY);
        System.clearProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY);
        System.clearProperty(ApplicationRuntimeSupport.LEGACY_CONFIG_FILE_SYSTEM_PROPERTY);
        System.clearProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY);
    }

    @Test
    void usesDefaultReportDirectoryWhenNoOverrideIsPresent() {
        Path expected = Path.of("target", "analysis-reports").toAbsolutePath().normalize();

        assertEquals(expected, ApplicationRuntimeSupport.resolveReportBundleDirectory());
    }

    @Test
    void usesSystemPropertyOverrideForReportDirectory() {
        System.setProperty(ApplicationRuntimeSupport.REPORT_DIRECTORY_SYSTEM_PROPERTY, "custom-report-bundles");

        Path expected = Path.of("custom-report-bundles").toAbsolutePath().normalize();
        assertEquals(expected, ApplicationRuntimeSupport.resolveReportBundleDirectory());
    }

    @Test
    void usesLegacySystemPropertyOverrideForReportDirectory() {
        System.setProperty(ApplicationRuntimeSupport.LEGACY_REPORT_DIRECTORY_SYSTEM_PROPERTY, "legacy-report-bundles");

        Path expected = Path.of("legacy-report-bundles").toAbsolutePath().normalize();
        assertEquals(expected, ApplicationRuntimeSupport.resolveReportBundleDirectory());
    }

    @Test
    void usesDefaultUserConfigFileWhenNoOverrideIsPresent() {
        Path expected = Path.of("config.json").toAbsolutePath().normalize();

        assertEquals(expected, ApplicationRuntimeSupport.resolveUserConfigFile());
    }

    @Test
    void usesApplicationHomeForDefaultUserConfigFile() throws Exception {
        Path applicationHome = Files.createTempDirectory("jtroubleshoot-app-home");
        System.setProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY, applicationHome.toString());

        assertEquals(
            applicationHome.resolve("config.json").toAbsolutePath().normalize(),
            ApplicationRuntimeSupport.resolveUserConfigFile()
        );
    }

    @Test
    void usesDistReportDirectoryWhenApplicationHomeLooksPackaged() throws Exception {
        Path applicationHome = Files.createTempDirectory("jtroubleshoot-dist-home");
        System.setProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY, applicationHome.toString());

        assertEquals(
            applicationHome.resolve("analysis-reports").toAbsolutePath().normalize(),
            ApplicationRuntimeSupport.resolveReportBundleDirectory()
        );
    }

    @Test
    void usesSystemPropertyOverrideForUserConfigFile() {
        System.setProperty(ApplicationRuntimeSupport.CONFIG_FILE_SYSTEM_PROPERTY, "custom-config/config.json");

        Path expected = Path.of("custom-config/config.json").toAbsolutePath().normalize();
        assertEquals(expected, ApplicationRuntimeSupport.resolveUserConfigFile());
    }

    @Test
    void exposesFilteredApplicationMetadataAndRuntimeInfo() {
        assertEquals("jtroubleshoot", ApplicationRuntimeSupport.applicationName());
        assertFalse(ApplicationRuntimeSupport.applicationVersion().isBlank());
        assertFalse("development".equals(ApplicationRuntimeSupport.applicationVersion()));
        assertTrue(ApplicationRuntimeSupport.javaRuntimeDescription().contains(System.getProperty("java.version")));
    }
}
