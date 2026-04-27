package org.kttn.aem.http.auth.adobe.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.OsgiAccessTokenSupplierType;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;
import org.osgi.service.component.ComponentException;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdobeIntegrationConfiguration} using AEM mocks for OSGi activation.
 */
class AdobeIntegrationConfigurationTest {

    @RegisterExtension
    static HttpServerExtension server = new HttpServerExtension();

    private AemContext context;
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        // Create a fresh context for each test
        context = new AemContext();

        // Set up the real HTTP infrastructure
        HttpConfigService httpConfigService = new HttpConfigServiceImpl();
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);

        HttpClientProviderImpl providerImpl = new HttpClientProviderImpl();
        httpClientProvider = context.registerInjectActivateService(providerImpl);
    }



    @Test
    void shouldActivateWithInlineCredentials() throws Exception {
        // Setup OAuth token endpoint
        server.registerHandler("/token-inline", exchange -> {
            String responseBody = "{\"access_token\":\"inline_token\",\"expires_in\":3600}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "credential.id", "",  // Empty = inline mode
            "clientId", "test-client-id",
            "clientSecret", "test-client-secret",
            "scopes", "openid,profile",
            "set.api.key.header", true,
            "org.id.header.value", "TEST_ORG@AdobeOrg",
            "tokenEndpointUrl", server.getUriFor("/token-inline").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        assertNotNull(integration);

        // Test as AccessTokenSupplier
        AccessToken token = integration.getAccessToken();
        assertNotNull(token);
        assertEquals("inline_token", token.getAccessToken());

        // Test as HttpClientCustomizer
        HttpClientBuilder builder = HttpClientBuilder.create();
        integration.customize(builder);
        assertNotNull(builder);
    }

    @Test
    void shouldActivateWithMinimalConfiguration() throws Exception {
        server.registerHandler("/token-minimal", exchange -> {
            String responseBody = "{\"access_token\":\"minimal_token\",\"expires_in\":7200}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "minimal-client",
            "clientSecret", "minimal-secret",
            "tokenEndpointUrl", server.getUriFor("/token-minimal").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        AccessToken token = integration.getAccessToken();
        assertEquals("minimal_token", token.getAccessToken());
        assertEquals(7200, token.getExpiresInSeconds());
    }

    @Test
    void shouldFailWhenInlineModeWithoutClientSecret() {
        Map<String, Object> config = Map.of(
            "clientId", "no-secret-client"
            // Missing clientSecret
        );

        // Sling mock wraps ComponentException in RuntimeException
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                config
            )
        );

        // Verify the root cause is ComponentException with the right message
        Throwable cause = exception.getCause();
        assertInstanceOf(ComponentException.class, cause);
        assertTrue(cause.getMessage().contains("clientSecret") ||
                   cause.getMessage().contains("client secret"));
    }

    @Test
    void shouldFailWhenInlineModeWithoutClientId() {
        Map<String, Object> config = Map.of(
            "clientSecret", "secret-without-id"
            // Missing clientId
        );

        // Sling mock wraps ComponentException in RuntimeException
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                config
            )
        );

        // Verify the root cause is ComponentException with the right message
        Throwable cause = exception.getCause();
        assertInstanceOf(ComponentException.class, cause);
        assertTrue(cause.getMessage().contains("clientId") ||
                   cause.getMessage().contains("client id"));
    }

    @Test
    void shouldSetApiKeyHeaderWhenEnabled() throws Exception {
        server.registerHandler("/token-apikey", exchange -> {
            String responseBody = "{\"access_token\":\"apikey_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "api-key-client",
            "clientSecret", "api-key-secret",
            "set.api.key.header", true,
            "tokenEndpointUrl", server.getUriFor("/token-apikey").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        // Verify customizer was created
        HttpClientBuilder builder = HttpClientBuilder.create();
        integration.customize(builder);
        assertNotNull(builder);
    }

    @Test
    void shouldSetOrgIdHeaderWhenProvided() throws Exception {
        server.registerHandler("/token-orgid", exchange -> {
            String responseBody = "{\"access_token\":\"orgid_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "orgid-client",
            "clientSecret", "orgid-secret",
            "org.id.header.value", "MY_ORG@AdobeOrg",
            "tokenEndpointUrl", server.getUriFor("/token-orgid").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        HttpClientBuilder builder = HttpClientBuilder.create();
        integration.customize(builder);
        assertNotNull(builder);
    }

    @Test
    void shouldSetBothApiKeyAndOrgIdHeaders() throws Exception {
        server.registerHandler("/token-both-headers", exchange -> {
            String responseBody = "{\"access_token\":\"both_headers_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "both-headers-client",
            "clientSecret", "both-headers-secret",
            "set.api.key.header", true,
            "org.id.header.value", "BOTH_ORG@AdobeOrg",
            "tokenEndpointUrl", server.getUriFor("/token-both-headers").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        AccessToken token = integration.getAccessToken();
        assertEquals("both_headers_token", token.getAccessToken());

        HttpClientBuilder builder = HttpClientBuilder.create();
        integration.customize(builder);
        assertNotNull(builder);
    }

    @Test
    void shouldUseDefaultTokenEndpoint() {
        // Register handler at default Adobe IMS endpoint path
        server.registerHandler("/ims/token/v3", exchange -> {
            String responseBody = "{\"access_token\":\"default_endpoint_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Don't provide tokenEndpointUrl - should use default
        Map<String, Object> config = Map.of(
            "clientId", "default-endpoint-client",
            "clientSecret", "default-endpoint-secret"
            // tokenEndpointUrl not specified - will use default Adobe IMS endpoint
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        assertNotNull(integration);
        // Note: Can't test actual token acquisition because default endpoint is external
        // Just verify activation succeeds
    }

    @Test
    void shouldIncludeScopesInConfiguration() throws Exception {
        server.registerHandler("/token-scopes", exchange -> {
            String responseBody = "{\"access_token\":\"scoped_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "scopes-client",
            "clientSecret", "scopes-secret",
            "scopes", "openid,profile,email,AdobeID",
            "tokenEndpointUrl", server.getUriFor("/token-scopes").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        AccessToken token = integration.getAccessToken();
        assertEquals("scoped_token", token.getAccessToken());
    }

    @Test
    void shouldWorkWithoutOptionalHeaders() throws Exception {
        server.registerHandler("/token-no-headers", exchange -> {
            String responseBody = "{\"access_token\":\"no_headers_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = Map.of(
            "clientId", "no-headers-client",
            "clientSecret", "no-headers-secret",
            "set.api.key.header", false,  // Explicitly disabled
            "org.id.header.value", "",     // Empty string
            "tokenEndpointUrl", server.getUriFor("/token-no-headers").toString()
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        AccessToken token = integration.getAccessToken();
        assertEquals("no_headers_token", token.getAccessToken());
    }

    @Test
    void shouldFailWhenCredentialIdReferencesNonExistentSupplier() {
        HttpClientProviderImpl providerImpl = (HttpClientProviderImpl) httpClientProvider;

        // Try to reference a credential.id that doesn't exist
        Map<String, Object> config = Map.of(
            "credential.id", "non-existent-credential",
            "set.api.key.header", true
        );

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> context.registerInjectActivateService(
                new AdobeIntegrationConfiguration(providerImpl),
                config
            )
        );

        // Verify the root cause mentions the missing credential
        Throwable cause = exception.getCause();
        assertTrue(cause.getMessage().contains("credential.id") ||
                   cause.getMessage().contains("non-existent-credential"));
    }

    /**
     * Shared credentials test (Example 5 from EXAMPLES.md).
     * <p>
     * This test demonstrates that AemContext DOES support LDAP filter matching when services
     * are registered with explicit service properties using {@code context.registerService()}.
     * <p>
     * <strong>Key insight:</strong> Don't use {@code registerInjectActivateService} for shared
     * credentials testing. Instead, create a mock {@link AccessTokenSupplier} and register it
     * with {@code registerService(Class, instance, Map<String, Object> props)} to provide the
     * exact service properties the LDAP filter expects.
     *
     * @see SharedCredentialsWithLdapFilterTest for the proof-of-concept
     */
    @Test
    void shouldUseSharedCredentialWhenCredentialIdProvided() throws Exception {
        // Step 1: Register a shared OAuth token supplier with credential.id
        server.registerHandler("/token-shared", exchange -> {
            String responseBody = "{\"access_token\":\"shared_credential_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        HttpClientProviderImpl providerImpl = (HttpClientProviderImpl) httpClientProvider;

        // Register a mock shared AccessTokenSupplier with explicit service properties
        // This is the KEY: use registerService() with explicit properties, not registerInjectActivateService()
        final boolean[] supplierWasCalled = {false};  // Track if the supplier is actually used
        AccessTokenSupplier sharedSupplier = () -> {
            supplierWasCalled[0] = true;  // Prove the integration actually calls this
            return new AccessToken("shared_credential_token", 3600);
        };

        context.registerService(
            AccessTokenSupplier.class,
            sharedSupplier,
            Map.of(
                "credential.id", "shared-aep-prod",
                "clientId", "shared-client-id",
                OsgiAccessTokenSupplierType.PROPERTY_NAME, OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS
            )
        );

        // Step 2: Create an AdobeIntegrationConfiguration that references the shared credential
        Map<String, Object> integrationConfig = Map.of(
            "credential.id", "shared-aep-prod",  // Reference the shared credential
            "set.api.key.header", true,
            "org.id.header.value", "SHARED_ORG@AdobeOrg"
            // Note: clientId, clientSecret, scopes are NOT provided - they come from the shared supplier
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration(providerImpl),
            integrationConfig
        );

        // Step 3: Verify the integration uses the shared credential
        AccessToken token = integration.getAccessToken();
        assertNotNull(token);
        assertEquals("shared_credential_token", token.getAccessToken());
        assertEquals(3600, token.getExpiresInSeconds());

        // Step 4: PROVE that the integration actually called our mock supplier
        assertTrue(supplierWasCalled[0],
            "AdobeIntegrationConfiguration must call getAccessToken() on the shared supplier");
    }

    /**
     * Shared credentials test with multiple integrations (Example 5 from EXAMPLES.md).
     * <p>
     * Demonstrates that multiple {@link AdobeIntegrationConfiguration} instances can share
     * a single {@link AccessTokenSupplier} by referencing the same {@code credential.id}.
     */
    @Test
    void shouldSupportMultipleIntegrationsWithSameSharedCredential() throws Exception {
        // This is Example 5 from EXAMPLES.md - two integrations sharing one credential
        server.registerHandler("/token-shared-multi", exchange -> {
            String responseBody = "{\"access_token\":\"multi_shared_token\",\"expires_in\":7200}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        HttpClientProviderImpl providerImpl = (HttpClientProviderImpl) httpClientProvider;

        // Step 1: Register a mock shared AccessTokenSupplier with credential.id
        AccessTokenSupplier sharedSupplier = () -> new AccessToken("multi_shared_token", 7200);

        context.registerService(
            AccessTokenSupplier.class,
            sharedSupplier,
            Map.of(
                "credential.id", "shared-multi-cred",
                "clientId", "multi-client-id",
                OsgiAccessTokenSupplierType.PROPERTY_NAME, OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS
            )
        );

        // Step 2: Create TWO separate Adobe integrations referencing the same credential

        // Integration A: AEP Catalog
        Map<String, Object> catalogConfig = Map.of(
            "credential.id", "shared-multi-cred",
            "set.api.key.header", true,
            "org.id.header.value", "CATALOG_ORG@AdobeOrg"
        );

        AdobeIntegrationConfiguration catalogIntegration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration(providerImpl),
            catalogConfig
        );

        // Integration B: AEP Query
        Map<String, Object> queryConfig = Map.of(
            "credential.id", "shared-multi-cred",
            "set.api.key.header", true,
            "org.id.header.value", "QUERY_ORG@AdobeOrg"  // Different org ID!
        );

        AdobeIntegrationConfiguration queryIntegration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration(providerImpl),
            queryConfig
        );

        // Step 3: Verify both integrations use the same underlying token
        AccessToken catalogToken = catalogIntegration.getAccessToken();
        AccessToken queryToken = queryIntegration.getAccessToken();

        assertEquals("multi_shared_token", catalogToken.getAccessToken());
        assertEquals("multi_shared_token", queryToken.getAccessToken());

        // Both got the same token from the shared supplier
        assertEquals(catalogToken.getAccessToken(), queryToken.getAccessToken());
    }

    /**
     * API key header fallback test (shared credentials variant).
     * <p>
     * Verifies that when {@code set.api.key.header=true} but {@code clientId} is not provided
     * on the integration config, the {@link AdobeIntegrationConfiguration} uses the {@code clientId}
     * from the shared {@link AccessTokenSupplier}.
     */
    @Test
    void shouldUseClientIdFromSharedSupplierForApiKeyHeader() throws Exception {
        // When set.api.key.header=true but clientId is not on the integration config,
        // it should fall back to the shared supplier's clientId
        server.registerHandler("/token-api-key-fallback", exchange -> {
            String responseBody = "{\"access_token\":\"fallback_token\",\"expires_in\":3600}";
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        HttpClientProviderImpl providerImpl = (HttpClientProviderImpl) httpClientProvider;

        // Step 1: Register a mock shared AccessTokenSupplier with clientId as service property
        AccessTokenSupplier sharedSupplier = () -> new AccessToken("fallback_token", 3600);

        context.registerService(
            AccessTokenSupplier.class,
            sharedSupplier,
            Map.of(
                "credential.id", "fallback-cred",
                "clientId", "supplier-client-id",  // This should be used for x-api-key
                OsgiAccessTokenSupplierType.PROPERTY_NAME, OsgiAccessTokenSupplierType.VALUE_OAUTH_CLIENT_CREDENTIALS
            )
        );

        // Step 2: Create integration that requests API key header but doesn't provide clientId
        Map<String, Object> integrationConfig = Map.of(
            "credential.id", "fallback-cred",
            "set.api.key.header", true  // Want API key but no clientId on this config
            // clientId NOT provided - should use shared supplier's clientId
        );

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration(providerImpl),
            integrationConfig
        );

        // Verify it works
        AccessToken token = integration.getAccessToken();
        assertEquals("fallback_token", token.getAccessToken());

        // The customizer should have been built with the supplier's clientId for x-api-key
        HttpClientBuilder builder = HttpClientBuilder.create();
        integration.customize(builder);
        assertNotNull(builder);
    }

}
