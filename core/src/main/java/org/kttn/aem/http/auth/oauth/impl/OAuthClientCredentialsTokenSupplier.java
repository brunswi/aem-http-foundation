package org.kttn.aem.http.auth.oauth.impl;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.OsgiAccessTokenSupplierType;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.kttn.aem.http.impl.InternalHttpClientProvider;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Map;

/**
 * OSGi factory component that publishes an {@link AccessTokenSupplier} backed by an OAuth 2.0
 * {@code client_credentials} grant against a configurable token endpoint.
 * <p>
 * This is the generic, protocol-oriented entry point — there are no Adobe-specific concerns
 * here. The default {@link Config#tokenEndpointUrl()} happens to be the Adobe IMS token endpoint
 * for convenience, but any RFC 6749 compliant authorization server is acceptable.
 * <p>
 * Each factory instance represents one credential set and is suitable for cases where multiple
 * consumers want to share the same credentials. Service properties exposed:
 * <ul>
 *   <li>{@code component.name} — fully qualified component name including the factory suffix
 *       (lookup pattern: {@code (component.name=...~<name>)}).</li>
 *   <li>{@code credential.id}  — optional logical identifier for shared-credential lookups
 *       (pattern: {@code (credential.id=<value>)}).</li>
 * </ul>
 * <p>
 * The HTTP call to the token endpoint uses a dedicated, bundle-internal pool obtained via
 * {@link InternalHttpClientProvider} under a key prefixed with
 * {@link HttpClientProvider#RESERVED_KEY_PREFIX}, so external callers cannot accidentally collide
 * with it. The actual acquisition and caching logic lives in {@link CachingTokenAcquirer}; this
 * component is intentionally a thin OSGi wrapper.
 *
 * @see AccessTokenSupplier
 * @see CachingTokenAcquirer
 */
@Slf4j
@Component(
    service = AccessTokenSupplier.class,
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    configurationPid = "org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier",
    property = {
        Constants.SERVICE_DESCRIPTION
            + "=Generic OAuth 2.0 client_credentials access token supplier",
        OsgiAccessTokenSupplierType.PROPERTY_NAME + "="
            + OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS
    })
@Designate(ocd = OAuthClientCredentialsTokenSupplier.Config.class, factory = true)
public class OAuthClientCredentialsTokenSupplier implements AccessTokenSupplier {

  /**
   * Reserved cache key for the dedicated OAuth token client pool.
   */
  private static final String TOKEN_CLIENT_KEY =
      HttpClientProvider.RESERVED_KEY_PREFIX + "oauth-token";

  @Reference
  private InternalHttpClientProvider httpClientProvider;

  private CachingTokenAcquirer acquirer;

  /**
   * OSGi / default; SCR uses this and injects {@link #httpClientProvider}.
   */
  public OAuthClientCredentialsTokenSupplier() {
  }

  /**
   * Package-private for unit tests that need an {@link InternalHttpClientProvider} without the
   * SCR field injector.
   */
  OAuthClientCredentialsTokenSupplier(final InternalHttpClientProvider httpClientProvider) {
    this.httpClientProvider = httpClientProvider;
  }

  /**
   * Loads the configuration and constructs a {@link CachingTokenAcquirer} bound to a dedicated
   * pooled client.
   *
   * @param config factory configuration instance
   */
  @Activate
  @Modified
  protected final void activate(@NonNull final Config config, @NonNull final Map<String, Object> properties) {
    final String label = componentNameLabel(properties);
    log.info(
        "{} '{}' activated (credentialId='{}', tokenEndpoint={}, clientId={})",
        getClass().getSimpleName(),
        label,
        config.credential_id(),
        config.tokenEndpointUrl(),
        config.clientId());
    final CloseableHttpClient client =
        httpClientProvider.provideInternal(TOKEN_CLIENT_KEY, null, null);
    this.acquirer = new CachingTokenAcquirer(
        client,
        config.tokenEndpointUrl(),
        config.clientId(),
        config.clientSecret(),
        config.scopes(),
        Collections.emptyMap(),
        CachingTokenAcquirer.DEFAULT_REFRESH_LENIENCY_SECONDS,
        label);
  }

  private static String componentNameLabel(@NonNull final Map<String, Object> properties) {
    final Object raw = properties.get(ComponentConstants.COMPONENT_NAME);
    if (raw == null) {
      return "unknown";
    }
    final String s = raw.toString().trim();
    return s.isEmpty() ? "unknown" : s;
  }

  @Override
  public AccessToken getAccessToken() throws TokenUnavailableException {
    return acquirer.getAccessToken();
  }

  /**
   * Test hook: returns the underlying {@link CachingTokenAcquirer} so unit tests can clear
   * caches or inspect state without reflection. Package-private.
   */
  CachingTokenAcquirer getAcquirerForTest() {
    return acquirer;
  }

  /**
   * OSGi Metatype for OAuth 2.0 {@code client_credentials}; factory allows multiple configured
   * suppliers (one per logical credential set).
   */
  @ObjectClassDefinition(name = "[HTTP] OAuth Client Credentials Token Supplier")
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Config {

    /**
     * Default Adobe IMS token endpoint (OAuth 2.0); valid for all regions.
     */
    String DEFAULT_IMS_TOKEN_URL = "https://ims-na1.adobelogin.com/ims/token/v3";

    /**
     * @return logical credential identifier used for shared-credential lookups via the
     * service property {@code credential.id}; empty when not used
     */
    @AttributeDefinition(
        name = "Credential ID",
        description = "Optional logical identifier exposed as the 'credential.id' service "
            + "property. Other components can look this supplier up via the LDAP filter "
            + "(credential.id=<value>) when sharing credentials across integrations. "
            + "Leave empty if this supplier is not shared.",
        required = false)
    String credential_id() default "";

    /**
     * @return OAuth {@code client_id}
     */
    @AttributeDefinition(
        name = "Client ID",
        description = "OAuth client identifier (client_id). For Adobe Developer Console "
            + "projects this is the Client ID shown on the OAuth Server-to-Server credential.")
    String clientId();

    /**
     * @return OAuth {@code client_secret}
     */
    @AttributeDefinition(
        name = "Client Secret",
        description = "OAuth client secret (client_secret). Use the AEMaaCS secret resolver "
            + "(for example $[secret:my.secret]) to keep the actual value out of source.")
    String clientSecret();

    /**
     * @return comma-separated OAuth {@code scope} values; may be empty
     */
    @AttributeDefinition(
        name = "Scopes",
        description = "Comma-separated OAuth scopes (scope) for the token request. "
            + "Leave empty to omit the 'scope' parameter.",
        required = false)
    String scopes() default "";

    /**
     * @return token endpoint URL; override only for non-default tenants or testing
     */
    @AttributeDefinition(
        name = "Token Endpoint URL",
        description = "OAuth 2.0 token endpoint. The default points at Adobe IMS (valid for "
            + "all regions); change it only for non-Adobe issuers or test environments.")
    String tokenEndpointUrl() default DEFAULT_IMS_TOKEN_URL;
  }
}
