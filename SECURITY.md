# Security Policy

## Reporting a vulnerability

Please **do not open a public GitHub issue** for security-related reports.

Instead, open a [GitHub Security Advisory](https://github.com/brunswi/aem-http-foundation/security/advisories/new) — this keeps the report private until a fix is published and lets us coordinate a CVE if appropriate.

Please include:

- The affected version(s) of `aem-http-foundation.core`.
- A clear description of the issue and its impact (e.g. token leakage, MITM, denial of service).
- Steps to reproduce, ideally with a minimal test case.
- Any known workarounds.

## What to expect

- **Best effort response** - This is a solo-maintained open source project provided as-is.
- Acknowledgement typically within 5 business days, but may be longer during vacations or high workload periods.
- A public fix and advisory once a patch is available.
- Credit in the advisory if you wish.

## Supported versions

Until `1.0.0` is released, only the latest published version on Maven Central receives security fixes. Older snapshots are not supported.

## Scope

In scope:

- The `aem-http-foundation.core` OSGi bundle.
- OAuth `client_credentials` token handling and credential exposure paths in `CachingTokenAcquirer`, `OAuthClientCredentialsTokenSupplier`, `AdobeIntegrationConfiguration`, and `BearerTokenRequestCustomizer`.
- TLS / trust store integration in `HttpClientProviderImpl`.

Out of scope:

- Vulnerabilities in AEM, Apache HttpClient, or other transitive dependencies — please report those to the upstream project.
- Issues caused by misconfiguration (e.g. committing real `clientSecret` values into `.cfg.json` files in your own project).
