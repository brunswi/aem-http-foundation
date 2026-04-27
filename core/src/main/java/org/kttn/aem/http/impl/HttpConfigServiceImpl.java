package org.kttn.aem.http.impl;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.kttn.aem.http.HttpConfig;
import org.kttn.aem.http.HttpConfigService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * OSGi {@link HttpConfigService} backed by Metatype. Exposes timeouts, pool sizing, and retry
 * intervals for {@link HttpClientProviderImpl} and other consumers.
 */
@Slf4j
@Component(service = HttpConfigService.class,
    property = {Constants.SERVICE_DESCRIPTION
        + "=HTTP client configuration (timeouts, pool limits, retry policy) from OSGi Metatype"})
@Designate(ocd = HttpConfigServiceImpl.Config.class)
public class HttpConfigServiceImpl implements HttpConfigService {

    private HttpConfig httpConfig;

    /**
     * Reads Metatype into an immutable {@link HttpConfig} on activation or configuration change.
     *
     * @param config bound OSGi configuration
     */
    @Activate
    final void activate(@NonNull final Config config) {
        log.info("{} -> activate/modified", getClass().getSimpleName());
        readConfig(config);
    }

    /**
     * Maps {@link Config} attributes to {@link HttpConfig} constructor arguments.
     *
     * @param config current Metatype
     */
    private void readConfig(@NonNull final Config config) {
        this.httpConfig = HttpConfig.builder()
            .connectionTimeout(config.http_config_connectionTimeout())
            .connectionManagerTimeout(config.http_config_connectionManagerTimeout())
            .socketTimeout(config.http_config_socketTimeout())
            .maxConnection(config.http_config_maxConnection())
            .maxConnectionPerRoute(config.http_config_maxConnectionPerRoute())
            .serviceUnavailableMaxRetryCount(config.http_config_serviceUnavailableMaxRetryCount())
            .serviceUnavailableRetryInterval(config.http_config_serviceUnavailableRetryInterval())
            .ioExceptionMaxRetryCount(config.http_config_ioExceptionMaxRetryCount())
            .ioExceptionRetryInterval(config.http_config_ioExceptionRetryInterval())
            .build();
        log.debug("httpConfig: {}", this.httpConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpConfig getHttpConfig() {
        return httpConfig;
    }

    /** OSGi Metatype for HTTP client defaults (single configuration instance). */
    @ObjectClassDefinition(name = "[HTTP] HTTP Client Configuration")
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Config {

        int DEFAULT_CONNECTION_MANAGER_TIMEOUT = 10000;
        int DEFAULT_CONNECTION_TIMEOUT = 10000;
        int DEFAULT_IO_EXCEPTION_MAX_RETRY_COUNT = 3;
        int DEFAULT_IO_EXCEPTION_RETRY_INTERVAL = 1000;
        int DEFAULT_MAX_CONNECTIONS = 100;
        int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 20;
        int DEFAULT_SERVICE_UNAVAILABLE_MAX_RETRY_COUNT = 3;
        int DEFAULT_SERVICE_UNAVAILABLE_RETRY_INTERVAL = 1000;
        int DEFAULT_SOCKET_TIMEOUT = 10000;

        /** @return TCP connect timeout in milliseconds */
        @AttributeDefinition(name = "Connection Timeout",
            description = "Connection Timeout (in milliseconds)")
        int http_config_connectionTimeout() default DEFAULT_CONNECTION_TIMEOUT;

        /** @return wait when leasing a connection from the pool, in milliseconds */
        @AttributeDefinition(name = "Connection Manager Timeout",
            description = "Connection Manager Timeout (in milliseconds)")
        int http_config_connectionManagerTimeout() default DEFAULT_CONNECTION_MANAGER_TIMEOUT;

        /** @return socket read timeout in milliseconds ({@code 0} = infinite) */
        @AttributeDefinition(name = "Socket Timeout",
            description = "Determines the default socket timeout(in milliseconds) value for non-blocking I/O operations. 0 (no timeout)")
        int http_config_socketTimeout() default DEFAULT_SOCKET_TIMEOUT;

        /** @return maximum total pooled connections */
        @AttributeDefinition(name = "Max Connection",
            description = "Max Connection(count) for PoolingHttpClientConnectionManager")
        int http_config_maxConnection() default DEFAULT_MAX_CONNECTIONS;

        /** @return maximum pooled connections per HTTP route */
        @AttributeDefinition(name = "Max Connection per Route",
            description = "Max Connection per Route(count) for PoolingHttpClientConnectionManager")
        int http_config_maxConnectionPerRoute() default DEFAULT_MAX_CONNECTIONS_PER_ROUTE;

        /** @return max retries after HTTP 503 ({@code 0} = none) */
        @AttributeDefinition(name = "Maximum number of retries in case of 503 response",
            description = "How many times to retry. 0 means no retries")
        int http_config_serviceUnavailableMaxRetryCount() default DEFAULT_SERVICE_UNAVAILABLE_MAX_RETRY_COUNT;

        /** @return delay in milliseconds between 503 retries */
        @AttributeDefinition(name = "Retry interval in case of 503 response",
            description = "Interval between the subsequent retries in milliseconds")
        int http_config_serviceUnavailableRetryInterval() default DEFAULT_SERVICE_UNAVAILABLE_RETRY_INTERVAL;

        /** @return max retries after retriable {@link java.io.IOException} ({@code 0} = none) */
        @AttributeDefinition(name = "Maximum number of retries in case of java.io.IOException",
            description = "How many times to retry. 0 means no retries")
        int http_config_ioExceptionMaxRetryCount() default DEFAULT_IO_EXCEPTION_MAX_RETRY_COUNT;

        /** @return delay in milliseconds between I/O retries */
        @AttributeDefinition(name = "Retry interval in case of java.io.IOException",
            description = "Interval between the subsequent retries in milliseconds")
        int http_config_ioExceptionRetryInterval() default DEFAULT_IO_EXCEPTION_RETRY_INTERVAL;
    }
}
