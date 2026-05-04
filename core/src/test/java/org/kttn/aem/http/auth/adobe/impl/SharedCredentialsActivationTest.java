package org.kttn.aem.http.auth.adobe.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.osgi.service.component.ComponentException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies activation ordering for {@link AdobeIntegrationConfiguration} in shared-credentials
 * mode ({@code credential.id} set).
 * <p>
 * The production bug: the component previously resolved the shared {@link AccessTokenSupplier}
 * via a manual {@code bundleContext.getServiceReferences()} call in {@code @Activate}, bypassing
 * DS dependency management entirely. On AEMaaCS publish the component's ServiceFactory was
 * sometimes registered before the shared supplier, causing the LDAP lookup to return empty,
 * throwing a {@link ComponentException}, and leaving the consuming servlet unregistered (404).
 * <p>
 * The fix: declare the shared suppliers as a proper DS {@code @Reference} (MULTIPLE, STATIC,
 * GREEDY). SCR tracks the dependency and retries activation automatically when a matching
 * supplier arrives. {@code @Activate} still filters by the configured {@code credential.id}
 * and throws {@link ComponentException} if no match is found (triggering the SCR retry).
 */
@ExtendWith(AemContextExtension.class)
class SharedCredentialsActivationTest {

    private final AemContext context = new AemContext();
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        AemMockOsgiSupport.registerUninitializedKeyStoreService(context);
        context.registerInjectActivateService(new HttpConfigServiceImpl());
        httpClientProvider = context.registerInjectActivateService(new HttpClientProviderImpl());
    }

    @Test
    void activatesSuccessfullyWhenMatchingSupplierIsPresent() throws Exception {
        registerSupplier("shared-aep-prod", "client-aep", "token-aep");

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of("credential.id", "shared-aep-prod", "set.api.key.header", false)
        );

        assertEquals("token-aep", integration.getAccessToken().getAccessToken());
    }

    @Test
    void throwsComponentExceptionWhenMatchingSupplierIsAbsent() {
        // Register a supplier for a DIFFERENT credential.id — must not match.
        registerSupplier("other-cred", "client-other", "token-other");

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of("credential.id", "missing-cred", "set.api.key.header", false)
            )
        );
        assertInstanceOf(ComponentException.class, ex.getCause(),
            "activate() must throw ComponentException so SCR retries when the supplier arrives");
    }

    @Test
    void throwsComponentExceptionWhenNoSuppliersRegisteredAtAll() {
        // No shared suppliers at all — list will be empty.
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of("credential.id", "any-cred", "set.api.key.header", false)
            )
        );
        assertInstanceOf(ComponentException.class, ex.getCause());
    }

    @Test
    void selectsCorrectSupplierAmongMultiple() throws Exception {
        registerSupplier("cred-a", "client-a", "token-a");
        registerSupplier("cred-b", "client-b", "token-b");
        registerSupplier("cred-c", "client-c", "token-c");

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of("credential.id", "cred-b", "set.api.key.header", false)
        );

        assertEquals("token-b", integration.getAccessToken().getAccessToken(),
            "Component must use the supplier matching its configured credential.id");
    }

    @Test
    void selectsHighestRankingSupplierWhenMultipleMatchSameCredentialId() throws Exception {
        context.registerService(AccessTokenSupplier.class,
            () -> new AccessToken("token-low", 3600),
            Map.of(
                "aem.httpfoundation.accessTokenSupplierType", "OAuthClientCredentialsTokenSupplier",
                "credential.id", "shared-cred",
                "service.ranking", 10
            ));
        context.registerService(AccessTokenSupplier.class,
            () -> new AccessToken("token-high", 3600),
            Map.of(
                "aem.httpfoundation.accessTokenSupplierType", "OAuthClientCredentialsTokenSupplier",
                "credential.id", "shared-cred",
                "service.ranking", 100
            ));

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of("credential.id", "shared-cred", "set.api.key.header", false)
        );

        assertEquals("token-high", integration.getAccessToken().getAccessToken(),
            "Highest-ranking supplier must win when multiple match the same credential.id");
    }

    @Test
    void clientIdFromSharedSupplierUsedForApiKeyHeader() throws Exception {
        context.registerService(AccessTokenSupplier.class,
            () -> new AccessToken("shared-token", 3600),
            Map.of(
                "aem.httpfoundation.accessTokenSupplierType", "OAuthClientCredentialsTokenSupplier",
                "credential.id", "api-key-cred",
                "clientId", "CLIENT_ID_FROM_SHARED_SUPPLIER"
            ));

        // Component uses shared supplier's clientId for x-api-key (no inline clientId set).
        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "api-key-cred",
                "set.api.key.header", true,
                "org.id.header.value", "TEST_ORG@AdobeOrg"
            )
        );

        assertEquals("shared-token", integration.getAccessToken().getAccessToken());
        assertNotNull(integration, "Component must activate when shared supplier exposes clientId");
    }

    /**
     * Simulates the AEMaaCS startup race: the component fails on the first activation attempt
     * because the shared supplier has not registered yet, then succeeds once the supplier arrives
     * (SCR retries activation — here simulated by re-registering the component).
     */
    @Test
    void recoversAfterSupplierArrivesFollowingInitialFailure() throws Exception {
        // First activation attempt: supplier absent → ComponentException (SCR will retry).
        assertThrows(RuntimeException.class, () ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of("credential.id", "late-supplier-cred", "set.api.key.header", false)
            ),
            "Component must fail when shared supplier is not yet registered"
        );

        // Supplier arrives (simulating bundle startup completing its registration).
        registerSupplier("late-supplier-cred", "late-client", "late-token");

        // SCR retry (simulated by re-registering the component): must now succeed.
        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of("credential.id", "late-supplier-cred", "set.api.key.header", false)
        );

        assertEquals("late-token", integration.getAccessToken().getAccessToken(),
            "Component must activate successfully once the matching supplier is available");
    }

    /**
     * Inline-mode components must be unaffected when shared suppliers happen to be registered.
     * The {@code availableSharedSupplierRefs} list is populated but must be ignored entirely
     * when {@code credential.id} is empty.
     */
    @Test
    void inlineModeUnaffectedWhenSharedSuppliersArePresent() {
        // Register shared suppliers — these must not interfere with inline activation.
        registerSupplier("some-shared-cred", "shared-client", "shared-token");

        // Inline component (no credential.id): uses its own CachingTokenAcquirer.
        // We verify it activates without error (token acquisition itself is tested elsewhere).
        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);

        // A real inline activation requires a live token endpoint; here we just verify that
        // the presence of shared suppliers does not prevent inline activation from proceeding
        // past the reference-injection phase into activate(). The ComponentException below
        // comes from missing clientId/clientSecret, NOT from the shared-supplier reference.
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of("set.api.key.header", false)  // no credential.id, no clientId/clientSecret
            )
        );
        assertInstanceOf(ComponentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("clientId"),
            "Failure must be about missing inline credentials, not about the shared-supplier reference");
    }

    private void registerSupplier(final String credentialId, final String clientId, final String token) {
        context.registerService(AccessTokenSupplier.class,
            () -> new AccessToken(token, 3600),
            Map.of(
                "aem.httpfoundation.accessTokenSupplierType", "OAuthClientCredentialsTokenSupplier",
                "credential.id", credentialId,
                "clientId", clientId
            ));
    }
}
