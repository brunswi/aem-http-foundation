package org.kttn.aem.http.impl;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;

/**
 * A thin {@link CloseableHttpClient} wrapper returned to consumers by {@link HttpClientProviderImpl}.
 * <p>
 * The underlying real client is held in a {@code volatile} field so that
 * {@link HttpClientProviderImpl} can swap it transparently when the Granite trust store changes —
 * without invalidating references already held by consumers.
 * <p>
 * Consumers must not call {@link #close()} — lifecycle is managed by the provider.
 * <p>
 * {@code CQRules:AMSCORE-553} is suppressed because {@link org.apache.http.params.HttpParams} and
 * {@link org.apache.http.conn.ClientConnectionManager} are abstract methods on the
 * {@link org.apache.http.client.HttpClient} interface that every concrete subclass must implement.
 * There is no way to extend {@link CloseableHttpClient} without referencing these deprecated types.
 */
@SuppressWarnings("CQRules:AMSCORE-553")
class ManagedHttpClient extends CloseableHttpClient {

    private volatile CloseableHttpClient delegate;

    ManagedHttpClient(final CloseableHttpClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Replaces the underlying real client. Called by {@link HttpClientProviderImpl} when the
     * Granite trust store changes. The old client is closed by the caller after this returns.
     */
    void update(final CloseableHttpClient newDelegate) {
        this.delegate = newDelegate;
    }

    @Override
    protected CloseableHttpResponse doExecute(
        final HttpHost target, final HttpRequest request, final HttpContext context)
        throws IOException {
        return delegate.execute(target, request, context);
    }

    /** No-op: lifecycle is managed by {@link HttpClientProviderImpl}. */
    @Override
    public void close() {
    }

    @Override
    @Deprecated
    public HttpParams getParams() {
        return delegate.getParams();
    }

    @Override
    @Deprecated
    public ClientConnectionManager getConnectionManager() {
        return delegate.getConnectionManager();
    }
}
