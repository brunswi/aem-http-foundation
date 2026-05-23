/*
 * Copyright (C) 2026 KTTN AEM Libraries
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kttn.aem.http.impl;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager; //NOSONAR - abstract method required by HttpClient interface
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams; //NOSONAR - abstract method required by HttpClient interface
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
