package org.kttn.aem.utilities;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.utils.URIBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

// credit: https://blogs.perficient.com/2019/01/23/how-to-test-apache-httpclient-in-the-context-of-aem/
public class HttpServerExtension implements BeforeAllCallback, AfterAllCallback {
    public static final int PORT = 6991;
    public static final String HOST = "localhost";
    public static final String SCHEME = "http";
    private HttpServer server;

    public static URI getUriFor(String path) throws URISyntaxException {
        return new URIBuilder()
            .setScheme(SCHEME)
            .setHost(HOST)
            .setPort(PORT)
            .setPath(path)
            .build();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        if (server != null) {
            server.stop(0); // doesn't wait all current exchange handlers complete
        }
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(null); // creates a default executor
        server.start();
    }

    public void registerHandler(String uriToHandle, HttpHandler httpHandler) {
        server.createContext(uriToHandle, httpHandler);
    }
}
