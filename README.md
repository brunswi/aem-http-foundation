# AEM HTTP Foundation

Shared outbound HTTP infrastructure for AEM OSGi bundles. Instead of each feature team building its own connection pooling, timeout configuration, TLS wiring, and Adobe IMS authentication from scratch, this library provides them once — letting you focus on the requests that matter to your integration.

## Why

Every AEM project that calls external APIs faces the same problems:

- Apache `HttpClient` needs a pooled, lifecycle-managed instance — raw `new DefaultHttpClient()` is not production-ready.
- Timeouts and retry policies should be externalized to OSGi configuration, not hard-coded.
- TLS must honor certificates AEM administrators install in the Granite trust store, not just the JVM defaults.
- Calling Adobe APIs (Campaign, Target, AEM Assets, etc.) requires IMS `client_credentials` tokens plus `x-api-key` and `x-gw-ims-org-id` headers on every request.

Solving these correctly takes days. This library solves them once.

## What you get

| Capability | Details |
|---|---|
| **Pooled HTTP clients** | `HttpClientProvider` returns shared `CloseableHttpClient` instances keyed by a logical name — one pool per integration, managed lifecycle, no ad-hoc `new` clients. |
| **Externalised configuration** | Timeouts, pool sizes, and retry counts come from OSGi Metatype (`HttpConfigServiceImpl`) and can be overridden per run mode. |
| **AEM-aware TLS** | Server certificates are validated against the AEM Granite trust store first, then the JVM default. Custom CA certs stay in AEM config, not in the bundle. |
| **Adobe IMS authentication** | `OAuthTokenSupplierImpl` acquires `client_credentials` tokens from Adobe IMS; `AIOAuthInterceptor` injects `Authorization`, `x-api-key`, and `x-gw-ims-org-id` transparently, with automatic token refresh. |
| **Standard Apache API** | `HttpGet`, `HttpPost`, `URIBuilder` — your request code stays unchanged. |

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `core` | `aem-http-foundation.core` | OSGi bundle containing all interfaces, implementations, and auth support. |
| `ui.config` | `aem-http-foundation.ui.config` | Sample OSGi run-mode configuration files for the core bundle. |
| `all` | `aem-http-foundation.all` | Content package that embeds `core` and `ui.config` for deployment via Cloud Manager or CRX Package Manager. |

## Quick start

### 1. Add the Maven dependency

```xml
<dependency>
    <groupId>org.kttn.aem</groupId>
    <artifactId>aem-http-foundation.core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 2. Deploy the bundle

Install the `all` content package, or embed `aem-http-foundation.core` in your own container package.

### 3. Inject and call

```java
@Reference
private HttpClientProvider httpClientProvider;

void fetchStatus() throws IOException {
    CloseableHttpClient client = httpClientProvider.provide("my-api");
    try (CloseableHttpResponse response = client.execute(new HttpGet("https://api.example.com/status"))) {
        // handle response — do not close the shared client
    }
}
```

### 4. With Adobe IMS authentication

Register the interceptor once on component activation; the named pool reuses it on every subsequent call.

```java
@Activate
void activate() {
    httpClientProvider.provide(
        "aio-campaign",
        httpConfigService.getHttpConfig(),
        builder -> builder.addInterceptorLast(new AIOAuthInterceptor(oAuthTokenSupplier))
    );
}

void callCampaign() throws IOException {
    // Returns the existing pool — interceptor is already attached.
    CloseableHttpClient client = httpClientProvider.provide("aio-campaign");
    // ... build and execute your request
}
```

## Technical reference

For architecture, class-by-class documentation, OSGi configuration properties, and further examples, see **[`core/README.md`](core/README.md)**.

## License

Apache License, Version 2.0
