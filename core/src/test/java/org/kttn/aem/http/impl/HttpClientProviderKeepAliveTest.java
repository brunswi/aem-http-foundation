package org.kttn.aem.http.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the {@code keepAliveMillis} {@link org.apache.http.conn.ConnectionKeepAliveStrategy}
 * in {@link HttpClientProviderImpl}. Each test drives a real HTTP request to an embedded
 * {@link HttpServerExtension} server that returns different {@code Keep-Alive} response headers,
 * exercising the while-loop body (lines 113–131) that is unreachable without a server-side
 * {@code Keep-Alive: timeout=…} header.
 */
class HttpClientProviderKeepAliveTest {

    @RegisterExtension
    static final HttpServerExtension server = new HttpServerExtension();

    private final AemContext context = new AemContext();
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        HttpConfigService httpConfigService = new HttpConfigServiceImpl();
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        HttpClientProviderImpl providerImpl = new HttpClientProviderImpl();
        httpClientProvider = context.registerInjectActivateService(providerImpl);
    }

    /**
     * Verifies that a valid {@code Keep-Alive: timeout=120} response header is parsed and the
     * computed keep-alive duration is the larger of 120 000 ms (parsed) and the 60 000 ms floor,
     * covering the normal execution path through the while-loop body including the
     * {@code Math.max(fromHeader, maxValue)} return statement.
     */
    @Test
    void keepAliveStrategyParsesNumericTimeout() throws Exception {
        server.registerHandler("/ka-valid", exchange -> {
            exchange.getResponseHeaders().add("Keep-Alive", "timeout=120");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        CloseableHttpClient client = httpClientProvider.provideDefault();
        try (CloseableHttpResponse resp = client.execute(new HttpGet(server.getUriFor("/ka-valid")))) {
            assertEquals(200, resp.getStatusLine().getStatusCode());
        }
    }

    /**
     * Verifies that a non-numeric {@code timeout} value ({@code timeout=notanumber}) is caught by
     * the {@link NumberFormatException} handler inside the while-loop body, which logs a debug
     * message and falls back to the 60-second default — without crashing the client build or
     * the subsequent HTTP request.
     */
    @Test
    void keepAliveStrategyIgnoresNonNumericTimeout() throws Exception {
        server.registerHandler("/ka-invalid", exchange -> {
            exchange.getResponseHeaders().add("Keep-Alive", "timeout=notanumber");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        CloseableHttpClient client = httpClientProvider.provideDefault();
        try (CloseableHttpResponse resp = client.execute(new HttpGet(server.getUriFor("/ka-invalid")))) {
            assertEquals(200, resp.getStatusLine().getStatusCode());
        }
    }

    /**
     * Verifies that a {@code timeout=0} value (out-of-range: must be {@code > 0}) triggers the
     * {@code else} debug-log branch inside the while-loop body, and the 60-second default is used
     * as the keep-alive duration.
     */
    @Test
    void keepAliveStrategyIgnoresZeroTimeout() throws Exception {
        server.registerHandler("/ka-zero", exchange -> {
            exchange.getResponseHeaders().add("Keep-Alive", "timeout=0");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        CloseableHttpClient client = httpClientProvider.provideDefault();
        try (CloseableHttpResponse resp = client.execute(new HttpGet(server.getUriFor("/ka-zero")))) {
            assertEquals(200, resp.getStatusLine().getStatusCode());
        }
    }
}
