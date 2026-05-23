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
package org.kttn.aem.http.auth.oauth;

/**
 * Generic supplier of OAuth 2.0 access tokens for outbound HTTP clients.
 * <p>
 * Implementations typically perform a {@code client_credentials} grant against a configured
 * token endpoint (for example Adobe IMS, but any RFC 6749 compliant authorization server is
 * acceptable) and map the JSON response to an immutable {@link AccessToken}. Caching and refresh
 * before expiry are implementation-defined; the canonical implementation
 * ({@code OAuthClientCredentialsTokenSupplier}) caches tokens until shortly before
 * {@link AccessToken#getExpiresInSeconds()} elapses.
 * <p>
 * This interface is intentionally protocol-oriented and contains no Adobe-specific concerns.
 * Adobe IMS specifics (org id header, api key header) are layered on top via dedicated
 * customizers in {@code org.kttn.aem.http.auth.adobe}.
 *
 * @see AccessToken
 */
public interface AccessTokenSupplier {

    /**
     * Returns an access token from the authorization server, normally via synchronous HTTP.
     * Implementations are expected to apply their own transport-level retries (and caching)
     * before failing.
     *
     * @return non-null {@link AccessToken}
     * @throws TokenUnavailableException if a usable token could not be obtained; callers should
     *     fail-fast and not retry at the transport layer (the supplier already did)
     */
    AccessToken getAccessToken() throws TokenUnavailableException;
}
