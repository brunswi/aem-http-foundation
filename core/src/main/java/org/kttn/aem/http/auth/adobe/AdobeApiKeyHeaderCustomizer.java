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
package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.HttpClientCustomizer;

import java.util.function.Supplier;

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
 * <p>
 * The header value can be supplied either as a fixed {@code String} (the common case, baked in at
 * construction time) or as a {@link Supplier} that is evaluated on every request — the latter is
 * useful when the value depends on a service that may register asynchronously, so the customizer
 * stays attached even before the value is known. When the supplier returns {@code null} or a blank
 * string, the header is omitted from the request rather than set to an empty value.
 */
public final class AdobeApiKeyHeaderCustomizer
    implements HttpClientCustomizer, HttpRequestInterceptor {

    /** Adobe API gateway header name; conventionally carries the OAuth {@code client_id}. */
    public static final String API_KEY_HEADER = "x-api-key";

    private final Supplier<String> apiKeySupplier;

    /**
     * @param apiKey non-null, non-blank header value (typically the Adobe Developer Console
     *               OAuth {@code client_id})
     */
    public AdobeApiKeyHeaderCustomizer(final String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be null or blank");
        }
        this.apiKeySupplier = () -> apiKey;
    }

    /**
     * @param apiKeySupplier non-null supplier evaluated on every outbound request; if it returns
     *                       {@code null} or blank, the {@code x-api-key} header is omitted for
     *                       that request
     */
    public AdobeApiKeyHeaderCustomizer(final Supplier<String> apiKeySupplier) {
        if (apiKeySupplier == null) {
            throw new IllegalArgumentException("apiKeySupplier must not be null");
        }
        this.apiKeySupplier = apiKeySupplier;
    }

    /**
     * Registers this instance as a {@code last} interceptor on the builder.
     */
    @Override
    public void customize(final HttpClientBuilder builder) {
        builder.addInterceptorLast(this);
    }

    /**
     * Sets {@value #API_KEY_HEADER} from the configured value or supplier; an existing value on
     * the request is overwritten so the customizer's view always wins. If the supplier returns
     * {@code null} or blank the header is left untouched on this request.
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) {
        final String value = apiKeySupplier.get();
        if (value != null && !value.isBlank()) {
            request.setHeader(API_KEY_HEADER, value);
        }
    }
}
