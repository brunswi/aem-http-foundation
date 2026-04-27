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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress and edge-case tests for {@link HttpClientProvider} covering connection pool
 * exhaustion, timeouts, and concurrent access patterns.
 */
class HttpClientProviderStressTest {

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
     * Tests connection pool exhaustion: when maxConnectionPerRoute concurrent requests are in-flight,
     * the next request should block waiting for a connection (or timeout via connectionManagerTimeout).
     */
    @Test
    void connectionPoolExhaustionBlocksAdditionalRequests() throws Exception {
        // Configure tiny pool: max 2 connections per route
        final HttpConfig tinyPoolConfig = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(1000) // Wait max 1 second for pool slot
            .socketTimeout(10000)
            .maxConnection(5)
            .maxConnectionPerRoute(2) // KEY: only 2 concurrent connections to same host
            .serviceUnavailableMaxRetryCount(1)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(1)
            .ioExceptionRetryInterval(1)
            .build();

        // Handler that holds connections open
        final CountDownLatch releaseConnections = new CountDownLatch(1);
        httpServerExtension.registerHandler("/slow", exchange -> {
            try {
                // Block until test releases the latch
                releaseConnections.await(30, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final CloseableHttpClient client = httpClientProvider.provide("tiny-pool", tinyPoolConfig);
        final ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            final List<Future<Long>> futures = new ArrayList<>();
            final CountDownLatch startLatch = new CountDownLatch(1);

            // Launch 3 requests, but pool only allows 2 concurrent
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    final long start = System.currentTimeMillis();
                    try (CloseableHttpResponse ignored = client.execute(
                        new HttpGet(httpServerExtension.getUriFor("/slow")))) {
                        return System.currentTimeMillis() - start;
                    }
                }));
            }

            startLatch.countDown();
            Thread.sleep(500); // Give threads time to grab pool slots

            // First 2 should be in-flight; 3rd should be blocked waiting for pool
            // Release them all
            releaseConnections.countDown();

            final List<Long> durations = new ArrayList<>();
            for (Future<Long> future : futures) {
                durations.add(future.get(15, TimeUnit.SECONDS));
            }

            // At least one request should have been delayed waiting for pool
            final long maxDuration = durations.stream().mapToLong(Long::longValue).max().orElse(0);
            assertTrue(maxDuration > 400, 
                "Expected at least one request delayed by pool wait, max duration was " + maxDuration + "ms");

        } finally {
            releaseConnections.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Tests that concurrent requests to the same key return the SAME cached client instance
     * (verifies thread-safe caching in computeIfAbsent).
     */
    @Test
    void concurrentProvideCallsReturnSameCachedInstance() throws Exception {
        final String key = "concurrent-key-" + System.nanoTime();
        final int threadCount = 50;
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<CloseableHttpClient>> futures = new ArrayList<>(threadCount);

            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return httpClientProvider.provide(key);
                }));
            }

            start.countDown();

            CloseableHttpClient first = null;
            for (Future<CloseableHttpClient> future : futures) {
                final CloseableHttpClient client = future.get(10, TimeUnit.SECONDS);
                if (first == null) {
                    first = client;
                } else {
                    assertTrue(first == client, "All concurrent calls should return same instance");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
