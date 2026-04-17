package org.kttn.aem.http.auth.aio;

/**
 * OAuth 2.0 access token payload from a token response (for example Adobe IMS
 * {@code client_credentials} JSON).
 * <p>
 * Property names follow RFC 6749: {@code access_token}, {@code expires_in}.
 *
 * @see OAuthTokenSupplier
 */
public interface AccessToken {

    /**
     * Raw bearer secret for an {@code Authorization: Bearer} header (without the {@code Bearer} prefix).
     *
     * @return token string from the authorization server
     */
    String getAccessToken();

    /**
     * Lifetime from the {@code expires_in} field. For Adobe IMS this is conventionally
     * <strong>seconds</strong>; always confirm against your {@link OAuthTokenSupplier} contract.
     *
     * @return lifetime in the issuer’s documented unit (often seconds)
     */
    long getExpiresIn();
}
