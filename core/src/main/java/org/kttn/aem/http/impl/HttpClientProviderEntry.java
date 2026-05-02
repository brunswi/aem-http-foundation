package org.kttn.aem.http.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.kttn.aem.http.HttpConfig;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
     * Atomically replaces the real client and connection manager, then schedules cleanup of the
     * old ones after a grace period equal to {@link HttpConfig#getSocketTimeout()}.
     * <p>
     * The sequence is:
     * <ol>
     *   <li>Fields and the {@link ManagedHttpClient} wrapper are updated — new requests
     *       immediately use the new pool.</li>
     *   <li>Idle connections in the old pool are evicted right away via
     *       {@link HttpClientConnectionManager#closeIdleConnections closeIdleConnections(0)}.</li>
     *   <li>Hard {@link HttpClientConnectionManager#shutdown shutdown()} of the old pool is
     *       deferred by {@code socketTimeout} milliseconds. Any request that grabbed a connection
     *       from the old pool just before the swap will either complete or time out within that
     *       window, so it is not cut off mid-flight.</li>
     * </ol>
     *
     * @param newRealClient         replacement pooled client
     * @param newConnectionManager  replacement connection manager
     * @param deferredCloseScheduler single-thread scheduler owned by {@link HttpClientProviderImpl};
     *                               used to fire the delayed shutdown
     */
    void swapUnderlying(
        final CloseableHttpClient newRealClient,
        final HttpClientConnectionManager newConnectionManager,
        final ScheduledExecutorService deferredCloseScheduler) {
        final CloseableHttpClient oldClient = this.realClient;
        final HttpClientConnectionManager oldManager = this.connectionManager;

        // Point the wrapper and the entry fields at the new pool. New requests use the new
        // client from this moment on.
        this.realClient = newRealClient;
        this.connectionManager = newConnectionManager;
        managedClient.update(newRealClient);

        // Evict idle connections immediately so stale sockets are not held open during the
        // grace period. Connections that are currently leased (in-flight requests) are left
        // untouched here.
        oldManager.closeIdleConnections(0, TimeUnit.MILLISECONDS);

        // The socket timeout is the tightest correct upper bound for an in-flight request:
        // any active request either completes or times out within this window, so deferring
        // the hard shutdown by that amount avoids a disruptive socket close mid-response.
        final long gracePeriodMs = config.getSocketTimeout();
        deferredCloseScheduler.schedule(() -> {
            try {
                oldManager.shutdown();
                oldClient.close();
            } catch (final IOException e) {
                log.error("Could not close superseded HTTP client during trust-store refresh", e);
            }
        }, gracePeriodMs, TimeUnit.MILLISECONDS);
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
