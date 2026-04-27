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
@Component(service = {HttpClientProvider.class, InternalHttpClientProvider.class},
    property = {
        Constants.SERVICE_DESCRIPTION
            + "=Provides pooled Apache HttpClient instances with AEM trust store integration"
    })
public class HttpClientProviderImpl implements HttpClientProvider, InternalHttpClientProvider {

    private static final String SERVICE_USER = "truststore-reader";
    /**
     * Minimum keep-alive duration for pooled connections: at least 60 seconds, or longer if the
     * origin sends a larger {@code Keep-Alive: timeout} value (see {@link #keepAliveMillis}).
     */
    private static final ConnectionKeepAliveStrategy KEEP_ALIVE_STRATEGY =
        HttpClientProviderImpl::keepAliveMillis;

    /**
     * Per-component cache of pooled clients. Lifecycle is bound to the SCR component instance:
     * populated lazily by {@link #provideInternal} and fully released by {@link #cleanup} on
     * {@link Deactivate}. Not static — each component activation owns its own map.
     */
    private final ConcurrentMap<String, HttpClientProviderEntry> entries = new ConcurrentHashMap<>();

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
                try {
                    final long timeoutSeconds = Long.parseLong(value.trim());
                    if (timeoutSeconds > 0 && timeoutSeconds <= Long.MAX_VALUE / 1000) {
                        fromHeader = timeoutSeconds * 1000;
                    } else {
                        log.debug("Keep-Alive timeout value out of range: {}", timeoutSeconds);
                    }
                } catch (final NumberFormatException e) {
                    log.debug("Invalid Keep-Alive timeout value '{}', using default", value);
                }
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
     * Thread-safe: {@link ConcurrentHashMap#computeIfAbsent computeIfAbsent} ensures at most one
     * build per key; the first completing caller supplies {@code config} and {@code builderMutator}.
     */
    @Override
    public CloseableHttpClient provide(
        final String key,
        final HttpConfig config,
        final Consumer<HttpClientBuilder> builderMutator) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("HttpClientProvider key must not be null or blank.");
        }
        if (key.startsWith(HttpClientProvider.RESERVED_KEY_PREFIX)) {
            throw new IllegalArgumentException(
                "Cache keys starting with '" + HttpClientProvider.RESERVED_KEY_PREFIX
                    + "' are reserved for internal use by aem-http-foundation; choose a different key.");
        }
        return provideInternal(key, config, builderMutator);
    }

    /**
     * Internal entry point used by other components in this bundle (for example
     * {@code OAuthClientCredentialsTokenSupplier}) to obtain a pooled client under a reserved key
     * without triggering the public-API guard. Exposed via {@link InternalHttpClientProvider};
     * not part of the {@link HttpClientProvider} contract.
     */
    @Override
    public CloseableHttpClient provideInternal(
        final String key,
        final HttpConfig config,
        final Consumer<HttpClientBuilder> builderMutator) {
        final HttpClientProviderEntry entry = entries.computeIfAbsent(key, k -> {
            final HttpConfig httpConfig = (config != null) ? config : this.httpConfigService.getHttpConfig();
            if (httpConfig == null) {
                throw new IllegalStateException(
                    "HttpConfigService not yet activated; cannot provide HTTP client");
            }

            HttpClientConnectionManager connectionManager = null;
            try {
                connectionManager = createConnectionManager(httpConfig);
                final HttpClientBuilder httpClientBuilder = createHttpClientBuilder(connectionManager,
                    httpConfig);
                if (builderMutator != null) {
                    builderMutator.accept(httpClientBuilder);
                }

                httpClientBuilder.setKeepAliveStrategy(KEEP_ALIVE_STRATEGY);

                final CloseableHttpClient client = httpClientBuilder.build();
                return new HttpClientProviderEntry.HttpClientProviderEntryBuilder()
                    .httpClient(client)
                    .connectionManager(connectionManager)
                    .build();
            } catch (final Exception e) {
                if (connectionManager != null) {
                    connectionManager.shutdown();
                }
                throw e;
            }
        });
        return entry.getHttpClient();
    }

    /**
     * Shuts down every pooled connection manager and closes cached clients when the component
     * is deactivated (bundle stop or configuration removal).
     */
    @Deactivate
    private void cleanup() {
        entries.values().stream().filter(Objects::nonNull).forEach(this::closeEntry);
        entries.clear();
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
                log.error("Unable to initialize custom SSL context; falling back to JVM default trust store.", e);
            }
        }
        if (connectionManager == null) {
            connectionManager = new PoolingHttpClientConnectionManager();
        }
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
        } catch (final LoginException e) {
            log.info("Granite trust store integration unavailable: service user '{}' not configured. Falling back to JVM default trust store.", SERVICE_USER);
            log.debug("Service user login failed:", e);
        } catch (final KeyStoreNotInitialisedException e) {
            log.info("Granite trust store integration unavailable: trust store not initialized in AEM. Falling back to JVM default trust store.");
            log.debug("Trust store not initialized:", e);
        } catch (final NoSuchAlgorithmException | KeyStoreException e) {
            log.warn("Granite trust store integration failed due to cryptographic configuration issue. Falling back to JVM default trust store.", e);
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
