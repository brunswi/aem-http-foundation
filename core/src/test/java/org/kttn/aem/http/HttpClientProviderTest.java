package org.kttn.aem.http;

import com.adobe.granite.keystore.KeyStoreService;
import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.utilities.HttpServerExtension;
import org.kttn.aem.utilities.JsonSuccessHandler;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HttpClientProviderTest {

    @RegisterExtension
    static final HttpServerExtension httpServerExtension = new HttpServerExtension();
    private final AemContext context = new AemContext();
    private final HttpClientProvider httpClientProvider = new HttpClientProviderImpl();
    private HttpConfigService httpConfigService = new HttpConfigServiceImpl();

    @BeforeEach
    protected void setUp() {
        context.registerInjectActivateService(httpConfigService);
        ResourceResolverFactory resolverFactory = mock(ResourceResolverFactory.class);
        context.registerService(ResourceResolverFactory.class, resolverFactory);
        KeyStoreService keyStoreService = mock(KeyStoreService.class);
        context.registerService(KeyStoreService.class, keyStoreService);
        context.registerInjectActivateService(httpClientProvider);
    }

    @Test
    void test() throws IOException, URISyntaxException {
        httpServerExtension.registerHandler("/test", new JsonSuccessHandler("{}"));
        final HttpGet httpGet = new HttpGet(HttpServerExtension.getUriFor("/test"));
        CloseableHttpResponse response = httpClientProvider.provideDefault().execute(httpGet);
        String content = IOUtils.toString(response.getEntity().getContent(), "utf-8");
        assertEquals("{}", content);
    }
}
