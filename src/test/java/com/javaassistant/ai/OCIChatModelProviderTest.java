package com.javaassistant.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OCIChatModelProviderTest {

    @Test
    void defaultsToApiKeyAuthenticationWhenConfigIsUnset() {
        assertEquals("config_file", OCIChatModelProvider.resolveAuthenticationMethodConfigValue(null));
        assertEquals("config_file", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("   "));
    }

    @Test
    void acceptsCanonicalAndProviderClassAuthenticationNames() {
        assertEquals("config_file", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("config_file"));
        assertEquals("config_file", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("api_key"));
        assertEquals(
            "config_file",
            OCIChatModelProvider.resolveAuthenticationMethodConfigValue("ConfigFileAuthenticationDetailsProvider")
        );
        assertEquals("session_token", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("session_token"));
        assertEquals(
            "session_token",
            OCIChatModelProvider.resolveAuthenticationMethodConfigValue("SessionTokenAuthenticationDetailsProvider")
        );
    }

    @Test
    void rejectsUnknownAuthenticationNames() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OCIChatModelProvider.resolveAuthenticationMethodConfigValue("instance_principal")
        );
    }
}
