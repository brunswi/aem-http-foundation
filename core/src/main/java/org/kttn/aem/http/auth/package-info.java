/**
 * Authentication primitives shared between the generic OAuth and Adobe-specific subpackages.
 * <p>
 * Contains {@link org.kttn.aem.http.auth.HttpClientCustomizer}, the contract used by request
 * customizers to register interceptors and other settings on an Apache
 * {@link org.apache.http.impl.client.HttpClientBuilder} before the client is built.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.osgi.annotation.bundle.Export
package org.kttn.aem.http.auth;
