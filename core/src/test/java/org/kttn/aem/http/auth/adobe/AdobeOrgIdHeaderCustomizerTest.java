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
 * Tests for {@link AdobeOrgIdHeaderCustomizer}.
 */
class AdobeOrgIdHeaderCustomizerTest {

    @Test
    void shouldRejectNullOrgId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeOrgIdHeaderCustomizer(null)
        );
        assertEquals("orgId must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldRejectBlankOrgId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeOrgIdHeaderCustomizer("   ")
        );
        assertEquals("orgId must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldRejectEmptyOrgId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeOrgIdHeaderCustomizer("")
        );
        assertEquals("orgId must not be null or blank", exception.getMessage());
    }

    @Test
    void shouldAcceptValidOrgId() {
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("12345ABCDE@AdobeOrg");
        assertNotNull(customizer);
    }

    @Test
    void shouldSetOrgIdHeader() {
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("98765FGHIJ@AdobeOrg");
        
        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        HttpContext context = new BasicHttpContext();
        
        customizer.process(request, context);
        
        assertEquals("98765FGHIJ@AdobeOrg", request.getFirstHeader("x-gw-ims-org-id").getValue());
    }

    @Test
    void shouldOverwriteExistingOrgIdHeader() {
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("NEW123@AdobeOrg");
        
        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        request.setHeader("x-gw-ims-org-id", "OLD456@AdobeOrg");
        HttpContext context = new BasicHttpContext();
        
        customizer.process(request, context);
        
        assertEquals("NEW123@AdobeOrg", request.getFirstHeader("x-gw-ims-org-id").getValue());
    }

    @Test
    void shouldRegisterAsLastInterceptor() throws Exception {
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("ORG123@AdobeOrg");
        HttpClientBuilder builder = HttpClientBuilder.create();
        
        customizer.customize(builder);
        
        // Verify the interceptor was registered
        List<HttpRequestInterceptor> interceptors = extractInterceptors(builder, "requestLast");
        assertFalse(interceptors.isEmpty(), "Interceptor should be registered");
        assertTrue(
            interceptors.stream().anyMatch(i -> i instanceof AdobeOrgIdHeaderCustomizer),
            "AdobeOrgIdHeaderCustomizer should be in the interceptor list"
        );
    }

    @Test
    void shouldWorkWithHttpClientBuilder() throws Exception {
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("TESTORG@AdobeOrg");
        HttpClientBuilder builder = HttpClientBuilder.create();
        
        customizer.customize(builder);
        
        // Extract and execute interceptors
        List<HttpRequestInterceptor> interceptors = extractInterceptors(builder, "requestLast");
        HttpRequest request = new BasicHttpRequest("GET", "https://api.adobe.io/test");
        HttpContext context = new BasicHttpContext();
        
        for (HttpRequestInterceptor interceptor : interceptors) {
            interceptor.process(request, context);
        }
        
        assertEquals("TESTORG@AdobeOrg", request.getFirstHeader("x-gw-ims-org-id").getValue());
    }

    @Test
    void shouldUseCorrectHeaderName() {
        assertEquals("x-gw-ims-org-id", AdobeOrgIdHeaderCustomizer.IMS_ORG_ID_HEADER);
    }

    @Test
    void shouldHandleOrgIdWithoutAdobeOrgSuffix() {
        // Validate that customizer accepts any format (not just @AdobeOrg suffix)
        AdobeOrgIdHeaderCustomizer customizer = new AdobeOrgIdHeaderCustomizer("custom-org-id");
        
        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        HttpContext context = new BasicHttpContext();
        
        customizer.process(request, context);
        
        assertEquals("custom-org-id", request.getFirstHeader("x-gw-ims-org-id").getValue());
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
