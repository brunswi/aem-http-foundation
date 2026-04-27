package org.kttn.aem.http.impl;

import java.util.function.Consumer;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfig;

/**
 * Bundle-internal SPI for obtaining pooled HTTP clients under reserved keys (see
 * {@link HttpClientProvider#RESERVED_KEY_PREFIX}) without triggering the public-API guard on
 * {@link HttpClientProvider#provide(String, HttpConfig, Consumer)}.
 * <p>
 * Implemented by {@link HttpClientProviderImpl} and exported as a separate OSGi service so that
 * other components in this bundle (for example
 * {@link org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier} or
 * {@link org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration}) can request pools under reserved
 * keys via {@code @Reference} without resorting to a class cast.
 * <p>
 * Not part of the public API; do not depend on this from consumer bundles.
 */
public interface InternalHttpClientProvider {

    CloseableHttpClient provideInternal(String key,
                                        HttpConfig config,
                                        Consumer<HttpClientBuilder> builderMutator);
}
