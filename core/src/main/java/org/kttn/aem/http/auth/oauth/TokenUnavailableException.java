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

import org.apache.http.client.ClientProtocolException;

/**
 * Thrown by {@link AccessTokenSupplier#getAccessToken()} when no usable bearer token can be
 * obtained from the authorization server: non-2xx token response after the supplier's own retry
 * budget, malformed body, or unrecoverable I/O failure.
 * <p>
 * Extends {@link ClientProtocolException} (an {@link java.io.IOException}) so it propagates
 * through {@link org.apache.http.HttpRequestInterceptor#process} and Apache HttpClient
 * execute paths without wrapping. It is listed as non-retriable in
 * {@link org.kttn.aem.http.impl.HttpRequestRetryHandler} so the outer client does not retry an
 * authentication precondition failure on top of the supplier's already-completed retries.
 */
public class TokenUnavailableException extends ClientProtocolException {

    private static final long serialVersionUID = 1L;

    public TokenUnavailableException(final String message) {
        super(message);
    }

    public TokenUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
