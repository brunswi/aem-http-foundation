/*
 * Copyright (C) 2026 KTTN AEM Libraries
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.osgi.util.tracker.ServiceTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedHashMap;
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
 * the same {@code credential.id} service property (see Example 5 in {@code EXAMPLES.md}). Inline
 * {@link Config#clientId()}, {@link Config#clientSecret()}, {@link Config#scopes()},
 * {@link Config#tokenEndpointUrl()}, and {@link Config#additional_token_params()} are then
 * ignored for token acquisition. For {@code x-api-key}, if {@link Config#set_api_key_header()} is
 * true and {@link Config#clientId()} is left blank, the client ID is read from the shared
 * supplier's service properties.
 *
 * <h2>Startup ordering</h2>
 * The shared supplier is looked up via an internal {@link ServiceTracker} rather than an OSGi DS
 * {@code @Reference}. This means {@code activate()} always succeeds for shared-credential
 * configurations: the integration's services are registered immediately and downstream consumers
 * can bind to them without a startup race. If the matching supplier is not yet registered when
 * a request arrives, {@link #getAccessToken()} throws {@link TokenUnavailableException} (and the
 * {@code x-api-key} header is omitted) until the supplier appears; once it does, requests start
 * succeeding without rebuilding any HTTP client.
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
    immediate = true,
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

  // Inline mode: own acquirer; null in shared mode.
  private volatile CachingTokenAcquirer inlineAcquirer;

  // Shared mode: tracker for the specific supplier matching credential.id; null in inline mode.
  private volatile ServiceTracker<AccessTokenSupplier, AccessTokenSupplier> sharedSupplierTracker;

  private volatile HttpClientCustomizer composedCustomizer;

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
   */
  @Activate
  @Modified
  protected final void activate(
      @NonNull final Config config,
      @NonNull final BundleContext bundleContext,
      @NonNull final Map<String, Object> properties) {
    closeSharedSupplierTracker();
    inlineAcquirer = null;

    final String label = componentNameLabel(properties);
    final String credentialId = normalizeCredentialId(config.credential_id());

    if (credentialId.isEmpty()) {
      activateInlineMode(config, label);
    } else {
      activateSharedMode(config, bundleContext, credentialId, label);
    }
  }

  private void activateInlineMode(@NonNull final Config config, @NonNull final String label) {
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

    inlineAcquirer = new CachingTokenAcquirer(
        tokenClient,
        config.tokenEndpointUrl(),
        clientId,
        clientSecret,
        config.scopes(),
        parseKeyValuePairs(config.additional_token_params()),
        CachingTokenAcquirer.DEFAULT_REFRESH_LENIENCY_SECONDS,
        label);

    final AdobeIntegrationCustomizers.Builder builder =
        AdobeIntegrationCustomizers.builder().bearer(this);
    if (config.set_api_key_header()) {
      builder.apiKey(clientId);
    }
    addCommonHeaders(builder, config);
    composedCustomizer = builder.build();
  }

  private void activateSharedMode(
      @NonNull final Config config,
      @NonNull final BundleContext bundleContext,
      @NonNull final String credentialId,
      @NonNull final String label) {
    warnIfSharedModeIgnoresTokenFields(config, label);

    final String filter = "(&("
        + OsgiAccessTokenSupplierType.PROPERTY_NAME + "="
        + OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS + ")"
        + "(credential.id=" + credentialId + "))";
    final ServiceTracker<AccessTokenSupplier, AccessTokenSupplier> tracker;
    try {
      tracker = new ServiceTracker<>(
          bundleContext,
          bundleContext.createFilter(filter),
          null);
    } catch (InvalidSyntaxException e) {
      throw new ComponentException(
          "Malformed credential.id '" + credentialId + "': " + e.getMessage());
    }
    tracker.open();
    sharedSupplierTracker = tracker;

    if (tracker.getService() != null) {
      log.info(
          "{} '{}' activated (shared credential id='{}', orgIdHeaderValue='{}')",
          getClass().getSimpleName(),
          label,
          credentialId,
          config.org_id_header_value());
    } else {
      log.info(
          "{} '{}' activated (shared credential id='{}'; supplier not yet registered, will "
              + "attach when it appears)",
          getClass().getSimpleName(),
          label,
          credentialId);
    }

    final AdobeIntegrationCustomizers.Builder builder =
        AdobeIntegrationCustomizers.builder().bearer(this);
    if (config.set_api_key_header()) {
      final String configuredClientId = nullToEmpty(config.clientId()).trim();
      if (!configuredClientId.isEmpty()) {
        builder.apiKey(configuredClientId);
      } else {
        builder.apiKey(this::resolveApiKeyFromTracker);
      }
    }
    addCommonHeaders(builder, config);
    composedCustomizer = builder.build();
  }

  private void addCommonHeaders(
      @NonNull final AdobeIntegrationCustomizers.Builder builder,
      @NonNull final Config config) {
    final String orgIdHeaderValue = config.org_id_header_value();
    if (orgIdHeaderValue != null && !orgIdHeaderValue.isBlank()) {
      builder.orgIdHeader(orgIdHeaderValue);
    }
    parseKeyValuePairs(config.additional_headers())
        .forEach(builder::additionalHeader);
  }

  private String resolveApiKeyFromTracker() {
    final ServiceTracker<AccessTokenSupplier, AccessTokenSupplier> tracker = sharedSupplierTracker;
    if (tracker == null) {
      return "";
    }
    final ServiceReference<AccessTokenSupplier> ref = tracker.getServiceReference();
    if (ref == null) {
      return "";
    }
    final Object p = ref.getProperty("clientId");
    return p == null ? "" : p.toString().trim();
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
   * Intentionally a no-op.
   * <p>
   * {@link org.kttn.aem.http.HttpClientProvider} caches HTTP clients by key for the lifetime of
   * the foundation bundle. When this component is deactivated and re-created (e.g., after a
   * config-only redeploy), the cached client's bearer interceptor still holds the OLD instance
   * via {@code bearer(this)}. If we closed the {@code ServiceTracker} or nulled out the
   * acquirer here, that interceptor would throw {@code TokenUnavailableException} on every
   * request until the foundation bundle restarts.
   * <p>
   * Instead we leave the tracker open so the OLD instance keeps serving correct tokens via the
   * still-valid OSGi registry. The instance becomes garbage when the foundation bundle stops
   * and {@code HttpClientProvider}'s cache is cleared — at which point the tracker is GC'd along
   * with it.
   * <p>
   * For {@code @Modified} re-activation (same instance, config change) the cleanup happens at
   * the top of {@link #activate}: the previous tracker is closed and a fresh one is opened.
   */
  @Deactivate
  protected final void deactivate() {
    // Intentionally empty — see Javadoc.
  }

  private void closeSharedSupplierTracker() {
    final ServiceTracker<AccessTokenSupplier, AccessTokenSupplier> tracker = sharedSupplierTracker;
    if (tracker != null) {
      tracker.close();
      sharedSupplierTracker = null;
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * Delegates to the inline {@link CachingTokenAcquirer} or to the shared supplier tracked by
   * the internal {@link ServiceTracker}. Throws {@link TokenUnavailableException} if the shared
   * supplier is not (yet) registered.
   */
  @Override
  public AccessToken getAccessToken() throws TokenUnavailableException {
    final CachingTokenAcquirer inline = inlineAcquirer;
    if (inline != null) {
      return inline.getAccessToken();
    }
    final ServiceTracker<AccessTokenSupplier, AccessTokenSupplier> tracker = sharedSupplierTracker;
    if (tracker == null) {
      throw new TokenUnavailableException(
          "AdobeIntegrationConfiguration is not active.");
    }
    final AccessTokenSupplier supplier = tracker.getService();
    if (supplier == null) {
      throw new TokenUnavailableException(
          "No OAuthClientCredentialsTokenSupplier registered yet for this credential.id; "
              + "the request will succeed once the supplier appears in the OSGi registry.");
    }
    return supplier.getAccessToken();
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
