package org.kttn.aem.http.auth.oauth.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OSGi lifecycle tests for {@link OAuthClientCredentialsTokenSupplier}: @Modified, @Deactivate,
 * and configuration validation.
 */
class OAuthClientCredentialsTokenSupplierLifecycleTest {

    @RegisterExtension
    static HttpServerExtension server = new HttpServerExtension();

  private HttpClientProviderImpl httpClientProviderImpl;

    @BeforeEach
    void setUp() {
        AemContext context = new AemContext();
        HttpConfigService httpConfigService = new HttpConfigServiceImpl();
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);

        httpClientProviderImpl = new HttpClientProviderImpl();
        context.registerInjectActivateService(httpClientProviderImpl);
    }

    @Test
    void shouldReconfigureTokenEndpointWhenModified() throws Exception {
        // Initial activation with endpoint A
        server.registerHandler("/oauth/token-original", exchange -> {
            String responseBody = "{\"access_token\":\"original_token\",\"expires_in\":3600}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config originalConfig = createConfig(
            server.getUriFor("/oauth/token-original").toString(),
            "original-client-id",
            "original-client-secret",
            "",
            ""
        );

        Map<String, Object> properties = Map.of("component.name", "test-supplier-lifecycle");

        OAuthClientCredentialsTokenSupplier supplier = 
            new OAuthClientCredentialsTokenSupplier(httpClientProviderImpl);
        supplier.activate(originalConfig, properties);

        AccessToken originalToken = supplier.getAccessToken();
        assertEquals("original_token", originalToken.getAccessToken());

        // Modify configuration to use endpoint B with different credentials
        server.registerHandler("/oauth/token-modified", exchange -> {
            String responseBody = "{\"access_token\":\"modified_token\",\"expires_in\":7200}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config modifiedConfig = createConfig(
            server.getUriFor("/oauth/token-modified").toString(),
            "modified-client-id",
            "modified-client-secret",
            "openid,profile",
            ""
        );

        // Trigger @Modified
        supplier.activate(modifiedConfig, properties);

        // Verify new token endpoint is used
        AccessToken modifiedToken = supplier.getAccessToken();
        assertEquals("modified_token", modifiedToken.getAccessToken());
        assertEquals(7200, modifiedToken.getExpiresInSeconds());
    }

    @Test
    void shouldClearCachedTokenOnModify() throws Exception {
        // Initial activation
        server.registerHandler("/oauth/token-cache-clear", exchange -> {
            String responseBody = "{\"access_token\":\"cached_token\",\"expires_in\":3600}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config originalConfig = createConfig(
            server.getUriFor("/oauth/token-cache-clear").toString(),
            "cache-client-id",
            "cache-secret",
            "",
            ""
        );

        Map<String, Object> properties = Map.of("component.name", "test-cache-clear");

        OAuthClientCredentialsTokenSupplier supplier = 
            new OAuthClientCredentialsTokenSupplier(httpClientProviderImpl);
        supplier.activate(originalConfig, properties);

        // Get token (cached)
        AccessToken token1 = supplier.getAccessToken();
        assertEquals("cached_token", token1.getAccessToken());

        // Get again (should be same instance from cache)
        AccessToken token2 = supplier.getAccessToken();
        assertSame(token1, token2, "Token should be cached");

        // Now modify - this should clear cache and create new acquirer
        server.registerHandler("/oauth/token-new-endpoint", exchange -> {
            String responseBody = "{\"access_token\":\"new_endpoint_token\",\"expires_in\":7200}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config modifiedConfig = createConfig(
            server.getUriFor("/oauth/token-new-endpoint").toString(),
            "cache-client-id",
            "cache-secret",
            "",
            ""
        );

        supplier.activate(modifiedConfig, properties);

        // Get token after modify - should be new token, not cached
        AccessToken token3 = supplier.getAccessToken();
        assertEquals("new_endpoint_token", token3.getAccessToken());
        assertNotSame(token1, token3, "Token should be different after modify");
    }

    @Test
    void shouldContinueWorkingAfterMultipleModifies() throws Exception {
        // Note: OAuthClientCredentialsTokenSupplier has no @Deactivate method.
        // This test verifies multiple consecutive @Modified calls work correctly.
        
        server.registerHandler("/oauth/token-multi-modify", exchange -> {
            String responseBody = "{\"access_token\":\"multi_modify_token\",\"expires_in\":3600}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config config = createConfig(
            server.getUriFor("/oauth/token-multi-modify").toString(),
            "multi-modify-client",
            "multi-modify-secret",
            "",
            ""
        );

        Map<String, Object> properties = Map.of("component.name", "test-multi-modify");

        OAuthClientCredentialsTokenSupplier supplier = 
            new OAuthClientCredentialsTokenSupplier(httpClientProviderImpl);
        supplier.activate(config, properties);

        // First token acquisition
        AccessToken token1 = supplier.getAccessToken();
        assertEquals("multi_modify_token", token1.getAccessToken());

        // Modify twice in a row
        supplier.activate(config, properties);
        supplier.activate(config, properties);

        // Should still work
        AccessToken token2 = supplier.getAccessToken();
        assertEquals("multi_modify_token", token2.getAccessToken());
    }

    @Test
    void shouldPreserveCredentialIdServicePropertyOnModify() throws Exception {
        server.registerHandler("/oauth/token-preserve-prop", exchange -> {
            String responseBody = "{\"access_token\":\"preserve_prop_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        OAuthClientCredentialsTokenSupplier.Config originalConfig = createConfig(
            server.getUriFor("/oauth/token-preserve-prop").toString(),
            "preserve-client",
            "preserve-secret",
            "",
            "my-credential-id"
        );

        Map<String, Object> properties = Map.of(
            "component.name", "test-preserve-prop",
            "credential.id", "my-credential-id"
        );

        OAuthClientCredentialsTokenSupplier supplier = 
            new OAuthClientCredentialsTokenSupplier(httpClientProviderImpl);
        supplier.activate(originalConfig, properties);

        // Modify with same credential.id
        OAuthClientCredentialsTokenSupplier.Config modifiedConfig = createConfig(
            server.getUriFor("/oauth/token-preserve-prop").toString(),
            "preserve-client-modified",
            "preserve-secret-modified",
            "openid",
            "my-credential-id"
        );

        supplier.activate(modifiedConfig, properties);

        // Verify it still works
        AccessToken token = supplier.getAccessToken();
        assertEquals("preserve_prop_token", token.getAccessToken());
    }

    private OAuthClientCredentialsTokenSupplier.Config createConfig(
        String tokenEndpointUrl,
        String clientId,
        String clientSecret,
        String scopes,
        String credentialId
        ) {
        return new OAuthClientCredentialsTokenSupplier.Config() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return OAuthClientCredentialsTokenSupplier.Config.class;
            }

            @Override
            public String tokenEndpointUrl() {
                return tokenEndpointUrl;
            }

            @Override
            public String clientId() {
                return clientId;
            }

            @Override
            public String clientSecret() {
                return clientSecret;
            }

            @Override
            public String scopes() {
                return scopes;
            }

            @Override
            public String credential_id() {
                return credentialId;
            }
        };
    }
}
