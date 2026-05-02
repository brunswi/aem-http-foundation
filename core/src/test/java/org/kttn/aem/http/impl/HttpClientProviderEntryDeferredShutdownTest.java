package org.kttn.aem.http.impl;

import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.kttn.aem.http.HttpConfig;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the deferred-shutdown behaviour in
 * {@link HttpClientProviderEntry#swapUnderlying}.
 * <p>
 * A mock {@link ScheduledExecutorService} is injected so the tests are deterministic: the
 * deferred task is captured via {@link ArgumentCaptor} and executed explicitly rather than
 * waiting for a real timer to fire.
 */
class HttpClientProviderEntryDeferredShutdownTest {

    private static final int SOCKET_TIMEOUT_MS = 5_000;

    private HttpClientProviderEntry buildEntry(
        final CloseableHttpClient realClient,
        final HttpClientConnectionManager cm) {
        final HttpConfig config = mock(HttpConfig.class);
        when(config.getSocketTimeout()).thenReturn(SOCKET_TIMEOUT_MS);
        final ManagedHttpClient managed = new ManagedHttpClient(realClient);
        return new HttpClientProviderEntry(managed, realClient, cm, config, null);
    }

    // --- immediate actions on swap ---

    /**
     * Idle connections in the old pool must be evicted right away so stale sockets are released
     * before the grace period begins.
     */
    @Test
    void idleConnectionsAreClosedImmediatelyOnSwap() {
        final HttpClientConnectionManager oldCm = mock(HttpClientConnectionManager.class);
        final HttpClientProviderEntry entry = buildEntry(mock(CloseableHttpClient.class), oldCm);

        entry.swapUnderlying(
            mock(CloseableHttpClient.class),
            mock(HttpClientConnectionManager.class),
            mock(ScheduledExecutorService.class));

        verify(oldCm).closeIdleConnections(0L, TimeUnit.MILLISECONDS);
    }

    /**
     * Hard shutdown must NOT happen synchronously: in-flight requests on the old pool
     * must not be disrupted at the moment of the swap.
     */
    @Test
    void shutdownIsNotCalledSynchronouslyOnSwap() throws IOException {
        final HttpClientConnectionManager oldCm = mock(HttpClientConnectionManager.class);
        final CloseableHttpClient oldClient = mock(CloseableHttpClient.class);
        final HttpClientProviderEntry entry = buildEntry(oldClient, oldCm);

        entry.swapUnderlying(
            mock(CloseableHttpClient.class),
            mock(HttpClientConnectionManager.class),
            mock(ScheduledExecutorService.class));

        verify(oldCm, never()).shutdown();
        verify(oldClient, never()).close();
    }

    // --- deferred task scheduling ---

    /**
     * The deferred shutdown must be scheduled with the entry's {@code socketTimeout} as the
     * delay. That is the tightest correct upper bound: any active request either completes
     * or times out within that window.
     */
    @Test
    void deferredShutdownIsScheduledWithSocketTimeout() {
        final HttpClientProviderEntry entry = buildEntry(
            mock(CloseableHttpClient.class), mock(HttpClientConnectionManager.class));
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        entry.swapUnderlying(
            mock(CloseableHttpClient.class),
            mock(HttpClientConnectionManager.class),
            scheduler);

        verify(scheduler).schedule(
            any(Runnable.class), eq((long) SOCKET_TIMEOUT_MS), eq(TimeUnit.MILLISECONDS));
    }

    // --- deferred task execution ---

    /**
     * When the deferred task fires it must shut down the old connection manager and close the
     * old real client.
     */
    @Test
    void deferredTaskShutsDownOldManagerAndClosesOldClient() throws IOException {
        final CloseableHttpClient oldClient = mock(CloseableHttpClient.class);
        final HttpClientConnectionManager oldCm = mock(HttpClientConnectionManager.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        buildEntry(oldClient, oldCm).swapUnderlying(
            mock(CloseableHttpClient.class),
            mock(HttpClientConnectionManager.class),
            scheduler);

        verify(scheduler).schedule(captor.capture(), anyLong(), any());
        captor.getValue().run();

        verify(oldCm).shutdown();
        verify(oldClient).close();
    }

    /**
     * An {@link IOException} thrown during deferred cleanup must be caught and logged, not
     * propagated — the scheduler thread must not be disrupted by a failed close.
     */
    @Test
    void deferredTaskDoesNotPropagateIOExceptionFromClose() throws IOException {
        final CloseableHttpClient oldClient = mock(CloseableHttpClient.class);
        final HttpClientConnectionManager oldCm = mock(HttpClientConnectionManager.class);
        doThrow(new IOException("socket already closed")).when(oldClient).close();
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        buildEntry(oldClient, oldCm).swapUnderlying(
            mock(CloseableHttpClient.class),
            mock(HttpClientConnectionManager.class),
            scheduler);

        verify(scheduler).schedule(captor.capture(), anyLong(), any());
        assertDoesNotThrow(() -> captor.getValue().run(),
            "IOException from close() must not propagate out of the deferred task");
        verify(oldCm).shutdown();
    }

    /**
     * Each call to {@link HttpClientProviderEntry#swapUnderlying} schedules exactly one deferred
     * task. Two successive swaps schedule two independent tasks targeting their respective old
     * resources.
     */
    @Test
    void eachSwapSchedulesOneIndependentDeferredTask() throws IOException {
        final CloseableHttpClient oldClient1 = mock(CloseableHttpClient.class);
        final HttpClientConnectionManager oldCm1 = mock(HttpClientConnectionManager.class);
        final CloseableHttpClient oldClient2 = mock(CloseableHttpClient.class);
        final HttpClientConnectionManager oldCm2 = mock(HttpClientConnectionManager.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        final HttpClientProviderEntry entry = buildEntry(oldClient1, oldCm1);

        entry.swapUnderlying(mock(CloseableHttpClient.class), oldCm2, scheduler);
        // Simulate: oldClient2 / oldCm2 are now "old" for the second swap
        final HttpClientProviderEntry entry2 = buildEntry(oldClient2, oldCm2);
        entry2.swapUnderlying(mock(CloseableHttpClient.class), mock(HttpClientConnectionManager.class), scheduler);

        verify(scheduler, times(2)).schedule(captor.capture(), anyLong(), any());

        // Each captured task cleans up its own old client — run both
        for (final Runnable task : captor.getAllValues()) {
            task.run();
        }
        verify(oldClient1).close();
        verify(oldClient2).close();
    }
}
