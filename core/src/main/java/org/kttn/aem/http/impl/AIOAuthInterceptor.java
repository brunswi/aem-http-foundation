package org.kttn.aem.http.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.aio.AccessToken;
import org.kttn.aem.http.auth.aio.OAuthTokenSupplier;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Apache {@link HttpRequestInterceptor} that adds Adobe IMS-oriented headers for outbound calls
 * (for example Adobe I/O Runtime with {@code require-adobe-auth}):
 * {@value #API_KEY_HEADER}, {@value #IMS_ORG_ID_HEADER}, and {@code Authorization: Bearer …}.
 * If the access token is missing, blank, or equals {@link OAuthTokenSupplier#PLACEHOLDER_ACCESS_TOKEN},
 * throws {@link BearerTokenUnavailableException} so the request is not sent (fail-fast). See that
 * type for interaction with {@link HttpRequestRetryHandler} and transient IMS recovery.
 * <p>
 * Tokens come from {@link OAuthTokenSupplier#getAccessToken()}. A cached token is reused until
 * shortly before {@link AccessToken#getExpiresIn()} (seconds, per OAuth 2.0) elapses; refresh is
 * synchronized to avoid stampedes. If the request already carries {@code Authorization}, this
 * interceptor does nothing.
 *
 * @see OAuthTokenSupplier
 */
@Slf4j
public class AIOAuthInterceptor
        implements HttpRequestInterceptor {

    /** Client ID sent as {@code x-api-key} for Adobe API gateways. */
    public static final String API_KEY_HEADER = "x-api-key";
    /** IMS organization id header required by Adobe IMS-secured endpoints. */
    public static final String IMS_ORG_ID_HEADER = "x-gw-ims-org-id";

    /** Message when {@link #hasBearerCredential(AccessToken)} is false after refresh. */
    public static final String MSG_NO_BEARER =
        "No valid Adobe IMS bearer token; request not sent.";

    /**
     * Refresh this many seconds before the OAuth {@code expires_in} instant so requests do not
     * run at the edge of expiry (same unit as {@link AccessToken#getExpiresIn()}).
     */
    private static final long TOKEN_REFRESH_LENIENCY_SECONDS = TimeUnit.MINUTES.toSeconds(5);

    private final OAuthTokenSupplier oAuthTokenSupplier;

    private volatile AccessToken accessToken;
    private volatile Instant expirationTime;

    /**
     * @param oAuthTokenSupplier source of client id, org id, and bearer tokens (typically OSGi)
     */
    public AIOAuthInterceptor(final OAuthTokenSupplier oAuthTokenSupplier) {
        this.oAuthTokenSupplier = oAuthTokenSupplier;
    }

    /**
     * Adds IMS headers when {@code Authorization} is absent; otherwise leaves the request unchanged.
     * Refreshes the cached token when missing or near expiry.
     *
     * @param request outbound request
     * @param context Apache context
     * @throws HttpException per Apache contract
     * @throws IOException   per Apache contract
     */
    @Override
    public void process(final HttpRequest request,
                        final HttpContext context)
            throws HttpException, IOException {
        if (!request.containsHeader(HttpHeaders.AUTHORIZATION)) {
            if (expirationTime == null || Instant.now().isAfter(expirationTime)) {
                refreshCachedToken();
            }
            if (!hasBearerCredential(accessToken)) {
                final BearerTokenUnavailableException ex = new BearerTokenUnavailableException(MSG_NO_BEARER);
                log.warn(ex.getMessage(), ex);
                throw ex;
            }
            request.setHeader(API_KEY_HEADER, oAuthTokenSupplier.getClientId());
            request.setHeader(IMS_ORG_ID_HEADER, oAuthTokenSupplier.getOrgId());
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.getAccessToken());
        }
    }

    /**
     * @return {@code true} if the token string is suitable for an {@code Authorization: Bearer} header
     */
    private static boolean hasBearerCredential(final AccessToken token) {
        if (token == null) {
            return false;
        }
        final String value = token.getAccessToken();
        if (value == null || value.isBlank()) {
            return false;
        }
        return !OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN.equals(value.trim());
    }

    /**
     * Fetches a new token from {@link OAuthTokenSupplier} and recomputes local expiry using
     * {@link AccessToken#getExpiresIn()} (seconds) minus {@link #TOKEN_REFRESH_LENIENCY_SECONDS}.
     */
    private synchronized void refreshCachedToken() {
        this.accessToken = oAuthTokenSupplier.getAccessToken();
        this.expirationTime = Instant.now()
            .plusSeconds(accessToken.getExpiresIn())
            .minusSeconds(TOKEN_REFRESH_LENIENCY_SECONDS);
        log.debug("Updated access token, expirationTime={}", expirationTime);
    }

}
