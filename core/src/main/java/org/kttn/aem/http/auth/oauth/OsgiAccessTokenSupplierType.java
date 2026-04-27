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
