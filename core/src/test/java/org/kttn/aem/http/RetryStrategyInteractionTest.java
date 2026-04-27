package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for retry strategy interaction: verifies that IO retries and 503 retries
 * work independently and compose correctly, and that retry limits are enforced.
 */
class RetryStrategyInteractionTest {

    @RegisterExtension
    static final HttpServerExtension httpServerExtension = new HttpServerExtension();

    private final AemContext context = new AemContext();
    private final HttpClientProvider httpClientProvider = new HttpClientProviderImpl();

    @BeforeEach
    protected void setUp() {
        final HttpConfigService httpConfigService = new HttpConfigServiceImpl();
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        context.registerInjectActivateService(httpClientProvider);
    }

    /**
     * Tests that HTTP 503 responses trigger ServiceUnavailableRetryStrategy up to maxRetryCount,
     * then fail with the last 503 response.
     */
    @Test
    void http503RetriesUpToMaxThenFails() throws Exception {
        final AtomicInteger attemptCount = new AtomicInteger(0);

        httpServerExtension.registerHandler("/always-503", exchange -> {
            attemptCount.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.getResponseBody().close();
        });

        final HttpConfig retryConfig = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(5000)
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(3) // Retry 503 up to 3 times
            .serviceUnavailableRetryInterval(10) // Fast retries for test
            .ioExceptionMaxRetryCount(1)
            .ioExceptionRetryInterval(1)
            .build();

        final CloseableHttpClient client = httpClientProvider.provide("retry-503-test", retryConfig);

        try (CloseableHttpResponse response = client.execute(
            new HttpGet(httpServerExtension.getUriFor("/always-503")))) {
            
            // Should eventually get 503 response (not throw exception)
            assertEquals(503, response.getStatusLine().getStatusCode());
        }

        // Should have tried: 1 initial + 3 retries = 4 total attempts
        assertEquals(4, attemptCount.get(), 
            "Expected 4 attempts (1 initial + 3 retries) for 503, got " + attemptCount.get());
    }

    /**
     * Tests that transient IO exceptions trigger HttpRequestRetryHandler up to maxRetryCount.
     * We simulate this by having the server close connection abruptly during response.
     */
    @Test
    void ioExceptionsRetryUpToMaxThenFail() throws Exception {
        final AtomicInteger attemptCount = new AtomicInteger(0);

        httpServerExtension.registerHandler("/flaky-connection", exchange -> {
            final int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                // First 2 attempts: close connection abruptly (triggers IO exception)
                exchange.close();
            } else {
                // 3rd attempt: succeed
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
                exchange.getResponseBody().close();
            }
        });

        final HttpConfig retryConfig = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(5000)
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(1)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(2) // Retry IO failures up to 2 times
            .ioExceptionRetryInterval(10) // Fast retries for test
            .build();

        final CloseableHttpClient client = httpClientProvider.provide("retry-io-test", retryConfig);

        try (CloseableHttpResponse response = client.execute(
            new HttpGet(httpServerExtension.getUriFor("/flaky-connection")))) {
            
            assertEquals(200, response.getStatusLine().getStatusCode());
        }

        // Should have tried: 2 failures + 1 success = 3 total attempts
        assertTrue(attemptCount.get() >= 3, 
            "Expected at least 3 attempts (2 IO failures + 1 success), got " + attemptCount.get());
    }

    /**
     * Tests that when retries are minimal (maxRetryCount = 1), failures are retried only once.
     */
    @Test
    void minimalRetriesLimitedToOne() throws Exception {
        final AtomicInteger attemptCount = new AtomicInteger(0);

        httpServerExtension.registerHandler("/minimal-retry-503", exchange -> {
            attemptCount.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.getResponseBody().close();
        });

        final HttpConfig minimalRetryConfig = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(5000)
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(1) // Only 1 retry (minimum allowed)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(1)
            .ioExceptionRetryInterval(1)
            .build();

        final CloseableHttpClient client = httpClientProvider.provide("minimal-retry-test", minimalRetryConfig);

        try (CloseableHttpResponse response = client.execute(
            new HttpGet(httpServerExtension.getUriFor("/minimal-retry-503")))) {

            assertEquals(503, response.getStatusLine().getStatusCode());
        }

        // Should have tried: 1 initial + 1 retry = 2 total attempts
        assertEquals(2, attemptCount.get(),
            "Expected exactly 2 attempts (1 initial + 1 retry), got " + attemptCount.get());
    }

    /**
     * Tests that retry interval is respected (requests are spaced out by at least the interval).
     */
    @Test
    void retryIntervalIsRespected() throws Exception {
        final AtomicInteger attemptCount = new AtomicInteger(0);
        final long[] attemptTimestamps = new long[4];

        httpServerExtension.registerHandler("/timed-503", exchange -> {
            final int attempt = attemptCount.getAndIncrement();
            attemptTimestamps[attempt] = System.currentTimeMillis();
            exchange.sendResponseHeaders(503, -1);
            exchange.getResponseBody().close();
        });

        final HttpConfig timedRetryConfig = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(5000)
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(3)
            .serviceUnavailableRetryInterval(200) // 200ms between retries
            .ioExceptionMaxRetryCount(0)
            .ioExceptionRetryInterval(0)
            .build();

        final CloseableHttpClient client = httpClientProvider.provide("timed-retry-test", timedRetryConfig);

        try (CloseableHttpResponse response = client.execute(
            new HttpGet(httpServerExtension.getUriFor("/timed-503")))) {
            
            assertEquals(503, response.getStatusLine().getStatusCode());
        }

        // Verify spacing between attempts is at least ~200ms
        for (int i = 1; i < attemptCount.get(); i++) {
            final long gap = attemptTimestamps[i] - attemptTimestamps[i - 1];
            assertTrue(gap >= 150, // Allow some margin for timing jitter
                "Expected at least 150ms between retry " + (i-1) + " and " + i + ", got " + gap + "ms");
        }
    }
}
