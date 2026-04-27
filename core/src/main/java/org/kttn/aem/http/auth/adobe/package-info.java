/**
 * Adobe-specific request enrichment for the AEM HTTP Foundation core bundle.
 * <p>
 * This package layers Adobe IMS / API Gateway conventions on top of the protocol-oriented
 * {@code auth.oauth} and {@code auth.bearer} packages. Components include:
 * <ul>
 *   <li>{@link org.kttn.aem.http.auth.adobe.AdobeApiKeyHeaderCustomizer} — sets {@code x-api-key}
 *       (typically the OAuth {@code client_id} of the Adobe Developer Console project).</li>
 *   <li>{@link org.kttn.aem.http.auth.adobe.AdobeOrgIdHeaderCustomizer} — sets
 *       {@code x-gw-ims-org-id} (Adobe IMS organization id).</li>
 *   <li>{@link org.kttn.aem.http.auth.adobe.AdobeIntegrationCustomizers} — fluent builder that
 *       composes bearer auth + Adobe headers into a single
 *       {@link org.kttn.aem.http.auth.HttpClientCustomizer}.</li>
 * </ul>
 * <p>
 * For the recommended primary entry point — one OSGi factory configuration per Adobe integration
 * that wires everything together — see
 * {@code org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration}.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http.auth.adobe;
