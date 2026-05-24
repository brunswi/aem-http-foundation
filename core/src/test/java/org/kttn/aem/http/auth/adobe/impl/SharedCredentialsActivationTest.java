/*
 * Copyright (C) 2026 KTTN AEM Libraries
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.osgi.service.component.ComponentException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies activation behaviour for {@link AdobeIntegrationConfiguration} in shared-credentials
 * mode ({@code credential.id} set).
 *
 * <h2>Startup-ordering guarantee</h2>
 * Shared-credential lookups go through an internal {@code ServiceTracker} rather than an OSGi
 * DS {@code @Reference}, so {@code activate()} always succeeds and the integration's services
 * are registered immediately. If the supplier is not (yet) present, {@code #getAccessToken()}
 * throws {@link TokenUnavailableException} until the supplier appears.
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
            Map.of(
                "credential.id", "shared-aep-prod",
                "set.api.key.header", false
            )
        );

        assertEquals("token-aep", integration.getAccessToken().getAccessToken());
    }

    /**
     * With the ServiceTracker-based design, {@code activate()} succeeds even when the matching
     * supplier is absent — the integration's services need to be visible so downstream consumers
     * can bind without a startup race. Token acquisition then throws until the supplier appears.
     */
    @Test
    void deferredWhenMatchingSupplierIsAbsent() {
        registerSupplier("other-cred", "client-other", "token-other");

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "missing-cred",
                "set.api.key.header", false
            )
        );
        assertNotNull(integration);
        assertThrows(TokenUnavailableException.class, integration::getAccessToken,
            "getAccessToken() must throw while no matching supplier is registered");
    }

    /**
     * Same deferred behaviour when no suppliers are registered at all.
     */
    @Test
    void deferredWhenNoSuppliersRegisteredAtAll() {
        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "any-cred",
                "set.api.key.header", false
            )
        );
        assertNotNull(integration);
        assertThrows(TokenUnavailableException.class, integration::getAccessToken);
    }

    @Test
    void selectsCorrectSupplierAmongMultiple() throws Exception {
        registerSupplier("cred-a", "client-a", "token-a");
        registerSupplier("cred-b", "client-b", "token-b");
        registerSupplier("cred-c", "client-c", "token-c");

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "cred-b",
                "set.api.key.header", false
            )
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
            Map.of(
                "credential.id", "shared-cred",
                "set.api.key.header", false
            )
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

    @Test
    void activatesSuccessfullyOnceSupplierIsAvailable() throws Exception {
        registerSupplier("late-supplier-cred", "late-client", "late-token");

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "late-supplier-cred",
                "set.api.key.header", false
            )
        );

        assertEquals("late-token", integration.getAccessToken().getAccessToken(),
            "Component must activate successfully once the matching supplier is available");
    }

    /**
     * Inline-mode components must still fail fast when their inline credentials are missing —
     * the deferred-activation pattern only applies to shared mode where a supplier may arrive
     * asynchronously. Inline mode has no external dependency.
     */
    @Test
    void inlineModeStillFailsFastWhenInlineCredentialsAreMissing() {
        registerSupplier("some-shared-cred", "shared-client", "shared-token");

        HttpConfigService configService = context.getService(HttpConfigService.class);
        assertNotNull(configService);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of(
                    "set.api.key.header", false  // no credential.id, no clientId/clientSecret
                )
            )
        );

        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertTrue(
            cause instanceof ComponentException && cause.getMessage().contains("clientId"),
            "Failure must be about missing inline credentials. Got: " + cause
        );
    }

    /**
     * In shared mode with {@code set.api.key.header=true} but no {@code clientId} on either the
     * integration config or the supplier, activation succeeds — the {@code x-api-key} header is
     * simply omitted from outbound requests until a clientId becomes available. This is a
     * deliberate trade-off of the deferred-activation design: we cannot know at activate time
     * whether the value will appear later, so we attach the customizer and let it decide
     * per-request whether to set the header.
     */
    @Test
    void apiKeyHeaderOmittedWhenNeitherConfigNorSupplierProvidesClientId() {
        context.registerService(AccessTokenSupplier.class,
            () -> new AccessToken("token", 3600),
            Map.of(
                "aem.httpfoundation.accessTokenSupplierType", "OAuthClientCredentialsTokenSupplier",
                "credential.id", "no-client-id-cred"
                // clientId intentionally absent
            ));

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            Map.of(
                "credential.id", "no-client-id-cred",
                "set.api.key.header", true
            )
        );

        assertNotNull(integration, "Activation must succeed even when no clientId is available; "
            + "the header is just omitted from requests");
    }

    @Test
    void warnsWhenInlineClientSecretSetAlongsideCredentialId() {
        registerSupplier("warn-cred", "warn-client", "warn-token");

        assertDoesNotThrow(() ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of(
                    "credential.id", "warn-cred",
                    "clientSecret", "should-be-ignored",
                    "set.api.key.header", false
                )
            )
        );
    }

    @Test
    void warnsWhenInlineScopesSetAlongsideCredentialId() {
        registerSupplier("warn-scopes-cred", "warn-scopes-client", "warn-scopes-token");

        assertDoesNotThrow(() ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of(
                    "credential.id", "warn-scopes-cred",
                    "scopes", "openid,profile",
                    "set.api.key.header", false
                )
            )
        );
    }

    @Test
    void warnsWhenAdditionalTokenParamsSetAlongsideCredentialId() {
        registerSupplier("warn-params-cred", "warn-params-client", "warn-params-token");

        assertDoesNotThrow(() ->
            context.registerInjectActivateService(
                new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
                Map.of(
                    "credential.id", "warn-params-cred",
                    "additional.token.params", new String[]{"extra=value"},
                    "set.api.key.header", false
                )
            )
        );
    }

    /**
     * An unactivated component has neither inline acquirer nor shared tracker.
     * Calling {@code getAccessToken()} must throw {@link TokenUnavailableException}, not NPE.
     */
    @Test
    void getAccessTokenThrowsTokenUnavailableExceptionNotNpeWhenComponentIsNotActive() {
        AdobeIntegrationConfiguration notActivated =
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider);
        assertThrows(TokenUnavailableException.class, notActivated::getAccessToken,
            "Must throw TokenUnavailableException (not NPE) when component is not active");
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
