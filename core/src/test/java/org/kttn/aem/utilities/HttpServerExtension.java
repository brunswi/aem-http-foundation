package org.kttn.aem.utilities;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import org.apache.http.client.utils.URIBuilder;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

// credit: https://blogs.perficient.com/2019/01/23/how-to-test-apache-httpclient-in-the-context-of-aem/
public class HttpServerExtension implements BeforeAllCallback, AfterAllCallback {
    public static final String HOST = "localhost";
    public static final String SCHEME = "http";
    private HttpServer server;
    @Getter
    private int port;

    /**
     * Build a URI for the given path against the ephemeral port bound by {@link #beforeAll}.
     * Must be called after the extension has started (use from {@code @Test} / {@code @BeforeEach}).
     */
    public URI getUriFor(final String path) throws URISyntaxException {
        return new URIBuilder()
            .setScheme(SCHEME)
            .setHost(HOST)
            .setPort(port)
            .setPath(path)
            .build();
    }

    @Override
    public void afterAll(@NonNull ExtensionContext extensionContext) {
        if (server != null) {
            server.stop(0); // doesn't wait for current exchange handlers to complete
        }
    }

    @Override
    public void beforeAll(@NonNull ExtensionContext extensionContext) throws Exception {
        // Bind to port 0 so the OS picks a free port (avoids collisions on parallel CI shards).
        server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();
    }

    public void registerHandler(String uriToHandle, HttpHandler httpHandler) {
        server.createContext(uriToHandle, httpHandler);
    }
}
