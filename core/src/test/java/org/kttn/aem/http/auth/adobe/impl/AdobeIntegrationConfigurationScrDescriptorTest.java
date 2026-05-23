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
package org.kttn.aem.http.auth.adobe.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the bnd-generated DS component descriptor (OSGI-INF XML) for
 * {@link AdobeIntegrationConfiguration}.
 *
 * <h2>What this guards</h2>
 * The shared-credential lookup goes through an internal {@code ServiceTracker} rather than an
 * OSGi DS {@code @Reference}. This test ensures the generated descriptor reflects that:
 * <ul>
 *   <li>{@code immediate="true"} so the integration's services are registered immediately on
 *       activation success and a failed activation leaves no zombie service in the registry.</li>
 *   <li>No {@code @Reference} to {@code AccessTokenSupplier} — that dependency is internal.
 *       Re-introducing such a reference would resurrect the startup-ordering race that the
 *       ServiceTracker design eliminates.</li>
 * </ul>
 */
class AdobeIntegrationConfigurationScrDescriptorTest {

    private static final String DESCRIPTOR_PATH =
        "/OSGI-INF/org.kttn.aem.http.auth.adobe.impl.AdobeIntegrationConfiguration.xml";

    private static Element componentElement;
    private static NodeList referenceElements;

    @BeforeAll
    static void loadDescriptor() throws Exception {
        InputStream is = AdobeIntegrationConfigurationScrDescriptorTest.class
            .getResourceAsStream(DESCRIPTOR_PATH);
        assertNotNull(is,
            "SCR descriptor not found at " + DESCRIPTOR_PATH
                + " — run 'mvn compile' before running tests, or check the bnd plugin config.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(is);

        componentElement = doc.getDocumentElement();
        referenceElements = doc.getElementsByTagNameNS("*", "reference");
    }

    /**
     * CRITICAL — do not remove {@code immediate="true"}.
     * <p>
     * Without {@code immediate="true"}, Felix DS registers a lazy service proxy for this
     * component before activation succeeds. When {@code activate()} throws (e.g. because inline
     * credentials are missing), the lazy proxy stays in the OSGi registry but its
     * {@code ServiceFactory.getService()} returns null, which permanently confuses Sling's
     * servlet resolver. {@code immediate="true"} ensures the service is only registered after
     * activation succeeds; a failed attempt leaves nothing in the registry.
     */
    @Test
    void immediateAttributeMustBeTrue() {
        assertEquals("true", componentElement.getAttribute("immediate"),
            "immediate must be 'true' so a failed activation does not leave a lazy proxy in the "
                + "OSGi registry whose getService() returns null.");
    }

    /**
     * CRITICAL — the integration must NOT declare a DS {@code @Reference} to
     * {@code AccessTokenSupplier}. The shared-credential lookup goes through an internal
     * {@link org.osgi.util.tracker.ServiceTracker} so {@code activate()} always succeeds and
     * downstream consumers can bind without a startup race. Re-introducing a DS reference here
     * (in any cardinality) would resurrect the race we deliberately designed out.
     */
    @Test
    void mustNotDeclareReferenceToAccessTokenSupplier() {
        for (int i = 0; i < referenceElements.getLength(); i++) {
            Element ref = (Element) referenceElements.item(i);
            String iface = ref.getAttribute("interface");
            assertNotEquals(
                "org.kttn.aem.http.auth.oauth.AccessTokenSupplier",
                iface,
                "AdobeIntegrationConfiguration must not declare a DS @Reference to "
                    + "AccessTokenSupplier — shared-credential lookup uses an internal "
                    + "ServiceTracker so activation never races with the supplier's registration.");
        }
    }
}
