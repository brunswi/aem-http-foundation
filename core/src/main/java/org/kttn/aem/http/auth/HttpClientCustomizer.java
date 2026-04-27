package org.kttn.aem.http.auth;

import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Mutates an Apache {@link HttpClientBuilder} before the client is built.
 * <p>
 * Equivalent in shape to {@link java.util.function.Consumer Consumer&lt;HttpClientBuilder&gt;},
 * but expressed as an explicit named contract so it reads better as an OSGi or public API
 * argument and so JavaDoc can describe responsibilities precisely. Composable customizers (for
 * example bearer auth plus Adobe-specific headers) typically implement this interface and can be
 * combined via {@link #andThen(HttpClientCustomizer)}.
 * <p>
 * Customizers are applied <strong>once</strong> when the client for a given key is first built;
 * they must not retain per-request state.
 */
@FunctionalInterface
public interface HttpClientCustomizer {

    /**
     * Applies this customization (registering interceptors, setting a user agent, etc.) to the
     * provided builder.
     *
     * @param builder non-null builder owned by the caller; do not call {@code build()} here
     */
    void customize(HttpClientBuilder builder);

    /**
     * Returns a customizer that first applies this one, then {@code after}.
     *
     * @param after non-null next customizer
     * @return composed customizer
     */
    default HttpClientCustomizer andThen(final HttpClientCustomizer after) {
        return builder -> {
            this.customize(builder);
            after.customize(builder);
        };
    }
}
