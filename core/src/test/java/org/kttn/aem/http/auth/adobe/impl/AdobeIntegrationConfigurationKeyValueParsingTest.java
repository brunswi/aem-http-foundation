package org.kttn.aem.http.auth.adobe.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code parseKeyValuePairs} logic inside
 * {@link AdobeIntegrationConfiguration}, exercised indirectly via
 * {@code additional.token.params} whose parsed result appears verbatim in the
 * OAuth token POST body.
 */
class AdobeIntegrationConfigurationKeyValueParsingTest {

    @RegisterExtension
    static HttpServerExtension server = new HttpServerExtension();

    private AemContext context;
    private HttpClientProvider httpClientProvider;

    @BeforeEach
    void setUp() {
        context = new AemContext();
        HttpConfigService httpConfigService = new HttpConfigServiceImpl();
        context.registerInjectActivateService(httpConfigService);
        AemMockOsgiSupport.registerForHttpClientProvider(context);
        HttpClientProviderImpl providerImpl = new HttpClientProviderImpl();
        httpClientProvider = context.registerInjectActivateService(providerImpl);
    }

    /**
     * Verifies that well-formed {@code key=value} entries in {@code additional.token.params}
     * are forwarded verbatim as extra form parameters in the OAuth token POST.
     */
    @Test
    void shouldIncludeValidKeyValuePairsInTokenRequest() throws Exception {
        String body = captureTokenRequest("/token-valid-pairs",
            new String[]{"audience=api://default", "extra=value123"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("audience=api://default"));
        assertTrue(decoded.contains("extra=value123"));
    }

    /**
     * Verifies that an entry without any {@code =} character is silently skipped (a warning is
     * logged) and does not appear as a parameter in the token request.
     */
    @Test
    void shouldSkipEntryWithNoEqualsSign() throws Exception {
        String body = captureTokenRequest("/token-no-equals",
            new String[]{"no-equals-entry", "valid=value"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertFalse(decoded.contains("no-equals-entry"));
        assertTrue(decoded.contains("valid=value"));
    }

    /**
     * Verifies that an entry whose {@code =} is at the very first position (empty key before
     * the separator) is silently skipped and does not appear in the token request.
     */
    @Test
    void shouldSkipEntryWhereEqualsIsAtFirstPosition() throws Exception {
        String body = captureTokenRequest("/token-equals-at-start",
            new String[]{"=value-no-key", "valid=value"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertFalse(decoded.contains("value-no-key"));
        assertTrue(decoded.contains("valid=value"));
    }

    /**
     * Verifies that empty strings and whitespace-only entries in the array are silently skipped
     * without causing an error, and that valid entries alongside them are still forwarded.
     */
    @Test
    void shouldSkipBlankEntries() throws Exception {
        String body = captureTokenRequest("/token-blank-entries",
            new String[]{"", "   ", "valid=kept"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("valid=kept"));
    }

    /**
     * Verifies that an entry whose key consists solely of whitespace (e.g. {@code " =value"})
     * is skipped after trimming produces an empty key, even though the entry passes the initial
     * {@code =} position check.
     */
    @Test
    void shouldSkipEntryWithWhitespaceOnlyKey() throws Exception {
        String body = captureTokenRequest("/token-whitespace-key",
            new String[]{" =skipped-value", "kept=yes"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertFalse(decoded.contains("skipped-value"));
        assertTrue(decoded.contains("kept=yes"));
    }

    /**
     * Verifies that only the first {@code =} is treated as the key/value separator, so a value
     * that itself contains {@code =} characters (e.g. a Base64 string or URL) is preserved intact.
     */
    @Test
    void shouldPreserveFullValueWhenValueContainsEquals() throws Exception {
        String body = captureTokenRequest("/token-value-with-equals",
            new String[]{"key=val=ue"});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("key=val=ue"),
            "Embedded '=' in value must not be treated as a second separator");
    }

    /**
     * Verifies that an empty {@code additional.token.params} array results in only the standard
     * OAuth parameters ({@code grant_type}, {@code client_id}, etc.) being sent.
     */
    @Test
    void shouldSendOnlyStandardParamsWhenArrayIsEmpty() throws Exception {
        String body = captureTokenRequest("/token-empty-params", new String[]{});

        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("grant_type=client_credentials"));
        assertTrue(decoded.contains("client_id="));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Activates an {@link AdobeIntegrationConfiguration} against a mock token endpoint at
     * {@code path}, triggers one token request, and returns the raw URL-encoded POST body
     * captured from the mock server.
     */
    private String captureTokenRequest(String path, String[] additionalTokenParams)
            throws Exception {

        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String responseBody = "{\"access_token\":\"test_token\",\"expires_in\":3600}";

        server.registerHandler(path, exchange -> {
            capturedBody.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });

        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "test-client");
        config.put("clientSecret", "test-secret");
        config.put("tokenEndpointUrl", server.getUriFor(path).toString());
        config.put("additional.token.params", additionalTokenParams);

        AdobeIntegrationConfiguration integration = context.registerInjectActivateService(
            new AdobeIntegrationConfiguration((HttpClientProviderImpl) httpClientProvider),
            config
        );

        integration.getAccessToken();
        return capturedBody.get();
    }
}