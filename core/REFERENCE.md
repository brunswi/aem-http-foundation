# Technical Reference

Technical reference for the `aem-http-foundation.core` OSGi bundle: architecture, public API, configuration model, and runtime boundaries.

This page is intentionally reference-oriented.   
If you are onboarding a project, start with:

- [Integration](../INTEGRATION.md) for setup and recommended integration paths
- [Usage Examples](../EXAMPLES.md) for concrete usage patterns

---

## Compatibility

| Component             | Version / Details                                                 |
|-----------------------|-------------------------------------------------------------------|
| **AEM**               | AEM as a Cloud Service                                            |
| **Java**              | Compiled to Java 11 bytecode; runs on Java 11 and Java 21         |
| **Apache HttpClient** | 4.5.x, supplied by the AEM runtime via `HttpClientBuilderFactory` |

---

## What this bundle is for

The core bundle standardizes **outbound HTTP infrastructure** for AEMaaCS.

It provides a shared foundation for:

- pooled outbound `CloseableHttpClient` instances
- timeout and retry configuration
- TLS and trust-store integration
- optional authentication layering
- optional Adobe-specific request customization

It is designed to centralize the repeating transport and auth-related mechanics of outbound integrations, while leaving API-specific logic in the consuming bundle.

---

## Overview

| Goal                                                           | Use                                                                                                        |
|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| Plain pooled `CloseableHttpClient` for any outbound call       | [`HttpClientProvider`](#httpclientprovider)                                                                |
| Adobe API integration with OAuth + Adobe gateway headers       | [`AdobeIntegrationConfiguration`](#adobeintegrationconfiguration)                                          |
| Multiple integrations intentionally sharing one credential set | [`OAuthClientCredentialsTokenSupplier`](#oauthclientcredentialstokensupplier) + bearer customization       |
| Generic OAuth `client_credentials` against any RFC 6749 issuer | [`OAuthClientCredentialsTokenSupplier`](#oauthclientcredentialstokensupplier) + bearer customization       |
| Custom auth scheme                                             | implement [`HttpClientCustomizer`](#httpclientcustomizer) and pass it to `HttpClientProvider.provide(...)` |

---

## Configuration model

The foundation uses three configuration layers:

- **Shared HTTP defaults** — `HttpConfigService` / `HttpConfig`: timeouts, pool sizing, retry behavior
- **Optional shared OAuth credentials** — `OAuthClientCredentialsTokenSupplier`: reusable credential sets shared across multiple integrations; advanced, not the default path
- **Integration-specific request policies** — `AdobeIntegrationConfiguration`: bearer token injection, Adobe headers, per-integration client customization

---

## Architecture

Outbound HTTP calls are keyed and pooled:

- **`HttpClientProvider`** creates or retrieves a `CloseableHttpClient` per logical key
- **`HttpConfigService`** supplies timeout, pool, and retry settings from OSGi metatype
- **Authentication** is layered on top in three clearly separated packages:
  - `auth.oauth` — generic OAuth 2.0 `client_credentials`
  - `auth.bearer` — generic `Authorization: Bearer ...` request enrichment
  - `auth.adobe` — Adobe-specific gateway headers and `AdobeIntegrationConfiguration`

```text
┌───────────────────────────┐   ┌───────────────────────────────┐
│   HttpClientProvider      │   │      HttpConfigService        │
│   (public interface)      │   │      (public interface)       │
└────────────┬──────────────┘   └──────────────┬────────────────┘
             │                                 │
┌────────────▼──────────────┐   ┌──────────────▼────────────────┐
│  HttpClientProviderImpl   │───│   HttpConfigServiceImpl       │
│  · pooling / cache        │   │   OSGi metatype → HttpConfig  │
│  · TLS / trust handling   │   └───────────────────────────────┘
│  · retry handlers         │
└────────────┬──────────────┘
             │ optional composition via HttpClientCustomizer
┌────────────▼─────────────────────────────────────────────────────────┐
│  AdobeIntegrationConfiguration                                       │
│  · HttpClientCustomizer                                              │
│  · AccessTokenSupplier                                               │
│  · bearer auth                                                       │
│  · x-api-key                                                         │
│  · x-gw-ims-org-id                                                   │
└────────────┬─────────────────────────────────────────────────────────┘
             │ delegates token acquisition and caching
┌────────────▼─────────────────────────────────────────────────────────┐
│  CachingTokenAcquirer                                                │
│  · OAuth client_credentials acquisition                              │
│  · cache and refresh before expiry                                   │
└──────────────────────────────────────────────────────────────────────┘
```

## Public contract vs internal implementation

#### Singleton services - inject via `@Reference`

- [`HttpClientProvider`](src/main/java/org/kttn/aem/http/HttpClientProvider.java)
- [`HttpConfigService`](src/main/java/org/kttn/aem/http/HttpConfigService.java)
- [`HttpClientCustomizer`](src/main/java/org/kttn/aem/http/auth/HttpClientCustomizer.java)
- [`AccessTokenSupplier`](src/main/java/org/kttn/aem/http/auth/oauth/AccessTokenSupplier.java)
- [`AccessToken`](src/main/java/org/kttn/aem/http/auth/oauth/AccessToken.java)
- [`HttpConfig`](src/main/java/org/kttn/aem/http/HttpConfig.java)

#### Factory components - one config file per integration context

- [`AdobeIntegrationConfiguration`](src/main/java/org/kttn/aem/http/auth/adobe/impl/AdobeIntegrationConfiguration.java)
- [`OAuthClientCredentialsTokenSupplier`](src/main/java/org/kttn/aem/http/auth/oauth/impl/OAuthClientCredentialsTokenSupplier.java)

#### Internal implementation

- [`HttpClientProviderImpl`](src/main/java/org/kttn/aem/http/impl/HttpClientProviderImpl.java)
- [`HttpConfigServiceImpl`](src/main/java/org/kttn/aem/http/impl/HttpConfigServiceImpl.java)
- [`HttpRequestRetryHandler`](src/main/java/org/kttn/aem/http/impl/HttpRequestRetryHandler.java)
- [`ServiceUnavailableRetryStrategy`](src/main/java/org/kttn/aem/http/impl/ServiceUnavailableRetryStrategy.java)
- [`CachingTokenAcquirer`](src/main/java/org/kttn/aem/http/auth/oauth/impl/CachingTokenAcquirer.java)
---

## Lifecycle guarantees

The foundation manages the lifecycle of pooled clients.

### Guaranteed behavior

- clients are pooled and cached by **logical key**
- the same logical key resolves to the same pooled client context
- client lifecycle is managed by the foundation
- pools and connection managers are shut down when the owning bundle deactivates

### Consumer responsibilities

- **do not call `close()`** on pooled `CloseableHttpClient` instances returned by the foundation
- **do close** each `CloseableHttpResponse`
- choose logical keys that reflect an integration or policy context, not just a single URL

This separation keeps pooling and shutdown behavior predictable.

---

## Public interfaces

### `HttpClientProvider`

Primary entry point for creating or retrieving pooled `CloseableHttpClient` instances by logical key.

Use this when you want:

- a shared client per integration key
- central lifecycle management
- optional per-client builder customization

Typical usage:

```java
CloseableHttpClient client = httpClientProvider.provide("orders-api");
```

or with a customizer and default config:

```java
CloseableHttpClient client = httpClientProvider.provide(
    "orders-api",
    builder -> builder.addInterceptorLast(myInterceptor)
);
```

or with explicit config and customizer:

```java
CloseableHttpClient client = httpClientProvider.provide(
    "orders-api",
    customConfig,
    builder -> builder.addInterceptorLast(myInterceptor)
);
```

### `HttpConfigService`

Provides the effective `HttpConfig` derived from OSGi configuration.

Use this when:

- you need to derive a per-integration override
- you want to inspect foundation defaults


### `HttpClientCustomizer`

Extension point for mutating the Apache `HttpClientBuilder` used by the provider.

Typical uses:

- request interceptors
- auth layers
- additional headers
- integration-specific builder behavior

### `AccessTokenSupplier` and `AccessToken`

Abstraction for components that can provide access tokens.

In practice this is used by:

- generic OAuth token suppliers
- Adobe integration configurations that also expose token supply

Use this when you need raw token access, not only automatic request customization.

---

## Public data objects

### `HttpConfig`

Immutable configuration object describing the effective HTTP settings used by the foundation.
All time-valued fields are expressed in **milliseconds**.

#### OSGi configuration

- **PID:** `org.kttn.aem.http.impl.HttpConfigServiceImpl` (singleton — not a factory)

```text
/apps/myapp/config/org.kttn.aem.http.impl.HttpConfigServiceImpl.cfg.json
```

#### Field reference

| Property                                      | Required | Default | Meaning                                                                                                                                          |
|-----------------------------------------------|:--------:|--------:|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `http.config.connectionTimeout`               |    no    | `10000` | TCP connect timeout to the remote host (milliseconds). Maps to Apache HttpClient `RequestConfig#setConnectTimeout`.                              |
| `http.config.connectionManagerTimeout`        |    no    | `10000` | Maximum time to wait when leasing a pooled connection (milliseconds). Maps to `RequestConfig#setConnectionRequestTimeout`.                       |
| `http.config.socketTimeout`                   |    no    | `10000` | Socket read/write timeout (`SO_TIMEOUT`) in milliseconds; `0` means infinite. Maps to `RequestConfig#setSocketTimeout`.                          |
| `http.config.maxConnection`                   |    no    |   `100` | Upper bound on total concurrent connections in the pool. Maps to `PoolingHttpClientConnectionManager#setMaxTotal`.                               |
| `http.config.maxConnectionPerRoute`           |    no    |    `20` | Upper bound on concurrent connections per HTTP route (host + scheme + port). Maps to `PoolingHttpClientConnectionManager#setDefaultMaxPerRoute`. |
| `http.config.serviceUnavailableMaxRetryCount` |    no    |     `3` | Maximum retries after an HTTP `503` response; `0` disables this retry path.                                                                      |
| `http.config.serviceUnavailableRetryInterval` |    no    |  `1000` | Delay in milliseconds between subsequent `503` retries (applied after the first attempt).                                                        |
| `http.config.ioExceptionMaxRetryCount`        |    no    |     `3` | Maximum retries after a retriable `java.io.IOException`; `0` disables.                                                                           |
| `http.config.ioExceptionRetryInterval`        |    no    |  `1000` | Delay in milliseconds between subsequent I/O retries (applied after the first attempt).                                                          |

#### Per-integration override

See [EXAMPLES.md → Example 6](../EXAMPLES.md#example-4-custom-timeouts-per-integration-intermediate) for the pattern.

---

## Authentication components

### `AdobeIntegrationConfiguration`

Factory component for Adobe server-to-server integration contexts.

#### OSGi configuration

- **Factory PID:** `org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration` (one configuration file per integration)
- **Example path:** `/apps/myapp/config/org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aio-runtime-prod.cfg.json`
- Inject a specific instance with an LDAP-style target filter (OSGi service filter syntax) on `service.pid`, for example `(service.pid=org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aio-runtime-prod)`.
- Multi-value properties are string arrays in `.cfg.json` (for example `"additional.headers": ["x-sandbox-name=prod"]`).

#### Field reference

| Property                  | Required |                                       Default | Meaning                                                                                                                                                                                      |
|---------------------------|:--------:|----------------------------------------------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `credential.id`           |    no    |                                     *(empty)* | When set, bearer tokens come from the shared `OAuthClientCredentialsTokenSupplier` with the same `credential.id`; inline token fields below are ignored for acquisition.                     |
| `clientId`                |   yes*   |                                     *(empty)* | OAuth client ID; required for inline mode. With `credential.id` set, optional: overrides `x-api-key` only; if omitted there, the value is read from the shared supplier `clientId` property. |
| `clientSecret`            |   yes*   |                                     *(empty)* | OAuth client secret; required when `credential.id` is empty; ignored when shared credentials are used.                                                                                       |
| `scopes`                  |    no    |                                     *(empty)* | Comma-separated OAuth scopes (inline mode only).                                                                                                                                             |
| `set.api.key.header`      |    no    |                                        `true` | When true, sends `x-api-key` using `clientId` or, in shared mode, the shared supplier `clientId` property if local `clientId` is blank.                                                      |
| `org.id.header.value`     |    no    |                                     *(empty)* | Value for the `x-gw-ims-org-id` header on API requests (not sent to the token endpoint).                                                                                                     |
| `tokenEndpointUrl`        |    no    | `https://ims-na1.adobelogin.com/ims/token/v3` | Token endpoint (inline mode only).                                                                                                                                                           |
| `additional.token.params` |    no    |                                     *(empty)* | Advanced: extra token POST form fields as `name=value` entries (inline mode only).                                                                                                           |
| `additional.headers`      |    no    |                                     *(empty)* | Advanced: extra static request headers as `name=value` entries.                                                                                                                              |

* required when `credential.id` is empty.

Responsibilities include:

- bearer token request customization
- optional Adobe API key header
- optional Adobe org id header
- inline or shared credential usage (shared tokens via `OAuthClientCredentialsTokenSupplier` + `credential.id`)
- token caching through the internal token acquirer

### `OAuthClientCredentialsTokenSupplier`

Generic OAuth 2.0 `client_credentials` supplier for non-Adobe and shared-credential use cases.

#### OSGi configuration

- **Factory PID:** `org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier` (one file per logical credential set)
- **Example path:** `/apps/myapp/config/org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier~aep-prod.cfg.json`
- Optional LDAP-style filter (OSGi service filter) on shared suppliers: `(credential.id=<your-id>)` (must match `credential.id` on `AdobeIntegrationConfiguration` when using Example 3).

#### Field reference

| Property           | Required |                                       Default | Meaning                                                                                         |
|--------------------|:--------:|----------------------------------------------:|-------------------------------------------------------------------------------------------------|
| `credential.id`    |    no    |                                     *(empty)* | Logical id exposed as the `credential.id` service property for Adobe integrations to reference. |
| `clientId`         |   yes    |                                             — | OAuth `client_id` (token request and service property for shared `x-api-key`).                  |
| `clientSecret`     |   yes    |                                             — | OAuth `client_secret`; use `$[secret:…]` in AEMaaCS where possible.                             |
| `scopes`           |    no    |                                     *(empty)* | Comma-separated scopes; empty omits the `scope` parameter.                                      |
| `tokenEndpointUrl` |    no    | `https://ims-na1.adobelogin.com/ims/token/v3` | OAuth 2.0 token endpoint.                                                                       |

---

## Design decisions

### Hybrid trust manager

The foundation can combine:

- JVM default trust material
- optional Granite trust-store material

This allows projects to preserve standard public CA behavior while also trusting project-specific certificates managed in AEM.

### Key-based client cache

Clients are cached by logical key rather than by URL.

That is deliberate:

- one integration may call multiple URLs
- the key models the client or policy context, not a single endpoint
- connection reuse and lifecycle are easier to reason about this way

### Layered auth packages

The bundle separates:

- generic OAuth token acquisition
- generic bearer request enrichment
- Adobe-specific request behavior

This keeps the core transport layer reusable while avoiding Adobe-specific assumptions in generic OAuth support.

---

## Non-goals

To keep the foundation focused, the following are intentionally out of scope:

- user-based OAuth flows such as Authorization Code or PKCE
- refresh-token workflows for user clients
- generic identity-provider integration beyond OAuth `client_credentials`
- product-specific API facades
- a universal abstraction over every Adobe or AEM endpoint
- automatic “service profiles” that hide what auth or headers are being applied

---

## Usage examples

| Example                                                                                                                                          | Level            | When to use it                                                                                                   |
|--------------------------------------------------------------------------------------------------------------------------------------------------|------------------|------------------------------------------------------------------------------------------------------------------|
| [Example 1 — Public REST API](../EXAMPLES.md#example-1-public-rest-api-basic)                                                                    | **Basic**        | Plain pooled outbound HTTP with no special authentication; recommended first smoke test after deployment.        |
| [Example 2 — Adobe integration](../EXAMPLES.md#example-2-adobe-integration-recommended)                                                          | **Recommended**  | Recommended default for Adobe server-to-server APIs (IMS OAuth `client_credentials`, optional Adobe headers).    |
| [Example 3 — Custom Basic Auth](../EXAMPLES.md#example-3-custom-basic-auth-intermediate)                                                         | **Intermediate** | Non-OAuth endpoints that use Basic authentication, while still reusing the foundation's pooled client lifecycle. |
| [Example 4 — Custom timeouts per integration](../EXAMPLES.md#example-4-custom-timeouts-per-integration-intermediate)                             | **Intermediate** | One integration needs different timeouts than the shared defaults, without loosening them globally.              |
| [Example 5 — Shared credentials across multiple integrations](../EXAMPLES.md#example-5-shared-credentials-across-multiple-integrations-advanced) | **Advanced**     | Multiple Adobe integrations intentionally share one OAuth credential set but keep separate integration contexts. |

---

## See also

- [Integration](../INTEGRATION.md) — choosing the right integration path
- [Examples](../EXAMPLES.md) — concrete usage patterns for common scenarios
