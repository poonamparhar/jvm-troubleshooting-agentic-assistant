package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EnvConfigTest {

    @AfterEach
    void clearRuntimeOverrides() {
        System.clearProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY);
    }

    @Test
    void resolvesPreferredEnvFileFromPackagedConfDirectory() throws Exception {
        Path applicationHome = Files.createTempDirectory("jtroubleshoot-dist-home");
        Path confDirectory = Files.createDirectories(applicationHome.resolve("conf"));
        Path envFile = confDirectory.resolve("jtroubleshoot.env");
        Files.writeString(envFile, "OPENAI_API_KEY=test-key\n", StandardCharsets.UTF_8);
        System.setProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY, applicationHome.toString());

        assertEquals(envFile.toAbsolutePath().normalize(), EnvConfig.resolvedEnvPath());
        assertEquals("test-key", EnvConfig.get("OPENAI_API_KEY"));
    }

    @Test
    void returnsNullWhenNoEnvFileExistsForPackagedApplicationHome() throws Exception {
        Path applicationHome = Files.createTempDirectory("jtroubleshoot-dist-home");
        System.setProperty(ApplicationRuntimeSupport.APPLICATION_HOME_SYSTEM_PROPERTY, applicationHome.toString());

        assertNull(EnvConfig.resolvedEnvPath());
    }
}
