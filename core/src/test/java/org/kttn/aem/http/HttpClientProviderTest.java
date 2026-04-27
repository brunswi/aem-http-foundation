package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;
import org.kttn.aem.utilities.JsonSuccessHandler;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientProviderTest {

    @RegisterExtension
    static final HttpServerExtension httpServerExtension = new HttpServerExtension();
    private final AemContext context = new AemContext();
    private final HttpClientProvider httpClientProvider = new HttpClientProviderImpl();
    private final HttpConfigService httpConfigService = new HttpConfigServiceImpl();

    @BeforeEach
    protected void setUp() {
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        context.registerInjectActivateService(httpClientProvider);
    }

    @Test
    void provideDefaultExecutesGet() throws IOException, URISyntaxException {
        httpServerExtension.registerHandler("/test", new JsonSuccessHandler("{}"));
        final HttpGet httpGet = new HttpGet(httpServerExtension.getUriFor("/test"));
        CloseableHttpResponse response = httpClientProvider.provideDefault().execute(httpGet);
        String content = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
        assertEquals("{}", content);
    }

    @Test
    void provideRejectsNullBlankAndReservedKeys() {
        assertThrows(IllegalArgumentException.class, () -> httpClientProvider.provide(null));
        assertThrows(IllegalArgumentException.class, () -> httpClientProvider.provide(""));
        assertThrows(IllegalArgumentException.class, () -> httpClientProvider.provide("  "));
        assertThrows(IllegalArgumentException.class,
            () -> httpClientProvider.provide(HttpClientProvider.RESERVED_KEY_PREFIX + "anything"));
    }

    @Test
    void provideReturnsSameInstanceForSameKeyUnderConcurrency() throws Exception {
        final String key = "concurrent-same-key-" + System.nanoTime();
        final int threads = 32;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<CloseableHttpClient>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return httpClientProvider.provide(key);
                }));
            }
            start.countDown();
            CloseableHttpClient first = null;
            for (Future<CloseableHttpClient> future : futures) {
                final CloseableHttpClient client = future.get(30, TimeUnit.SECONDS);
                if (first == null) {
                    first = client;
                } else {
                    assertSame(first, client);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
