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
 * Generic OAuth 2.0 client credentials primitives used by the AEM HTTP Foundation core bundle.
 * <p>
 * This package contains protocol-oriented types only — no Adobe-specific concerns. Implementations
 * of {@link org.kttn.aem.http.auth.oauth.AccessTokenSupplier} acquire tokens against any RFC 6749
 * compliant authorization server. Adobe IMS specifics (api key header, org id header, integration
 * configuration) live in {@code org.kttn.aem.http.auth.adobe}.
 *
 * @see org.kttn.aem.http.auth.oauth.AccessTokenSupplier
 * @see org.kttn.aem.http.auth.oauth.AccessToken
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http.auth.oauth;
