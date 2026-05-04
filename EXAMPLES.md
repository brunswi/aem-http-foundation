# Usage Examples

Use this page when you want to get from zero to a working outbound HTTP call quickly.

For API reference and OSGi configuration details, see [Technical Reference](core/REFERENCE.md).

> **Important:** do not call `close()` on `CloseableHttpClient` instances returned by `HttpClientProvider`. The foundation manages the client lifecycle and shuts down pools on bundle deactivation. Only close the `CloseableHttpResponse` returned by `execute()`.

---

## About 90 seconds to first use

The most common path is the simplest one:

- inject `HttpClientProvider`
- get a pooled client and execute requests with it
- add auth only if the target API needs it

Start with **Example 1** unless you already know you need OAuth or Adobe headers.

---

## Example index

| Example                                                                                                                            | Level            | When to use it                                                             |
|------------------------------------------------------------------------------------------------------------------------------------|------------------|----------------------------------------------------------------------------|
| [Example 1 — Public REST API](#example-1-public-rest-api-basic)                                                                    | **Basic**        | Plain pooled outbound HTTP                                                 |
| [Example 2 — Adobe integration](#example-2-adobe-integration-recommended)                                                          | **Recommended**  | Standard Adobe server-to-server path                                       |
| [Example 3 — Custom Basic Auth](#example-3-custom-basic-auth-intermediate)                                                         | **Intermediate** | Non-OAuth endpoint using Basic auth                                        |
| [Example 4 — Custom timeouts per integration](#example-4-custom-timeouts-per-integration-intermediate)                             | **Intermediate** | Per-integration HTTP override                                              |
| [Example 5 — Shared credentials across multiple integrations](#example-5-shared-credentials-across-multiple-integrations-advanced) | **Advanced**     | Multiple Adobe integrations intentionally sharing one OAuth credential set |

---

## Example 1: Public REST API (Basic)

**Use this when:** you want a plain pooled HTTP client with no special authentication.

This is also the recommended **first smoke test** after deployment.

```java
@Component(service = PublicApiService.class)
public class PublicApiServiceImpl implements PublicApiService {

    @Reference
    private HttpClientProvider httpClientProvider;

    private CloseableHttpClient httpClient;

    @Activate
    void activate() {
        httpClient = httpClientProvider.provide("public-api");
    }

    @Override
    public String fetch() throws IOException {
        HttpGet request =
            new HttpGet("https://petstore.swagger.io/v2/pet/findByStatus?status=available");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
```

### What this demonstrates

- the string key identifies the integration context; the foundation caches and reuses the pooled client for the same key
- a simple outbound call works without additional auth wiring

---

## Example 2: Adobe integration (Recommended)

**Use this when:** you want the default path for an Adobe server-to-server integration.

This applies to common Adobe server-to-server patterns such as:

- I/O Runtime invocations
- Experience Platform requests
- other Adobe APIs using IMS / OAuth `client_credentials` plus optional Adobe gateway headers

### Step 1: Configure the integration

Create:

```text
/apps/myapp/config/org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aio-runtime-prod.cfg.json
```

```json
{
  "clientId": "YOUR_CLIENT_ID",
  "clientSecret": "$[secret:aio_runtime_client_secret]",
  "scopes": "openid,AdobeID,read_organizations",
  "set.api.key.header": true,
  "org.id.header.value": "YOUR_ORG_ID@AdobeOrg"
}
```

Use the factory configuration name to identify the integration context. A recommended naming pattern is:

```text
<product>-<capability>-<environment>
```

Example:

```text
aio-runtime-prod
```

### Step 2: Inject and use

`AdobeIntegrationConfiguration` is registered as both:

- an `HttpClientCustomizer`
- an `AccessTokenSupplier`

The example below injects the customizer and attaches it to the pooled client.

```java
@Slf4j
@Component(service = AdobeIoRuntimeService.class)
public class AdobeIoRuntimeServiceImpl implements AdobeIoRuntimeService {

    private static final String RUNTIME_INVOKE =
        "https://<namespace>.adobeioruntime.net/api/v1/web/...";

    @Reference
    private HttpClientProvider httpClientProvider;

    @Reference(
        target = "(service.pid=org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aio-runtime-prod)"
    )
    private HttpClientCustomizer adobeCustomizer;

    private CloseableHttpClient httpClient;

    @Activate
    void activate() {
        httpClient = httpClientProvider.provide("aio-runtime-prod", adobeCustomizer::customize);
    }

    @Override
    public String callRuntime() throws IOException {
        HttpGet request = new HttpGet(RUNTIME_INVOKE);

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
```

### What this demonstrates

- bearer token acquired, cached, and injected on each request
- optional `x-api-key` and `x-gw-ims-org-id` headers applied when configured
- pooled outbound client reuse
- one integration context owns the credential and header policy

> If you only need bearer auth and do not want Adobe headers, use the generic OAuth supplier path instead.

---

## Example 3: Custom Basic Auth (Intermediate)

**Use this when:** you call a non-OAuth endpoint that uses Basic authentication.

This keeps Basic auth as a small request concern while still using the foundation for:

- pooled client reuse
- shared timeout and retry behavior
- lifecycle management

### Step 1: Implement a request interceptor

```java
public final class BasicAuthInterceptor implements HttpRequestInterceptor {

    private final String authorizationValue;

    public BasicAuthInterceptor(String username, String password) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8)
        );
        this.authorizationValue = "Basic " + encoded;
    }

    @Override
    public void process(HttpRequest request, HttpContext context) {
        if (!request.containsHeader(HttpHeaders.AUTHORIZATION)) {
            request.setHeader(HttpHeaders.AUTHORIZATION, authorizationValue);
        }
    }
}
```

### Step 2: Use it with a pooled client

```java
@Component(service = ProtectedApiService.class)
public class ProtectedApiServiceImpl implements ProtectedApiService {

    private static final String RESOURCE_URL =
        "https://api.example.com/protected/resource";

    @Reference
    private HttpClientProvider httpClientProvider;

    @Reference
    private HttpConfigService httpConfigService;

    private CloseableHttpClient httpClient;

    @Activate
    void activate() {
        String username = "api-user";
        String password = getPasswordFromOSGiConfig();

        httpClient = httpClientProvider.provide(
            "protected-api",
            httpConfigService.getHttpConfig(),
            builder -> builder.addInterceptorLast(new BasicAuthInterceptor(username, password))
        );
    }

    @Override
    public String callProtectedEndpoint() throws IOException {
        HttpGet request = new HttpGet(RESOURCE_URL);

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    private String getPasswordFromOSGiConfig() {
        return "resolve from OSGi config / secret placeholder";
    }
}
```

> **Tip:** resolve credentials from OSGi configuration or secret placeholders, not hard-coded values.

---

## Example 4: Custom timeouts per integration (Intermediate)

**Use this when:** one integration needs different timeouts than the shared defaults.

```java
@Component(service = SlowExportService.class)
public class SlowExportServiceImpl implements SlowExportService {

    @Reference
    private HttpClientProvider httpClientProvider;

    @Reference
    private HttpConfigService httpConfigService;

    private CloseableHttpClient httpClient;

    @Activate
    void activate() {
        HttpConfig customConfig = httpConfigService.getHttpConfig().toBuilder()
            .socketTimeout(300_000)
            .connectionTimeout(30_000)
            .build();

        httpClient = httpClientProvider.provide("slow-export", customConfig);
    }

    @Override
    public String export() throws IOException {
        HttpGet request = new HttpGet("https://api.example.com/export");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
```

### What this demonstrates

You can keep one shared HTTP default while still giving a specific integration its own timeout profile when needed.

---

## Example 5: Shared credentials across multiple integrations (Advanced)

**Use this when:** multiple Adobe integrations intentionally reuse the same OAuth credential set, but still need separate integration contexts.

> **Most teams can skip this example.**  
> The recommended default is still a single Adobe integration configuration with inline credentials.

Use shared credentials when reuse is intentional, for example:

- one Adobe Developer Console credential is already the approved integration anchor
- multiple integrations belong to the same ownership boundary
- you want one place for secret rotation, but separate integration-level request policies

### Step 1: Define shared credentials

Create a shared OAuth supplier configuration:

```text
/apps/myapp/config/org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier~shared-aep-prod.cfg.json
```

```json
{
  "credential.id": "shared-aep-prod",
  "tokenEndpointUrl": "https://ims-na1.adobelogin.com/ims/token/v3",
  "clientId": "YOUR_CLIENT_ID",
  "clientSecret": "$[secret:shared_aep_client_secret]",
  "scopes": "openid,AdobeID,read_organizations"
}
```

This configuration is responsible only for:

- token acquisition
- token caching
- token refresh before expiry

It does **not** define Adobe header behavior for a concrete target API.

### Step 2: Define two separate Adobe integration contexts

#### Integration A: AEP Catalog

```text
/apps/myapp/config/org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aep-catalog-prod.cfg.json
```

```json
{
  "credential.id": "shared-aep-prod",
  "set.api.key.header": true,
  "org.id.header.value": "YOUR_ORG_ID@AdobeOrg"
}
```

#### Integration B: AEP Query

```text
/apps/myapp/config/org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aep-query-prod.cfg.json
```

```json
{
  "credential.id": "shared-aep-prod",
  "set.api.key.header": true,
  "org.id.header.value": "YOUR_ORG_ID@AdobeOrg"
}
```

Both integrations reuse the same shared credential, but remain separate integration contexts.

That means you still get:

- separate logical client keys
- separate integration-level configuration
- separate customizer references in consuming code

### Step 3: Consume both integrations in code

```java
@Slf4j
@Component(service = AepCompositeService.class)
public class AepCompositeServiceImpl implements AepCompositeService {

    private static final String CATALOG_URL =
        "https://platform.adobe.io/data/foundation/catalog/dataSets";

    private static final String QUERY_URL =
        "https://platform.adobe.io/data/foundation/query/queries";

    @Reference
    private HttpClientProvider httpClientProvider;

    @Reference(
        target = "(service.pid=org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aep-catalog-prod)"
    )
    private HttpClientCustomizer catalogCustomizer;

    @Reference(
        target = "(service.pid=org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aep-query-prod)"
    )
    private HttpClientCustomizer queryCustomizer;

    private CloseableHttpClient catalogClient;
    private CloseableHttpClient queryClient;

    @Activate
    void activate() {
        catalogClient = httpClientProvider.provide(
            "aep-catalog-prod",
            null,
            catalogCustomizer::customize
        );

        queryClient = httpClientProvider.provide(
            "aep-query-prod",
            null,
            queryCustomizer::customize
        );
    }

    @Override
    public String fetchCatalog() throws IOException {
        HttpGet request = new HttpGet(CATALOG_URL);

        try (CloseableHttpResponse response = catalogClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String fetchQueries() throws IOException {
        HttpGet request = new HttpGet(QUERY_URL);

        try (CloseableHttpResponse response = queryClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
```

### What this demonstrates

This example shows the real point of shared credentials:

- **one** shared OAuth credential source
- **two** separate Adobe integration configurations
- **two** separate pooled clients
- **two** separate integration contexts in consuming code

Both integrations draw from the same OAuth credential configuration, while each remains an independent integration context.

> Shared credentials are supported, but they are not the recommended default for every Adobe integration.

---

## Troubleshooting

### Granite trust store not in use (silent fallback)

**Symptoms:** TLS calls succeed, but certificates installed in AEM’s Granite trust store are not being used. There is no hard failure.

**How to detect:** look for this INFO line during startup:

```text
Granite trust store integration unavailable: service user 'truststore-reader' not configured. Falling back to JVM default trust store.
```

**What to check:**

**1. Enable DEBUG logging to see the root cause.**

Add a Sling Logger configuration for `org.kttn.aem.http.impl.HttpClientProviderImpl` at DEBUG level. The actual `LoginException` message will tell you exactly what is wrong.

**2. Verify the OSGi config is active.**

In the AEM Developer Console → OSGi → Configurations, search for `ServiceUserMapperImpl.amended`. Confirm your amended instance is listed and that `user.mapping` contains the expected entry.

---

### Connection timeouts

**Symptoms:** `SocketTimeoutException` or `ConnectTimeoutException`.

**What to check:**

- foundation timeout settings
- DNS / proxy / networking / egress rules
- whether this integration needs a custom timeout instead of changing the global default

---

### Certificate errors

**Symptoms:** `SSLHandshakeException`, `CertPathValidatorException`, or similar certificate-chain failures.

**What to check:**

- CA chain completeness and expiry
- private/self-signed certificate installation in AEM trust store
- whether Granite trust store integration is actually enabled

---

### Adobe IMS token failures

**Symptoms:** token acquisition fails before the outgoing API request is sent.

**What to check:**

- `clientId`
- `clientSecret`
- `scopes`
- `org.id.header.value`
- secret placeholder resolution in the target environment
- logs around token acquisition and issuer response status

A common real-world cause is HTTP `400 invalid_client`, which usually means the configured client id and secret do not match the intended Adobe Developer Console project.

---

## Next steps

- [Integration](INTEGRATION.md) — choosing the right integration path
- [Technical Reference](core/REFERENCE.md) — architecture and configuration reference
