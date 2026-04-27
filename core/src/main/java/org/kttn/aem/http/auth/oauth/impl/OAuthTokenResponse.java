package org.kttn.aem.http.auth.oauth.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.kttn.aem.http.auth.oauth.AccessToken;

/**
 * Internal Jackson DTO for OAuth 2.0 token responses (RFC 6749 fields {@code access_token} and
 * {@code expires_in}). Unknown JSON properties are ignored for forward compatibility with
 * non-standard issuer extensions.
 * <p>
 * This type is package-private by intent: it represents the wire format and must not leak into
 * the public API. The {@link #toAccessToken()} method converts it to the immutable public value
 * type {@link AccessToken}.
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
final class OAuthTokenResponse {

    private final String accessToken;
    private final long expiresIn;

    /**
     * @param accessToken token string from {@code access_token}
     * @param expiresIn   value from {@code expires_in} (issuer-specific unit; IMS uses seconds)
     */
    @JsonCreator
    OAuthTokenResponse(
        @JsonProperty("access_token") final String accessToken,
        @JsonProperty("expires_in") final long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }

    /**
     * @return immutable public-API value object that mirrors the wire response
     */
    AccessToken toAccessToken() {
        return new AccessToken(accessToken, expiresIn);
    }
}
