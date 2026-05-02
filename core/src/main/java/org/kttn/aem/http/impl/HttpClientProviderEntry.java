package org.kttn.aem.http.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.kttn.aem.http.HttpConfig;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Holds a {@link ManagedHttpClient} wrapper together with the real pooled client, its connection
 * manager, and the build parameters needed to rebuild everything when the Granite trust store
 * changes.
 *
 * @see HttpClientProviderImpl
 */
@Slf4j
class HttpClientProviderEntry {

    /** Stable wrapper returned to consumers; its delegate is swapped on trust-store change. */
    @Getter
    private final ManagedHttpClient managedClient;

    /** Original config used to size and tune the pool; preserved for rebuild. */
    @Getter
    private final HttpConfig config;

    /** Original builder mutator (auth wiring, custom interceptors, …); preserved for rebuild. */
    @Getter
    private final Consumer<org.apache.http.impl.client.HttpClientBuilder> builderMutator;

    /** The real pooled client behind {@link #managedClient}. Replaced on trust-store change. */
    private volatile CloseableHttpClient realClient;

    /** The connection manager backing {@link #realClient}. Replaced on trust-store change. */
    private volatile HttpClientConnectionManager connectionManager;

    HttpClientProviderEntry(
        final ManagedHttpClient managedClient,
        final CloseableHttpClient realClient,
        final HttpClientConnectionManager connectionManager,
        final HttpConfig config,
        final Consumer<org.apache.http.impl.client.HttpClientBuilder> builderMutator) {
        this.managedClient = managedClient;
        this.realClient = realClient;
        this.connectionManager = connectionManager;
        this.config = config;
        this.builderMutator = builderMutator;
    }

    HttpClientConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Atomically replaces the real client and connection manager, then closes the old ones.
     * The {@link ManagedHttpClient} wrapper is updated first so that new requests immediately
     * use the new client while any in-flight request on the old connection drains normally.
     */
    void swapUnderlying(
        final CloseableHttpClient newRealClient,
        final HttpClientConnectionManager newConnectionManager) {
        final CloseableHttpClient oldClient = this.realClient;
        final HttpClientConnectionManager oldManager = this.connectionManager;

        this.realClient = newRealClient;
        this.connectionManager = newConnectionManager;
        managedClient.update(newRealClient);

        try {
            oldManager.shutdown();
            oldClient.close();
        } catch (IOException e) {
            log.error("Could not close superseded HTTP client during trust-store refresh", e);
        }
    }

    /** Shuts down the connection manager and closes the real client on component deactivation. */
    void close() {
        try {
            connectionManager.shutdown();
            realClient.close();
        } catch (IOException e) {
            log.error("Could not close HTTP client on deactivation", e);
        }
    }
}
