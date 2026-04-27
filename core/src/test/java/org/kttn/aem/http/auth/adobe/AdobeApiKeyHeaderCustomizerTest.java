package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdobeApiKeyHeaderCustomizer}.
 */
class AdobeApiKeyHeaderCustomizerTest {

    @Test
    void shouldRejectNullApiKey() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeApiKeyHeaderCustomizer(null)
        );
        assertEquals("apiKey must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldRejectBlankApiKey() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeApiKeyHeaderCustomizer("   ")
        );
        assertEquals("apiKey must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldRejectEmptyApiKey() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeApiKeyHeaderCustomizer("")
        );
        assertEquals("apiKey must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldAcceptValidApiKey() {
        AdobeApiKeyHeaderCustomizer customizer = new AdobeApiKeyHeaderCustomizer("test-api-key");
        assertNotNull(customizer);
    }

    @Test
    void shouldSetApiKeyHeader() {
        AdobeApiKeyHeaderCustomizer customizer = new AdobeApiKeyHeaderCustomizer("test-client-id-123");
        
        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        HttpContext context = new BasicHttpContext();
        
        customizer.process(request, context);
        
        assertEquals("test-client-id-123", request.getFirstHeader("x-api-key").getValue());
    }

    @Test
    void shouldOverwriteExistingApiKeyHeader() {
        AdobeApiKeyHeaderCustomizer customizer = new AdobeApiKeyHeaderCustomizer("new-api-key");
        
        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        request.setHeader("x-api-key", "old-value");
        HttpContext context = new BasicHttpContext();
        
        customizer.process(request, context);
        
        assertEquals("new-api-key", request.getFirstHeader("x-api-key").getValue());
    }

    @Test
    void shouldRegisterAsLastInterceptor() throws Exception {
        AdobeApiKeyHeaderCustomizer customizer = new AdobeApiKeyHeaderCustomizer("test-key");
        HttpClientBuilder builder = HttpClientBuilder.create();
        
        customizer.customize(builder);
        
        // Verify the interceptor was registered
        List<HttpRequestInterceptor> interceptors = extractInterceptors(builder, "requestLast");
        assertFalse(interceptors.isEmpty(), "Interceptor should be registered");
        assertTrue(
            interceptors.stream().anyMatch(i -> i instanceof AdobeApiKeyHeaderCustomizer),
            "AdobeApiKeyHeaderCustomizer should be in the interceptor list"
        );
    }

    @Test
    void shouldWorkWithHttpClientBuilder() throws Exception {
        AdobeApiKeyHeaderCustomizer customizer = new AdobeApiKeyHeaderCustomizer("integration-key");
        HttpClientBuilder builder = HttpClientBuilder.create();
        
        customizer.customize(builder);
        
        // Extract and execute interceptors
        List<HttpRequestInterceptor> interceptors = extractInterceptors(builder, "requestLast");
        HttpRequest request = new BasicHttpRequest("GET", "https://api.adobe.io/test");
        HttpContext context = new BasicHttpContext();
        
        for (HttpRequestInterceptor interceptor : interceptors) {
            interceptor.process(request, context);
        }
        
        assertEquals("integration-key", request.getFirstHeader("x-api-key").getValue());
    }

    @Test
    void shouldUseCorrectHeaderName() {
        assertEquals("x-api-key", AdobeApiKeyHeaderCustomizer.API_KEY_HEADER);
    }

    /**
     * Extracts interceptors from HttpClientBuilder via reflection.
     * Credit: pattern from AdobeIntegrationCustomizersTest
     */
    @SuppressWarnings("unchecked")
    private static List<HttpRequestInterceptor> extractInterceptors(
        final HttpClientBuilder builder,
        final String fieldName
    ) throws Exception {
        final Field field = HttpClientBuilder.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<HttpRequestInterceptor>) field.get(builder);
    }
}
