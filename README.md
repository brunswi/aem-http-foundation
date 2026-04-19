# AEM HTTP Foundation

**Drop-in HTTP for AEM:** shared [Apache HttpClient](https://hc.apache.org/) pools, OSGi-driven timeouts and retries, TLS that respects the **AEM trust store**, and an optional **Adobe IMS** pipeline for calling Adobe APIs from your bundles—without reinventing connection management or auth glue in every feature.

You still write normal [`HttpGet`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/methods/HttpGet.html) / [`HttpPost`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/methods/HttpPost.html) / [`URIBuilder`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/utils/URIBuilder.html) code; this library owns how the client is built, configured, and (when needed) authenticated.

---

## What you get on top of plain Apache HttpClient

In an AEM / AEMaaCS project, “just use HttpClient” still leaves you to implement pooling, lifecycle, retries, TLS trust for certs AEM admins install, and—when calling Adobe—IMS tokens and gateway headers. Teams usually reimplement that in every codebase.

This library gives you:

- Shared [`CloseableHttpClient`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/impl/client/CloseableHttpClient.html) instances keyed by name via [`HttpClientProvider`](core/src/main/java/org/kttn/aem/http/HttpClientProvider.java), so one pool per integration instead of ad hoc `new` clients.
- [`HttpConfigService`](core/src/main/java/org/kttn/aem/http/HttpConfigService.java) / [`HttpConfig`](core/src/main/java/org/kttn/aem/http/HttpConfig.java): connect and socket timeouts, pool limits, and retries on I/O failures and HTTP 503—driven from OSGi config, not hard-coded.
- TLS that validates server chains against the **AEM Granite keystore** and the JVM trust store ([`HttpClientProviderImpl`](core/src/main/java/org/kttn/aem/http/impl/HttpClientProviderImpl.java)).
- Optional Adobe IMS: [`OAuthTokenSupplier`](core/src/main/java/org/kttn/aem/http/auth/aio/OAuthTokenSupplier.java) for `client_credentials` tokens and [`AIOAuthInterceptor`](core/src/main/java/org/kttn/aem/http/impl/AIOAuthInterceptor.java) to add `Authorization`, `x-api-key`, and `x-gw-ims-org-id` (with refresh before expiry)—what Adobe I/O Runtime and similar gateways expect.
- No change to how you build requests: `HttpGet`, `HttpPost`, `URIBuilder`, etc. stay standard Apache.

**Modules:** `core` (bundle), `ui.config` (sample OSGi configs), `all` (container package that embeds them for install).

**Core bundle details:** for architecture, class-by-class notes, OSGi config keys, and longer examples, see **[`core/README.md`](core/README.md)**.

---

## Using it in your bundle

1. Add a Maven dependency on **`aem-http-foundation.core`** (same `groupId` / `version` as this reactor, or your published coordinates).
2. In AEM, deploy the core bundle (e.g. via the **`all`** package or your own container).
3. Inject **`HttpClientProvider`** (and optionally **`HttpConfigService`**, **`OAuthTokenSupplier`**) and build requests with the usual Apache HttpClient APIs.

---

## Examples

### 1. Pooled client for an integration

Use a **stable key** per outbound system so the first caller’s settings (including interceptors) define the pool for that key.

```java
@Reference
private HttpClientProvider httpClientProvider;

void callRemoteApi() throws IOException {
    CloseableHttpClient client = httpClientProvider.provide("payments-api");
    HttpGet get = new HttpGet("https://api.example.com/v1/status");
    try (CloseableHttpResponse response = client.execute(get)) {
        // handle response (do not close the shared client here)
    }
}
```

### 2. Client with Adobe IMS headers (e.g. Adobe I/O Runtime)

Register an interceptor that uses your [`OAuthTokenSupplier`](core/src/main/java/org/kttn/aem/http/auth/aio/OAuthTokenSupplier.java) when the client is **first** created for that key (typically from a `@Component` `activate` method).

```java
httpClientProvider.provide(
    "aio-campaign",
    httpConfigService.getHttpConfig(),
    builder -> builder.addInterceptorLast(new AIOAuthInterceptor(oAuthTokenSupplier)));
```

Then build the request URL and body in **your** code (for example [`URIBuilder`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/utils/URIBuilder.html) + [`HttpPost`](https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/methods/HttpPost.html) with a JSON entity) and `execute` with the same `provide("aio-campaign")` without passing the mutator again.

---

## License

Apache License, Version 2.0 