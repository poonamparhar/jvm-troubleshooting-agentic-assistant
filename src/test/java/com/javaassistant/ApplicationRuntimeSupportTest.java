package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApplicationRuntimeSupportTest {

    @AfterEach
    void clearReportDirectoryOverride() {
        System.clearProperty(ApplicationRuntimeSupport.REPORT_DIRECTORY_SYSTEM_PROPERTY);
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
    void exposesFilteredApplicationMetadataAndRuntimeInfo() {
        assertEquals("jvm-troubleshooting-agentic-assistant", ApplicationRuntimeSupport.applicationName());
        assertFalse(ApplicationRuntimeSupport.applicationVersion().isBlank());
        assertFalse("development".equals(ApplicationRuntimeSupport.applicationVersion()));
        assertTrue(ApplicationRuntimeSupport.javaRuntimeDescription().contains(System.getProperty("java.version")));
    }
}
