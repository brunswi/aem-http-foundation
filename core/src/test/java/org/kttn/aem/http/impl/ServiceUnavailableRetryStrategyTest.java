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
package org.kttn.aem.http.impl;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ServiceUnavailableRetryStrategy}: fast path for 200, retry budget, and 503 handling
 * delegated to Apache's {@link org.apache.http.impl.client.DefaultServiceUnavailableRetryStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class ServiceUnavailableRetryStrategyTest {

    @Mock
    private HttpResponse response;

    @Mock
    private StatusLine statusLine;

    @Test
    void returnsFalseForOkWithout503RetryLogic() {
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);

        final ServiceUnavailableRetryStrategy strategy = new ServiceUnavailableRetryStrategy(3, 100);

        assertFalse(strategy.retryRequest(response, 1, new BasicHttpContext()));
    }

    @Test
    void returnsFalseWhenExecutionCountExceedsMaxRetries() {
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_SERVICE_UNAVAILABLE);

        final ServiceUnavailableRetryStrategy strategy = new ServiceUnavailableRetryStrategy(2, 1);

        assertFalse(strategy.retryRequest(response, 3, new BasicHttpContext()));
    }

    @Test
    void retriesServiceUnavailableWhileUnderBudget() {
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_SERVICE_UNAVAILABLE);

        final ServiceUnavailableRetryStrategy strategy = new ServiceUnavailableRetryStrategy(5, 100);

        assertTrue(strategy.retryRequest(response, 1, new BasicHttpContext()));
    }

    @Test
    void doesNotRetryNonServiceFailure() {
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_NOT_FOUND);

        final ServiceUnavailableRetryStrategy strategy = new ServiceUnavailableRetryStrategy(5, 100);

        assertFalse(strategy.retryRequest(response, 1, new BasicHttpContext()));
    }
}
