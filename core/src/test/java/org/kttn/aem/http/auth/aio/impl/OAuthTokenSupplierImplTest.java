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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthTokenSupplierImplTest {

    @Mock
    private HttpClientProvider httpClientProvider;

    @Mock
    private CloseableHttpClient httpClient;

    private OAuthTokenSupplierImpl supplier;

    @BeforeEach
    void setUp() throws Exception {
        when(httpClientProvider.provide(eq("IMSService"))).thenReturn(httpClient);

        OAuthTokenSupplierImpl.Config config = mock(OAuthTokenSupplierImpl.Config.class);
        // orgId is not read during activate(); lenient avoids strict stubbing errors in tests that only call getAccessToken
        lenient().when(config.orgId()).thenReturn("org@test");
        when(config.clientId()).thenReturn("client-id");
        when(config.clientSecret()).thenReturn("client-secret");
        when(config.scopes()).thenReturn("openid,AdobeID");

        supplier = new OAuthTokenSupplierImpl();
        Field httpClientProviderField = OAuthTokenSupplierImpl.class.getDeclaredField("httpClientProvider");
        httpClientProviderField.setAccessible(true);
        httpClientProviderField.set(supplier, httpClientProvider);
        supplier.activate(config);
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
        assertEquals("N/A", token.getAccessToken());
    }
}
