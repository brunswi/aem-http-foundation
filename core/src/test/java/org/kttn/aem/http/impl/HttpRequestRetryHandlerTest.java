package org.kttn.aem.http.impl;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link HttpRequestRetryHandler} behaviour beyond stock Apache defaults: retry budget,
 * non-retriable {@link BearerTokenUnavailableException}, and interrupt during backoff.
 */
class HttpRequestRetryHandlerTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void returnsFalseWhenExecutionCountExceedsMaxRetries() {
        final HttpRequestRetryHandler handler = new HttpRequestRetryHandler(2, 0L);
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setAttribute(HttpCoreContext.HTTP_REQUEST, new HttpGet("http://example.com/"));

        assertFalse(handler.retryRequest(new IOException("fail"), 3, ctx));
    }

    @Test
    void returnsFalseForBearerTokenUnavailableException() {
        final HttpRequestRetryHandler handler = new HttpRequestRetryHandler(5, 0L);
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setAttribute(HttpCoreContext.HTTP_REQUEST, new HttpGet("http://example.com/"));

        assertFalse(handler.retryRequest(
            new BearerTokenUnavailableException("no token"),
            1,
            ctx));
    }

    @Test
    void returnsFalseForSocketTimeoutException() {
        final HttpRequestRetryHandler handler = new HttpRequestRetryHandler(5, 0L);
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setAttribute(HttpCoreContext.HTTP_REQUEST, new HttpGet("http://example.com/"));

        assertFalse(handler.retryRequest(new SocketTimeoutException(), 1, ctx));
    }

    /**
     * Plain {@link IOException} is not in the non-retriable set; for an idempotent GET the stock
     * handler typically allows another attempt while under the retry count.
     */
    @Test
    void delegatesToParentForRetriableIoExceptionOnGet() {
        final HttpRequestRetryHandler handler = new HttpRequestRetryHandler(3, 0L);
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setAttribute(HttpCoreContext.HTTP_REQUEST, new HttpGet("http://example.com/"));

        assertTrue(handler.retryRequest(new IOException("transient"), 1, ctx));
    }

    @Test
    void returnsFalseAndRestoresInterruptWhenBackoffSleepInterrupted() {
        final HttpRequestRetryHandler handler = new HttpRequestRetryHandler(3, 50L);
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setAttribute(HttpCoreContext.HTTP_REQUEST, new HttpGet("http://example.com/"));

        Thread.currentThread().interrupt();

        assertFalse(handler.retryRequest(new IOException("fail"), 2, ctx));

        assertTrue(Thread.interrupted(), "interrupt flag must remain set for callers");
    }
}
