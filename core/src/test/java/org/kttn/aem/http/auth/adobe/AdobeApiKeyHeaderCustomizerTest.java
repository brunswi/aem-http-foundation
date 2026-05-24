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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdobeApiKeyHeaderCustomizer}.
 */
class AdobeApiKeyHeaderCustomizerTest {

    @Test
    void shouldRejectNullApiKey() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeApiKeyHeaderCustomizer((String) null)
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

    @Test
    void shouldRejectNullSupplier() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new AdobeApiKeyHeaderCustomizer((Supplier<String>) null)
        );
        assertEquals("apiKeySupplier must not be null", exception.getMessage());
    }

    @Test
    void supplierBasedConstructorEvaluatesPerRequest() {
        AtomicReference<String> currentValue = new AtomicReference<>("first");
        AdobeApiKeyHeaderCustomizer customizer =
            new AdobeApiKeyHeaderCustomizer(currentValue::get);

        HttpRequest request1 = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(request1, new BasicHttpContext());
        assertEquals("first", request1.getFirstHeader("x-api-key").getValue());

        currentValue.set("second");
        HttpRequest request2 = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(request2, new BasicHttpContext());
        assertEquals("second", request2.getFirstHeader("x-api-key").getValue(),
            "Supplier must be evaluated on every request, not cached");
    }

    @Test
    void supplierReturningNullOmitsHeader() {
        AdobeApiKeyHeaderCustomizer customizer =
            new AdobeApiKeyHeaderCustomizer(() -> null);

        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(request, new BasicHttpContext());

        assertNull(request.getFirstHeader("x-api-key"),
            "Header must be omitted when supplier returns null");
    }

    @Test
    void supplierReturningBlankOmitsHeader() {
        AdobeApiKeyHeaderCustomizer customizer =
            new AdobeApiKeyHeaderCustomizer(() -> "   ");

        HttpRequest request = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(request, new BasicHttpContext());

        assertNull(request.getFirstHeader("x-api-key"),
            "Header must be omitted when supplier returns blank");
    }

    @Test
    void supplierStartingBlankThenReturningValueAttachesHeaderOnSubsequentRequest() {
        AtomicReference<String> currentValue = new AtomicReference<>(null);
        AdobeApiKeyHeaderCustomizer customizer =
            new AdobeApiKeyHeaderCustomizer(currentValue::get);

        HttpRequest deferred = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(deferred, new BasicHttpContext());
        assertNull(deferred.getFirstHeader("x-api-key"),
            "Header must be omitted while supplier returns null");

        currentValue.set("eventual-client-id");
        HttpRequest resolved = new BasicHttpRequest("GET", "https://example.adobe.io/api");
        customizer.process(resolved, new BasicHttpContext());
        assertEquals("eventual-client-id", resolved.getFirstHeader("x-api-key").getValue(),
            "Header must be attached once the supplier starts returning a value");
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
