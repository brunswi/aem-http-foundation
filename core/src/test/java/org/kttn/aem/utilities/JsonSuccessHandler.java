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
