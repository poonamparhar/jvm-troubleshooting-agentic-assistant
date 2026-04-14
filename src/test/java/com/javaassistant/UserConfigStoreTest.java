package com.javaassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UserConfigStoreTest {

    @Test
    void savesAndLoadsOciAuthenticationMethod() throws Exception {
        Path configFile = Files.createTempDirectory("jtroubleshoot-user-config").resolve("config.json");
        UserConfigStore configStore = new UserConfigStore(configFile);

        configStore.save(new UserConfigStore.StoredConfig(
            "oci",
            "xai.grok-4",
            "config_file"
        ));

        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(!savedJson.contains("\"schemaVersion\""));
        assertTrue(savedJson.contains("\"ociAuthenticationMethod\": \"config_file\""));

        UserConfigStore.StoredConfig loaded = configStore.load();
        assertEquals("oci", loaded.provider());
        assertEquals("xai.grok-4", loaded.model());
        assertEquals("config_file", loaded.ociAuthenticationMethod());
    }

    @Test
    void loadsLegacyConfigThatStillContainsSchemaVersion() throws Exception {
        Path configFile = Files.createTempDirectory("jtroubleshoot-user-config").resolve("config.json");
        Files.writeString(
            configFile,
            """
                {
                  "schemaVersion": 1,
                  "provider": "oci",
                  "model": "xai.grok-4",
                  "ociAuthenticationMethod": "config_file"
                }
                """,
            StandardCharsets.UTF_8
        );
        UserConfigStore configStore = new UserConfigStore(configFile);

        UserConfigStore.StoredConfig loaded = configStore.load();

        assertEquals("oci", loaded.provider());
        assertEquals("xai.grok-4", loaded.model());
        assertEquals("config_file", loaded.ociAuthenticationMethod());
    }
}
