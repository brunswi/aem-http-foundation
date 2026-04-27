package org.kttn.aem.http.auth.oauth;

import java.util.Objects;

/**
 * Immutable value object representing an OAuth 2.0 access token plus its declared lifetime.
 * <p>
 * Property semantics follow RFC 6749:
 * <ul>
 *   <li>{@code access_token} — the bearer secret (without the {@code Bearer} prefix).</li>
 *   <li>{@code expires_in}   — token lifetime as reported by the authorization server.
 *       For Adobe IMS this is conventionally <strong>seconds</strong>; always confirm against
 *       the supplier contract.</li>
 * </ul>
 * <p>
 * This type is intentionally minimal and decoupled from any wire format: the JSON shape returned
 * by the authorization server is mapped via the internal Jackson DTO
 * {@code OAuthTokenResponse} and then converted into an {@code AccessToken}. Public API consumers
 * only see this immutable value type.
 *
 * @see AccessTokenSupplier
 */
public final class AccessToken {

    private final String accessToken;
    private final long expiresInSeconds;

    /**
     * @param accessToken      bearer secret from {@code access_token}; may be {@code null} or
     *                         blank to represent an "unusable token" returned by a misbehaving
     *                         issuer (callers are expected to validate before use)
     * @param expiresInSeconds lifetime declared by the issuer; for Adobe IMS this is seconds
     */
    public AccessToken(final String accessToken, final long expiresInSeconds) {
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    /**
     * Raw bearer secret for an {@code Authorization: Bearer} header (without the {@code Bearer}
     * prefix).
     *
     * @return token string from the authorization server; may be blank when the issuer responded
     *         successfully but with an unusable token (callers should validate)
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Lifetime from the {@code expires_in} field. For Adobe IMS this is
     * <strong>seconds</strong>; consult the supplier contract for non-Adobe issuers.
     *
     * @return lifetime in the issuer's documented unit (often seconds)
     */
    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    /**
     * Redacts the bearer secret; keeps {@code expiresInSeconds} for troubleshooting.
     *
     * @return string safe for log output
     */
    @Override
    public String toString() {
        return "AccessToken{accessToken='**secret**', expiresInSeconds=" + expiresInSeconds + "}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccessToken)) {
            return false;
        }
        final AccessToken that = (AccessToken) o;
        return expiresInSeconds == that.expiresInSeconds
            && Objects.equals(accessToken, that.accessToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, expiresInSeconds);
    }
}
