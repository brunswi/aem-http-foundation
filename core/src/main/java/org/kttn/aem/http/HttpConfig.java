package org.kttn.aem.http;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
/**
 * Immutable value object for Apache HttpClient connection limits, timeouts, and retry behaviour.
 * Time fields are expressed in <strong>milliseconds</strong>.
 * <p>
 * Populated from OSGi via {@link org.kttn.aem.http.impl.HttpConfigServiceImpl}, or built with
 * {@link #toBuilder()} when a caller needs per-integration overrides (for example extended
 * timeouts) while keeping pool sizing and retry settings from the service defaults.
 *
 * <pre>{@code
 * HttpConfig extended = httpConfigService.getHttpConfig().toBuilder()
 *     .socketTimeout(120_000)
 *     .build();
 * }</pre>
 *
 * @see HttpConfigService
 */
@Builder(toBuilder = true)
@ToString
public class HttpConfig {

    /** Milliseconds to wait for a TCP connection to the remote host. */
    @Getter
    private final int connectionTimeout;

    /** Milliseconds to wait when borrowing a connection from the pool ({@code ConnectionRequestTimeout}). */
    @Getter
    private final int connectionManagerTimeout;

    /** Milliseconds for socket read/write blocking operations ({@code SO_TIMEOUT}). */
    @Getter
    private final int socketTimeout;

    /** Upper bound on total concurrent connections in the pool. */
    @Getter
    private final int maxConnection;

    /** Upper bound on concurrent connections per HTTP route (host/scheme). */
    @Getter
    private final int maxConnectionPerRoute;

    /** Maximum number of retries after HTTP 503; {@code 0} disables this retry path. */
    @Getter
    private final int serviceUnavailableMaxRetryCount;

    /** Delay in milliseconds between 503 retries (after the first attempt). */
    @Getter
    private final int serviceUnavailableRetryInterval;

    /** Maximum number of retries after I/O failures eligible for retry; {@code 0} disables. */
    @Getter
    private final int ioExceptionMaxRetryCount;

    /** Delay in milliseconds between I/O retries (after the first attempt). */
    @Getter
    private final int ioExceptionRetryInterval;
}
