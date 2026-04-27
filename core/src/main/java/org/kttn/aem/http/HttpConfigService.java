package org.kttn.aem.http;

/**
 * Provides the active {@link HttpConfig} snapshot for outbound HTTP clients (typically from OSGi
 * Metatype on author/publish).
 *
 * @see org.kttn.aem.http.impl.HttpConfigServiceImpl
 */
public interface HttpConfigService {

    /**
     * Returns the current HTTP client configuration (timeouts, pool size, retry counts).
     * <p>
     * In normal AEM lifecycle this is safe to call after the implementing component has activated;
     * callers should not rely on a non-null result before activation completes.
     *
     * @return configuration snapshot; not null once the backing component is active
     */
    HttpConfig getHttpConfig();
}
