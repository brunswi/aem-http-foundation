package org.kttn.aem.http.auth.aio.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.kttn.aem.http.auth.aio.AccessToken;

/**
 * Jackson mapping for OAuth 2.0 token responses ({@code access_token}, {@code expires_in}).
 * Unknown JSON properties are ignored for forward compatibility.
 *
 * @see org.kttn.aem.http.auth.aio.OAuthTokenSupplier
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessTokenImpl implements AccessToken {

    private final String accessToken;
    private final long expiresIn;

    /**
     * @param accessToken token string from {@code access_token}
     * @param expiresIn   value from {@code expires_in} (issuer-specific unit; IMS uses seconds)
     */
    @JsonCreator
    public AccessTokenImpl(
        @JsonProperty("access_token") final String accessToken,
        @JsonProperty("expires_in") final long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @JsonProperty("access_token")
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @JsonProperty("expires_in")
    public long getExpiresIn() {
        return expiresIn;
    }

    /**
     * Redacts the bearer secret; keeps {@code expiresIn} for troubleshooting.
     *
     * @return string safe for logs
     */
    @Override
    public String toString() {
        return "AccessToken{ accessToken='**secret**', expiresIn=" + this.expiresIn + "}";
    }
}
