# AEM HTTP Foundation

A shared, supportable default for outbound HTTP in **AEM as a Cloud Service**.

Use it when your AEM project calls internal, external, or Adobe APIs and you do **not** want to rebuild the same transport and auth glue in every codebase.

<!--
  Badge styling notes:
  - All badges use shields.io with palette colors pinned via &color= and &labelColor=:
      green   = 9bc68d   (success / "we have it" / live data)
      blue    = a1b6d0   (neutral / static info)
      label   = 555555   (left side, all badges)
      logo    = fdf0ed   (cream tint, recolors brand SVGs to fit the palette)
-->
[![Maven Central](https://img.shields.io/maven-central/v/org.kttn.aem/aem-http-foundation.core?style=flat-square&logo=apachemaven&logoColor=fdf0ed&label=Maven%20Central&color=9bc68d&labelColor=555555)](https://central.sonatype.com/artifact/org.kttn.aem/aem-http-foundation.core)
[![Coverage](https://img.shields.io/codecov/c/github/brunswi/aem-http-foundation?style=flat-square&logo=codecov&logoColor=fdf0ed&label=Coverage&color=9bc68d&labelColor=555555)](https://app.codecov.io/github/brunswi/aem-http-foundation)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/brunswi/aem-http-foundation/codeql.yml?style=flat-square&logo=github&logoColor=fdf0ed&label=CodeQL&color=9bc68d&labelColor=555555)](https://github.com/brunswi/aem-http-foundation/actions/workflows/codeql.yml)

---

## Why not just use raw Apache HttpClient

You can — but most AEM projects then end up re-implementing:

- pooling
- retries
- TLS handling
- token acquisition
- token caching
- request interceptors

## Why use this

**AEM HTTP Foundation** turns those recurring concerns into a shared default, so projects can focus on API-specific logic instead of rebuilding transport and auth mechanics.

It was extracted from recurring outbound HTTP and authentication needs across multiple AEMaaCS projects and is intended to make that pattern reusable across teams.
It is especially useful for:

- **general outbound HTTP** to internal or external APIs
- **Adobe server-to-server integrations** using IMS / OAuth `client_credentials`
- **consistent request customization** such as bearer auth and Adobe-specific headers

---

## When to use it

Use AEM HTTP Foundation if your AEM project:

- calls internal or external APIs
- needs connection pooling, retries, or TLS customization
- uses OAuth `client_credentials`
- calls Adobe APIs with recurring IMS and header wiring

If your AEM project calls external or Adobe APIs, this is the default worth trying.

## When not to use it

You probably do **not** need this if:

- your project makes no outbound HTTP calls
- you only need a one-off local experiment
- you want a full API client framework rather than shared transport infrastructure

---

## Quick start

### Choose your path

| I want to call...                                | Use                                                       |
|--------------------------------------------------|-----------------------------------------------------------|
| A public or internal API with no special auth    | `HttpClientProvider`                                      |
| A non-Adobe API using OAuth `client_credentials` | `OAuthClientCredentialsTokenSupplier` + bearer customizer |
| An Adobe API using IMS / Adobe headers           | `AdobeIntegrationConfiguration`                           |
| Something custom                                 | your own `HttpClientCustomizer`                           |

### 1. Add the Maven dependency

Use the current released version shown in the Maven Central badge above.

### 2. Deploy the bundle

Deploy `aem-http-foundation.core` once to your AEM environment, typically via the `all` package.

### 3. Inject and call

For the simplest path, inject `HttpClientProvider`, create or retrieve a pooled client once, and execute requests with it.

```java
@Component(service = HealthcheckService.class)
public class HealthcheckServiceImpl implements HealthcheckService {

    @Reference
    private HttpClientProvider httpClientProvider;

    private CloseableHttpClient httpClient;

    @Activate
    void activate() {
        httpClient = httpClientProvider.provide("healthcheck-api");
    }

    @Override
    public String ping() throws IOException {
        HttpGet request = new HttpGet("https://httpbin.org/get");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
```

> **Important:** do not call `close()` on `CloseableHttpClient` instances returned by the foundation. The bundle manages the client lifecycle. Only close the `CloseableHttpResponse`.

---

## Documentation

| Document                                 | Purpose                                                            |
|------------------------------------------|--------------------------------------------------------------------|
| [Integration](INTEGRATION.md)            | Setup, Deployment, Verification, and Recommended Integration Paths |
| [Examples](EXAMPLES.md)                  | Recommended Usage Patterns and Troubleshooting                     |
| [Technical Reference](core/REFERENCE.md) | Architecture, Public API, and Configuration Reference              |

---

## License

Apache License, Version 2.0
