package org.kttn.aem.http.auth.adobe;

import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.kttn.aem.http.auth.HttpClientCustomizer;

/**
 * Sets the Adobe IMS gateway header {@code x-gw-ims-org-id} on outbound requests.
 * <p>
 * The value is the Adobe IMS organization id (for example
 * {@code 12345ABCDE67890@AdobeOrg}). This customizer is intentionally separate from
 * {@link AdobeApiKeyHeaderCustomizer}: not every Adobe service requires both headers, and the
 * single-responsibility split allows callers to opt in per integration.
 */
public final class AdobeOrgIdHeaderCustomizer
    implements HttpClientCustomizer, HttpRequestInterceptor {

    /** Adobe IMS gateway header name carrying the organization id. */
    public static final String IMS_ORG_ID_HEADER = "x-gw-ims-org-id";

    private final String orgId;

    /**
     * @param orgId non-null, non-blank Adobe IMS organization id (typically ending in
     *              {@code @AdobeOrg})
     */
    public AdobeOrgIdHeaderCustomizer(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("orgId must not be null or blank");
        }
        this.orgId = orgId;
    }

    /**
     * Registers this instance as a {@code last} interceptor on the builder.
     */
    @Override
    public void customize(final HttpClientBuilder builder) {
        builder.addInterceptorLast((HttpRequestInterceptor) this);
    }

    /**
     * Sets {@value #IMS_ORG_ID_HEADER} unconditionally; an existing value on the request is
     * overwritten so the customizer's view always wins.
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) {
        request.setHeader(IMS_ORG_ID_HEADER, orgId);
    }
}
