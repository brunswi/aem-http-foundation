package org.kttn.aem.http.auth.aio;

/**
 * Supplies Adobe IMS (or compatible) OAuth credentials and {@link AccessToken} values for
 * outbound HTTP clients (for example Adobe I/O Runtime secured with {@code require-adobe-auth}).
 * <p>
 * Implementations typically perform a {@code client_credentials} grant against IMS and map the
 * JSON response to {@link AccessToken}. Caching is implementation-defined; callers may compose
 * interceptors that cache until expiry.
 *
 * @see AccessToken
 */
public interface OAuthTokenSupplier {

    /**
     * Adobe IMS organization id used in headers such as {@code x-gw-ims-org-id}.
     *
     * @return non-null org identifier when properly configured
     */
    String getOrgId();

    /**
     * OAuth {@code client_id}; for Adobe APIs often also sent as {@code x-api-key}.
     *
     * @return non-null client identifier when properly configured
     */
    String getClientId();

    /**
     * Returns a token response from the authorization server, normally via synchronous HTTP.
     * Implementations may return a sentinel token on failure; callers should validate the token
     * string and expiry before use in production flows.
     *
     * @return non-null {@link AccessToken} (possibly a failure placeholder per implementation)
     */
    AccessToken getAccessToken();
}
