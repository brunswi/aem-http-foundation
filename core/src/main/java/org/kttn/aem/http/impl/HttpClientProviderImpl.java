package org.kttn.aem.http.impl;

import com.adobe.granite.keystore.KeyStoreNotInitialisedException;
import com.adobe.granite.keystore.KeyStoreService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfig;
import org.kttn.aem.http.HttpConfigService;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * OSGi {@link HttpClientProvider} that builds pooled {@link CloseableHttpClient} instances,
 * merges AEM Granite trust material with the JVM default trust store, and applies configurable
 * I/O and 503 retry policies.
 * <p>
 * Clients are cached by key: the first {@code provide} call for a key constructs the pool and
 * client; subsequent calls return the same instance until deactivation.
 * {@link HttpConfigService} supplies defaults when {@code config} is {@code null}.
 *
 * @see HttpConfigService
 * @see KeyStoreService
 */
@Slf4j
@Component(service = {HttpClientProvider.class},
    property = {
        Constants.SERVICE_DESCRIPTION
            + "=Provides pooled Apache HttpClient instances with AEM trust store integration"
    })
@SuppressWarnings("CQRules:ConnectionTimeoutMechanism")
public class HttpClientProviderImpl implements HttpClientProvider {

    private static final String SERVICE_USER = "truststore-reader";
    private static final ConcurrentMap<String, HttpClientProviderEntry> ENTRY_MAP = new ConcurrentHashMap<>();
    /**
     * Minimum keep-alive duration for pooled connections: at least 60 seconds, or longer if the
     * origin sends a larger {@code Keep-Alive: timeout} value (see {@link #keepAliveMillis}).
     */
    private static final ConnectionKeepAliveStrategy KEEP_ALIVE_STRATEGY =
        HttpClientProviderImpl::keepAliveMillis;

    @Reference
    private HttpConfigService httpConfigService;
    @Reference
    private ResourceResolverFactory resolverFactory;
    @Reference
    private KeyStoreService keyStoreService;

    /**
     * Derives keep-alive duration from the response {@code Connection} / keep-alive headers.
     * Parses {@code timeout} (seconds), converts to milliseconds, then returns
     * {@code max(parsed, 60_000)} so pooled connections are not torn down sooner than one minute
     * unless the server requests a longer idle time. If no timeout is present, returns 60 seconds.
     *
     * @param response the response that may carry keep-alive metadata
     * @param context  Apache execution context (unused)
     * @return keep-alive time in milliseconds
     */
    private static long keepAliveMillis(final HttpResponse response, final HttpContext context) {

        long fromHeader = 0L;
        final long maxValue = 60L * 1000L;
        final HeaderElementIterator it = new BasicHeaderElementIterator
            (response.headerIterator(HTTP.CONN_KEEP_ALIVE));

        while (it.hasNext()) {
            final HeaderElement he = it.nextElement();
            final String param = he.getName();
            final String value = he.getValue();
            if (value != null && param.equalsIgnoreCase
                ("timeout")) {
                fromHeader = Long.parseLong(value) * 1000;
                break;
            }
        }

        if (fromHeader == 0L) {
            return maxValue;
        }

        return Math.max(fromHeader, maxValue);
    }

    /**
     * Builds a composite {@link X509TrustManager}: server chains are validated against the AEM
     * keystore first, then the JVM default trust store if the former rejects the chain.
     * Client-certificate validation is delegated to the default manager only.
     *
     * @param defaultTrustManager JVM default trust manager (never null in valid JVM state)
     * @param aemTrustManager     trust manager from {@link KeyStoreService}
     * @return delegate trust manager for {@link SSLContext#init}
     */
    private static TrustManager getTrustManager(
        final X509TrustManager defaultTrustManager,
        final X509TrustManager aemTrustManager) {
        return new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return defaultTrustManager.getAcceptedIssuers();
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain,
                                           final String authType) throws CertificateException {
                try {
                    aemTrustManager.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    defaultTrustManager.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain,
                                           final String authType) throws CertificateException {
                defaultTrustManager.checkClientTrusted(chain, authType);
            }
        };
    }

    /**
     * Returns the first {@link X509TrustManager} from the factory, or {@code null} if none.
     *
     * @param tmf initialized {@link TrustManagerFactory}
     * @return first X.509 trust manager, or {@code null}
     */
    private static X509TrustManager getDefaultTrustManager(final TrustManagerFactory tmf) {
        X509TrustManager defaultTm = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                defaultTm = (X509TrustManager) tm;
                break;
            }
        }
        return defaultTm;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Thread-safe for concurrent use; first call per key wins the cache slot (no lock across keys).
     */
    @Override
    public CloseableHttpClient provide(
        final String key,
        final HttpConfig config,
        final Consumer<HttpClientBuilder> builderMutator) {
        if (ENTRY_MAP.containsKey(key)) {
            return ENTRY_MAP.get(key).getHttpClient();
        }

        final HttpConfig httpConfig = (config != null) ? config : this.httpConfigService.getHttpConfig();
        final HttpClientConnectionManager connectionManager = createConnectionManager(httpConfig);
        final HttpClientBuilder httpClientBuilder = createHttpClientBuilder(connectionManager,
            httpConfig);
        if (builderMutator != null) {
            builderMutator.accept(httpClientBuilder);
        }

        httpClientBuilder.setKeepAliveStrategy(KEEP_ALIVE_STRATEGY);

        final CloseableHttpClient client = httpClientBuilder.build();
        final HttpClientProviderEntry entry = new HttpClientProviderEntry.HttpClientProviderEntryBuilder().
            httpClient(client).
            connectionManager(connectionManager).build();

        ENTRY_MAP.put(key, entry);
        return entry.getHttpClient();
    }

    /**
     * Shuts down every pooled connection manager and closes cached clients when the component
     * is deactivated (bundle stop or configuration removal).
     */
    @Deactivate
    private void cleanup() {
        ENTRY_MAP.values().stream().filter(Objects::nonNull).forEach(this::closeEntry);
        ENTRY_MAP.clear();
    }

    /**
     * Shuts down the connection manager, then closes the HTTP client.
     *
     * @param entry pooled client entry; must not be null
     */
    private void closeEntry(final HttpClientProviderEntry entry) {
        try {
            entry.getConnectionManager().shutdown();
            entry.getHttpClient().close();
        } catch (IOException e) {
            log.error("Could not close HTTP client for pooled entry", e);
        }
    }

    /**
     * Creates a {@link PoolingHttpClientConnectionManager} with optional TLS trust merging AEM
     * and JVM defaults, then applies socket timeout and pool limits from {@code httpConfig}.
     *
     * @param httpConfig non-null connection settings
     * @return non-null connection manager
     */
    @NonNull
    public HttpClientConnectionManager createConnectionManager(
        @NonNull final HttpConfig httpConfig) {
        PoolingHttpClientConnectionManager connectionManager = null;
        final TrustManager trustManager = createTrustManager();
        if (trustManager != null) {
            try {
                final SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{trustManager}, null);

                // Do not disable hostname verification in production; resolving self-signed certs
                // belongs in the AEM trust store or a dedicated trust anchor, not NoopHostnameVerifier.
                final SSLConnectionSocketFactory sslConnectionFactory = new SSLConnectionSocketFactory(
                    sslContext,
                    SSLConnectionSocketFactory.getDefaultHostnameVerifier());
                final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("https", sslConnectionFactory)
                    .register("http", new PlainConnectionSocketFactory())
                    .build();
                connectionManager = new PoolingHttpClientConnectionManager(registry);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                log.error("unable to initialize custom SSL context", e);
            }
        } else {
            connectionManager = new PoolingHttpClientConnectionManager();
        }
        assert connectionManager != null;
        final SocketConfig socketConfig = SocketConfig.custom()
            .setSoTimeout(httpConfig.getSocketTimeout()).build();
        connectionManager.setDefaultSocketConfig(socketConfig);
        connectionManager.setMaxTotal(httpConfig.getMaxConnection());
        connectionManager.setDefaultMaxPerRoute(httpConfig.getMaxConnectionPerRoute());
        return connectionManager;
    }

    /**
     * Obtains a composite trust manager using the service user {@value #SERVICE_USER} to read
     * AEM-managed certificates, combined with the JVM default trust anchors.
     *
     * @return trust manager, or {@code null} if setup fails (caller falls back to plain pool)
     */
    private TrustManager createTrustManager() {
        TrustManager trustManager = null;
        try (ResourceResolver resourceResolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SERVICE_USER))) {
            final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);

            final X509TrustManager finalDefaultTm = getDefaultTrustManager(trustManagerFactory);
            final X509TrustManager finalAemTM = (X509TrustManager) keyStoreService.getTrustManager(
                resourceResolver);

            trustManager = getTrustManager(finalDefaultTm, finalAemTM);
        } catch (final NoSuchAlgorithmException | KeyStoreException | LoginException | KeyStoreNotInitialisedException e) {
            log.error(e.getMessage(), e);
        }
        return trustManager;
    }

    /**
     * Configures {@link RequestConfig} timeouts, {@link HttpRequestRetryHandler}, and
     * {@link ServiceUnavailableRetryStrategy} from {@code httpConfig}.
     *
     * @param connectionManager non-null pool for the client
     * @param httpConfig        non-null timeout and retry settings
     * @return non-null builder (not yet {@link HttpClientBuilder#build()})
     */
    @NonNull
    private HttpClientBuilder createHttpClientBuilder(
        @NonNull final HttpClientConnectionManager connectionManager,
        @NonNull final HttpConfig httpConfig) {
        final RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(httpConfig.getConnectionTimeout())
            .setConnectionRequestTimeout(httpConfig.getConnectionManagerTimeout())
            .setSocketTimeout(httpConfig.getSocketTimeout())
            .build();
        return HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .setRetryHandler(
                new HttpRequestRetryHandler(
                    httpConfig.getIoExceptionMaxRetryCount(),
                    httpConfig.getIoExceptionRetryInterval()))
            .setServiceUnavailableRetryStrategy(
                new ServiceUnavailableRetryStrategy(
                    httpConfig.getServiceUnavailableMaxRetryCount(),
                    httpConfig.getServiceUnavailableRetryInterval()));
    }
}
