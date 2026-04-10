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
            UserConfigStore.CURRENT_SCHEMA_VERSION,
            "oci",
            "xai.grok-4",
            "config_file"
        ));

        String savedJson = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"ociAuthenticationMethod\": \"config_file\""));

        UserConfigStore.StoredConfig loaded = configStore.load();
        assertEquals("oci", loaded.provider());
        assertEquals("xai.grok-4", loaded.model());
        assertEquals("config_file", loaded.ociAuthenticationMethod());
    }
}
