package org.kttn.aem.http.auth.aio.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.kttn.aem.http.HttpClientProvider;
import org.kttn.aem.http.auth.aio.OAuthTokenSupplier;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OSGi factory component implementing {@link OAuthTokenSupplier} with Adobe IMS OAuth 2.0
 * {@code client_credentials} against {@value #OAUTH2_ADOBELOGIN_IMS_TOKEN_URL}.
 * <p>
 * On activation, form parameters and credentials are read from {@link Config}, and a dedicated
 * {@link CloseableHttpClient} is obtained from {@link HttpClientProvider} under the key
 * {@code IMSService}. Each {@link #getAccessToken()} issues a POST and deserializes a 200 JSON body
 * to {@link AccessTokenImpl}.
 * <p>
 * On non-200 responses or I/O failures, logs and returns a placeholder {@link AccessTokenImpl} with token
 * {@value org.kttn.aem.http.auth.aio.OAuthTokenSupplier#PLACEHOLDER_ACCESS_TOKEN} and {@code expires_in}
 * {@value #PLACEHOLDER_EXPIRES_IN_SECONDS} (seconds, per OAuth 2.0), so caches treat the token as
 * already expired relative to {@link org.kttn.aem.http.impl.AIOAuthInterceptor} leniency.
 *
 * @see OAuthTokenSupplier
 */
@Slf4j
@Component(
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = OAuthTokenSupplierImpl.Config.class, factory = true)
public class OAuthTokenSupplierImpl implements OAuthTokenSupplier {

    private static final String DEFAULT_GRANT_TYPE = "client_credentials";
    private static final String PARAM_OAUTH2_GRANT_TYPE = "grant_type";
    private static final String PARAM_OAUTH2_CLIENT_ID = "client_id";
    private static final String PARAM_OAUTH2_CLIENT_SECRET = "client_secret";
    private static final String PARAM_OAUTH2_SCOPE = "scope";

    /** Adobe IMS token endpoint (OAuth 2.0) for the default NA region. */
    protected static final String OAUTH2_ADOBELOGIN_IMS_TOKEN_URL = "https://ims-na1.adobelogin.com/ims/token/v3";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Placeholder {@code expires_in} in seconds (OAuth 2.0). {@code 0} keeps computed expiry in the
     * past after {@link org.kttn.aem.http.impl.AIOAuthInterceptor} subtracts its refresh leniency.
     */
    static final long PLACEHOLDER_EXPIRES_IN_SECONDS = 0L;

    /** Mutable grant parameters refreshed on each {@link #activate} / {@link org.osgi.service.component.annotations.Modified}. */
    protected final Map<String, String> oauthTokenParams = new HashMap<>();

    /** Title shown in OSGi console and activation logs. */
    public static final String OSGI_LABEL = "MDB Config: OAuth Token Supplier";

    @Reference
    private HttpClientProvider httpClientProvider;
    private Config config;
    private CloseableHttpClient client;

    /** OSGi / default; SCR uses this and injects {@link #httpClientProvider}. */
    public OAuthTokenSupplierImpl() {
    }

    /**
     * Package-private for unit tests that need a {@link HttpClientProvider} without the SCR field injector.
     */
    OAuthTokenSupplierImpl(final HttpClientProvider httpClientProvider) {
        this.httpClientProvider = httpClientProvider;
    }

    /**
     * Loads configuration, rebuilds the form parameter map, and binds a pooled client for IMS.
     *
     * @param config factory configuration instance
     */
    @Activate
    @Modified
    protected void activate(final Config config) {
        log.info("Applying configuration settings on '{}'", OSGI_LABEL);
        this.config = config;

        oauthTokenParams.clear();
        oauthTokenParams.put(PARAM_OAUTH2_GRANT_TYPE, DEFAULT_GRANT_TYPE);
        oauthTokenParams.put(PARAM_OAUTH2_CLIENT_ID, config.clientId());
        oauthTokenParams.put(PARAM_OAUTH2_CLIENT_SECRET, config.clientSecret());
        oauthTokenParams.put(PARAM_OAUTH2_SCOPE, config.scopes());

        client = httpClientProvider.provide("IMSService");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOrgId() {
        return config.orgId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getClientId() {
        return config.clientId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * POSTs {@code application/x-www-form-urlencoded} credentials to IMS once; on HTTP 200 returns
     * deserialized {@link AccessTokenImpl}. If there are no form fields (misconfiguration), logs
     * and returns a placeholder without calling IMS. Any other non-200 status or {@link IOException}
     * yields a placeholder {@link AccessTokenImpl} for observability.
     */
    @Override
    public AccessTokenImpl getAccessToken() {
        final List<NameValuePair> formParams = getOAuthParams();
        if (formParams.isEmpty()) {
            log.error("getAccessToken: no OAuth form parameters; cannot call IMS token endpoint");
            return new AccessTokenImpl(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, PLACEHOLDER_EXPIRES_IN_SECONDS);
        }

        final HttpPost httpPost = new HttpPost(OAUTH2_ADOBELOGIN_IMS_TOKEN_URL);
        httpPost.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));

        try (final CloseableHttpResponse postResponse = client.execute(httpPost)) {
            final int statusCode = postResponse.getStatusLine().getStatusCode();
            final HttpEntity responseEntity = postResponse.getEntity();
            final String responseEntityString =
                responseEntity != null ? EntityUtils.toString(responseEntity) : "";

            if (statusCode == HttpStatus.SC_OK) {
                return OBJECT_MAPPER.readValue(responseEntityString, AccessTokenImpl.class);
            }
            log.error("getAccessToken failed with HTTP {}. Response text : {}", statusCode, responseEntityString);
        } catch (final IOException e) {
            log.error("getAccessToken failed.", e);
        }

        return new AccessTokenImpl(OAuthTokenSupplier.PLACEHOLDER_ACCESS_TOKEN, PLACEHOLDER_EXPIRES_IN_SECONDS);
    }

    /**
     * Snapshot of grant parameters from {@link #oauthTokenParams} as immutable name/value pairs.
     *
     * @return unmodifiable list for the token POST body
     */
    private List<NameValuePair> getOAuthParams() {
        final List<NameValuePair> formParams = new ArrayList<>();
        oauthTokenParams.forEach((k, v) -> formParams.add(new BasicNameValuePair(k, v)));
        return Collections.unmodifiableList(formParams);
    }

    /**
     * OSGi Metatype for IMS {@code client_credentials}; factory allows multiple configured suppliers.
     */
    @ObjectClassDefinition(name = OAuthTokenSupplierImpl.OSGI_LABEL)
    public @interface Config {

        /** @return Adobe IMS organization ID */
        @AttributeDefinition(name = "ORG ID", description = "Adobe IMS organization ID (org)")
        String orgId();

        /** @return OAuth {@code client_id} */
        @AttributeDefinition(
            name = "Client Id",
            description = "Adobe Developer Console OAuth client ID (client_id)")
        String clientId();

        /** @return OAuth {@code client_secret} */
        @AttributeDefinition(
            name = "Client Secret",
            description = "Adobe Developer Console OAuth client secret (client_secret)")
        String clientSecret();

        /** @return comma-separated OAuth {@code scope} values */
        @AttributeDefinition(
            name = "Scopes",
            description = "Comma-separated OAuth scopes (scope) for the IMS token request")
        String scopes();
    }
}
