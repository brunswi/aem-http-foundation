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
