package org.kttn.aem.http;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(AemContextExtension.class)
@ExtendWith(MockitoExtension.class)
public class HttpConfigTest {

    private final AemContext context = new AemContext();
    private HttpConfigService httpConfigService = new HttpConfigServiceImpl();

    @BeforeEach
    protected void setUp() {
        context.registerInjectActivateService(httpConfigService);

    }

    @Test
    protected void test() throws IOException {
        HttpConfig httpConfig = httpConfigService.getHttpConfig();
        assertNotNull(httpConfig);
    }

}
