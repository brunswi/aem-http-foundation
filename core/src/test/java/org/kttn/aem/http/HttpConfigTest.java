package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HttpConfig} builder, toBuilder, and integration with {@link HttpConfigService}.
 */
@ExtendWith(AemContextExtension.class)
@ExtendWith(MockitoExtension.class)
public class HttpConfigTest {

    private final AemContext context = new AemContext();
    private HttpConfigService httpConfigService;

    @BeforeEach
    protected void setUp() {
        httpConfigService = context.registerInjectActivateService(new HttpConfigServiceImpl());
    }

    @Test
    void shouldGetHttpConfigFromService() {
        HttpConfig httpConfig = httpConfigService.getHttpConfig();
        assertNotNull(httpConfig);
    }

    @Test
    void shouldUseDefaultValuesWhenNoConfigurationProvided() {
        HttpConfig config = httpConfigService.getHttpConfig();

        // Verify defaults from HttpConfigServiceImpl.Config
        assertEquals(10000, config.getConnectionTimeout());
        assertEquals(10000, config.getConnectionManagerTimeout());
        assertEquals(10000, config.getSocketTimeout());
        assertEquals(100, config.getMaxConnection());
        assertEquals(20, config.getMaxConnectionPerRoute());
        assertEquals(3, config.getServiceUnavailableMaxRetryCount());
        assertEquals(1000, config.getServiceUnavailableRetryInterval());
        assertEquals(3, config.getIoExceptionMaxRetryCount());
        assertEquals(1000, config.getIoExceptionRetryInterval());
    }

    @Test
    void shouldApplyCustomConfiguration() {
        // Recreate service with custom config
        httpConfigService = context.registerInjectActivateService(
            new HttpConfigServiceImpl(),
            Map.of(
                "http.config.connectionTimeout", 5000,
                "http.config.socketTimeout", 15000,
                "http.config.maxConnection", 200,
                "http.config.maxConnectionPerRoute", 50
            )
        );

        HttpConfig config = httpConfigService.getHttpConfig();
        assertEquals(5000, config.getConnectionTimeout());
        assertEquals(15000, config.getSocketTimeout());
        assertEquals(200, config.getMaxConnection());
        assertEquals(50, config.getMaxConnectionPerRoute());
    }

    @Test
    void shouldBuildHttpConfigUsingBuilder() {
        HttpConfig config = HttpConfig.builder()
            .connectionTimeout(2000)
            .connectionManagerTimeout(3000)
            .socketTimeout(4000)
            .maxConnection(150)
            .maxConnectionPerRoute(30)
            .serviceUnavailableMaxRetryCount(5)
            .serviceUnavailableRetryInterval(2000)
            .ioExceptionMaxRetryCount(4)
            .ioExceptionRetryInterval(1500)
            .build();

        assertNotNull(config);
        assertEquals(2000, config.getConnectionTimeout());
        assertEquals(3000, config.getConnectionManagerTimeout());
        assertEquals(4000, config.getSocketTimeout());
        assertEquals(150, config.getMaxConnection());
        assertEquals(30, config.getMaxConnectionPerRoute());
        assertEquals(5, config.getServiceUnavailableMaxRetryCount());
        assertEquals(2000, config.getServiceUnavailableRetryInterval());
        assertEquals(4, config.getIoExceptionMaxRetryCount());
        assertEquals(1500, config.getIoExceptionRetryInterval());
    }

    @Test
    void shouldSupportToBuilderPattern() {
        HttpConfig original = HttpConfig.builder()
            .connectionTimeout(1000)
            .connectionManagerTimeout(2000)
            .socketTimeout(3000)
            .maxConnection(100)
            .maxConnectionPerRoute(20)
            .serviceUnavailableMaxRetryCount(3)
            .serviceUnavailableRetryInterval(1000)
            .ioExceptionMaxRetryCount(3)
            .ioExceptionRetryInterval(1000)
            .build();

        HttpConfig modified = original.toBuilder()
            .socketTimeout(120000) // Extend socket timeout for long-running operations
            .build();

        // Original values preserved except for the modified field
        assertEquals(1000, modified.getConnectionTimeout());
        assertEquals(2000, modified.getConnectionManagerTimeout());
        assertEquals(120000, modified.getSocketTimeout()); // Modified
        assertEquals(100, modified.getMaxConnection());
        assertEquals(20, modified.getMaxConnectionPerRoute());
        assertEquals(3, modified.getServiceUnavailableMaxRetryCount());
        assertEquals(1000, modified.getServiceUnavailableRetryInterval());
        assertEquals(3, modified.getIoExceptionMaxRetryCount());
        assertEquals(1000, modified.getIoExceptionRetryInterval());
    }

    @Test
    void shouldCreateIndependentCopyWithToBuilder() {
        HttpConfig original = HttpConfig.builder()
            .connectionTimeout(1000)
            .socketTimeout(2000)
            .maxConnection(50)
            .maxConnectionPerRoute(10)
            .serviceUnavailableMaxRetryCount(2)
            .serviceUnavailableRetryInterval(500)
            .ioExceptionMaxRetryCount(2)
            .ioExceptionRetryInterval(500)
            .connectionManagerTimeout(1000)
            .build();

        HttpConfig copy = original.toBuilder().build();

        // Verify all values are copied
        assertEquals(original.getConnectionTimeout(), copy.getConnectionTimeout());
        assertEquals(original.getSocketTimeout(), copy.getSocketTimeout());
        assertEquals(original.getMaxConnection(), copy.getMaxConnection());
        assertEquals(original.getMaxConnectionPerRoute(), copy.getMaxConnectionPerRoute());
    }

    @Test
    void shouldProduceReadableToString() {
        HttpConfig config = HttpConfig.builder()
            .connectionTimeout(5000)
            .connectionManagerTimeout(6000)
            .socketTimeout(7000)
            .maxConnection(100)
            .maxConnectionPerRoute(20)
            .serviceUnavailableMaxRetryCount(3)
            .serviceUnavailableRetryInterval(1000)
            .ioExceptionMaxRetryCount(3)
            .ioExceptionRetryInterval(1000)
            .build();

        String toString = config.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("connectionTimeout=5000"));
        assertTrue(toString.contains("socketTimeout=7000"));
        assertTrue(toString.contains("maxConnection=100"));
    }

    @Test
    void shouldAllowZeroRetries() {
        HttpConfig config = HttpConfig.builder()
            .connectionTimeout(1000)
            .connectionManagerTimeout(1000)
            .socketTimeout(1000)
            .maxConnection(10)
            .maxConnectionPerRoute(5)
            .serviceUnavailableMaxRetryCount(0) // No 503 retries
            .serviceUnavailableRetryInterval(0)
            .ioExceptionMaxRetryCount(0) // No I/O retries
            .ioExceptionRetryInterval(0)
            .build();

        assertEquals(0, config.getServiceUnavailableMaxRetryCount());
        assertEquals(0, config.getIoExceptionMaxRetryCount());
    }
}
