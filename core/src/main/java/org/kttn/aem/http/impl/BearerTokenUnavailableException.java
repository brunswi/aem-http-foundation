package org.kttn.aem.http.impl;

import org.apache.http.client.ClientProtocolException;

/**
 * Thrown by {@link AIOAuthInterceptor} when no usable IMS bearer token is available before the
 * outbound request is sent. Extends {@link ClientProtocolException} so it participates in the
 * usual {@link java.io.IOException} handling around {@link org.apache.http.impl.client.CloseableHttpClient#execute(org.apache.http.client.methods.HttpUriRequest)}.
 * <p>
 * <strong>Retries:</strong> Listed as non-retriable in {@link HttpRequestRetryHandler} so transport
 * I/O retries are not applied to a precondition failure (missing bearer after token acquisition).
 */
public class BearerTokenUnavailableException extends ClientProtocolException {

    private static final long serialVersionUID = 1L;

    public BearerTokenUnavailableException(final String message) {
        super(message);
    }
}
