package org.kttn.aem.http.auth.bearer;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.auth.oauth.AccessToken;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;
import org.kttn.aem.http.auth.oauth.TokenUnavailableException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BearerTokenRequestCustomizerTest {

    @Mock
    private AccessTokenSupplier accessTokenSupplier;

    @Test
    void setsAuthorizationHeaderFromSuppliedToken() throws Exception {
        when(accessTokenSupplier.getAccessToken())
            .thenReturn(new AccessToken("real-token", 3600L));

        final BearerTokenRequestCustomizer customizer =
            new BearerTokenRequestCustomizer(accessTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");
        customizer.process(request, new BasicHttpContext());

        assertEquals("Bearer real-token",
            request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
    }

    @Test
    void leavesAuthorizationUntouchedWhenAlreadyPresent() throws Exception {
        final BearerTokenRequestCustomizer customizer =
            new BearerTokenRequestCustomizer(accessTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");
        request.setHeader(HttpHeaders.AUTHORIZATION, "Basic xyz");

        customizer.process(request, new BasicHttpContext());

        assertEquals("Basic xyz", request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
    }

    @Test
    void propagatesTokenUnavailableException() throws TokenUnavailableException {
        when(accessTokenSupplier.getAccessToken())
            .thenThrow(new TokenUnavailableException("issuer unreachable"));

        final BearerTokenRequestCustomizer customizer =
            new BearerTokenRequestCustomizer(accessTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");

        final TokenUnavailableException ex = assertThrows(TokenUnavailableException.class,
            () -> customizer.process(request, new BasicHttpContext()));
        assertEquals("issuer unreachable", ex.getMessage());
        assertNull(request.getFirstHeader(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void throwsBearerTokenUnavailableForBlankToken() throws TokenUnavailableException {
        when(accessTokenSupplier.getAccessToken()).thenReturn(new AccessToken("  ", 60L));

        final BearerTokenRequestCustomizer customizer =
            new BearerTokenRequestCustomizer(accessTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");

        assertThrows(BearerTokenUnavailableException.class,
            () -> customizer.process(request, new BasicHttpContext()));
    }
}
