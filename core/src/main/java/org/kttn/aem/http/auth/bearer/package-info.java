/**
 * Generic bearer-token request enrichment for the AEM HTTP Foundation core bundle.
 * <p>
 * {@link org.kttn.aem.http.auth.bearer.BearerTokenRequestCustomizer} sets the
 * {@code Authorization: Bearer ...} header on outbound requests using any
 * {@link org.kttn.aem.http.auth.oauth.AccessTokenSupplier}. No Adobe-specific concerns live in
 * this package — those belong in {@code org.kttn.aem.http.auth.adobe}.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http.auth.bearer;
