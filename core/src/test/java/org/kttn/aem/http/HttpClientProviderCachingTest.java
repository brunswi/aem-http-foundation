package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HttpClientProvider client caching and key validation.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Calling provide() with the same key returns the SAME client instance</li>
 *   <li>Different keys create DIFFERENT client instances</li>
 *   <li>Reserved key prefixes are rejected</li>
 *   <li>Client lifecycle is managed properly</li>
 * </ul>
 */
@ExtendWith(AemContextExtension.class)
class HttpClientProviderCachingTest {

    private final AemContext context = new AemContext();
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        // Register required services
        AemMockOsgiSupport.registerUninitializedKeyStoreService(context);
        context.registerInjectActivateService(new HttpConfigServiceImpl());
        httpClientProvider = context.registerInjectActivateService(new HttpClientProviderImpl());
    }

    /**
     * Test that calling provide() with the same key returns the SAME client instance.
     * <p>
     * This is critical for connection pooling efficiency - we don't want to create
     * a new pool for every request!
     */
    @Test
    void testSameKeyReturnsSameClientInstance() {
        CloseableHttpClient client1 = httpClientProvider.provide("my-api");
        CloseableHttpClient client2 = httpClientProvider.provide("my-api");
        CloseableHttpClient client3 = httpClientProvider.provide("my-api");

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotNull(client3);

        // All three should be THE SAME INSTANCE
        assertSame(client1, client2, "Second call with same key should return same instance");
        assertSame(client1, client3, "Third call with same key should return same instance");
        assertSame(client2, client3, "All calls with same key should return same instance");
    }

    /**
     * Test that different keys create DIFFERENT client instances.
     */
    @Test
    void testDifferentKeysReturnDifferentInstances() {
        CloseableHttpClient clientA = httpClientProvider.provide("api-a");
        CloseableHttpClient clientB = httpClientProvider.provide("api-b");
        CloseableHttpClient clientC = httpClientProvider.provide("api-c");

        assertNotNull(clientA);
        assertNotNull(clientB);
        assertNotNull(clientC);

        // All three should be DIFFERENT INSTANCES
        assertNotSame(clientA, clientB, "Different keys should create different instances");
        assertNotSame(clientA, clientC, "Different keys should create different instances");
        assertNotSame(clientB, clientC, "Different keys should create different instances");
   }

    /**
     * Test that reserved key prefixes are rejected.
     */
    @Test
    void testReservedKeyPrefixIsRejected() {
        // Try to use a reserved key prefix
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> httpClientProvider.provide("__internal__:something"),
            "Should reject reserved key prefix"
        );

        String message = exception.getMessage();
        assertNotNull(message);
    }

    /**
     * Test that the same key with different configs returns the SAME cached instance.
     * <p>
     * This is the expected behavior - the cache key is based on the string key alone,
     * not the config. If you need different configs, use different keys.
     */
    @Test
    void testSameKeyWithDifferentConfigsReturnsSameInstance() {
        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);
        HttpConfig config1 = configService.getHttpConfig().toBuilder()
            .socketTimeout(10_000)
            .build();

        HttpConfig config2 = configService.getHttpConfig().toBuilder()
            .socketTimeout(20_000)  // Different timeout
            .build();

        CloseableHttpClient client1 = httpClientProvider.provide("my-api", config1);
        CloseableHttpClient client2 = httpClientProvider.provide("my-api", config2);

        assertNotNull(client1);
        assertNotNull(client2);

        // Should be THE SAME instance - caching is based on key alone
        assertSame(client1, client2,
            "Same key returns same cached instance, even with different config");
    }

    /**
     * Test that providing the same key with the same config returns the cached instance.
     */
    @Test
    void testSameKeyWithSameConfigReturnsCachedInstance() {
        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);
        HttpConfig config = configService.getHttpConfig().toBuilder()
            .socketTimeout(15_000)
            .build();

        CloseableHttpClient client1 = httpClientProvider.provide("my-api", config);
        CloseableHttpClient client2 = httpClientProvider.provide("my-api", config);

        assertNotNull(client1);
        assertNotNull(client2);

        // Should be THE SAME instance
        assertSame(client1, client2, 
            "Same key with same config should return cached instance");
    }

    /**
     * Test that null or empty keys are handled properly.
     */
    @Test
    void testNullOrEmptyKeysAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> httpClientProvider.provide(null),
            "Null key should be rejected"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> httpClientProvider.provide(""),
            "Empty key should be rejected"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> httpClientProvider.provide("   "),
            "Blank key should be rejected"
        );
    }
}
