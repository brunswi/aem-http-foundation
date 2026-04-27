package org.kttn.aem.http.impl;

import lombok.Builder;
import lombok.Getter;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Holds a {@link CloseableHttpClient} together with its {@link HttpClientConnectionManager} so both
 * can be released on shutdown (manager first, then client).
 *
 * @see HttpClientProviderImpl
 */
@Builder
@Getter
public class HttpClientProviderEntry {

    /** Pool backing the client; must be {@link HttpClientConnectionManager#shutdown()} before close. */
    private HttpClientConnectionManager connectionManager;

    /** Client using {@link #connectionManager}. */
    private CloseableHttpClient httpClient;
}
