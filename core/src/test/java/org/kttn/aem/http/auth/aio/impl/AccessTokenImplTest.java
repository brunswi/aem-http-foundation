package org.kttn.aem.http.auth.aio.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTokenImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesStandardOAuthJson() throws Exception {
        String json = "{\"access_token\":\"abc\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
        AccessTokenImpl token = MAPPER.readValue(json, AccessTokenImpl.class);
        assertEquals("abc", token.getAccessToken());
        assertEquals(3600L, token.getExpiresIn());
    }

    @Test
    void toStringRedactsAccessToken() {
        AccessTokenImpl token = new AccessTokenImpl("super-secret", 42);
        String s = token.toString();
        assertTrue(s.contains("**secret**"));
        assertFalse(s.contains("super-secret"));
        assertTrue(s.contains("42"));
    }
}
