package org.kttn.aem.http.auth.bearer;

import org.apache.http.client.ClientProtocolException;

/**
 * Thrown by {@link BearerTokenRequestCustomizer} when no usable bearer token is available before
 * an outbound request is sent — for example when the underlying
 * {@link org.kttn.aem.http.auth.oauth.AccessTokenSupplier} returned a token whose
 * {@code access_token} string is {@code null} or blank.
 * <p>
 * Extends {@link ClientProtocolException} so it participates in the usual
 * {@link java.io.IOException} handling around
 * {@link org.apache.http.impl.client.CloseableHttpClient#execute(org.apache.http.client.methods.HttpUriRequest)}.
 * <p>
 * <strong>Retries:</strong> Listed as non-retriable in
 * {@link org.kttn.aem.http.impl.HttpRequestRetryHandler} so transport I/O retries are not applied
 * to a precondition failure (missing bearer after token acquisition).
 */
public class BearerTokenUnavailableException extends ClientProtocolException {

    private static final long serialVersionUID = 1L;

    public BearerTokenUnavailableException(final String message) {
        super(message);
    }
}
