package org.kttn.aem.http.auth.oauth.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of OAuth 2.0 {@code client_credentials} token acquisition against a
 * configured token endpoint, with caching until shortly before expiry.
 * <p>
 * The class is {@code public} so it can be constructed from {@code auth.adobe.impl} as well as
 * from this package; the {@code .impl} package and OSGi non-exported packages still signal that
 * this is not a stable API for other bundles to depend on.
 * <p>
 * This is the <strong>single source of truth</strong> for token acquisition and caching logic in
 * this bundle. Both the OSGi factory component
 * {@link OAuthClientCredentialsTokenSupplier} and the Adobe integration entry point
 * ({@code AdobeIntegrationConfiguration}) delegate to instances of this class so the wire-level
 * contract, retry/expiry semantics, and JSON mapping are implemented exactly once.
 * <p>
 * Thread-safety: callers may invoke {@link #getAccessToken()} from many threads concurrently.
 * The cached token is refreshed under a private monitor using double-checked locking — the hot
 * path (cached token still valid) is unsynchronized.
 *
 * <h2>Token lifetime</h2>
 * Refreshes are scheduled {@link #DEFAULT_REFRESH_LENIENCY_SECONDS} seconds (by default) before
 * the issuer’s {@code expires_in} elapses. If {@code expires_in} is shorter than that lead time
 * (unusual for production IMS, possible for tests or a misbehaving issuer), the lead time is
 * clamped so the cache lifetime stays non-negative; see {@code refreshUnsafe}.
 */
@Slf4j
public final class CachingTokenAcquirer implements AccessTokenSupplier {

  /**
   * Default refresh leniency: refresh 5 minutes before {@code expires_in} elapses.
   */
  public static final long DEFAULT_REFRESH_LENIENCY_SECONDS = TimeUnit.MINUTES.toSeconds(5);

  /**
   * Maximum token lifetime accepted from the issuer (1 year). Larger values are clamped to
   * prevent arithmetic overflow or unreasonably long cache lifetimes.
   */
  private static final long MAX_TOKEN_LIFETIME_SECONDS = 365L * 24 * 60 * 60;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String DEFAULT_GRANT_TYPE = "client_credentials";
  private static final String PARAM_OAUTH2_GRANT_TYPE = "grant_type";
  private static final String PARAM_OAUTH2_CLIENT_ID = "client_id";
  private static final String PARAM_OAUTH2_CLIENT_SECRET = "client_secret";
  private static final String PARAM_OAUTH2_SCOPE = "scope";

  private final CloseableHttpClient httpClient;
  private final String tokenEndpointUrl;
  private final List<NameValuePair> tokenRequestParams;
  private final long refreshLeniencySeconds;
  private final String label;

  private volatile AccessToken cachedToken;
  private volatile Instant localExpiry;

  /**
   * @param httpClient             pooled client used <strong>only</strong> for token requests
   *                               (typically obtained under a reserved internal cache key)
   * @param tokenEndpointUrl       fully qualified token endpoint URL (for example
   *                               {@code https://ims-na1.adobelogin.com/ims/token/v3})
   * @param clientId               OAuth {@code client_id}
   * @param clientSecret           OAuth {@code client_secret}
   * @param scopes                 comma-separated OAuth {@code scope} values; pass empty string
   *                               or {@code null} to omit the {@code scope} parameter
   * @param additionalTokenParams  optional extra form parameters to send with the token POST
   *                               (escape hatch for issuer-specific extensions); {@code null}
   *                               or empty for none
   * @param refreshLeniencySeconds how many seconds before {@code expires_in} the token should
   *                               be refreshed; pass {@link #DEFAULT_REFRESH_LENIENCY_SECONDS}
   *                               for the default
   * @param label                  short human-readable label used in log lines (for example
   *                               {@code "Asset Compute"} or {@code "aep-prod"})
   */
  public CachingTokenAcquirer(
      final CloseableHttpClient httpClient,
      final String tokenEndpointUrl,
      final String clientId,
      final String clientSecret,
      final String scopes,
      final Map<String, String> additionalTokenParams,
      final long refreshLeniencySeconds,
      final String label) {
    this.httpClient = httpClient;
    this.tokenEndpointUrl = tokenEndpointUrl;
    this.tokenRequestParams = buildTokenRequestParams(
        clientId, clientSecret, scopes, additionalTokenParams);
    this.refreshLeniencySeconds = refreshLeniencySeconds;
    this.label = label;
  }

  /**
   * Returns a usable token, refreshing the cache when missing or near expiry.
   * <p>
   * Uses double-checked locking so the hot path (token still valid) does not synchronize. When
   * a refresh is required, only the first thread to enter the synchronized block performs the
   * IMS POST; later threads observe the freshly cached token.
   */
  @Override
  @SuppressWarnings("AEM Rules:AEM-15")
  public AccessToken getAccessToken() throws TokenUnavailableException {
    if (localExpiry == null || Instant.now().isAfter(localExpiry)) {
      synchronized (this) {
        if (localExpiry == null || Instant.now().isAfter(localExpiry)) {
          refreshUnsafe();
        }
      }
    }
    final AccessToken token = cachedToken;
    if (token == null) {
      throw new TokenUnavailableException(
          "Token acquisition failed; no cached token available for '" + label + "'");
    }
    return token;
  }

  /**
   * Performs the token POST and updates {@link #cachedToken} and {@link #localExpiry}.
   * <strong>Must be called while holding the monitor on {@code this}.</strong>
   */
  private void refreshUnsafe() throws TokenUnavailableException {
    if (tokenRequestParams.isEmpty()) {
      throw new TokenUnavailableException(
          "No OAuth credentials configured; cannot call token endpoint");
    }
    final HttpPost httpPost = new HttpPost(tokenEndpointUrl);
    httpPost.setEntity(new UrlEncodedFormEntity(tokenRequestParams, StandardCharsets.UTF_8));
    try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
      final int statusCode = response.getStatusLine().getStatusCode();
      final HttpEntity entity = response.getEntity();
      final String body = entity != null ? EntityUtils.toString(entity) : "";
      if (statusCode != HttpStatus.SC_OK) {
        throw new TokenUnavailableException(
            "OAuth token request failed for '" + label + "' with HTTP " + statusCode
                + ": " + body);
      }
      this.cachedToken = OBJECT_MAPPER.readValue(body, OAuthTokenResponse.class).toAccessToken();
    } catch (final IOException e) {
      throw new TokenUnavailableException(
          "OAuth token request failed for '" + label + "': " + e.getMessage(), e);
    }

    final long expiresIn = cachedToken.getExpiresInSeconds();
    // Cap expires_in to a reasonable maximum to prevent arithmetic overflow and unreasonably
    // long cache lifetimes from a malicious or buggy issuer.
    final long boundedExpiresIn = Math.min(expiresIn, MAX_TOKEN_LIFETIME_SECONDS);
    // "Refresh N seconds before expiry" cannot be larger than the token’s own window; otherwise
    // (expires_in - N) is negative. Clamp the lead to at most max(0, expires_in - 1).
    final long leadSeconds = Math.min(
        refreshLeniencySeconds, Math.max(0L, boundedExpiresIn - 1L));
    final long validSeconds = boundedExpiresIn - leadSeconds;
    this.localExpiry = validSeconds > 0
        ? Instant.now().plusSeconds(validSeconds)
        : Instant.EPOCH;

    if (expiresIn > MAX_TOKEN_LIFETIME_SECONDS) {
      log.warn(
          "OAuth token for '{}' has excessive expires_in={}s; capped to {}s",
          label, expiresIn, MAX_TOKEN_LIFETIME_SECONDS);
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Cached OAuth access token for '{}': next refresh in {}s ({}s from issuer, {}s lead, localExpiry={})",
          label, validSeconds, boundedExpiresIn, leadSeconds, localExpiry);
    }
  }

  /**
   * Builds the immutable list of form parameters posted to the token endpoint.
   */
  private static List<NameValuePair> buildTokenRequestParams(
      final String clientId,
      final String clientSecret,
      final String scopes,
      final Map<String, String> additionalTokenParams) {
    if (clientId == null || clientId.isBlank()
        || clientSecret == null || clientSecret.isBlank()) {
      return Collections.emptyList();
    }
    // LinkedHashMap preserves declaration order in logs; later additional params can override.
    final Map<String, String> merged = new LinkedHashMap<>();
    merged.put(PARAM_OAUTH2_GRANT_TYPE, DEFAULT_GRANT_TYPE);
    merged.put(PARAM_OAUTH2_CLIENT_ID, clientId);
    merged.put(PARAM_OAUTH2_CLIENT_SECRET, clientSecret);
    if (scopes != null && !scopes.isBlank()) {
      merged.put(PARAM_OAUTH2_SCOPE, scopes);
    }
    if (additionalTokenParams != null) {
      additionalTokenParams.forEach((k, v) -> {
        if (k != null && !k.isBlank() && v != null) {
          merged.put(k, v);
        }
      });
    }
    final List<NameValuePair> params = new ArrayList<>(merged.size());
    merged.forEach((k, v) -> params.add(new BasicNameValuePair(k, v)));
    return Collections.unmodifiableList(params);
  }

  /**
   * Test hook: forces the next {@link #getAccessToken()} call to refresh, regardless of any
   * previously cached token. Package-private; intended only for unit tests.
   * <p>
   * {@code synchronized} is used here to match the locking in {@link #getAccessToken()} so that
   * a test thread invalidating the cache is always visible to the next acquirer thread.
   */
  @SuppressWarnings("AEM Rules:AEM-15")
  synchronized void invalidateCacheForTest() {
    this.cachedToken = null;
    this.localExpiry = null;
  }
}
