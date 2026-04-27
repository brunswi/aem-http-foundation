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
}
