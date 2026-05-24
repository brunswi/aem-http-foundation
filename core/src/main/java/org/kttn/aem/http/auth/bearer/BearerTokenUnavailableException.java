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
package org.kttn.aem.http.auth.bearer;

import org.apache.http.client.ClientProtocolException;

/**
 * Thrown by {@link BearerTokenRequestCustomizer} when no usable bearer token is available before
 * an outbound request is sent — for example when the underlying
 * {@link org.kttn.aem.http.auth.oauth.AccessTokenSupplier} returned a token whose
 * {@code access_token} string is {@code null} or blank.
 * <p>
 * Extends {@link ClientProtocolException} so it participates in the usual
 * {@link java.io.IOException} handling around
 * {@link org.apache.http.impl.client.CloseableHttpClient#execute(org.apache.http.client.methods.HttpUriRequest)}.
 * <p>
 * <strong>Retries:</strong> Listed as non-retriable in
 * {@link org.kttn.aem.http.impl.HttpRequestRetryHandler} so transport I/O retries are not applied
 * to a precondition failure (missing bearer after token acquisition).
 */
public class BearerTokenUnavailableException extends ClientProtocolException {

    private static final long serialVersionUID = 1L;

    public BearerTokenUnavailableException(final String message) {
        super(message);
    }
}
