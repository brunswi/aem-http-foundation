package org.kttn.aem.http.auth.adobe.impl;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.auth.HttpClientCustomizer;
import org.kttn.aem.http.auth.adobe.AdobeIntegrationCustomizers;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.OsgiAccessTokenSupplierType;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.kttn.aem.http.auth.oauth.impl.CachingTokenAcquirer;
import org.kttn.aem.http.impl.InternalHttpClientProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.ComponentException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Primary entry point for configuring an Adobe integration with one OSGi factory configuration
 * per integration.
 * <p>
 * This component encapsulates everything a typical Adobe API integration needs — OAuth
 * {@code client_credentials} token acquisition, the {@code Authorization: Bearer ...} header,
 * and the curated Adobe gateway headers ({@code x-api-key}, {@code x-gw-ims-org-id}) — and
 * publishes the assembled pipeline as both:
 * <ul>
 *   <li>an {@link AccessTokenSupplier} (so other code can also obtain raw tokens for the same
 *       credentials), and</li>
 *   <li>an {@link HttpClientCustomizer} (so consumers can pass it directly to
 *       {@code HttpClientProvider.provide(key, config, customizer::customize)}).</li>
 * </ul>
 * <p>
 * The actual token acquisition and caching logic lives in the shared
 * {@link CachingTokenAcquirer}; this component is the user-facing OSGi configuration plus a thin
 * assembly of the customizers.
 *
 * <h2>Per-integration filter</h2>
 * Components consume a specific configuration via the {@code service.pid} LDAP filter, for
 * example:
 * <pre>{@code
 * @Reference(target = "(service.pid=org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration~aep-prod)")
 * private HttpClientCustomizer aepCustomizer;
 * }</pre>
 *
 * <h2>Shared OAuth credentials</h2>
 * When {@link Config#credential_id()} is non-empty, bearer tokens are obtained from an
 * {@link org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier} registered with
 * the same {@code credential.id} service property (see Example 3 in {@code EXAMPLES.md}). Inline
 * {@link Config#clientId()}, {@link Config#clientSecret()}, {@link Config#scopes()},
 * {@link Config#tokenEndpointUrl()}, and {@link Config#additional_token_params()} are then
 * ignored for token acquisition. For {@code x-api-key}, if {@link Config#set_api_key_header()} is
 * true and {@link Config#clientId()} is left blank, the client ID is read from the shared
 * supplier's service properties.
 *
 * <h2>Escape hatches</h2>
 * The {@link Config#additional_token_params()} and {@link Config#additional_headers()}
 * properties are <strong>advanced</strong>: use them only when an Adobe service requires
 * non-standard token parameters or request headers (for example {@code x-sandbox-name}). They
 * are intentionally not the primary onboarding path; reach for the dedicated fields
 * ({@code clientId}, {@code clientSecret}, {@code orgIdHeaderValue}) first.
 */
@Slf4j
@Component(
    service = {AccessTokenSupplier.class, HttpClientCustomizer.class},
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    property = {
        Constants.SERVICE_DESCRIPTION
            + "=Adobe integration configuration (OAuth client_credentials + Adobe headers)",
        OsgiAccessTokenSupplierType.PROPERTY_NAME + "="
            + OsgiAccessTokenSupplierType.VALUE_ADOBE_INTEGRATION
    })
@Designate(ocd = AdobeIntegrationConfiguration.Config.class, factory = true)
public class AdobeIntegrationConfiguration implements AccessTokenSupplier, HttpClientCustomizer {

  /**
   * Reserved cache key for the dedicated token client pool used by Adobe integrations.
   */
  private static final String TOKEN_CLIENT_KEY =
      HttpClientProvider.RESERVED_KEY_PREFIX + "adobe-integration-token";

  @Reference
  private InternalHttpClientProvider httpClientProvider;

  private AccessTokenSupplier bearerSource;
  private ServiceReference<AccessTokenSupplier> sharedCredentialRef;
  private HttpClientCustomizer composedCustomizer;

  public AdobeIntegrationConfiguration() {
  }

  /**
   * Package-private for unit tests.
   */
  AdobeIntegrationConfiguration(final InternalHttpClientProvider httpClientProvider) {
    this.httpClientProvider = httpClientProvider;
  }

  /**
   * Builds token acquisition (inline or shared) and assembles the Adobe header customizer chain.
   *
   * @param config factory configuration instance
   */
  @Activate
  @Modified
  protected final void activate(
      @NonNull final Config config,
      @NonNull final BundleContext bundleContext,
      @NonNull final Map<String, Object> properties) {
    releaseSharedCredential(bundleContext);

    final String label = componentNameLabel(properties);
    final String credentialId = normalizeCredentialId(config.credential_id());

    final CachingTokenAcquirer nextOwnedAcquirer;
    final AccessTokenSupplier nextBearerSource;
    final ServiceReference<AccessTokenSupplier> nextSharedRef;

    if (credentialId.isEmpty()) {
      final String clientId = nullToEmpty(config.clientId());
      final String clientSecret = nullToEmpty(config.clientSecret());
      if (clientId.isBlank() || clientSecret.isBlank()) {
        throw new ComponentException(
            "clientId and clientSecret are required when credential.id is not set.");
      }

      log.info(
          "{} '{}' activated (inline OAuth, clientId={}, orgIdHeaderValue='{}', tokenEndpoint={})",
          getClass().getSimpleName(),
          label,
          clientId,
          config.org_id_header_value(),
          config.tokenEndpointUrl());

      final CloseableHttpClient tokenClient =
          httpClientProvider.provideInternal(TOKEN_CLIENT_KEY, null, null);

      nextOwnedAcquirer =
          new CachingTokenAcquirer(
              tokenClient,
              config.tokenEndpointUrl(),
              clientId,
              clientSecret,
              config.scopes(),
              parseKeyValuePairs(config.additional_token_params()),
              CachingTokenAcquirer.DEFAULT_REFRESH_LENIENCY_SECONDS,
              label);
      nextBearerSource = nextOwnedAcquirer;
      nextSharedRef = null;
    } else {
      warnIfSharedModeIgnoresTokenFields(config, label);

      final String filter = sharedOAuthSupplierLdapFilter(credentialId);
      final Collection<ServiceReference<AccessTokenSupplier>> refs;
      try {
        refs = bundleContext.getServiceReferences(AccessTokenSupplier.class, filter);
      } catch (final InvalidSyntaxException e) {
        throw new ComponentException("Invalid LDAP filter for credential.id lookup: " + filter, e);
      }
      if (refs == null || refs.isEmpty()) {
        throw new ComponentException(
            "No OAuthClientCredentialsTokenSupplier registered for credential.id='"
                + credentialId
                + "' (filter: "
                + filter
                + ").");
      }

      final ServiceReference<AccessTokenSupplier> chosen = selectHighestRanking(refs);
      final AccessTokenSupplier shared = bundleContext.getService(chosen);
      if (shared == null) {
        throw new ComponentException(
            "Could not obtain AccessTokenSupplier for credential.id='" + credentialId + "'.");
      }

      log.info(
          "{} '{}' activated (shared credential id='{}', orgIdHeaderValue='{}')",
          getClass().getSimpleName(),
          label,
          credentialId,
          config.org_id_header_value());

      nextBearerSource = shared;
      nextSharedRef = chosen;
    }

    final AdobeIntegrationCustomizers.Builder builder =
        AdobeIntegrationCustomizers.builder().bearer(nextBearerSource);

    if (config.set_api_key_header()) {
      final String apiKey = resolveApiKeyHeaderValue(config, nextSharedRef);
      if (apiKey.isBlank()) {
        if (nextSharedRef != null) {
          bundleContext.ungetService(nextSharedRef);
        }
        throw new ComponentException(
            "set.api.key.header is true but no client id is available for x-api-key "
                + "(set clientId on this integration or on the shared OAuth supplier).");
      }
      builder.apiKey(apiKey);
    }
    final String orgIdHeaderValue = config.org_id_header_value();
    if (orgIdHeaderValue != null && !orgIdHeaderValue.isBlank()) {
      builder.orgIdHeader(orgIdHeaderValue);
    }
    parseKeyValuePairs(config.additional_headers())
        .forEach(builder::additionalHeader);

    final HttpClientCustomizer nextCustomizer = builder.build();

    this.bearerSource = nextBearerSource;
    this.sharedCredentialRef = nextSharedRef;
    this.composedCustomizer = nextCustomizer;
  }

  private static void warnIfSharedModeIgnoresTokenFields(
      @NonNull final Config config,
      @NonNull final String label) {
    if (!nullToEmpty(config.clientSecret()).isBlank()) {
      log.warn(
          "{} '{}': clientSecret is set but credential.id is active; inline client secret is "
              + "ignored (token comes from the shared supplier).",
          AdobeIntegrationConfiguration.class.getSimpleName(),
          label);
    }
    if (!nullToEmpty(config.scopes()).isBlank()) {
      log.warn(
          "{} '{}': scopes is set but credential.id is active; inline scopes are ignored.",
          AdobeIntegrationConfiguration.class.getSimpleName(),
          label);
    }
    if (config.additional_token_params() != null && config.additional_token_params().length > 0) {
      log.warn(
          "{} '{}': additional.token.params is set but credential.id is active; "
              + "inline token params are ignored.",
          AdobeIntegrationConfiguration.class.getSimpleName(),
          label);
    }
  }

  private static String normalizeCredentialId(final String raw) {
    return raw == null ? "" : raw.trim();
  }

  private static String componentNameLabel(@NonNull final Map<String, Object> properties) {
    final Object raw = properties.get(ComponentConstants.COMPONENT_NAME);
    if (raw == null) {
      return "unknown";
    }
    final String s = raw.toString().trim();
    return s.isEmpty() ? "unknown" : s;
  }

  private static String nullToEmpty(final String s) {
    return s == null ? "" : s;
  }

  /**
   * LDAP filter for the generic OAuth supplier that exposes {@code credential.id}. The supplier
   * type property excludes this component's own {@link AccessTokenSupplier} registration.
   */
  static String sharedOAuthSupplierLdapFilter(@NonNull final String credentialId) {
    return "(&("
        + OsgiAccessTokenSupplierType.PROPERTY_NAME
        + "="
        + OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS
        + ")(credential.id="
        + escapeLdapAssertionValue(credentialId)
        + "))";
  }

  /**
   * Escapes {@code \ * ( )} for use inside an LDAP equality assertion value.
   */
  static String escapeLdapAssertionValue(@NonNull final String value) {
    final StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == '\\' || c == '*' || c == '(' || c == ')') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  private static ServiceReference<AccessTokenSupplier> selectHighestRanking(
      @NonNull final Collection<ServiceReference<AccessTokenSupplier>> refs) {
    final List<ServiceReference<AccessTokenSupplier>> list = new ArrayList<>(refs);
    if (list.isEmpty()) {
      throw new IllegalArgumentException("Cannot select from empty service reference collection");
    }
    list.sort(
        Comparator.comparingInt(
            ref -> {
              final Object v = ref.getProperty(Constants.SERVICE_RANKING);
              return v instanceof Integer ? (Integer) v : 0;
            }));
    return list.get(list.size() - 1);
  }

  private static String resolveApiKeyHeaderValue(
      @NonNull final Config config,
      final ServiceReference<AccessTokenSupplier> sharedRef) {
    final String fromConfig = nullToEmpty(config.clientId()).trim();
    if (!fromConfig.isEmpty()) {
      return fromConfig;
    }
    if (sharedRef != null) {
      final Object p = sharedRef.getProperty("clientId");
      if (p != null) {
        final String s = p.toString().trim();
        if (!s.isEmpty()) {
          return s;
        }
      }
    }
    return "";
  }

  @Deactivate
  protected final void deactivate(@NonNull final BundleContext bundleContext) {
    releaseSharedCredential(bundleContext);
    bearerSource = null;
    composedCustomizer = null;
  }

  private void releaseSharedCredential(final BundleContext bundleContext) {
    if (sharedCredentialRef != null && bundleContext != null) {
      bundleContext.ungetService(sharedCredentialRef);
    }
    sharedCredentialRef = null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Delegates to the inline {@link CachingTokenAcquirer} or the shared
   * {@link AccessTokenSupplier}, depending on configuration.
   */
  @Override
  public AccessToken getAccessToken() throws TokenUnavailableException {
    return bearerSource.getAccessToken();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Registers the composed Adobe pipeline (bearer auth, optional {@code x-api-key},
   * {@code x-gw-ims-org-id}, optional additional headers) on the builder.
   */
  @Override
  public void customize(final org.apache.http.impl.client.HttpClientBuilder builder) {
    composedCustomizer.customize(builder);
  }

  /**
   * Parses an array of {@code key=value} entries into an ordered map; entries that do not
   * contain {@code =} or whose key is blank are skipped with a warning.
   */
  private static Map<String, String> parseKeyValuePairs(final String[] entries) {
    final Map<String, String> parsed = new LinkedHashMap<>();
    if (entries == null) {
      return parsed;
    }
    for (final String raw : entries) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      final int eq = raw.indexOf('=');
      if (eq <= 0) {
        log.warn("Ignoring malformed key=value entry '{}' (expected 'name=value')", raw);
        continue;
      }
      final String key = raw.substring(0, eq).trim();
      final String value = raw.substring(eq + 1);
      if (!key.isEmpty()) {
        parsed.put(key, value);
      }
    }
    return parsed;
  }

  /**
   * OSGi Metatype: one factory configuration per Adobe integration.
   */
  @ObjectClassDefinition(
      name = "[HTTP] Adobe Integration Configuration",
      description = "One configuration per Adobe integration. Encapsulates OAuth client "
          + "credentials (inline or shared via credential.id), the curated Adobe gateway headers "
          + "(x-api-key, x-gw-ims-org-id), and optional escape-hatch fields for non-standard token "
          + "parameters or headers.")
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Config {

    /**
     * Default Adobe IMS token endpoint (OAuth 2.0); valid for all regions.
     */
    String DEFAULT_IMS_TOKEN_URL = "https://ims-na1.adobelogin.com/ims/token/v3";

    /**
     * @return logical credential identifier matching an {@code OAuthClientCredentialsTokenSupplier}
     *     {@code credential.id}; empty for inline OAuth on this configuration
     */
    @AttributeDefinition(
        name = "Credential ID (shared)",
        description = "When set, bearer tokens are obtained from the shared "
            + "OAuthClientCredentialsTokenSupplier registered with the same credential.id. "
            + "Inline clientSecret, scopes, tokenEndpointUrl, and additional.token.params are then "
            + "ignored for token acquisition. Leave empty for inline credentials on this configuration.",
        required = false)
    String credential_id() default "";

    /**
     * @return OAuth {@code client_id} for inline mode, or optional override for x-api-key when
     *     using shared credentials
     */
    @AttributeDefinition(
        name = "Client ID",
        description = "Adobe Developer Console OAuth Client ID (client_id). Required when "
            + "credential.id is empty. When credential.id is set, optional: used for x-api-key "
            + "only; if omitted, the client id is taken from the shared supplier registration.")
    String clientId() default "";

    /**
     * @return OAuth {@code client_secret} for inline mode only
     */
    @AttributeDefinition(
        name = "Client Secret",
        description = "Adobe Developer Console OAuth Client Secret (client_secret). Required "
            + "when credential.id is empty; ignored when credential.id references a shared supplier. "
            + "Use the AEMaaCS secret resolver (for example $[secret:my.secret]) to keep the actual "
            + "value out of source.",
        required = false)
    String clientSecret() default "";

    /**
     * @return comma-separated OAuth {@code scope} values; may be empty
     */
    @AttributeDefinition(
        name = "Scopes",
        description = "Comma-separated OAuth scopes (scope) for the token request. Adobe "
            + "Developer Console projects show the required scopes per credential. Used only "
            + "for inline OAuth when credential.id is empty.",
        required = false)
    String scopes() default "";

    /**
     * @return whether to set the {@code x-api-key} request header (using {@link #clientId()}
     * or the shared supplier's client id as the value)
     */
    @AttributeDefinition(
        name = "Set x-api-key Header",
        description = "If true, sets the x-api-key request header. When credential.id is empty, "
            + "the value is clientId. When credential.id is set, the value is clientId if set, "
            + "otherwise the clientId from the shared OAuth supplier configuration.")
    boolean set_api_key_header() default true;

    /**
     * @return value for the {@code x-gw-ims-org-id} request header; empty when not needed
     */
    @AttributeDefinition(
        name = "x-gw-ims-org-id Header Value",
        description = "Adobe IMS organization id (typically ending in '@AdobeOrg') sent as "
            + "the x-gw-ims-org-id request header. Leave empty for Adobe services that do "
            + "not require this header. Note: this is the request-header value only; it is "
            + "not sent to the OAuth token endpoint.",
        required = false)
    String org_id_header_value() default "";

    /**
     * @return token endpoint URL; override only for non-default tenants or testing
     */
    @AttributeDefinition(
        name = "Token Endpoint URL",
        description = "OAuth 2.0 token endpoint for inline mode only (ignored when credential.id "
            + "is set). The default points at Adobe IMS (valid for all regions).")
    String tokenEndpointUrl() default DEFAULT_IMS_TOKEN_URL;

    /**
     * @return additional form parameters for the token POST as {@code key=value} entries
     */
    @AttributeDefinition(
        name = "Additional Token Parameters (advanced)",
        description = "Extra form parameters sent with the OAuth token request, encoded as "
            + "'name=value' entries. Used only for inline OAuth when credential.id is empty.",
        cardinality = Integer.MAX_VALUE,
        required = false)
    String[] additional_token_params() default {};

    /**
     * @return additional static request headers as {@code name=value} entries
     */
    @AttributeDefinition(
        name = "Additional Request Headers (advanced)",
        description = "Extra static headers added to every outbound request, encoded as "
            + "'name=value' entries. Escape hatch for non-standard Adobe headers (for "
            + "example 'x-sandbox-name=prod'). The curated headers x-api-key and "
            + "x-gw-ims-org-id are configured via their dedicated fields above.",
        cardinality = Integer.MAX_VALUE,
        required = false)
    String[] additional_headers() default {};
  }
}
