package org.kttn.aem.http.auth.oauth;

import org.apache.http.client.ClientProtocolException;

/**
 * Thrown by {@link AccessTokenSupplier#getAccessToken()} when no usable bearer token can be
 * obtained from the authorization server: non-2xx token response after the supplier's own retry
 * budget, malformed body, or unrecoverable I/O failure.
 * <p>
 * Extends {@link ClientProtocolException} (an {@link java.io.IOException}) so it propagates
 * through {@link org.apache.http.HttpRequestInterceptor#process} and Apache HttpClient
 * execute paths without wrapping. It is listed as non-retriable in
 * {@link org.kttn.aem.http.impl.HttpRequestRetryHandler} so the outer client does not retry an
 * authentication precondition failure on top of the supplier's already-completed retries.
 */
public class TokenUnavailableException extends ClientProtocolException {

    private static final long serialVersionUID = 1L;

    public TokenUnavailableException(final String message) {
        super(message);
    }

    public TokenUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
