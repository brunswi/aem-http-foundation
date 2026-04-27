package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.kttn.aem.http.auth.HttpClientCustomizer;
import org.kttn.aem.http.auth.bearer.BearerTokenRequestCustomizer;
import org.kttn.aem.http.auth.oauth.AccessTokenSupplier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder that assembles the typical Adobe header set for an outbound integration into a
 * single {@link HttpClientCustomizer}.
 * <p>
 * Use this instead of wiring {@link BearerTokenRequestCustomizer},
 * {@link AdobeApiKeyHeaderCustomizer} and {@link AdobeOrgIdHeaderCustomizer} by hand when you
 * want an opinionated, consistent assembly. Each ingredient is opt-in: only the parts you set
 * are added.
 *
 * <h2>Typical use</h2>
 * <pre>{@code
 * HttpClientCustomizer customizer = AdobeIntegrationCustomizers.builder()
 *     .bearer(accessTokenSupplier)
 *     .apiKey(clientId)
 *     .orgIdHeader(orgId)
 *     .build();
 *
 * httpClientProvider.provide("aep-prod", null, customizer::customize);
 * }</pre>
 *
 * <h2>Composition order</h2>
 * The bearer customizer is registered first (so other request modifications can observe the
 * authorization header), followed by api-key, org-id and any additional headers.
 */
public final class AdobeIntegrationCustomizers {

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private AdobeIntegrationCustomizers() {
    }

    /** Fluent builder; not thread-safe. */
    public static final class Builder {

        private AccessTokenSupplier bearerSupplier;
        private String apiKey;
        private String orgId;
        private final Map<String, String> additionalHeaders = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds an {@code Authorization: Bearer ...} interceptor backed by the given supplier.
         *
         * @param accessTokenSupplier non-null source of bearer tokens
         */
        public Builder bearer(final AccessTokenSupplier accessTokenSupplier) {
            this.bearerSupplier = accessTokenSupplier;
            return this;
        }

        /**
         * Adds an {@code x-api-key} header (typically the Adobe Developer Console
         * {@code client_id}). Pass {@code null} or blank to omit.
         */
        public Builder apiKey(final String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Adds an {@code x-gw-ims-org-id} header. Pass {@code null} or blank to omit.
         */
        public Builder orgIdHeader(final String orgId) {
            this.orgId = orgId;
            return this;
        }

        /**
         * Adds an arbitrary static header. Intended as an <strong>escape hatch</strong> for
         * Adobe services that require non-standard request headers (for example
         * {@code x-sandbox-name}); the curated headers ({@code x-api-key},
         * {@code x-gw-ims-org-id}) should be set via the dedicated builder methods so they
         * remain easy to find in code review and logs.
         */
        public Builder additionalHeader(final String name, final String value) {
            if (name != null && !name.isBlank() && value != null) {
                this.additionalHeaders.put(name, value);
            }
            return this;
        }

        /**
         * @return composed customizer that registers each enabled ingredient as a {@code last}
         *         request interceptor; never {@code null}
         */
        public HttpClientCustomizer build() {
            final List<HttpClientCustomizer> parts = new ArrayList<>(4);
            if (bearerSupplier != null) {
                parts.add(new BearerTokenRequestCustomizer(bearerSupplier));
            }
            if (apiKey != null && !apiKey.isBlank()) {
                parts.add(new AdobeApiKeyHeaderCustomizer(apiKey));
            }
            if (orgId != null && !orgId.isBlank()) {
                parts.add(new AdobeOrgIdHeaderCustomizer(orgId));
            }
            if (!additionalHeaders.isEmpty()) {
                parts.add(new StaticHeadersCustomizer(additionalHeaders));
            }
            return builder -> parts.forEach(p -> p.customize(builder));
        }
    }

    /**
     * Internal customizer that registers a single interceptor setting a fixed map of headers.
     */
    private static final class StaticHeadersCustomizer implements HttpClientCustomizer {

        private final Map<String, String> headers;

        StaticHeadersCustomizer(final Map<String, String> headers) {
            // Defensive copy to prevent external modification
            this.headers = new LinkedHashMap<>(headers);
        }

        @Override
        public void customize(final HttpClientBuilder builder) {
            builder.addInterceptorLast((HttpRequestInterceptor) (request, context) ->
                headers.forEach(request::setHeader));
        }
    }
}
