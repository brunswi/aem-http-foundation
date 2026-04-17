package org.kttn.aem.http.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.impl.client.DefaultServiceUnavailableRetryStrategy;
import org.apache.http.protocol.HttpContext;

/**
 * Wraps {@link DefaultServiceUnavailableRetryStrategy} with debug logging and a fast path for
 * HTTP 200 so successful responses are not delegated unnecessarily.
 *
 * @see <a href="https://github.com/apache/httpcomponents-client/blob/4.5.x/httpclient/src/main/java/org/apache/http/impl/client/DefaultServiceUnavailableRetryStrategy.java">DefaultServiceUnavailableRetryStrategy</a>
 */
@Slf4j
public class ServiceUnavailableRetryStrategy extends DefaultServiceUnavailableRetryStrategy {

    private final int maxRetries;

    /**
     * @param maxRetries     maximum 503 retries (same semantics as Apache base type)
     * @param retryInterval  pause in milliseconds between retries
     */
    public ServiceUnavailableRetryStrategy(final int maxRetries, final int retryInterval) {
        super(maxRetries, retryInterval);
        this.maxRetries = maxRetries;
    }

    /**
     * Skips retry when status is 200, caps attempts at {@link #maxRetries}, otherwise delegates to
     * {@link DefaultServiceUnavailableRetryStrategy#retryRequest(HttpResponse, int, HttpContext)}.
     *
     * @param response       last response
     * @param executionCount attempt count for this request
     * @param context        Apache context
     * @return {@code true} if another request should be issued
     */
    @Override
    public boolean retryRequest(final HttpResponse response, final int executionCount, final HttpContext context) {
        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_OK) {
            return false;
        }
        if (executionCount > this.maxRetries) {
            log.warn("Maximum 503 retries reached: executionCount={}, maxRetries={}, statusCode={}",
                executionCount, this.maxRetries, statusCode);
            return false;
        }
        final boolean retryRequest = super.retryRequest(response, executionCount, context);
        if (log.isDebugEnabled()) {
            log.debug("statusCode={}, retry={}, executionCount={}",
                response.getStatusLine().getStatusCode(), retryRequest, executionCount);
        }
        return retryRequest;
    }
}
