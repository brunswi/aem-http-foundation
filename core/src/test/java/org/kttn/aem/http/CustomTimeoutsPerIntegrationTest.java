package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Example 4 from EXAMPLES.md: Custom timeouts per integration.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Different clients can have different timeout configurations</li>
 *   <li>Timeout configurations are isolated per client</li>
 *   <li>Custom timeouts override global defaults</li>
 *   <li>The pattern from EXAMPLES.md actually works</li>
 * </ul>
 */
class CustomTimeoutsPerIntegrationTest {

    @RegisterExtension
    static final HttpServerExtension httpServer = new HttpServerExtension();

    private final AemContext context = new AemContext();
    private HttpClientProvider httpClientProvider;
    private HttpConfigService httpConfigService;

    @BeforeEach
    void setUp() {
        // Register required services
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        httpConfigService = context.registerInjectActivateService(new HttpConfigServiceImpl());
        httpClientProvider = context.registerInjectActivateService(new HttpClientProviderImpl());
    }

    /**
     * Test the exact pattern from Example 4: custom timeouts for a slow export integration.
     */
    @Test
    void testCustomTimeoutPatternFromExample4() throws Exception {
        httpServer.registerHandler("/export", exchange -> {
            String response = "{\"export\":\"data\"}";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // Create custom config with extended timeouts (from Example 4)
        HttpConfig customConfig = httpConfigService.getHttpConfig().toBuilder()
            .socketTimeout(300_000)  // 5 minutes
            .connectionTimeout(30_000)  // 30 seconds
            .build();

        CloseableHttpClient httpClient = httpClientProvider.provide("slow-export", customConfig);

        // Verify the client works with the custom config
        HttpGet request = new HttpGet(httpServer.getUriFor("/export"));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            assertEquals("{\"export\":\"data\"}", body);
        }
    }

    /**
     * Test that different clients can have different timeout configurations.
     */
    @Test
    void testDifferentClientsHaveDifferentTimeouts() throws Exception {
        // Create two clients with VERY different socket timeouts
        HttpConfig shortTimeoutConfig = httpConfigService.getHttpConfig().toBuilder()
            .socketTimeout(100)  // 100ms - very short
            .build();

        HttpConfig longTimeoutConfig = httpConfigService.getHttpConfig().toBuilder()
            .socketTimeout(30_000)  // 30 seconds - very long
            .build();

        CloseableHttpClient shortTimeoutClient = httpClientProvider.provide(
            "short-timeout-client", 
            shortTimeoutConfig
        );
        CloseableHttpClient longTimeoutClient = httpClientProvider.provide(
            "long-timeout-client", 
            longTimeoutConfig
        );

        // Create a slow endpoint that takes 500ms to respond
        httpServer.registerHandler("/slow", exchange -> {
            try {
                Thread.sleep(500);  // Delay response
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        // Short timeout client should fail
        HttpGet request1 = new HttpGet(httpServer.getUriFor("/slow"));
        assertThrows(SocketTimeoutException.class, () -> {
            try (CloseableHttpResponse response = shortTimeoutClient.execute(request1)) {
                // Should time out before getting here
            }
        }, "Client with 100ms timeout should fail on 500ms delay");

        // Long timeout client should succeed
        HttpGet request2 = new HttpGet(httpServer.getUriFor("/slow"));
        try (CloseableHttpResponse response = longTimeoutClient.execute(request2)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            assertEquals("OK", body);
        }
    }

    /**
     * Test that HttpConfig can be customized via builder pattern.
     */
    @Test
    void testHttpConfigBuilderPattern() {
        HttpConfig defaultConfig = httpConfigService.getHttpConfig();
        assertNotNull(defaultConfig);

        // Build a custom config based on defaults
        HttpConfig customConfig = defaultConfig.toBuilder()
            .socketTimeout(60_000)
            .connectionTimeout(10_000)
            .build();

        // Verify the custom values
        assertEquals(60_000, customConfig.getSocketTimeout());
        assertEquals(10_000, customConfig.getConnectionTimeout());

        // Verify default config is unchanged
        assertEquals(10_000, defaultConfig.getSocketTimeout());
        assertEquals(10_000, defaultConfig.getConnectionTimeout());
    }
}
