# Changelog

## [0.10.0]

### Added
- HTTP client pools are rebuilt automatically when the Granite trust store changes.
  Consumers holding a cached client reference are not affected — the stable wrapper
  transparently picks up the new pool on the next request.
- Superseded connection pools are shut down after a grace period equal to the configured
  socket timeout, so in-flight requests are not interrupted during a trust-store refresh.
- Config-aware cache invalidation: calling `provide(key, newConfig)` with a changed
  `HttpConfig` now rebuilds the underlying pool instead of silently returning the stale
  client. A warning is logged on every rebuild. Relevant for local development; on
  AEMaaCS every deployment restarts all bundles so the cache is always fresh.

### Fixed
- `@Reference` target filters now use `service.pid` instead of `component.name`,
  which resolves filter mismatches when multiple service instances are registered.
- Granite trust store integration: corrected bundle symbolic name, added empty-store
  fallback to JVM defaults, and fixed `@Activate` setup order.

## [0.9.1]

### Added
- New `provide(key, mutator)` overload on `HttpClientProvider` for applying a
  `HttpClientBuilder` customisation without supplying an explicit `HttpConfig`.

### Fixed
- Maven Central publishing via `central-publishing-maven-plugin`.

## [0.9.0]

Initial public release.

- `HttpClientProvider` — OSGi service for obtaining pooled, cached `CloseableHttpClient`
  instances keyed by a string identifier.
- `HttpConfig` / `HttpConfigService` — OSGi-configurable value object covering
  connection, socket, and pool limits as well as I/O and 503 retry policies.
- Adobe IMS / OAuth 2.0 client-credentials authentication via
  `AdobeIntegrationConfiguration` and `OAuthClientCredentialsTokenSupplier`.
- `BearerTokenRequestCustomizer` for attaching arbitrary bearer tokens to requests.
- `AdobeApiKeyHeaderCustomizer` and `AdobeOrgIdHeaderCustomizer` for Adobe API headers.
- Automatic retry on I/O exceptions and HTTP 503 responses with configurable limits
  and intervals.
- AEM Granite trust store integration: server certificates managed in AEM are trusted
  alongside the JVM default trust anchors.

[0.10.0]: https://github.com/brunswi/aem-http-foundation/compare/aem-http-foundation-0.9.1...aem-http-foundation-0.10.0
[0.9.1]: https://github.com/brunswi/aem-http-foundation/compare/aem-http-foundation-0.9.0...aem-http-foundation-0.9.1
[0.9.0]: https://github.com/brunswi/aem-http-foundation/releases/tag/aem-http-foundation-0.9.0
