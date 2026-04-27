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
