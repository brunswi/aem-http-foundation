package org.kttn.aem.http.impl;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kttn.aem.http.auth.aio.OAuthTokenSupplier;
import org.kttn.aem.http.auth.aio.impl.AccessTokenImpl;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIOAuthInterceptorTest {

    @Mock
    private OAuthTokenSupplier oAuthTokenSupplier;

    @Test
    void throwsClientProtocolExceptionForPlaceholderTokenBeforeSend() {
        when(oAuthTokenSupplier.getAccessToken()).thenReturn(
            new AccessTokenImpl(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, 0L));

        final AIOAuthInterceptor interceptor = new AIOAuthInterceptor(oAuthTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");

        final BearerTokenUnavailableException ex = assertThrows(BearerTokenUnavailableException.class,
            () -> interceptor.process(request, new BasicHttpContext()));
        assertEquals(AIOAuthInterceptor.MSG_NO_BEARER, ex.getMessage());
    }

    @Test
    void setsAuthorizationWhenTokenIsValid() throws Exception {
        when(oAuthTokenSupplier.getClientId()).thenReturn("client-id");
        when(oAuthTokenSupplier.getOrgId()).thenReturn("org-id");
        when(oAuthTokenSupplier.getAccessToken()).thenReturn(new AccessTokenImpl("real-token", 3600L));

        final AIOAuthInterceptor interceptor = new AIOAuthInterceptor(oAuthTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");
        interceptor.process(request, new BasicHttpContext());

        assertEquals("Bearer real-token", request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
    }

    @Test
    void throwsClientProtocolExceptionWhenTokenBlank() {
        when(oAuthTokenSupplier.getAccessToken()).thenReturn(new AccessTokenImpl("  ", 60L));

        final AIOAuthInterceptor interceptor = new AIOAuthInterceptor(oAuthTokenSupplier);
        final HttpRequest request = new BasicHttpRequest("GET", "https://example.test/api");

        assertThrows(BearerTokenUnavailableException.class, () -> interceptor.process(request, new BasicHttpContext()));
    }
}
