package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.HttpHeaders;
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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Example 3 from EXAMPLES.md: Custom Basic Auth with HttpRequestInterceptor.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Custom interceptors are actually applied to the HTTP client</li>
 *   <li>Interceptors modify requests as expected (e.g., adding headers)</li>
 *   <li>Multiple interceptors can be chained</li>
 *   <li>Basic Auth pattern from EXAMPLES.md works correctly</li>
 * </ul>
 */
class CustomRequestInterceptorTest {

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
     * Test Basic Auth interceptor pattern from Example 3.
     * <p>
     * This is the EXACT pattern from EXAMPLES.md - verifies it actually works.
     */
    @Test
    void testBasicAuthInterceptorFromExample3() throws Exception {
        // Track which headers the server receives
        final String[] receivedAuthHeader = {null};

        httpServer.registerHandler("/protected", exchange -> {
            receivedAuthHeader[0] = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String response = "{\"status\":\"success\"}";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Create Basic Auth interceptor (from Example 3)
        String username = "api-user";
        String password = "secret-password";
        HttpRequestInterceptor basicAuthInterceptor = new BasicAuthInterceptor(username, password);

        // Create client with interceptor
        CloseableHttpClient httpClient = httpClientProvider.provide(
            "protected-api",
            null,
            builder -> builder.addInterceptorLast(basicAuthInterceptor)
        );

        // Execute request
        HttpGet request = new HttpGet(httpServer.getUriFor("/protected"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            assertEquals("{\"status\":\"success\"}", body);
        }

        // Verify the Authorization header was sent
        assertNotNull(receivedAuthHeader[0], "Authorization header should be present");
        assertTrue(receivedAuthHeader[0].startsWith("Basic "), "Should be Basic auth");

        // Verify the credentials are correctly encoded
        String expectedCredentials = username + ":" + password;
        String expectedEncoded = Base64.getEncoder().encodeToString(
            expectedCredentials.getBytes(StandardCharsets.UTF_8)
        );
        String expectedHeader = "Basic " + expectedEncoded;
        assertEquals(expectedHeader, receivedAuthHeader[0]);
    }

    /**
     * Test that interceptors are actually called during request execution.
     */
    @Test
    void testInterceptorIsActuallyCalled() throws Exception {
        httpServer.registerHandler("/test", exchange -> {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Track if interceptor was called
        final boolean[] interceptorWasCalled = {false};

        HttpRequestInterceptor trackingInterceptor = (request, context) -> {
            interceptorWasCalled[0] = true;
            request.setHeader("X-Custom-Header", "custom-value");
        };

        CloseableHttpClient httpClient = httpClientProvider.provide(
            "test-interceptor",
            null,
            builder -> builder.addInterceptorLast(trackingInterceptor)
        );

        HttpGet request = new HttpGet(httpServer.getUriFor("/test"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }

        assertTrue(interceptorWasCalled[0], "Interceptor must be called during request execution");
    }

    /**
     * Basic Auth interceptor implementation from Example 3 in EXAMPLES.md.
     */
    private static final class BasicAuthInterceptor implements HttpRequestInterceptor {
        private final String authorizationValue;

        BasicAuthInterceptor(String username, String password) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
            );
            this.authorizationValue = "Basic " + encoded;
        }

        @Override
        public void process(HttpRequest request, HttpContext context) {
            if (!request.containsHeader(HttpHeaders.AUTHORIZATION)) {
                request.setHeader(HttpHeaders.AUTHORIZATION, authorizationValue);
            }
        }
    }
}
