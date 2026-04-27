package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the builder mutator (Consumer&lt;HttpClientBuilder&gt;) is actually applied.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>The mutator function is actually called</li>
 *   <li>Changes made via the mutator are reflected in the resulting client</li>
 *   <li>Multiple customizations can be applied via the mutator</li>
 * </ul>
 */
class HttpClientBuilderMutatorTest {

    @RegisterExtension
    static final HttpServerExtension httpServer = new HttpServerExtension();

    private final AemContext context = new AemContext();
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        // Register required services
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        context.registerInjectActivateService(new HttpConfigServiceImpl());
        httpClientProvider = context.registerInjectActivateService(new HttpClientProviderImpl());
    }

    /**
     * Test that the builder mutator is actually called.
     */
    @Test
    void testBuilderMutatorIsActuallyCalled() {
        final boolean[] mutatorWasCalled = {false};

        httpClientProvider.provide(
            "test-mutator",
            null,
            builder -> {
                mutatorWasCalled[0] = true;
                // Don't actually modify anything, just track that we were called
            }
        );

        assertTrue(mutatorWasCalled[0], "Builder mutator must be called during client creation");
    }

    /**
     * Test that changes made via the mutator are reflected in the resulting client.
     */
    @Test
    void testBuilderMutatorChangesAreApplied() throws Exception {
        final String[] receivedHeader = {null};

        httpServer.registerHandler("/test", exchange -> {
            receivedHeader[0] = exchange.getRequestHeaders().getFirst("X-Test-Mutator");
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Create a client with a mutator that adds an interceptor
        CloseableHttpClient httpClient = httpClientProvider.provide(
            "test-mutator-applied",
            null,
            builder -> builder.addInterceptorLast((HttpRequestInterceptor) (request, context) -> request.setHeader("X-Test-Mutator", "mutator-was-applied"))
        );

        // Execute a request
        HttpGet request = new HttpGet(httpServer.getUriFor("/test"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }

        // Verify the interceptor added by the mutator was actually used
        assertEquals("mutator-was-applied", receivedHeader[0], 
            "Header added by mutator interceptor should be present");
    }

    /**
     * Test that multiple customizations can be chained in a single mutator.
     */
    @Test
    void testMultipleCustomizationsViaBuilderMutator() throws Exception {
        final String[] receivedHeader1 = {null};
        final String[] receivedHeader2 = {null};

        httpServer.registerHandler("/multi", exchange -> {
            receivedHeader1[0] = exchange.getRequestHeaders().getFirst("X-Custom-1");
            receivedHeader2[0] = exchange.getRequestHeaders().getFirst("X-Custom-2");
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Create a client with multiple customizations
        CloseableHttpClient httpClient = httpClientProvider.provide(
            "test-multi-mutator",
            null,
            builder -> {
                // Add multiple interceptors
                builder.addInterceptorLast((HttpRequest request, HttpContext context) -> request.setHeader("X-Custom-1", "value-1"));
                builder.addInterceptorLast((HttpRequest request, HttpContext context) -> request.setHeader("X-Custom-2", "value-2"));
                // Could also customize other aspects:
                // builder.setUserAgent("custom-agent");
                // builder.setRetryHandler(...);
                // etc.
            }
        );

        // Execute a request
        HttpGet request = new HttpGet(httpServer.getUriFor("/multi"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }

        // Verify both headers were added
        assertEquals("value-1", receivedHeader1[0], "First header should be present");
        assertEquals("value-2", receivedHeader2[0], "Second header should be present");
    }

    /**
     * Test that null mutator is handled gracefully (no-op).
     */
    @Test
    void testNullMutatorIsHandledGracefully() throws Exception {
        httpServer.registerHandler("/null-mutator", exchange -> {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Create a client with null mutator (should be treated as no-op)
        CloseableHttpClient httpClient = httpClientProvider.provide(
            "test-null-mutator",
            null,
            null  // null mutator
        );

        // Should still work
        HttpGet request = new HttpGet(httpServer.getUriFor("/null-mutator"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            assertEquals("OK", body);
        }
    }
}
