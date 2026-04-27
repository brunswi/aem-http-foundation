/**
 * Public API of the AEM HTTP Foundation core bundle.
 * <p>
 * Exposes {@link org.kttn.aem.http.HttpClientProvider} for obtaining pooled, configured
 * {@link org.apache.http.impl.client.CloseableHttpClient} instances and
 * {@link org.kttn.aem.http.HttpConfigService} for the active timeout / pool / retry configuration.
 */
@org.osgi.annotation.versioning.Version("0.9.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http;
