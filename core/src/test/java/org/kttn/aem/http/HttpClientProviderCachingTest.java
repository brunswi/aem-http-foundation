package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
     * Test that the same key with different configs returns the SAME wrapper instance.
     * <p>
     * The stable {@link org.apache.http.impl.client.CloseableHttpClient} wrapper is never replaced;
     * only the underlying pooled client is swapped when the config changes.  Consumers that cached
     * the wrapper reference automatically pick up the new pool on the next request.
     */
    @Test
    void testSameKeyWithDifferentConfigsReturnsSameWrapperInstance() {
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

        // The wrapper (ManagedHttpClient) is the same object; only the underlying pool was rebuilt.
        assertSame(client1, client2,
            "Same key must always return the same wrapper instance regardless of config change");
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
     * When {@code config} is {@code null} on a re-provide call the provider must treat it as
     * "no change intended" and must not rebuild the underlying pool.
     * <p>
     * Verified by counting builder-mutator invocations: the mutator is called exactly once (during
     * the initial build); a subsequent {@code null}-config call must not increment the count.
     */
    @Test
    void testNullConfigOnReprovideDoesNotTriggerRebuild() {
        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);
        HttpConfig config = configService.getHttpConfig().toBuilder().socketTimeout(10_000).build();

        AtomicInteger buildCount = new AtomicInteger(0);
        Consumer<HttpClientBuilder> countingMutator = builder -> buildCount.incrementAndGet();

        httpClientProvider.provide("my-api", config, countingMutator);
        assertEquals(1, buildCount.get(), "mutator must be called exactly once during initial build");

        httpClientProvider.provide("my-api", (HttpConfig) null, null);
        assertEquals(1, buildCount.get(), "null config must not trigger a rebuild");
    }

    /**
     * When a config change triggers a pool rebuild, the incoming {@code builderMutator} must be
     * stored in the cache entry so that subsequent trust-store-triggered rebuilds use the updated
     * mutator rather than the one supplied at first-provide time.
     * <p>
     * The trust-store refresh is simulated by calling
     * {@link ResourceChangeListener#onChange} directly on the provider.
     */
    @Test
    void testBuilderMutatorIsUpdatedOnConfigChange() {
        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);
        HttpConfig config1 = configService.getHttpConfig().toBuilder().socketTimeout(10_000).build();
        HttpConfig config2 = configService.getHttpConfig().toBuilder().socketTimeout(20_000).build();

        AtomicInteger mutator1Calls = new AtomicInteger(0);
        AtomicInteger mutator2Calls = new AtomicInteger(0);
        Consumer<HttpClientBuilder> mutator1 = builder -> mutator1Calls.incrementAndGet();
        Consumer<HttpClientBuilder> mutator2 = builder -> mutator2Calls.incrementAndGet();

        // Initial build: mutator1 is applied.
        httpClientProvider.provide("my-api", config1, mutator1);
        assertEquals(1, mutator1Calls.get(), "mutator1 must be called once during initial build");
        assertEquals(0, mutator2Calls.get());

        // Config change: mutator2 is applied for the rebuild; mutator1 must not be called again.
        httpClientProvider.provide("my-api", config2, mutator2);
        assertEquals(1, mutator1Calls.get(), "mutator1 must not be called again after config change");
        assertEquals(1, mutator2Calls.get(), "mutator2 must be called once during config-change rebuild");

        // Trust-store refresh: must use mutator2 (the stored one), not mutator1.
        ResourceChangeListener listener = context.getService(ResourceChangeListener.class);
        assertNotNull(listener, "HttpClientProviderImpl must be registered as ResourceChangeListener");
        listener.onChange(List.of());
        assertEquals(1, mutator1Calls.get(), "mutator1 must not be called during trust-store refresh");
        assertEquals(2, mutator2Calls.get(), "mutator2 must be called again during trust-store refresh");
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
