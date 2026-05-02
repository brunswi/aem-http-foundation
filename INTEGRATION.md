# Integration Guide

Use this guide when you want to add AEM HTTP Foundation to an AEM as a Cloud Service project.

The recommended mental model is simple:

- use the foundation for **outbound HTTP infrastructure**
- add **OAuth** only when your target API needs it
- use **Adobe integration configuration** when you need IMS / Adobe gateway headers
- keep API-specific request and response handling in your own bundle

---

## Prerequisites

- AEM as a Cloud Service project
- Maven 3.6+
- Java 11 or Java 21
- a deployment path for the foundation bundle, typically through the `all` package

---

## Step 1: Add Maven dependency

**Version:** use the version shown in the Maven Central badge on the [main README](README.md).

**Scope:** use `provided` scope. The bundle is deployed once into AEM and your application bundle resolves against it at runtime.

### 1.1 Update root POM

Add to `<dependencyManagement>` in your root `pom.xml`:

```xml
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.kttn.aem</groupId>
        <artifactId>aem-http-foundation.core</artifactId>
        <version><!-- use current release --></version>
        <scope>provided</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

### 1.2 Update core bundle POM

Add the dependency to the bundle that consumes the foundation:

```xml
<project>
  <dependencies>
    <dependency>
      <groupId>org.kttn.aem</groupId>
      <artifactId>aem-http-foundation.core</artifactId>
      <scope>provided</scope>
    </dependency>
  </dependencies>
</project>
```

### 1.3 Embed in all package

Make sure the foundation bundle is deployed into the target environment as part of your normal packaging and deployment flow.

---

## Step 2: Optional defaults

Most projects can start with the foundation defaults and only add extra OSGi configuration when needed.

Typical reasons to configure defaults are:

- non-default connection and socket timeouts
- different pool sizing
- retry policy tuning
- trust-store integration requirements

If you do not have a concrete reason to change these, start with the defaults.

---

## Step 3: Build and deploy

Build and deploy your project as usual.

After the bundle is available in AEM, consuming bundles can inject:

- `HttpClientProvider`
- `HttpConfigService`
- `HttpClientCustomizer`
- `AccessTokenSupplier`

depending on the chosen integration path.

---

## Verify that it is working

After deployment to AEMaaCS:

- confirm the deployment succeeded in Cloud Manager
- confirm the bundle and OSGi configuration are active in the AEM Developer Console
- run one real smoke call

[Example 1](EXAMPLES.md#example-1-public-rest-api-basic) is a good baseline verification check.  

If anything fails, inspect the environment logs for activation, configuration, TLS, token, or authorization errors.

---

## Optional: Granite trust store integration

Use this only when you need the foundation to trust certificates installed in AEM’s Granite trust store in addition to the JVM trust store.

### When to use this

Use Granite trust store integration when:

- you call an internal or partner endpoint with a private CA
- you rely on certificates stored in AEM rather than only the JVM default trust store

If you only call public APIs with standard CA chains, you usually do not need this.

### Prerequisite: initialise and activate the Granite trust store

Ensure the Granite trust store exists and is properly initialized in the target environment.

### Configure service user

The foundation’s `HttpClientProvider` requests a service resource resolver for the `truststore-reader` subservice. You must map that subservice to a repository identity that can read the Granite trust store (`/etc/truststore`).

An out-of-the-box `truststore-reader-service` system user already exists in AEM as a Cloud Service. Point the foundation bundle at that principal by creating an amended service user mapping OSGi factory configuration.

Create the file at:

```text
ui.config/src/main/content/jcr_root/apps/<your-app>/osgiconfig/config/
  org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~aem-http-foundation.cfg.json
```

with the following content:

```json
{
  "service.ranking": 0,
  "user.mapping": [
    "org.kttn.aem.aem-http-foundation.core:truststore-reader=[truststore-reader-service]"
  ]
}
```
If the service user is not available, the foundation falls back to the JVM trust store. See [EXAMPLES.md → Troubleshooting](EXAMPLES.md#troubleshooting).

---

## Optional: Adobe integration (OAuth + IMS headers)

Use this when you call Adobe APIs that require:

- OAuth `client_credentials`
- `Authorization: Bearer <token>`
- optional Adobe-specific gateway headers such as:
    - `x-api-key`
    - `x-gw-ims-org-id`

### Adobe I/O, Experience Platform, and the same pattern

If you are used to “Adobe I/O” in day-to-day work, you already have the right mental model:

- Adobe Developer Console project
- IMS / OAuth
- `client_id`
- `client_secret`
- bearer token
- optional API key and org context headers

That same technical pattern often applies to:

- I/O Runtime
- Experience Platform APIs
- other Adobe APIs exposed through the same server-to-server model

What changes between those APIs are typically:

- hostnames
- paths
- scopes
- product permissions

What does **not** change is the client-side transport pattern handled here.

`AdobeIntegrationConfiguration` standardizes that recurring client-side pattern. Your application code still owns the actual URLs and payloads.

### Recommended default

For most Adobe server-to-server integrations, use a single `AdobeIntegrationConfiguration` instance.

That gives you:

- one configuration
- one integration context
- one token cache
- one header policy
- one customizer ready to attach to a pooled client

### Configure one integration per integration context

Create one factory configuration per Adobe integration context.

In practice, this usually means one configuration per:

- environment
- credential set
- header policy combination

Filename pattern:

```text
org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~<integration>.cfg.json
```

Example:

```json
{
  "clientId": "YOUR_CLIENT_ID",
  "clientSecret": "$[secret:aio.runtime.client.secret]",
  "scopes": "openid,AdobeID,read_organizations",
  "set.api.key.header": true,
  "org.id.header.value": "YOUR_ORG_ID@AdobeOrg"
}
```

This configuration provides:

- an `AccessTokenSupplier` for bearer token acquisition and caching
- an `HttpClientCustomizer` that applies bearer auth and the configured Adobe headers

See [EXAMPLES.md](EXAMPLES.md) for the consumer-side usage.

### Shared credentials

If multiple Adobe integrations intentionally share the same OAuth credential set, define a shared `OAuthClientCredentialsTokenSupplier` configuration and reference it from multiple integrations.

See [EXAMPLES.md](EXAMPLES.md#example-5-shared-credentials-across-multiple-integrations-advanced) for an example of this.

### Generic OAuth *without* Adobe headers

For non-Adobe authorization servers, or for APIs that only need bearer auth, use:

- `org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier~<name>.cfg.json`
- plus `BearerTokenRequestCustomizer`

This is the right path when you want generic OAuth `client_credentials` without Adobe-specific header behavior.

---

## Next steps

- [Examples](EXAMPLES.md) — recommended usage patterns and troubleshooting
- [Technical Reference](core/REFERENCE.md) — architecture, public API, and OSGi configuration reference
