package org.kttn.aem.http.auth.bearer;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.HttpClientCustomizer;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;

import java.io.IOException;

/**
 * Sets {@code Authorization: Bearer &lt;access_token&gt;} on outbound requests using an
 * {@link AccessTokenSupplier}.
 * <p>
 * This is the generic, protocol-oriented half of the auth pipeline — it knows nothing about
 * Adobe IMS. Token acquisition, caching, and refresh are entirely the supplier's responsibility;
 * this customizer only consumes whatever {@link AccessTokenSupplier#getAccessToken()} returns.
 * <p>
 * Implements both {@link HttpClientCustomizer} and {@link HttpRequestInterceptor}: pass an
 * instance to {@code HttpClientProvider.provide(...)} as the customizer and it will register
 * itself as a {@code last} interceptor on the builder.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li><strong>Skip if already authenticated:</strong> if the request already carries an
 *       {@code Authorization} header, this customizer leaves it untouched.</li>
 *   <li><strong>Token unavailable:</strong> {@link TokenUnavailableException} from
 *       {@link AccessTokenSupplier#getAccessToken()} propagates as-is so the request is not
 *       sent.</li>
 *   <li><strong>Blank token:</strong> if the supplier returns a token whose value is
 *       {@code null} or blank, {@link BearerTokenUnavailableException} is thrown. Both exception
 *       types are non-retriable in
 *       {@link org.kttn.aem.http.impl.HttpRequestRetryHandler}.</li>
 * </ul>
 */
@Slf4j
public final class BearerTokenRequestCustomizer
    implements HttpClientCustomizer, HttpRequestInterceptor {

    /** Message when the supplier returned a token with a blank {@code access_token}. */
    public static final String MSG_NO_BEARER =
        "No usable bearer token available; request not sent.";

    private final AccessTokenSupplier accessTokenSupplier;

    /**
     * @param accessTokenSupplier non-null source of bearer tokens
     */
    public BearerTokenRequestCustomizer(final AccessTokenSupplier accessTokenSupplier) {
        this.accessTokenSupplier = accessTokenSupplier;
    }

    /**
     * Registers this instance as a {@code last} interceptor on the builder.
     */
    @Override
    public void customize(final HttpClientBuilder builder) {
        builder.addInterceptorLast(this);
    }

    /**
     * Adds the {@code Authorization: Bearer ...} header when missing.
     *
     * @throws IOException if {@link AccessTokenSupplier#getAccessToken()} fails or the supplier
     *                     returned an unusable (blank) token
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) throws IOException {
        if (request.containsHeader(HttpHeaders.AUTHORIZATION)) {
            return;
        }
        final AccessToken token = accessTokenSupplier.getAccessToken();
        if (!hasBearerCredential(token)) {
            final BearerTokenUnavailableException ex =
                new BearerTokenUnavailableException(MSG_NO_BEARER);
            log.warn(ex.getMessage(), ex);
            throw ex;
        }
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.getAccessToken());
    }

    /**
     * @return {@code true} if the token string is suitable for an {@code Authorization: Bearer}
     *         header (non-null, non-blank)
     */
    private static boolean hasBearerCredential(final AccessToken token) {
        if (token == null) {
            return false;
        }
        final String value = token.getAccessToken();
        return value != null && !value.isBlank();
    }
}
