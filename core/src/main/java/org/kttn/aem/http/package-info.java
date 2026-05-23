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
/**
 * Public API of the AEM HTTP Foundation core bundle.
 * <p>
 * Exposes {@link org.kttn.aem.http.HttpClientProvider} for obtaining pooled, configured
 * {@link org.apache.http.impl.client.CloseableHttpClient} instances and
 * {@link org.kttn.aem.http.HttpConfigService} for the active timeout / pool / retry configuration.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http;
