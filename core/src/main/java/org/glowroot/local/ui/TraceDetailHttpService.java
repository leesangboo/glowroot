/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.SQLException;
import java.util.Deque;

import com.google.common.base.Charsets;
import com.google.common.io.CharSource;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Singleton;

/**
 * Http service to read trace detail, bound to /backend/trace/entries, /backend/trace/profile and
 * /backend/trace/outlier-profile.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceDetailHttpService implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(TraceDetailHttpService.class);

    private final TraceCommonService traceCommonService;

    TraceDetailHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    // TODO test this can still return "{expired: true}" if user viewing trace, and it expires
    // before they expand detail
    @Override
    public void handleRequest(HttpServerExchange exchange) throws IOException {
        String requestPath = exchange.getRequestPath();
        String traceComponent = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        String traceId = getQueryParameter("trace-id", exchange);
        if (traceId == null) {
            throw new IllegalStateException("Missing trace id in query string: "
                    + exchange.getQueryString());
        }
        logger.debug("handleRequest(): traceComponent={}, traceId={}", traceComponent, traceId);

        exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, "application/json; charset=UTF-8");
        CharSource charSource;
        try {
            if (traceComponent.equals("entries")) {
                charSource = traceCommonService.getEntries(traceId);
            } else if (traceComponent.equals("profile")) {
                charSource = traceCommonService.getProfile(traceId);
            } else if (traceComponent.equals("outlier-profile")) {
                charSource = traceCommonService.getOutlierProfile(traceId);
            } else {
                throw new IllegalStateException("Unexpected uri: " + requestPath);
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        HttpServices.preventCaching(exchange);
        if (charSource == null) {
            // UI checks entriesExistence/outlierProfileExistence/traceProfileExistence so should
            // not end up here, but tests don't, send json null value to them
            exchange.getResponseSender().send("null");
        } else {
            exchange.startBlocking();
            OutputStreamWriter out =
                    new OutputStreamWriter(exchange.getOutputStream(), Charsets.UTF_8);
            charSource.copyTo(out);
            out.close();
        }
    }

    @Nullable
    private static String getQueryParameter(String key, HttpServerExchange exchange) {
        Deque<String> params = exchange.getQueryParameters().get(key);
        if (params == null) {
            return null;
        }
        return params.peek();
    }
}
