package org.kttn.aem.utilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;

// credit: https://gist.github.com/rponte/710d65dc3beb28d97655#file-httpserverrule-java
public class JsonSuccessHandler implements HttpHandler {

    private String responseBody;

    public JsonSuccessHandler() {
    }

    public JsonSuccessHandler(String responseBody) {
        this.responseBody = responseBody;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, responseBody.length());
        IOUtils.write(responseBody, exchange.getResponseBody(), Charset.defaultCharset());
        exchange.close();
    }
}
