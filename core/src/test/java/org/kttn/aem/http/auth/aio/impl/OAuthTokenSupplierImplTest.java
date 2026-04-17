package org.kttn.aem.http.auth.aio.impl;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.auth.aio.OAuthTokenSupplier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.kttn.aem.http.auth.aio.impl.OAuthTokenSupplierImpl.PLACEHOLDER_EXPIRES_IN_SECONDS;

@ExtendWith(MockitoExtension.class)
class OAuthTokenSupplierImplTest {

    @Mock
    private HttpClientProvider httpClientProvider;

    @Mock
    private CloseableHttpClient httpClient;

    private OAuthTokenSupplierImplUnderTest supplier;

    @BeforeEach
    void setUp() {
        when(httpClientProvider.provide(eq("IMSService"))).thenReturn(httpClient);

        OAuthTokenSupplierImpl.Config config = mock(OAuthTokenSupplierImpl.Config.class);
        // orgId is not read during activate(); lenient avoids strict stubbing errors in tests that only call getAccessToken
        lenient().when(config.orgId()).thenReturn("org@test");
        when(config.clientId()).thenReturn("client-id");
        when(config.clientSecret()).thenReturn("client-secret");
        when(config.scopes()).thenReturn("openid,AdobeID");

        supplier = new OAuthTokenSupplierImplUnderTest(httpClientProvider);
        supplier.activate(config);
    }

    /**
     * Subclass exposes {@link #clearGrantParametersForTest()} so the empty-form guard can be tested
     * without reflection.
     */
    static final class OAuthTokenSupplierImplUnderTest extends OAuthTokenSupplierImpl {

        OAuthTokenSupplierImplUnderTest(final HttpClientProvider httpClientProvider) {
            super(httpClientProvider);
        }

        void clearGrantParametersForTest() {
            oauthTokenParams.clear();
        }
    }

    @Test
    void getOrgIdAndClientIdReflectConfig() {
        assertEquals("org@test", supplier.getOrgId());
        assertEquals("client-id", supplier.getClientId());
    }

    @Test
    void getAccessTokenParsesJsonOnHttp200() throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);
        when(response.getStatusLine()).thenReturn(statusLine);
        HttpEntity entity = new StringEntity(
            "{\"access_token\":\"from-ims\",\"expires_in\":3600}",
            ContentType.APPLICATION_JSON);
        when(response.getEntity()).thenReturn(entity);
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        AccessTokenImpl token = supplier.getAccessToken();
        assertEquals("from-ims", token.getAccessToken());
        assertEquals(3600L, token.getExpiresIn());
    }

    @Test
    void getAccessTokenReturnsPlaceholderOnNonOk() throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_BAD_REQUEST);
        when(response.getStatusLine()).thenReturn(statusLine);
        HttpEntity entity = new StringEntity("{\"error\":\"invalid_client\"}", ContentType.APPLICATION_JSON);
        when(response.getEntity()).thenReturn(entity);
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        AccessTokenImpl token = supplier.getAccessToken();
        assertEquals(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, token.getAccessToken());
        assertEquals(PLACEHOLDER_EXPIRES_IN_SECONDS, token.getExpiresIn());
    }

    @Test
    void getAccessTokenReturnsPlaceholderOnIOException() throws IOException {
        when(httpClient.execute(any(HttpPost.class))).thenThrow(new IOException("network down"));

        AccessTokenImpl token = supplier.getAccessToken();
        assertEquals(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, token.getAccessToken());
        assertEquals(PLACEHOLDER_EXPIRES_IN_SECONDS, token.getExpiresIn());
    }

    @Test
    void getAccessTokenReturnsPlaceholderWithoutCallingImsWhenFormParamsEmpty() throws IOException {
        supplier.clearGrantParametersForTest();

        final AccessTokenImpl token = supplier.getAccessToken();
        assertEquals(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, token.getAccessToken());
        assertEquals(PLACEHOLDER_EXPIRES_IN_SECONDS, token.getExpiresIn());
        verify(httpClient, never()).execute(any(HttpPost.class));
    }
}
