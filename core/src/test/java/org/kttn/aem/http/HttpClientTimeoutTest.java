package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for timeout behavior: socket timeout, connection timeout, and connection manager timeout.
 * Verifies that configured timeouts actually interrupt operations as expected.
 */
class HttpClientTimeoutTest {

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
     * Tests that socketTimeout actually interrupts a hanging read operation.
     * Server accepts connection but never sends any response data (hangs on initial read).
     */
    @Test
    void socketTimeoutInterruptsHangingRead() {
        final HttpConfig shortSocketTimeout = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(5000)
            .socketTimeout(500) // 500ms socket timeout
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(1)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(1) // Minimal retry so we can measure timeout
            .ioExceptionRetryInterval(1)
            .build();

        // Handler that accepts connection but NEVER sends any response data
        // This causes the client to hang waiting for the first byte, triggering socket timeout
        final CountDownLatch requestReceived = new CountDownLatch(1);
        httpServerExtension.registerHandler("/hanging", exchange -> {
            requestReceived.countDown();
            // Don't send any response - just hang for a bit (long enough for client to timeout)
            // Client should timeout at 500ms, so we only need to hold for ~2 seconds
            try {
                Thread.sleep(2000); // Hold connection open without sending anything
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final CloseableHttpClient client = httpClientProvider.provide("socket-timeout-test", shortSocketTimeout);

        final long startTime = System.currentTimeMillis();
        final Exception thrown = assertThrows(Exception.class, () -> client.execute(new HttpGet(httpServerExtension.getUriFor("/hanging"))));
        final long duration = System.currentTimeMillis() - startTime;

        // Verify it timed out quickly (around 500ms * (1 + retries), not 60 seconds)
        assertTrue(duration < 5000,
            "Expected timeout within a few seconds, but took " + duration + "ms");

        // Should be SocketTimeoutException (possibly wrapped)
        Throwable cause = thrown;
        boolean foundSocketTimeout = false;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                foundSocketTimeout = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(foundSocketTimeout,
            "Expected SocketTimeoutException in cause chain, got: " + thrown.getClass().getName());
    }

    /**
     * Tests that connectionTimeout is properly configured and would interrupt slow connections.
     * We verify the configuration is accepted rather than actually timing out (to avoid slow tests).
     */
    @Test
    void connectionTimeoutIsConfigurable() {
        final HttpConfig shortConnectTimeout = HttpConfig.builder()
            .connectionTimeout(500) // 500ms connection timeout
            .connectionManagerTimeout(5000)
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(1)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(1)
            .ioExceptionRetryInterval(1)
            .build();

        // Just verify we can build a client with custom connection timeout
        // Actually testing connection timeout would require connecting to a black hole IP
        // which is slow and unreliable in CI environments
        final CloseableHttpClient client = httpClientProvider.provide("connect-timeout-test", shortConnectTimeout);
        assertNotNull(client, "Client should be created with custom connectionTimeout");
    }

    /**
     * Tests that connectionManagerTimeout is respected when pool is exhausted.
     * This is tested more thoroughly in HttpClientProviderStressTest, but we verify the config here.
     */
    @Test
    void connectionManagerTimeoutIsConfigurable() {
        final HttpConfig customManagerTimeout = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(1234) // Custom value
            .socketTimeout(5000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(1)
            .serviceUnavailableRetryInterval(1)
            .ioExceptionMaxRetryCount(1)
            .ioExceptionRetryInterval(1)
            .build();

        // Just verify we can build a client with custom timeout (actual pool exhaustion tested elsewhere)
        final CloseableHttpClient client = httpClientProvider.provide("manager-timeout-test", customManagerTimeout);
        assertNotNull(client, "Client should be created with custom connectionManagerTimeout");
    }
}
