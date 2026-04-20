# `org.kttn.aem.http` — Core Bundle

Technical reference for the `aem-http-foundation.core` OSGi bundle: architecture, public API, OSGi configuration, and usage examples.

For a project overview and quick-start guide, see the [root README](../README.md).

---

## Architecture

Outbound HTTP calls are keyed and pooled. `HttpClientProvider` creates or retrieves a `CloseableHttpClient` per logical key; `HttpConfigService` supplies timeout, pool, and retry settings from OSGi Metatype. Adobe IMS authentication is an optional composition: `OAuthTokenSupplierImpl` acquires tokens, and `AIOAuthInterceptor` injects them via the Apache `HttpClientBuilder` interceptor chain.

```text
┌───────────────────────────┐   ┌───────────────────────────────┐
│   HttpClientProvider      │   │      HttpConfigService        │
│   (public interface)      │   │      (public interface)       │
└────────────┬──────────────┘   └──────────────┬────────────────┘
             │                                 │
┌────────────▼──────────────┐   ┌──────────────▼────────────────┐
│  HttpClientProviderImpl   │───│   HttpConfigServiceImpl       │
│  · PoolingHttpClient...   │   │   (OSGi Metatype → config)    │
│  · Granite + JVM TLS      │   └───────────────────────────────┘
│  · per-key client cache   │
│  · retry handlers         │
└────────────┬──────────────┘
             │ optional composition
┌────────────▼──────────────────────────────────────────────────┐
│  AIOAuthInterceptor  (HttpRequestInterceptor)                 │
│  · injects x-api-key, x-gw-ims-org-id, Authorization: Bearer  │
│  · caches token; refreshes 5 min before expiry                │
└────────────┬──────────────────────────────────────────────────┘
             │
┌────────────▼──────────────────────────────────────────────────┐
│  OAuthTokenSupplierImpl  (OSGi factory)                       │
│  · POSTs client_credentials to Adobe IMS                      │
│  · returns AccessToken (access_token + expires_in seconds)    │
└───────────────────────────────────────────────────────────────┘
```

**Supporting internals** (not part of the public API):

- `HttpClientProviderEntry` — pairs a `CloseableHttpClient` with its `HttpClientConnectionManager` for ordered shutdown.
- `HttpRequestRetryHandler` — extends `DefaultHttpRequestRetryHandler` with configurable delay and `ConnectException` retries; `BearerTokenUnavailableException` is explicitly non-retriable.
- `ServiceUnavailableRetryStrategy` — wraps `DefaultServiceUnavailableRetryStrategy` with logging and a fast path for HTTP 200.

---

## Public interfaces

### `HttpClientProvider`

Factory and cache for `CloseableHttpClient` instances. The first call for a given key builds a pooled client and applies any `HttpClientBuilder` mutations (e.g. interceptors); subsequent calls for the same key return the existing client unchanged.

**Source:** [`HttpClientProvider.java`](src/main/java/org/kttn/aem/http/HttpClientProvider.java)

```java
CloseableHttpClient provideDefault()
CloseableHttpClient provide(String key)
CloseableHttpClient provide(String key, HttpConfig config)
CloseableHttpClient provide(String key, HttpConfig config, Consumer<HttpClientBuilder> builderMutator)
```

- `provideDefault()` is equivalent to `provide("DEFAULT")`.
- Passing `null` for `config` falls back to the active `HttpConfigService` defaults.
- Passing `null` for `builderMutator` skips the builder customization step.
- The `builderMutator` is only invoked when the key is first registered. To attach an interceptor, call `provide(key, config, mutator)` once during component activation, then use `provide(key)` for all subsequent requests.

---

### `HttpConfigService`

Exposes the current `HttpConfig` snapshot built from OSGi Metatype after component activation.

**Source:** [`HttpConfigService.java`](src/main/java/org/kttn/aem/http/HttpConfigService.java)

```java
HttpConfig getHttpConfig()
```

Returns a non-null `HttpConfig` once the component has activated.

---

### `OAuthTokenSupplier` and `AccessToken`

Abstractions for Adobe IMS OAuth credentials. `OAuthTokenSupplier` provides the org ID, client ID, and a freshly acquired `AccessToken`; `AccessToken` carries the bearer string and its `expires_in` duration in **seconds** (per the OAuth 2.0 specification).

**Sources:**
- [`auth/aio/OAuthTokenSupplier.java`](src/main/java/org/kttn/aem/http/auth/aio/OAuthTokenSupplier.java)
- [`auth/aio/AccessToken.java`](src/main/java/org/kttn/aem/http/auth/aio/AccessToken.java)

```java
String      getOrgId()
String      getClientId()
AccessToken getAccessToken()
```

> **On IMS failure:** `OAuthTokenSupplierImpl` returns a placeholder token (constant `OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN`, `expires_in = 0`). `AIOAuthInterceptor` treats this as an unusable bearer and throws `BearerTokenUnavailableException` — the request is not sent. Callers consuming `getAccessToken()` directly should also check for the placeholder value before use.

---

## Public data objects

### `HttpConfig`

Immutable value object holding timeout, pool, and retry settings. All time fields are in **milliseconds** unless noted otherwise.

**Source:** [`HttpConfig.java`](src/main/java/org/kttn/aem/http/HttpConfig.java)

```java
@Builder(toBuilder = true)
public class HttpConfig {
    int connectionTimeout;                // TCP connect timeout (ms)
    int connectionManagerTimeout;         // Max wait to lease a connection from the pool (ms)
    int socketTimeout;                    // Socket read timeout (ms); 0 = no timeout
    int maxConnection;                    // Total pool size
    int maxConnectionPerRoute;            // Per-route connection cap
    int serviceUnavailableMaxRetryCount;  // Retries after HTTP 503 (0 = disabled)
    int serviceUnavailableRetryInterval;  // Delay between 503 retries (ms)
    int ioExceptionMaxRetryCount;         // Retries after retriable IOException (0 = disabled)
    int ioExceptionRetryInterval;         // Delay between I/O retries (ms)
}
```

Use `toBuilder()` to create a modified copy from an existing instance without touching unrelated fields:

```java
HttpConfig extended = httpConfigService.getHttpConfig().toBuilder()
    .socketTimeout(120_000)
    .build();
```

---

## Implementations

### `HttpClientProviderImpl`

OSGi implementation of `HttpClientProvider`. Maintains a key-indexed map of `HttpClientProviderEntry` objects (client + connection manager pairs). On deactivation (`@Deactivate`), all clients and connection managers are closed in order.

**Source:** [`impl/HttpClientProviderImpl.java`](src/main/java/org/kttn/aem/http/impl/HttpClientProviderImpl.java)

**OSGi dependencies:**

| Service | Role |
|---|---|
| `HttpConfigService` | Default configuration when `config` is `null` in `provide(...)`. |
| `ResourceResolverFactory` | Opens a service resource resolver under the `truststore-reader` service user for Granite key store access. |
| `KeyStoreService` | Provides the AEM Granite trust material for TLS validation. |

**TLS strategy:** Combines the AEM Granite trust store and the JVM default trust store into a single `X509TrustManager`. AEM-managed certificates are checked first; public CAs still work when absent from the Granite store.

---

### `HttpConfigServiceImpl`

OSGi implementation of `HttpConfigService`. Single (non-factory) configuration instance.

**Source:** [`impl/HttpConfigServiceImpl.java`](src/main/java/org/kttn/aem/http/impl/HttpConfigServiceImpl.java)

**OSGi configuration PID:** `org.kttn.aem.http.impl.HttpConfigServiceImpl`

Configure per environment using a `.cfg.json` file in `ui.config`:

```
ui.config/src/main/content/jcr_root/apps/<app>/osgiconfig/config/
    org.kttn.aem.http.impl.HttpConfigServiceImpl.cfg.json
```

| Property | Default | Description |
|---|---:|---|
| `http_config_connectionTimeout` | `10000` | TCP connect timeout (ms) |
| `http_config_connectionManagerTimeout` | `10000` | Max wait to lease a connection from the pool (ms) |
| `http_config_socketTimeout` | `10000` | Socket read timeout (ms); `0` = no timeout |
| `http_config_maxConnection` | `100` | Total pool size |
| `http_config_maxConnectionPerRoute` | `20` | Per-route connection cap |
| `http_config_serviceUnavailableMaxRetryCount` | `3` | Retries after HTTP 503; `0` = disabled |
| `http_config_serviceUnavailableRetryInterval` | `1000` | Delay between 503 retries (ms) |
| `http_config_ioExceptionMaxRetryCount` | `3` | Retries after retriable `IOException`; `0` = disabled |
| `http_config_ioExceptionRetryInterval` | `1000` | Delay between I/O retries (ms) |

Per-integration overrides (without affecting global defaults) can be passed directly to `HttpClientProvider.provide(key, customConfig, mutator)`.

---

### `OAuthTokenSupplierImpl`

OSGi factory component implementing `OAuthTokenSupplier`. Each factory instance represents one Adobe Developer Console credential set and POSTs `client_credentials` to the Adobe IMS token endpoint (`https://ims-na1.adobelogin.com/ims/token/v3`). The HTTP call uses `HttpClientProvider` with the reserved key `IMSService`.

**Source:** [`auth/aio/impl/OAuthTokenSupplierImpl.java`](src/main/java/org/kttn/aem/http/auth/aio/impl/OAuthTokenSupplierImpl.java)

**OSGi configuration PID:** `org.kttn.aem.http.auth.aio.impl.OAuthTokenSupplierImpl~<name>`

Because this is a factory component, each `.cfg.json` filename must include a unique suffix (e.g. `…~campaign.cfg.json`) to produce a separate service instance.

| Property | Description |
|---|---|
| `orgId` | Adobe IMS organization ID |
| `clientId` | Adobe Developer Console OAuth client ID |
| `clientSecret` | Adobe Developer Console OAuth client secret |
| `scopes` | Comma-separated OAuth scopes for the IMS token request |

---

### `AIOAuthInterceptor`

`HttpRequestInterceptor` that injects Adobe IMS headers into outbound requests. Attach it to a named client pool once at activation via `HttpClientProvider.provide(key, config, builder -> builder.addInterceptorLast(...))`.

**Source:** [`impl/AIOAuthInterceptor.java`](src/main/java/org/kttn/aem/http/impl/AIOAuthInterceptor.java)

**Headers set:**

| Header | Value |
|---|---|
| `Authorization` | `Bearer <access_token>` |
| `x-api-key` | `OAuthTokenSupplier.getClientId()` |
| `x-gw-ims-org-id` | `OAuthTokenSupplier.getOrgId()` |

**Behaviour:**

- Skips entirely if `Authorization` is already present on the request.
- Caches the active `AccessToken` and proactively refreshes it **5 minutes** before `expires_in` elapses (refresh is synchronized to prevent concurrent stampedes).
- If no valid token is available after a refresh attempt, throws `BearerTokenUnavailableException`. This exception is registered as non-retriable in `HttpRequestRetryHandler` — the request fails immediately rather than being retried.

---

## Usage examples

### Basic pooled client

```java
@Reference
private HttpClientProvider httpClientProvider;

public void fetchStatus() throws IOException {
    CloseableHttpClient client = httpClientProvider.provide("payments-api");
    try (CloseableHttpResponse response = client.execute(new HttpGet("https://api.example.com/status"))) {
        // handle response — do not close the shared client
    }
}
```

### Client with Adobe IMS authentication

Register the interceptor once during component activation, then reuse the named pool on every call:

```java
@Component(service = MyService.class, immediate = true)
public class MyServiceImpl implements MyService {

    @Reference private HttpClientProvider httpClientProvider;
    @Reference private OAuthTokenSupplier oAuthTokenSupplier;
    @Reference private HttpConfigService httpConfigService;

    @Activate
    void activate() {
        // First call for "aio-campaign": builds pool and attaches interceptor.
        httpClientProvider.provide(
            "aio-campaign",
            httpConfigService.getHttpConfig(),
            builder -> builder.addInterceptorLast(new AIOAuthInterceptor(oAuthTokenSupplier))
        );
    }

    public void callCampaign() throws IOException {
        // Returns the existing pool — builderMutator is ignored on subsequent calls.
        CloseableHttpClient client = httpClientProvider.provide("aio-campaign");
        HttpPost post = new HttpPost("https://mc.adobe.io/your-tenant/campaign/...");
        post.setEntity(new StringEntity("{}", ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = client.execute(post)) {
            // handle response
        }
    }
}
```

### Custom `HttpConfig` for a single integration

Use `toBuilder()` to derive a copy from the current service defaults and override only the fields you need:

```java
@Activate
void activate() {
    HttpConfig longTimeouts = httpConfigService.getHttpConfig().toBuilder()
        .connectionTimeout(60_000)
        .connectionManagerTimeout(60_000)
        .socketTimeout(120_000)   // longer for slow export APIs
        .build();
    httpClientProvider.provide("slow-export-api", longTimeouts, null);
}
```

---

## Design decisions

### Hybrid trust manager

Custom certificates installed in the AEM Granite key store take precedence; the JVM default trust store is the fallback. AEM administrators can manage CA certificates without rebuilding or redeploying the bundle.

### Key-based client cache

One pooled client per key; configuration and interceptors are fixed at the time the key is first registered. This provides predictable connection reuse and clear lifecycle ownership. Integrations are isolated by distinct, stable keys (e.g. `DEFAULT`, `IMSService`, `aio-campaign`).

Keys must be application-defined constants. Do not derive them from user input or request data.

### OAuth as interceptor and supplier

`OAuthTokenSupplier` has no dependency on Apache HTTP types; `AIOAuthInterceptor` is the only component that connects the two. This separation allows the token service to be tested and reused independently of HTTP client setup.

---

## Maintenance notes

### Adding a new pooled integration

1. Define a stable, application-scoped key constant.
2. Call `provide(key, configOrNull, mutatorOrNull)` once on component activation to register the pool.
3. Call `provide(key)` for all subsequent requests within that component.

### Changing timeouts or retries globally

Update the `HttpConfigServiceImpl` OSGi configuration in `ui.config`. Changes take effect on the next component activation cycle.

### Changing timeouts for a single integration only

Pass a custom `HttpConfig` to `provide(key, config, mutator)`. Other integrations sharing the same `HttpConfigService` defaults are not affected.

### Adding a new Adobe IMS credential set

1. Create a new factory configuration file for `OAuthTokenSupplierImpl` with a unique name suffix.
2. Set `orgId`, `clientId`, `clientSecret`, and `scopes` for the target Adobe Developer Console project.
3. Reference the resulting `OAuthTokenSupplier` service (filtered by factory PID if multiple suppliers are deployed) and pass it into a new `AIOAuthInterceptor` at client build time.

---

## Tests

[`src/test/java/org/kttn/aem/http/`](src/test/java/org/kttn/aem/http/)
