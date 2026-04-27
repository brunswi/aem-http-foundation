package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.auth.HttpClientCustomizer;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdobeIntegrationCustomizersTest {

    @Mock
    private AccessTokenSupplier accessTokenSupplier;

    @Test
    void buildsCustomizerThatSetsAdobeHeaders() throws Exception {
        when(accessTokenSupplier.getAccessToken())
            .thenReturn(new AccessToken("token-xyz", 3600L));

        final HttpClientCustomizer customizer = AdobeIntegrationCustomizers.builder()
            .bearer(accessTokenSupplier)
            .apiKey("client-id-value")
            .orgIdHeader("123@AdobeOrg")
            .additionalHeader("x-sandbox-name", "prod")
            .build();

        final HttpRequest request = applyToRequest(customizer);

        assertEquals("Bearer token-xyz",
            request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
        assertEquals("client-id-value",
            request.getFirstHeader(AdobeApiKeyHeaderCustomizer.API_KEY_HEADER).getValue());
        assertEquals("123@AdobeOrg",
            request.getFirstHeader(AdobeOrgIdHeaderCustomizer.IMS_ORG_ID_HEADER).getValue());
        assertEquals("prod", request.getFirstHeader("x-sandbox-name").getValue());
    }

    @Test
    void omitsBlankIngredients() throws Exception {
        when(accessTokenSupplier.getAccessToken())
            .thenReturn(new AccessToken("t", 3600L));

        final HttpClientCustomizer customizer = AdobeIntegrationCustomizers.builder()
            .bearer(accessTokenSupplier)
            .apiKey("")
            .orgIdHeader(null)
            .build();

        final HttpRequest request = applyToRequest(customizer);

        assertNotNull(request.getFirstHeader(HttpHeaders.AUTHORIZATION));
        assertNull(request.getFirstHeader(AdobeApiKeyHeaderCustomizer.API_KEY_HEADER));
        assertNull(request.getFirstHeader(AdobeOrgIdHeaderCustomizer.IMS_ORG_ID_HEADER));
    }

    /**
     * Applies a customizer to a fresh builder, extracts the registered interceptors via
     * reflection, and runs them against a basic GET request so the resulting headers can be
     * asserted.
     */
    private static HttpRequest applyToRequest(final HttpClientCustomizer customizer)
        throws Exception {
        final HttpClientBuilder builder = HttpClientBuilder.create();
        customizer.customize(builder);

        final List<HttpRequestInterceptor> interceptors =
            extractInterceptors(builder, "requestLast");

        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");
        final HttpContext context = new BasicHttpContext();
        for (final HttpRequestInterceptor interceptor : interceptors) {
            interceptor.process(request, context);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<HttpRequestInterceptor> extractInterceptors(
        final HttpClientBuilder builder, final String fieldName) throws Exception {
        final Field field = HttpClientBuilder.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        final List<HttpRequestInterceptor> list =
            (List<HttpRequestInterceptor>) field.get(builder);
        return list != null ? list : new LinkedList<>();
    }
}
