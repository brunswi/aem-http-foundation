package org.kttn.aem.http.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.bearer.BearerTokenUnavailableException;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Extends {@link DefaultHttpRequestRetryHandler} with optional backoff between attempts and
 * retries for {@link java.net.ConnectException}, which the stock handler does not retry.
 * <p>
 * Several exception types remain non-retriable (see constructor); align changes with Apache’s
 * {@link DefaultHttpRequestRetryHandler} behaviour. {@link BearerTokenUnavailableException} is
 * included so transport-layer retries are not wasted on auth preconditions (no bearer after
 * {@link org.kttn.aem.http.auth.oauth.AccessTokenSupplier#getAccessToken()}).
 *
 * @see <a href="https://github.com/apache/httpcomponents-client/blob/4.5.x/httpclient/src/main/java/org/apache/http/impl/client/DefaultHttpRequestRetryHandler.java">DefaultHttpRequestRetryHandler</a>
 */
@Slf4j
public class HttpRequestRetryHandler extends DefaultHttpRequestRetryHandler {

    /** Default pause between I/O retries (ms); Apache’s base handler uses 0. */
    public static final int DEFAULT_RETRY_INTERVAL = 0;

    /**
     * Default passed to {@link DefaultHttpRequestRetryHandler} for “retry after send” on
     * non-idempotent requests; {@code true} enables that path for this project’s handlers.
     */
    public static final boolean PV_RETRY_NON_IDEMPOTENT_REQUESTS = true;

    private final long retryInterval;
    private final int maxRetries;

    /**
     * Same as {@link #HttpRequestRetryHandler(int, long, boolean)} with
     * {@link #PV_RETRY_NON_IDEMPOTENT_REQUESTS} for the third argument.
     *
     * @param maxRetries    maximum attempts after the first failure; {@code 0} disables retries
     * @param retryInterval milliseconds to sleep before retry 2+, exclusive of Apache’s own logic
     */
    public HttpRequestRetryHandler(final int maxRetries, final long retryInterval) {
        this(maxRetries, retryInterval, PV_RETRY_NON_IDEMPOTENT_REQUESTS);
    }

    /**
     * Two-argument constructor using {@link #DEFAULT_RETRY_INTERVAL} (no sleep between attempts).
     *
     * @param maxRetries              maximum attempts after the first failure; {@code 0} disables
     * @param requestSentRetryEnabled forwarded to {@link DefaultHttpRequestRetryHandler}
     */
    public HttpRequestRetryHandler(final int maxRetries, final boolean requestSentRetryEnabled) {
        this(maxRetries, DEFAULT_RETRY_INTERVAL, requestSentRetryEnabled);
    }

    /**
     * Configures non-retriable exception types (parent constructor) and stores backoff settings.
     * Unlike {@link DefaultHttpRequestRetryHandler} alone, {@link java.net.ConnectException} is
     * not listed as non-retriable and may be retried.
     *
     * @param maxRetries              maximum attempts after the first failure; {@code 0} disables
     * @param retryInterval           sleep in ms before the second and later retries ({@code >0});
     *                                first failure is retried without delay here
     * @param requestSentRetryEnabled whether to allow retries when the request may already have
     *                                been sent on the wire
     */
    public HttpRequestRetryHandler(final int maxRetries, final long retryInterval, final boolean requestSentRetryEnabled) {
        super(maxRetries, requestSentRetryEnabled, Arrays.asList(
            InterruptedIOException.class,
            UnknownHostException.class,
            NoRouteToHostException.class,
            SSLException.class,
            SocketTimeoutException.class,
            BearerTokenUnavailableException.class,
            TokenUnavailableException.class));
        this.maxRetries = maxRetries;
        this.retryInterval = retryInterval;
    }

    /**
     * Enforces {@link #maxRetries}, applies optional {@link #retryInterval} for attempts after
     * the first, then delegates to the parent to classify the {@link IOException}.
     * If backoff sleep is interrupted, {@linkplain Thread#interrupt() the interrupt status is restored}
     * and {@code false} is returned.
     *
     * @param exception      failure from the last execution
     * @param executionCount 1-based attempt count for this request
     * @param context        Apache execution context
     * @return {@code true} if another attempt should be made
     */
    @Override
    @SuppressWarnings("CQRules:GRANITE-54181") // Restoring interrupt status is correct practice when catching InterruptedException
    public boolean retryRequest(final IOException exception, final int executionCount, final HttpContext context) {
        if (executionCount > this.maxRetries) {
            log.warn("Maximum I/O retries reached: executionCount={}, maxRetries={}, exception={}",
                executionCount, this.maxRetries, exception.getClass().getName());
            return false;
        }
        if (executionCount > 1 && retryInterval > 0) {
            try {
                Thread.sleep(retryInterval);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("I/O retry backoff interrupted; not retrying");
                return false;
            }
        }
        final boolean retryRequest = super.retryRequest(exception, executionCount, context);
        if (log.isDebugEnabled()) {
            log.debug("exception={}, retry={}, executionCount={}", exception.getClass().getName(), retryRequest, executionCount);
        }
        return retryRequest;
    }
}
