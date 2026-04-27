package org.kttn.aem.http.auth.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTokenTest {

    @Test
    void exposesGettersAsConstructed() {
        final AccessToken token = new AccessToken("abc", 3600L);
        assertEquals("abc", token.getAccessToken());
        assertEquals(3600L, token.getExpiresInSeconds());
    }

    @Test
    void toStringRedactsSecretButKeepsLifetime() {
        final AccessToken token = new AccessToken("super-secret", 42L);
        final String s = token.toString();
        assertTrue(s.contains("**secret**"));
        assertFalse(s.contains("super-secret"));
        assertTrue(s.contains("42"));
    }

    @Test
    void equalsAndHashCodeOnFieldEquality() {
        final AccessToken a = new AccessToken("x", 100L);
        final AccessToken b = new AccessToken("x", 100L);
        final AccessToken c = new AccessToken("x", 200L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
