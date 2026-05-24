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
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ManagedHttpClient}: delegate swapping and lifecycle semantics.
 */
class ManagedHttpClientTest {

    // ManagedHttpClient.doExecute calls delegate.execute(host, request, context).
    // When entering via execute(HttpUriRequest), the context is null, so use nullable().

    @Test
    void executeDelegatesToUnderlyingClient() throws IOException {
        CloseableHttpClient delegate = mock(CloseableHttpClient.class);
        CloseableHttpResponse expectedResponse = mock(CloseableHttpResponse.class);
        when(delegate.execute(any(HttpHost.class), any(HttpRequest.class), nullable(HttpContext.class)))
            .thenReturn(expectedResponse);

        ManagedHttpClient managed = new ManagedHttpClient(delegate);
        CloseableHttpResponse actual = managed.execute(new HttpGet("http://example.com/"));

        assertSame(expectedResponse, actual);
        verify(delegate).execute(any(HttpHost.class), any(HttpRequest.class), nullable(HttpContext.class));
    }

    @Test
    void updateSwapsDelegateTransparently() throws IOException {
        CloseableHttpClient first = mock(CloseableHttpClient.class);
        CloseableHttpClient second = mock(CloseableHttpClient.class);
        CloseableHttpResponse responseFromSecond = mock(CloseableHttpResponse.class);
        when(second.execute(any(HttpHost.class), any(HttpRequest.class), nullable(HttpContext.class)))
            .thenReturn(responseFromSecond);

        ManagedHttpClient managed = new ManagedHttpClient(first);
        managed.update(second);

        CloseableHttpResponse actual = managed.execute(new HttpGet("http://example.com/"));

        assertSame(responseFromSecond, actual);
        verifyNoInteractions(first);
        verify(second).execute(any(HttpHost.class), any(HttpRequest.class), nullable(HttpContext.class));
    }

    @Test
    void closesAreNoOps() throws IOException {
        CloseableHttpClient delegate = mock(CloseableHttpClient.class);
        ManagedHttpClient managed = new ManagedHttpClient(delegate);

        managed.close();

        // Provider-managed lifecycle: close() on the wrapper must never reach the real client.
        verify(delegate, never()).close();
    }

}
