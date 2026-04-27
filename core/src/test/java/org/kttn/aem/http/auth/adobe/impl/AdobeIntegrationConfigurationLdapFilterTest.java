package org.kttn.aem.http.auth.adobe.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdobeIntegrationConfigurationLdapFilterTest {

  @Test
  void escape_escapesSpecialLdapCharacters() {
    assertEquals("ab\\)c\\(", AdobeIntegrationConfiguration.escapeLdapAssertionValue("ab)c("));
    assertEquals("x\\\\y", AdobeIntegrationConfiguration.escapeLdapAssertionValue("x\\y"));
  }

  @Test
  void sharedFilter_containsSupplierTypeAndEscapedCredentialId() {
    assertEquals(
        "(&(aem.httpfoundation.accessTokenSupplierType=OAuthClientCredentialsTokenSupplier)"
            + "(credential.id=shared\\)a))",
        AdobeIntegrationConfiguration.sharedOAuthSupplierLdapFilter("shared)a"));
  }
}
