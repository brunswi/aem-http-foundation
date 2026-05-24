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
package org.kttn.aem.http;

/**
 * Provides the active {@link HttpConfig} snapshot for outbound HTTP clients (typically from OSGi
 * Metatype on author/publish).
 *
 * @see org.kttn.aem.http.impl.HttpConfigServiceImpl
 */
public interface HttpConfigService {

    /**
     * Returns the current HTTP client configuration (timeouts, pool size, retry counts).
     * <p>
     * In normal AEM lifecycle this is safe to call after the implementing component has activated;
     * callers should not rely on a non-null result before activation completes.
     *
     * @return configuration snapshot; not null once the backing component is active
     */
    HttpConfig getHttpConfig();
}
