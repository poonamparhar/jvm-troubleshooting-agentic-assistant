package com.javaassistant.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OCIChatModelProviderTest {

    @Test
    void defaultsToApiKeyAuthenticationWhenConfigIsUnset() {
        assertEquals("api_key", OCIChatModelProvider.resolveAuthenticationMethodConfigValue(null));
        assertEquals("api_key", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("   "));
    }

    @Test
    void acceptsCanonicalAndProviderClassAuthenticationNames() {
        assertEquals("api_key", OCIChatModelProvider.resolveAuthenticationMethodConfigValue("api_key"));
        assertEquals(
            "api_key",
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
            () -> OCIChatModelProvider.resolveAuthenticationMethodConfigValue("config_file")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OCIChatModelProvider.resolveAuthenticationMethodConfigValue("instance_principal")
        );
    }

    @Test
    void acceptsCompartmentOcids() {
        assertEquals(
            "ocid1.compartment.oc1..exampleuniqueid",
            OCIChatModelProvider.validatedCompartmentId("ocid1.compartment.oc1..exampleuniqueid")
        );
    }

    @Test
    void rejectsInvalidCompartmentOcids() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OCIChatModelProvider.validatedCompartmentId("icid1.compartment.oc1..exampleuniqueid")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OCIChatModelProvider.validatedCompartmentId("ocid1.user.oc1..exampleuniqueid")
        );
    }
}
