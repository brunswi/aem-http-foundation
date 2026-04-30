package org.kttn.aem.http.auth.oauth.impl;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachingTokenAcquirerTest {

    private static final String ENDPOINT = "https://ims-na1.adobelogin.com/ims/token/v3";

    @Mock
    private CloseableHttpClient httpClient;

    private CachingTokenAcquirer newAcquirer(final String clientId,
                                             final String clientSecret,
                                             final String scopes,
                                             final Map<String, String> additional) {
        return new CachingTokenAcquirer(
            httpClient, ENDPOINT, clientId, clientSecret, scopes,
            additional,
            CachingTokenAcquirer.DEFAULT_REFRESH_LENIENCY_SECONDS,
            "unit-test");
    }

    private static CloseableHttpResponse okResponse(final String body) {
        final CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        final StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);
        when(response.getStatusLine()).thenReturn(statusLine);
        final HttpEntity entity = new StringEntity(body, ContentType.APPLICATION_JSON);
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    @Test
    void parsesJsonOnHttp200() throws IOException {
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"from-issuer\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        final AccessToken token = newAcquirer("cid", "csec", "openid", Collections.emptyMap())
            .getAccessToken();

        assertEquals("from-issuer", token.getAccessToken());
        assertEquals(3600L, token.getExpiresInSeconds());
    }

    @Test
    void reusesCachedTokenAcrossCalls() throws IOException {
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"first\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        final CachingTokenAcquirer acquirer =
            newAcquirer("cid", "csec", "openid", Collections.emptyMap());
        for (int i = 0; i < 5; i++) {
            assertEquals("first", acquirer.getAccessToken().getAccessToken());
        }
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    @Test
    void throwsOnNonOkAndIncludesStatus() throws IOException {
        final CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        final StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_BAD_REQUEST);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity()).thenReturn(
            new StringEntity("{\"error\":\"invalid_client\"}", ContentType.APPLICATION_JSON));
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        final TokenUnavailableException ex = assertThrows(TokenUnavailableException.class,
            () -> newAcquirer("cid", "csec", "", Collections.emptyMap()).getAccessToken());
        assertTrue(ex.getMessage().contains("HTTP 400"));
    }

    @Test
    void wrapsIoExceptionAsTokenUnavailable() throws IOException {
        final IOException cause = new IOException("network down");
        when(httpClient.execute(any(HttpPost.class))).thenThrow(cause);

        final TokenUnavailableException ex = assertThrows(TokenUnavailableException.class,
            () -> newAcquirer("cid", "csec", "", Collections.emptyMap()).getAccessToken());
        assertSame(cause, ex.getCause());
    }

    @Test
    void throwsWithoutCallingEndpointWhenCredentialsMissing() throws IOException {
        final TokenUnavailableException ex = assertThrows(TokenUnavailableException.class,
            () -> newAcquirer("", "", "", Collections.emptyMap()).getAccessToken());
        assertTrue(ex.getMessage().contains("No OAuth credentials configured"));
        verify(httpClient, times(0)).execute(any(HttpPost.class));
    }

    @Test
    void mergesAdditionalTokenParams() throws IOException {
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"t\",\"expires_in\":60}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        final Map<String, String> extras = new LinkedHashMap<>();
        extras.put("org_id", "12345@AdobeOrg");

        newAcquirer("cid", "csec", "openid", extras).getAccessToken();
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    /**
     * Verifies that {@link CachingTokenAcquirer#invalidateCacheForTest()} discards the cached token
     * so that the next {@code getAccessToken()} call performs a fresh HTTP request rather than
     * returning the previously cached value.
     */
    @Test
    void invalidateCacheForcesFreshTokenRequest() throws IOException {
        final CloseableHttpResponse first =
            okResponse("{\"access_token\":\"t1\",\"expires_in\":3600}");
        final CloseableHttpResponse second =
            okResponse("{\"access_token\":\"t2\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(first, second);

        final CachingTokenAcquirer acquirer =
            newAcquirer("cid", "csec", "", Collections.emptyMap());

        assertEquals("t1", acquirer.getAccessToken().getAccessToken());
        acquirer.invalidateCacheForTest();
        assertEquals("t2", acquirer.getAccessToken().getAccessToken());
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }

    /**
     * Verifies that an {@code expires_in} of zero causes {@code localExpiry} to be set to
     * {@code Instant.EPOCH}, which is always in the past, so every subsequent call triggers
     * a fresh token request rather than serving a cached value.
     */
    @Test
    void immediatelyRenewsWhenExpiresInIsZero() throws IOException {
        final CloseableHttpResponse first =
            okResponse("{\"access_token\":\"t1\",\"expires_in\":0}");
        final CloseableHttpResponse second =
            okResponse("{\"access_token\":\"t2\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(first, second);

        final CachingTokenAcquirer acquirer =
            newAcquirer("cid", "csec", "", Collections.emptyMap());

        assertEquals("t1", acquirer.getAccessToken().getAccessToken());
        assertEquals("t2", acquirer.getAccessToken().getAccessToken());
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }

    /**
     * Verifies that an {@code expires_in} value exceeding the one-year maximum is silently capped
     * and a token is still returned successfully, preventing arithmetic overflow or unreasonably
     * long cache lifetimes from a misbehaving issuer.
     */
    @Test
    void capsExpiresInWhenAboveYearMaximum() throws IOException {
        final long overMax = 365L * 24 * 60 * 60 + 1;
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"t\",\"expires_in\":" + overMax + "}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        final AccessToken token =
            newAcquirer("cid", "csec", "", Collections.emptyMap()).getAccessToken();
        assertEquals("t", token.getAccessToken());
    }

    /**
     * Verifies that a {@code null} scopes argument does not cause a NullPointerException and
     * simply omits the {@code scope} parameter from the token POST, since some OAuth endpoints
     * do not require a scope.
     */
    @Test
    void omitsScopeParamWhenScopesIsNull() throws IOException {
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"t\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        newAcquirer("cid", "csec", null, Collections.emptyMap()).getAccessToken();
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    /**
     * Verifies that {@code null} additional token params are handled gracefully without a
     * NullPointerException, and that a token is still acquired successfully.
     */
    @Test
    void handlesNullAdditionalTokenParams() throws IOException {
        final CloseableHttpResponse response =
            okResponse("{\"access_token\":\"t\",\"expires_in\":3600}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        newAcquirer("cid", "csec", "openid", null).getAccessToken();
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    /**
     * Verifies that a {@code null} client ID is treated as missing credentials: no HTTP request
     * is made and {@link TokenUnavailableException} is thrown immediately.
     */
    @Test
    void throwsWhenClientIdIsNull() {
        final TokenUnavailableException ex = assertThrows(TokenUnavailableException.class,
            () -> newAcquirer(null, "csec", "", Collections.emptyMap()).getAccessToken());
        assertTrue(ex.getMessage().contains("No OAuth credentials configured"));
    }
}
