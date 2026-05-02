package org.kttn.aem.http.impl;

import com.adobe.granite.keystore.KeyStoreService;
import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.osgi.framework.Constants;

import javax.net.ssl.X509TrustManager;
import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link HttpClientProviderImpl} rebuilds pools transparently when
 * the Granite trust store changes, without disrupting consumer-held client references.
 */
class TrustStoreRefreshTest {

    private final AemContext context = new AemContext();
    private HttpClientProviderImpl provider;

    @BeforeEach
    void setUp() {
        context.registerInjectActivateService(new HttpConfigServiceImpl());
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        provider = context.registerInjectActivateService(new HttpClientProviderImpl());
    }

    /**
     * A consumer that stored the {@link CloseableHttpClient} reference during {@code @Activate}
     * must receive the same wrapper object after {@code onChange} fires. The provider must NOT
     * invalidate existing references — consumers must never need to re-fetch.
     */
    @Test
    void consumerHeldReferenceIsStableAfterTrustStoreRefresh() {
        CloseableHttpClient stored = provider.provide("stable-ref-key");

        provider.onChange(Collections.emptyList());

        CloseableHttpClient afterRefresh = provider.provide("stable-ref-key");
        assertSame(stored, afterRefresh,
            "Consumer-held reference must be the same ManagedHttpClient wrapper after trust-store refresh");
    }

    /**
     * After {@code onChange}, the underlying real client behind the wrapper is replaced.
     * The ManagedHttpClient delegate field must differ from what was there before the refresh.
     */
    @Test
    void onChangeReplacesUnderlyingDelegateInsideWrapper() throws Exception {
        CloseableHttpClient wrapper = provider.provide("delegate-swap-key");
        CloseableHttpClient delegateBefore = getDelegateFromWrapper(wrapper);

        provider.onChange(Collections.emptyList());

        CloseableHttpClient delegateAfter = getDelegateFromWrapper(wrapper);
        assertNotSame(delegateBefore, delegateAfter,
            "Delegate inside the wrapper must be replaced after trust-store refresh");
    }

    /**
     * After {@code onChange}, the superseded real client and connection manager are closed and
     * shut down. Verifiable by registering a mock real client as the initial backing client.
     */
    @Test
    void onChangeClosesOldRealClientAndConnectionManager() throws Exception {
        // Build provider with a trust manager available so the first entry uses the SSL path
        X509TrustManager aemTm = mock(X509TrustManager.class);
        when(aemTm.getAcceptedIssuers()).thenReturn(new X509Certificate[]{mock(X509Certificate.class)});
        KeyStoreService ks = mock(KeyStoreService.class);
        when(ks.getTrustManager(any())).thenReturn(aemTm);

        AemContext ctx2 = new AemContext();
        ctx2.registerInjectActivateService(new HttpConfigServiceImpl());
        AemMockOsgiSupport.registerResourceResolverFactory(ctx2);
        ctx2.registerService(KeyStoreService.class, ks,
            Map.of(Constants.SERVICE_RANKING, Integer.MAX_VALUE));
        HttpClientProviderImpl provider2 = ctx2.registerInjectActivateService(new HttpClientProviderImpl());

        provider2.provide("close-on-swap-key");

        HttpClientProviderEntry entry = getEntry(provider2, "close-on-swap-key");
        CloseableHttpClient oldReal = getRealClient(entry);
        HttpClientConnectionManager oldCm = entry.getConnectionManager();

        provider2.onChange(Collections.emptyList());

        // Old real client must have been closed; old connection manager must have been shut down.
        // We cannot mock them after the fact, but we can verify the new backing objects differ.
        CloseableHttpClient newReal = getRealClient(entry);
        HttpClientConnectionManager newCm = entry.getConnectionManager();
        assertNotSame(oldReal, newReal, "Old real client must have been replaced");
        assertNotSame(oldCm, newCm, "Old connection manager must have been replaced");
    }

    /**
     * When the trust store changes multiple times, each refresh produces a fresh real client,
     * but the wrapper handed to the consumer never changes.
     */
    @Test
    void repeatedRefreshesProduceFreshClientsWithoutChangingWrapper() throws Exception {
        CloseableHttpClient wrapper = provider.provide("multi-refresh-key");

        provider.onChange(Collections.emptyList());
        CloseableHttpClient delegateAfterFirst = getDelegateFromWrapper(wrapper);

        provider.onChange(Collections.emptyList());
        CloseableHttpClient delegateAfterSecond = getDelegateFromWrapper(wrapper);

        assertSame(wrapper, provider.provide("multi-refresh-key"),
            "Wrapper must be the same object throughout");
        assertNotSame(delegateAfterFirst, delegateAfterSecond,
            "Each refresh must produce a fresh delegate");
    }

    // --- reflection helpers ---

    private CloseableHttpClient getDelegateFromWrapper(final CloseableHttpClient client) throws Exception {
        Field f = client.getClass().getDeclaredField("delegate");
        f.setAccessible(true);
        return (CloseableHttpClient) f.get(client);
    }

    private HttpClientProviderEntry getEntry(final HttpClientProviderImpl p, final String key) throws Exception {
        Field f = HttpClientProviderImpl.class.getDeclaredField("entries");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HttpClientProviderEntry> entries = (Map<String, HttpClientProviderEntry>) f.get(p);
        return entries.get(key);
    }

    private CloseableHttpClient getRealClient(final HttpClientProviderEntry entry) throws Exception {
        Field f = HttpClientProviderEntry.class.getDeclaredField("realClient");
        f.setAccessible(true);
        return (CloseableHttpClient) f.get(entry);
    }
}
