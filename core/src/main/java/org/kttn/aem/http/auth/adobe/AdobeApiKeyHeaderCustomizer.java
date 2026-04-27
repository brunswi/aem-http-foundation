package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.HttpClientCustomizer;

/**
 * Sets the Adobe API gateway header {@code x-api-key} on outbound requests.
 * <p>
 * The value is normally the OAuth {@code client_id} of the Adobe Developer Console project, but
 * this customizer treats it as opaque: it never reads it from a token supplier. Compose with
 * {@link org.kttn.aem.http.auth.bearer.BearerTokenRequestCustomizer} to add the bearer token.
 * <p>
 * Single responsibility: only the {@code x-api-key} header. Use
 * {@link AdobeOrgIdHeaderCustomizer} for {@code x-gw-ims-org-id}, or
 * {@link AdobeIntegrationCustomizers} to assemble the full Adobe header set in one call.
 */
public final class AdobeApiKeyHeaderCustomizer
    implements HttpClientCustomizer, HttpRequestInterceptor {

    /** Adobe API gateway header name; conventionally carries the OAuth {@code client_id}. */
    public static final String API_KEY_HEADER = "x-api-key";

    private final String apiKey;

    /**
     * @param apiKey non-null, non-blank header value (typically the Adobe Developer Console
     *               OAuth {@code client_id})
     */
    public AdobeApiKeyHeaderCustomizer(final String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be null or blank");
        }
        this.apiKey = apiKey;
    }

    /**
     * Registers this instance as a {@code last} interceptor on the builder.
     */
    @Override
    public void customize(final HttpClientBuilder builder) {
        builder.addInterceptorLast((HttpRequestInterceptor) this);
    }

    /**
     * Sets {@value #API_KEY_HEADER} unconditionally; an existing value on the request is
     * overwritten so the customizer's view always wins.
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) {
        request.setHeader(API_KEY_HEADER, apiKey);
    }
}
