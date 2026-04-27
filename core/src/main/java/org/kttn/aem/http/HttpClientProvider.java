package org.kttn.aem.http;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.util.function.Consumer;

/**
 * Factory for pooled, configured {@link CloseableHttpClient} instances addressed by a logical key.
 * <p>
 * Typical AEM usage: inject this OSGi service and call {@code provide(key)} with a stable key
 * per integration; optionally pass a {@code builderMutator} to attach interceptors (for example
 * via {@link org.kttn.aem.http.auth.HttpClientCustomizer}) before the client is built.
 *
 * @see org.kttn.aem.http.impl.HttpClientProviderImpl
 */
public interface HttpClientProvider {

    /**
     * Reserved prefix for cache keys used by this bundle's own components (for example the Adobe
     * IMS token client). Public callers passing a key that starts with this prefix will receive an
     * {@link IllegalArgumentException} from {@code provide(...)}; this prevents accidental
     * collisions with internal pools.
     */
    String RESERVED_KEY_PREFIX = "__internal__:";

    /**
     * Equivalent to {@code provide("DEFAULT")} with default configuration and no builder mutation.
     *
     * @return the client bound to the {@code DEFAULT} cache key
     */
    default CloseableHttpClient provideDefault() {
        return provide("DEFAULT");
    }

    /**
     * Returns a client for the given key using default configuration from the implementation
     * (usually {@link HttpConfigService}).
     *
     * @param key non-null logical identifier; each distinct key typically maps to one pooled client
     * @return cached or newly created client
     */
    default CloseableHttpClient provide(String key) {
        return provide(key, null);
    }

    /**
     * Returns a client for the given key, applying {@code config} when non-null; otherwise using
     * the implementation’s configured defaults.
     *
     * @param key    non-null cache key
     * @param config optional overrides; {@code null} selects default {@link HttpConfig}
     * @return cached or newly created client
     */
    default CloseableHttpClient provide(String key, HttpConfig config) {
        return provide(key, config, null);
    }

    /**
     * Returns a pooled {@link CloseableHttpClient} for {@code key}, merging optional {@code config}
     * and optional {@link HttpClientBuilder} customization.
     * <p>
     * Implementations normally cache clients by {@code key}: the first call for a key builds the
     * client and registers it; later calls return the same instance. Mutations via
     * {@code builderMutator} apply only when the client is first created for that key.
     *
     * @param key             non-null logical cache key
     * @param config          optional timeouts and retry settings; {@code null} uses service defaults
     * @param builderMutator  optional hook to register interceptors, SSL context, or other builder
     *                        settings; {@code null} if not needed
     * @return shared {@link CloseableHttpClient} for {@code key}
     */
    CloseableHttpClient provide(String key, HttpConfig config, Consumer<HttpClientBuilder> builderMutator);

}
