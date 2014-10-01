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
import java.net.ServerSocket;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import io.undertow.Undertow;
import io.undertow.predicate.Predicate;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import org.glowroot.markers.Singleton;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@Singleton
public class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final String bindAddress;
    private final EncodingHandler encodingHandler;
    private volatile Undertow undertow;
    private volatile int port;

    HttpServer(String bindAddress, int port, LayoutJsonService layoutJsonService,
            ImmutableMap<Pattern, Object> uriMappings, HttpSessionManager httpSessionManager,
            List<Object> jsonServices) throws AnyAvailablePortBindException {

        int bindPort;
        if (port == 0) {
            bindPort = getAnyAvailablePort();
        } else {
            try {
                testFreePort(port);
                bindPort = port;
            } catch (IOException e) {
                // log stack trace at debug level
                logger.debug(e.getMessage(), e);
                bindPort = getAnyAvailablePort();
            }
        }
        this.bindAddress = bindAddress;
        this.encodingHandler = new EncodingHandler(new ContentEncodingRepository()
                .addEncodingHandler("gzip", new GzipEncodingProvider(), 1, new GzipPredicate()));
        encodingHandler.setNext(new HttpServerHandler(layoutJsonService, uriMappings,
                httpSessionManager, jsonServices));
        undertow = createUndertow(bindPort, bindAddress, encodingHandler);
        undertow.start();
        if (port != 0 && bindPort != port) {
            logger.error("error binding to port: {} (bound to port {} instead)", port, bindPort);
        }
        this.port = bindPort;
        logger.debug("<init>(): http server bound");
    }

    int getPort() {
        return port;
    }

    Undertow changePort(int newPort) throws PortChangeFailedException {
        try {
            testFreePort(newPort);
        } catch (IOException e) {
            throw new PortChangeFailedException(e);
        }
        Undertow previousUndertow = undertow;
        undertow = createUndertow(newPort, bindAddress, encodingHandler);
        undertow.start();
        port = newPort;
        return previousUndertow;
    }

    void close() {
        logger.debug("close(): stopping http server");
        undertow.stop();
        logger.debug("close(): http server stopped");
    }

    private static Undertow createUndertow(int bindPort, String bindAddress,
            EncodingHandler encodingHandler) {
        return Undertow.builder()
                .addHttpListener(bindPort, bindAddress)
                .setHandler(encodingHandler)
                .setWorkerOption(Options.WORKER_NAME, "Glowroot-XNIO-Worker")
                .setWorkerOption(Options.THREAD_DAEMON, true)
                .setWorkerOption(Options.WORKER_IO_THREADS, 2)
                .setWorkerOption(Options.WORKER_TASK_CORE_THREADS, 2)
                .setWorkerOption(Options.WORKER_TASK_MAX_THREADS, 2)
                .build();
    }

    private static void testFreePort(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        serverSocket.close();
    }

    private static int getAnyAvailablePort() throws AnyAvailablePortBindException {
        int bindPort;
        try {
            ServerSocket serverSocket = new ServerSocket(0);
            bindPort = serverSocket.getLocalPort();
            serverSocket.close();
        } catch (IOException e) {
            throw new AnyAvailablePortBindException(e);
        }
        return bindPort;
    }

    @SuppressWarnings("serial")
    static class AnyAvailablePortBindException extends Exception {
        private AnyAvailablePortBindException(Exception cause) {
            super(cause);
        }
    }

    @VisibleForTesting
    @SuppressWarnings("serial")
    public static class PortChangeFailedException extends Exception {
        private PortChangeFailedException(Exception cause) {
            super(cause);
        }
    }

    private static class GzipPredicate implements Predicate {

        @Override
        public boolean resolve(HttpServerExchange exchange) {
            String contentType = exchange.getResponseHeaders().getFirst(Headers.CONTENT_TYPE);
            if (MediaType.ZIP.toString().equals(contentType)) {
                // never compress zip file downloads
                return false;
            }
            String length = exchange.getResponseHeaders().getFirst(Headers.CONTENT_LENGTH);
            if (length == null) {
                // this will be chunked transfer encoded
                return true;
            }
            return Long.parseLong(length) > 1024;
        }
    }
}
