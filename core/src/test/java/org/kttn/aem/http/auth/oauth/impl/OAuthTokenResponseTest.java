package org.kttn.aem.http.auth.oauth.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kttn.aem.http.auth.oauth.AccessToken;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OAuthTokenResponse} JSON deserialization and conversion to {@link AccessToken}.
 */
class OAuthTokenResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldDeserializeValidTokenResponse() throws Exception {
        String json = "{\"access_token\":\"eyJhbGciOi...\",\"expires_in\":86400}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        assertNotNull(response);
        AccessToken token = response.toAccessToken();
        assertEquals("eyJhbGciOi...", token.getAccessToken());
        assertEquals(86400, token.getExpiresInSeconds());
    }

    @Test
    void shouldDeserializeAdobeImsResponse() throws Exception {
        // Real Adobe IMS token response structure
        String json = "{\n" +
            "  \"access_token\": \"eyJhbGciOiJSUzI1NiIsIng1dSI6Imltc19uYTEta2V5LTEuY2VyIiwia2lkIjoiaW1zX25hMS1rZXktMSIsIml0dCI6ImF0In0\",\n" +
            "  \"token_type\": \"bearer\",\n" +
            "  \"expires_in\": 86399996\n" +
            "}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        assertNotNull(response);
        AccessToken token = response.toAccessToken();
        assertTrue(token.getAccessToken().startsWith("eyJhbGciOi"));
        assertEquals(86399996, token.getExpiresInSeconds());
    }

    @Test
    void shouldIgnoreUnknownFields() throws Exception {
        // Response with extra fields that aren't in OAuth spec
        String json = "{\n" +
            "  \"access_token\": \"test-token\",\n" +
            "  \"expires_in\": 3600,\n" +
            "  \"token_type\": \"bearer\",\n" +
            "  \"scope\": \"openid profile\",\n" +
            "  \"refresh_token\": \"should-be-ignored\",\n" +
            "  \"custom_field\": \"unknown\"\n" +
            "}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        assertNotNull(response);
        AccessToken token = response.toAccessToken();
        assertEquals("test-token", token.getAccessToken());
        assertEquals(3600, token.getExpiresInSeconds());
    }

    @Test
    void shouldHandleMinimalResponse() throws Exception {
        // Only required fields
        String json = "{\"access_token\":\"minimal-token\",\"expires_in\":1800}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        AccessToken token = response.toAccessToken();
        assertEquals("minimal-token", token.getAccessToken());
        assertEquals(1800, token.getExpiresInSeconds());
    }

    @Test
    void shouldHandleZeroExpiresIn() throws Exception {
        String json = "{\"access_token\":\"token\",\"expires_in\":0}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        AccessToken token = response.toAccessToken();
        assertEquals(0, token.getExpiresInSeconds());
    }

    @Test
    void shouldHandleLargeExpiresIn() throws Exception {
        // Some providers return very long expiration times
        String json = "{\"access_token\":\"long-lived\",\"expires_in\":2147483647}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        AccessToken token = response.toAccessToken();
        assertEquals(2147483647, token.getExpiresInSeconds());
    }

    @Test
    void shouldHandleEmptyAccessToken() throws Exception {
        // Edge case: empty token string (invalid but should deserialize)
        String json = "{\"access_token\":\"\",\"expires_in\":3600}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        AccessToken token = response.toAccessToken();
        assertEquals("", token.getAccessToken());
    }

    @Test
    void shouldConvertToAccessToken() throws Exception {
        String json = "{\"access_token\":\"conversion-test\",\"expires_in\":7200}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);
        AccessToken token = response.toAccessToken();

        assertNotNull(token);
        assertEquals("conversion-test", token.getAccessToken());
        assertEquals(7200, token.getExpiresInSeconds());
    }

    @Test
    void shouldHandleWhitespaceInJson() throws Exception {
        String json = "{\n" +
            "  \"access_token\"  :  \"whitespace-token\"  ,\n" +
            "  \"expires_in\"    :  3600\n" +
            "}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        assertEquals("whitespace-token", response.toAccessToken().getAccessToken());
    }

    @Test
    void shouldHandleNumericTokenAsString() throws Exception {
        // Some providers might return numeric-looking tokens
        String json = "{\"access_token\":\"12345678901234567890\",\"expires_in\":3600}";

        OAuthTokenResponse response = objectMapper.readValue(json, OAuthTokenResponse.class);

        assertEquals("12345678901234567890", response.toAccessToken().getAccessToken());
    }
}
