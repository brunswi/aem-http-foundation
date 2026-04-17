# HTTP Package (`org.kttn.aem.http`)

Reusable outbound HTTP plumbing for AEM OSGi: pooled Apache `CloseableHttpClient` instances, Granite trust-store integration, configurable retries, and optional Adobe IMS OAuth for interceptors.

## Architecture Overview

Outbound calls are keyed and cached: configuration flows from Metatype into `HttpConfig`, while `HttpClientProvider` builds (or reuses) a client per key. Adobe IMS token acquisition lives in `auth.aio` and is composed via `HttpClientBuilder` interceptors.

```text
┌─────────────────────────────────────────────────────────────┐
│  HttpClientProvider              HttpConfigService            │
└────────────┬───────────────────────────┬────────────────────┘
             │                           │
┌────────────▼────────────┐   ┌───────────▼─────────────────────┐
│ HttpClientProviderImpl  │   │ HttpConfigServiceImpl (OSGi)   │
│  · pool + SSL + retries │   │  · Metatype → HttpConfig       │
└────────────┬────────────┘   └───────────────┬─────────────────┘
             │                                │
             │                    ┌─────────────▼─────────────┐
             │                    │ HttpConfig (immutable)   │
             │                    └──────────────────────────┘
┌────────────▼────────────────────────────────────────────────┐
│  impl: keep-alive, HttpRequestRetryHandler,               │
│        ServiceUnavailableRetryStrategy, HttpClientProviderEntry │
└────────────┬────────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────────┐
│  auth.aio: OAuthTokenSupplier, AccessToken,                 │
│            OAuthTokenSupplierImpl (IMS client_credentials)   │
│  impl: AIOAuthInterceptor (x-api-key, org id, Bearer)      │
└─────────────────────────────────────────────────────────────┘
```

**Public interfaces**

- [HttpClientProvider](#httpclientprovider) — pooled clients by logical key
- [HttpConfigService](#httpconfigservice) — active `HttpConfig` from OSGi
- [OAuthTokenSupplier and AccessToken](#oauthtokensupplier-and-accesstoken) — IMS credentials and token material

**Data objects**

- [HttpConfig](#httpconfig) — timeouts, pool limits, proxy flag, retry settings (ms)

**Implementations**

- [HttpClientProviderImpl](#httpclientproviderimpl) — OSGi `HttpClientProvider`
- [HttpConfigServiceImpl](#httpconfigserviceimpl) — OSGi `HttpConfigService`
- [OAuthTokenSupplierImpl](#oauthtokensupplierimpl) — factory OSGi component for IMS tokens
- [AIOAuthInterceptor](#aioauthinterceptor) — adds IMS headers to outgoing requests

**Internal / supporting**

- [HttpClientProviderEntry](#httpclientproviderentry), [HttpRequestRetryHandler](#httprequestretryhandler), [ServiceUnavailableRetryStrategy](#serviceunavailableretrystrategy) — pooling lifecycle and retry policies

---

## Public interfaces

### HttpClientProvider

Factory for `CloseableHttpClient` instances. Implementations cache one client per non-null key; the first call for a key builds the pool and applies optional `HttpClientBuilder` mutations (for example interceptors). Passing `null` for `HttpConfig` uses the injected `HttpConfigService` defaults.

- **Source:** [`HttpClientProvider.java`](HttpClientProvider.java)

**Key methods:**

```java
CloseableHttpClient provideDefault()
CloseableHttpClient provide(String key)
CloseableHttpClient provide(String key, HttpConfig config)
CloseableHttpClient provide(String key, HttpConfig config, Consumer<HttpClientBuilder> builderMutator)
```

**Features:**

- Stable keys for per-integration pooling (for example `DEFAULT`, `IMSService`, `aio-oauth`)
- Optional per-client `HttpConfig` overrides without changing global OSGi settings
- `builderMutator` hook for `HttpRequestInterceptor` / SSL customizations

### HttpConfigService

Exposes the current `HttpConfig` built from OSGi Metatype after component activation.

- **Source:** [`HttpConfigService.java`](HttpConfigService.java)

**Key method:**

```java
HttpConfig getHttpConfig()
```

**Returns:**

- Non-null snapshot once the implementing component has activated

### OAuthTokenSupplier and AccessToken

IMS-oriented OAuth for Adobe APIs: org id, client id, and `client_credentials` token responses (`access_token`, `expires_in` in seconds).

- **Source:** [`auth/aio/OAuthTokenSupplier.java`](auth/aio/OAuthTokenSupplier.java)
- **Source:** [`auth/aio/AccessToken.java`](auth/aio/AccessToken.java)

**Key methods:**

```java
String getOrgId()
String getClientId()
AccessToken getAccessToken()
```

**Features:**

- Implementations may return sentinel tokens on failure; callers should validate before production use

---

## Public data objects

### HttpConfig

Immutable constructor-bound settings: all time fields are in **milliseconds**.

- **Source:** [`HttpConfig.java`](HttpConfig.java)

**Structure:**

```java
@RequiredArgsConstructor
public class HttpConfig {
    int connectionTimeout;               // ms — connect
    int connectionManagerTimeout;       // ms — lease from pool
    int socketTimeout;                  // ms — SO_TIMEOUT
    int maxConnection;
    int maxConnectionPerRoute;
    boolean useProxy;                   // fluent accessor: useProxy()
    int serviceUnavailableMaxRetryCount;
    int serviceUnavailableRetryInterval; // ms
    int ioExceptionMaxRetryCount;
    int ioExceptionRetryInterval;        // ms
}
```

---

## Public implementations

### HttpClientProviderImpl

OSGi `HttpClientProvider`: `PoolingHttpClientConnectionManager`, hybrid AEM + JVM trust manager (service user `truststore-reader`), I/O and 503 retry handlers, keep-alive strategy, `@Deactivate` shutdown.

- **Location:** [`impl/HttpClientProviderImpl.java`](impl/HttpClientProviderImpl.java)
- **Implements:** `HttpClientProvider`

**Key dependencies:**

- `HttpConfigService` — defaults when `provide(..., null, ...)` is used
- `ResourceResolverFactory` — service resource resolver for trust store access
- `KeyStoreService` — AEM Granite trust manager

**Responsibilities:**

- Cache `CloseableHttpClient` + `HttpClientConnectionManager` pairs per key
- Merge custom certs from AEM with the JVM default trust store for TLS

### HttpConfigServiceImpl

OSGi `HttpConfigService` with Metatype **name** `[HTTP] HTTP Client Configuration`. Override values per run mode with the usual `ui.config` `.cfg.json` patterns for this bundle’s configuration PID (see OSGi console for the exact persistent identifier).

- **Location:** [`impl/HttpConfigServiceImpl.java`](impl/HttpConfigServiceImpl.java)
- **Implements:** `HttpConfigService`

**Configuration:**

Callers may also build a custom `HttpConfig` in code and pass it into `HttpClientProvider.provide(key, config, mutator)` to override only selected fields (for example extended timeouts) while keeping pool and retry defaults from the service.

| Property | Default | Description |
|----------|--------:|-------------|
| `http_config_connectionTimeout` | 10000 | Connect timeout (ms) |
| `http_config_connectionManagerTimeout` | 10000 | Connection lease from pool (ms) |
| `http_config_socketTimeout` | 10000 | Socket timeout (ms) |
| `http_config_maxConnection` | 100 | Total pool size |
| `http_config_maxConnectionPerRoute` | 20 | Per-route cap |
| `http_config_useProxy` | false | Egress proxy flag (reserved for future routing) |
| `http_config_serviceUnavailableMaxRetryCount` | 3 | 503 retries (`0` = off) |
| `http_config_serviceUnavailableRetryInterval` | 1000 | Delay between 503 retries (ms) |
| `http_config_ioExceptionMaxRetryCount` | 3 | Retriable I/O retries (`0` = off) |
| `http_config_ioExceptionRetryInterval` | 1000 | Delay between I/O retries (ms) |

### OAuthTokenSupplierImpl

Factory OSGi component (`@Designate` factory) that POSTs `client_credentials` to Adobe IMS (`ims-na1.adobelogin.com`) and maps JSON to `AccessTokenImpl`. Uses `HttpClientProvider` with key `IMSService` for the token HTTP call.

- **Location:** [`auth/aio/impl/OAuthTokenSupplierImpl.java`](auth/aio/impl/OAuthTokenSupplierImpl.java)
- **Implements:** `OAuthTokenSupplier`

**Configuration:**

Metatype title matches `OAuthTokenSupplierImpl.OSGI_LABEL` (factory instances: org id, client id, secret, scopes).

### AIOAuthInterceptor

`HttpRequestInterceptor` that sets `x-api-key`, `x-gw-ims-org-id`, and `Authorization: Bearer …` from an `OAuthTokenSupplier`. Skips if `Authorization` is already set. Refreshes the cached token shortly before `expires_in` (seconds), using a configurable **seconds**-based leniency window.

- **Location:** [`impl/AIOAuthInterceptor.java`](impl/AIOAuthInterceptor.java)

**Key dependencies:**

- `OAuthTokenSupplier` — [`auth/aio/OAuthTokenSupplier.java`](auth/aio/OAuthTokenSupplier.java)

---

## Internal / supporting types

### HttpClientProviderEntry

Pairs `CloseableHttpClient` with `HttpClientConnectionManager` for ordered shutdown.

- **Location:** [`impl/HttpClientProviderEntry.java`](impl/HttpClientProviderEntry.java)

### HttpRequestRetryHandler

Extends Apache `DefaultHttpRequestRetryHandler` with optional delay between attempts and retriable `ConnectException`; several exception types remain non-retriable (see source).

- **Location:** [`impl/HttpRequestRetryHandler.java`](impl/HttpRequestRetryHandler.java)

### ServiceUnavailableRetryStrategy

Wraps `DefaultServiceUnavailableRetryStrategy` with logging and a fast path for HTTP 200.

- **Location:** [`impl/ServiceUnavailableRetryStrategy.java`](impl/ServiceUnavailableRetryStrategy.java)

---

## Usage examples

### Default HTTP client

```java
@Reference
private HttpClientProvider httpClientProvider;

public void executeRequest() throws IOException {
    try (CloseableHttpResponse response = httpClientProvider.provideDefault()
            .execute(new HttpGet("https://example.com/api"))) {
        // handle entity
    }
}
```

### HTTP client with IMS OAuth interceptor

```java
@Reference
private HttpClientProvider httpClientProvider;

@Reference
private OAuthTokenSupplier oAuthTokenSupplier;

public CloseableHttpClient getAuthenticatedClient() {
    return httpClientProvider.provide("aio-oauth", null, builder ->
        builder.addInterceptorFirst(new AIOAuthInterceptor(oAuthTokenSupplier))
    );
}
```

### Custom `HttpConfig` (extended timeouts)

```java
@Reference
private HttpConfigService httpConfigService;

@Reference
private HttpClientProvider httpClientProvider;

private HttpConfig extendedTimeouts() {
    HttpConfig defaults = httpConfigService.getHttpConfig();
    return new HttpConfig(
        60_000,
        60_000,
        60_000,
        defaults.getMaxConnection(),
        defaults.getMaxConnectionPerRoute(),
        defaults.useProxy(),
        defaults.getServiceUnavailableMaxRetryCount(),
        defaults.getServiceUnavailableRetryInterval(),
        defaults.getIoExceptionMaxRetryCount(),
        defaults.getIoExceptionRetryInterval()
    );
}

public CloseableHttpClient getExtendedTimeoutClient() {
    return httpClientProvider.provide("ext-timeout", extendedTimeouts(), null);
}
```

---

## Design decisions

### Hybrid trust manager

**Decision:** Validate server certificates against the AEM Granite trust material first, then fall back to the JVM default trust store.

**Rationale:**

- Custom anchors can be managed in AEM without rebuilding the bundle
- Public CAs still work when the AEM store does not include every issuer

### Key-based client cache

**Decision:** One pooled client per `HttpClientProvider.provide` key; configuration and interceptors are fixed at first use for that key.

**Rationale:**

- Predictable connection reuse and simpler lifecycle than per-request clients
- Callers isolate integrations with distinct keys (`DEFAULT`, `IMSService`, product-specific names)

### OAuth as interceptor + supplier

**Decision:** Keep `OAuthTokenSupplier` independent of Apache HTTP types; attach `AIOAuthInterceptor` only where IMS headers are required.

**Rationale:**

- Same token service can be reused in tests and non-HTTP contexts
- HTTP client setup stays in `HttpClientProvider` and `HttpClientBuilder`

---

## Maintenance notes

### Adding a new pooled client key

1. Choose a stable string (avoid user input as the key).
2. Call `provide(key, configOrNull, mutatorOrNull)` once per needed combination; reuse the same key everywhere for that integration.

### Changing timeouts or retries

1. Prefer OSGi `HttpConfigServiceImpl` Metatype for global defaults.
2. For a single integration, pass a custom `HttpConfig` only to that key’s `provide` call.

### IMS OAuth

1. Deploy a configured `OAuthTokenSupplierImpl` factory instance (org, client, secret, scopes).
2. Reference that service and pass it into `AIOAuthInterceptor` when building the client.

---

## Related tests

Tests for this package: [`../../../../../../test/java/org/kttn/aem/http/`](../../../../../../test/java/org/kttn/aem/http/)
