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
 * OSGi service registration property used to tell {@link AccessTokenSupplier} implementations
 * apart when resolving shared credentials by {@code credential.id}. Adobe integrations register
 * the same {@link AccessTokenSupplier} service interface and must not match the generic OAuth
 * supplier LDAP filter.
 */
public final class OsgiAccessTokenSupplierType {

  /**
   * Service property name; value is one of {@link #VALUE_OAUTH_CLIENT_CREDENTIALS} or
   * {@link #VALUE_ADOBE_INTEGRATION}.
   */
  public static final String PROPERTY_NAME = "aem.httpfoundation.accessTokenSupplierType";

  /** Value for {@link org.kttn.aem.http.auth.oauth.impl.OAuthClientCredentialsTokenSupplier}. */
  public static final String VALUE_OAUTH_CLIENT_CREDENTIALS = "OAuthClientCredentialsTokenSupplier";

  /** Value for {@link org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration}. */
  public static final String VALUE_ADOBE_INTEGRATION = "AdobeIntegrationConfiguration";

  private OsgiAccessTokenSupplierType() {
  }
}
