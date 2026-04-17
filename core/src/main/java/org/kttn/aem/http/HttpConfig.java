package org.kttn.aem.http;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Immutable value object for Apache HttpClient connection limits, timeouts, proxy flag, and
 * retry behaviour. Time fields are expressed in <strong>milliseconds</strong>.
 * <p>
 * Populated from OSGi via {@link org.kttn.aem.http.impl.HttpConfigServiceImpl}, or built manually
 * when a caller needs overrides (for example extended timeouts for a single outbound integration)
 * while keeping pool sizing from the service.
 *
 * @see HttpConfigService
 */
@RequiredArgsConstructor
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

    /**
     * Whether outbound traffic should use the platform egress proxy. Interpreted by higher layers
     * or future {@link org.kttn.aem.http.impl.HttpClientProviderImpl} extensions; pool setup
     * currently does not read this flag.
     */
    @Getter
    @Accessors(fluent = true)
    private final boolean useProxy;

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
