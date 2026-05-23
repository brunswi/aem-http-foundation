/*
 * Copyright (C) 2026 KTTN AEM Libraries
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
        // same expiresInSeconds, different token string → Objects.equals returns false
        final AccessToken d = new AccessToken("y", 100L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
    }
}
