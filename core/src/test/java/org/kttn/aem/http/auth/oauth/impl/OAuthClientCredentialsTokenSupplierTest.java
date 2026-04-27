package org.kttn.aem.http.auth.oauth.impl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.kttn.aem.http.HttpConfigService;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.kttn.aem.http.impl.HttpClientProviderImpl;
import org.kttn.aem.http.impl.HttpConfigServiceImpl;
import org.kttn.aem.http.support.AemMockOsgiSupport;
import org.kttn.aem.utilities.HttpServerExtension;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OAuthClientCredentialsTokenSupplier} using a real HTTP server to verify
 * OAuth flow without heavy mocking.
 */
class OAuthClientCredentialsTokenSupplierTest {

  @RegisterExtension
  static HttpServerExtension server = new HttpServerExtension();

  private final AemContext context = new AemContext();
  private OAuthClientCredentialsTokenSupplier supplier;

  @BeforeEach
  void setUp() {
    // Set up the real HTTP infrastructure (same pattern as HttpClientProviderTest)
    HttpConfigService httpConfigService = new HttpConfigServiceImpl();
    context.registerInjectActivateService(httpConfigService);
    AemMockOsgiSupport.registerForHttpClientProvider(context);

    HttpClientProviderImpl providerImpl = new HttpClientProviderImpl();
    context.registerInjectActivateService(providerImpl);

    // Create the supplier and manually inject the provider (OSGi simulation)
    // HttpClientProviderImpl implements both HttpClientProvider and InternalHttpClientProvider
    supplier = new OAuthClientCredentialsTokenSupplier(providerImpl);
  }

  @Test
  void shouldAcquireTokenFromValidServer() throws Exception {
    // Register a handler that returns valid OAuth response
    server.registerHandler("/token", exchange -> {
      String responseBody = "{\"access_token\":\"test_token_123\",\"expires_in\":3600}";
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "test-supplier");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token").toString(),
        "test-client-id",
        "test-client-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    AccessToken token = supplier.getAccessToken();

    assertNotNull(token);
    assertEquals("test_token_123", token.getAccessToken());
    assertEquals(3600, token.getExpiresInSeconds());
  }

  @Test
  void shouldHandleServerError() throws Exception {
    server.registerHandler("/token-error", exchange -> exchange.sendResponseHeaders(500, -1));

    Map<String, Object> properties = Map.of("component.name", "error-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-error").toString(),
        "test-client",
        "test-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    TokenUnavailableException exception = assertThrows(
        TokenUnavailableException.class,
        () -> supplier.getAccessToken()
    );

    assertTrue(exception.getMessage().contains("HTTP 500"));
  }

  @Test
  void shouldHandleUnauthorized() throws Exception {
    server.registerHandler("/token-401", exchange -> {
      String errorBody = "{\"error\":\"invalid_client\",\"error_description\":\"Invalid credentials\"}";
      exchange.sendResponseHeaders(401, errorBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(errorBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "401-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-401").toString(),
        "bad-client",
        "bad-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    TokenUnavailableException exception = assertThrows(
        TokenUnavailableException.class,
        () -> supplier.getAccessToken()
    );

    assertTrue(exception.getMessage().contains("HTTP 401"));
  }

  @Test
  void shouldHandleMalformedJsonResponse() throws Exception {
    server.registerHandler("/token-malformed", exchange -> {
      String responseBody = "{\"access_token\":\"incomplete\""; // Malformed JSON
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "malformed-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-malformed").toString(),
        "test-client",
        "test-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    assertThrows(
        TokenUnavailableException.class,
        () -> supplier.getAccessToken()
    );
  }

  @Test
  void shouldIncludeScopesInRequest() throws Exception {
    final String[] capturedScopes = new String[1];

    server.registerHandler("/token-scopes", exchange -> {
      // Capture the request body to verify scopes were sent
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      String requestString = new String(requestBody, StandardCharsets.UTF_8);
      capturedScopes[0] = requestString;

      String responseBody = "{\"access_token\":\"scoped_token\",\"expires_in\":7200}";
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "scopes-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-scopes").toString(),
        "client-with-scopes",
        "secret",
        "openid,profile,email",
        ""
    );

    supplier.activate(config, properties);
    AccessToken token = supplier.getAccessToken();

    assertNotNull(token);
    assertEquals("scoped_token", token.getAccessToken());
    assertTrue(capturedScopes[0].contains("scope=openid"),
        "Request should contain scopes: " + capturedScopes[0]);
  }

  @Test
  void shouldCacheTokenUntilExpiry() throws Exception {
    final int[] callCount = {0};

    server.registerHandler("/token-cache", exchange -> {
      callCount[0]++;
      String responseBody = "{\"access_token\":\"cached_token_" + callCount[0] + "\",\"expires_in\":3600}";
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "cache-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-cache").toString(),
        "cache-client",
        "cache-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    // First call should hit the server
    AccessToken token1 = supplier.getAccessToken();
    assertEquals("cached_token_1", token1.getAccessToken());
    assertEquals(1, callCount[0]);

    // Second call should use cached token
    AccessToken token2 = supplier.getAccessToken();
    assertEquals("cached_token_1", token2.getAccessToken());
    assertEquals(1, callCount[0], "Token should be cached, server should not be called again");
  }

  @Test
  void shouldSetCredentialIdServiceProperty() throws Exception {
    server.registerHandler("/token-credential-id", exchange -> {
      String responseBody = "{\"access_token\":\"test\",\"expires_in\":3600}";
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "credential-id-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-credential-id").toString(),
        "client",
        "secret",
        "",
        "my-shared-credential"
    );

    supplier.activate(config, properties);

    // Just verify activation succeeds with credential_id
    AccessToken token = supplier.getAccessToken();
    assertNotNull(token);
  }

  /**
   * Helper to create a Config instance for testing.
   */
  private OAuthClientCredentialsTokenSupplier.Config createConfig(
      String tokenUrl,
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
      public String credential_id() {
        return credentialId;
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
      public String tokenEndpointUrl() {
        return tokenUrl;
      }
    };
  }

  /**
   * Test that concurrent token requests only trigger ONE server call.
   * <p>
   * This verifies the double-checked locking in {@link CachingTokenAcquirer} works correctly
   * to prevent thundering herd problems when multiple threads request a token simultaneously.
   */
  @Test
  void shouldHandleConcurrentTokenRefreshCorrectly() throws Exception {
    final AtomicInteger serverCallCount = new AtomicInteger(0);

    server.registerHandler("/token-concurrent", exchange -> {
      try {
        int callNumber = serverCallCount.incrementAndGet();
        // Simulate some processing delay to increase likelihood of concurrent requests
        Thread.sleep(100);
        String responseBody = "{\"access_token\":\"concurrent_token_" + callNumber + "\",\"expires_in\":3600}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length());
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(responseBody.getBytes(StandardCharsets.UTF_8));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    });

    Map<String, Object> properties = Map.of("component.name", "concurrent-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-concurrent").toString(),
        "concurrent-client",
        "concurrent-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    // Launch 20 threads that all request token simultaneously
    final int threadCount = 20;
    final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    final CountDownLatch startLatch = new CountDownLatch(1);
    final List<Future<AccessToken>> futures = new ArrayList<>();

    try {
      for (int i = 0; i < threadCount; i++) {
        futures.add(executor.submit(() -> {
          startLatch.await(); // Wait for all threads to be ready
          return supplier.getAccessToken();
        }));
      }

      // Release all threads at once
      startLatch.countDown();

      // Collect all tokens
      AccessToken firstToken = null;
      for (Future<AccessToken> future : futures) {
        AccessToken token = future.get(10, TimeUnit.SECONDS);
        assertNotNull(token);
        if (firstToken == null) {
          firstToken = token;
        } else {
          // All tokens should be the SAME instance (from cache)
          assertSame(firstToken, token, "All concurrent requests should get the same cached token");
        }
      }

      // Verify the server was called ONLY ONCE despite 20 concurrent requests
      assertEquals(1, serverCallCount.get(),
          "Server should be called only once despite " + threadCount + " concurrent requests");
      assertNotNull(firstToken);
      assertEquals("concurrent_token_1", firstToken.getAccessToken());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Test that tokens are refreshed when they expire (or near expiry with leniency).
   * <p>
   * This tests the expiry logic in {@link CachingTokenAcquirer} to ensure tokens
   * are refreshed before they become invalid.
   */
  @Test
  void shouldRefreshTokenNearExpiry() throws Exception {
    final AtomicInteger serverCallCount = new AtomicInteger(0);

    server.registerHandler("/token-expiry", exchange -> {
      int callNumber = serverCallCount.incrementAndGet();
      // First call: short-lived token (2 seconds)
      // Second call: longer token
      int expiresIn = (callNumber == 1) ? 2 : 3600;
      String responseBody = "{\"access_token\":\"token_call_" + callNumber + "\",\"expires_in\":" + expiresIn + "}";
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    });

    Map<String, Object> properties = Map.of("component.name", "expiry-test");

    OAuthClientCredentialsTokenSupplier.Config config = createConfig(
        server.getUriFor("/token-expiry").toString(),
        "expiry-client",
        "expiry-secret",
        "",
        ""
    );

    supplier.activate(config, properties);

    // First call should get the short-lived token
    AccessToken token1 = supplier.getAccessToken();
    assertEquals("token_call_1", token1.getAccessToken());
    assertEquals(2, token1.getExpiresInSeconds());
    assertEquals(1, serverCallCount.get());

    // Wait for the token to expire (2 seconds + leniency buffer)
    // CachingTokenAcquirer has 60 second default leniency, but the token is only 2 seconds
    // So it should be expired almost immediately when accounting for leniency
    Thread.sleep(2500);

    // Second call should refresh the token
    AccessToken token2 = supplier.getAccessToken();
    assertEquals("token_call_2", token2.getAccessToken());
    assertEquals(3600, token2.getExpiresInSeconds());
    assertEquals(2, serverCallCount.get(), "Token should have been refreshed after expiry");
  }
}
