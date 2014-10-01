/*
 * Copyright 2013-2014 the original author or authors.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.glowroot.local.ui.HttpServerHandler.HttpMethod.GET;
import static org.glowroot.local.ui.HttpServerHandler.HttpMethod.POST;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.DateUtils;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.ObjectMappers;
import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.common.Reflections.ReflectiveTargetException;
import org.glowroot.markers.Singleton;
import org.h2.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class HttpServerHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final long TEN_YEARS = DAYS.toMillis(365 * 10);
    private static final long ONE_DAY = DAYS.toMillis(1);
    private static final long FIVE_MINUTES = MINUTES.toMillis(5);

    private static final ImmutableMap<String, String> mimeTypes =
            ImmutableMap.<String, String>builder()
                    .put("html", "text/html; charset=UTF-8")
                    .put("js", "application/javascript; charset=UTF-8")
                    .put("css", "text/css; charset=UTF-8")
                    .put("png", "image/png")
                    .put("ico", "image/x-icon")
                    .put("woff", "application/font-woff")
                    .put("eot", "application/vnd.ms-fontobject")
                    .put("ttf", "application/x-font-ttf")
                    .put("swf", "application/x-shockwave-flash")
                    .put("map", "application/json")
                    .build();

    private final LayoutJsonService layoutJsonService;
    private final ImmutableMap<Pattern, Object> uriMappings;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;
    private final HttpSessionManager httpSessionManager;

    HttpServerHandler(LayoutJsonService layoutJsonService,
            ImmutableMap<Pattern, Object> uriMappings, HttpSessionManager httpSessionManager,
            List<Object> jsonServices) {
        this.layoutJsonService = layoutJsonService;
        this.uriMappings = uriMappings;
        this.httpSessionManager = httpSessionManager;
        List<JsonServiceMapping> jsonServiceMappings = Lists.newArrayList();
        for (Object jsonService : jsonServices) {
            for (Method method : jsonService.getClass().getDeclaredMethods()) {
                GET annotationGET = method.getAnnotation(GET.class);
                if (annotationGET != null) {
                    jsonServiceMappings.add(new JsonServiceMapping(GET, annotationGET.value(),
                            jsonService, method.getName()));
                }
                POST annotationPOST = method.getAnnotation(POST.class);
                if (annotationPOST != null) {
                    jsonServiceMappings.add(new JsonServiceMapping(POST, annotationPOST.value(),
                            jsonService, method.getName()));
                }
            }
        }

        this.jsonServiceMappings = ImmutableList.copyOf(jsonServiceMappings);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        logger.debug("handleRequest(): requestPath={}", exchange.getRequestPath());
        String path = exchange.getRequestPath();
        // TODO this is hacky and points to flaw of using '+' in urls
        // e.g. /backend/config/capture-points/+
        path = path.replace(' ', '+');
        logger.debug("handleRequest(): path={}", path);
        if (path.equals("/backend/login")) {
            httpSessionManager.login(exchange);
            return;
        }
        if (path.equals("/backend/sign-out")) {
            httpSessionManager.signOut(exchange);
            return;
        }
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof HttpHandler) {
                    if (httpSessionManager.needsAuthentication(exchange)) {
                        handleUnauthorized(exchange);
                        return;
                    }
                    exchange.dispatch((HttpHandler) uriMappingEntry.getValue());
                    return;
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    handleStaticResource(resourcePath, exchange);
                    return;
                }
            }
        }
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            if (!jsonServiceMapping.httpMethod.name().equals(
                    exchange.getRequestMethod().toString())) {
                continue;
            }
            Matcher matcher = jsonServiceMapping.pattern.matcher(path);
            if (matcher.matches()) {
                if (httpSessionManager.needsAuthentication(exchange)) {
                    handleUnauthorized(exchange);
                    return;
                }
                String requestText = getRequestText(exchange);
                String[] args = new String[matcher.groupCount()];
                for (int i = 0; i < args.length; i++) {
                    String group = matcher.group(i + 1);
                    checkNotNull(group);
                    args[i] = group;
                }
                handleJsonRequest(jsonServiceMapping.service, jsonServiceMapping.methodName, args,
                        requestText, exchange);
                return;
            }
        }
        logger.warn("unexpected path: {}", path);
        exchange.setResponseCode(StatusCodes.NOT_FOUND);
    }

    private void handleUnauthorized(HttpServerExchange exchange) {
        exchange.setResponseCode(StatusCodes.UNAUTHORIZED);
        if (httpSessionManager.getSessionId(exchange) != null) {
            exchange.getResponseSender().send("{\"timedOut\":true}");
        }
    }

    private void handleStaticResource(String path, HttpServerExchange exchange) throws IOException {
        int extensionStartIndex = path.lastIndexOf('.');
        if (extensionStartIndex == -1) {
            logger.warn("path has no extension: {}", path);
            exchange.setResponseCode(StatusCodes.NOT_FOUND);
            return;
        }
        String extension = path.substring(extensionStartIndex + 1);
        String mimeType = mimeTypes.get(extension);
        if (mimeType == null) {
            logger.warn("path {} has unexpected extension: {}", path, extension);
            exchange.setResponseCode(StatusCodes.NOT_FOUND);
            return;
        }
        if (path.startsWith("org/glowroot/local/ui/app-dist/favicon.")) {
            Date expiresDate = new Date(System.currentTimeMillis() + ONE_DAY);
            exchange.getResponseHeaders().put(Headers.EXPIRES, DateUtils.toDateString(expiresDate));
        } else if (path.endsWith(".js.map") || path.startsWith("/sources/")) {
            // javascript source maps and source files are not versioned
            Date expiresDate = new Date(System.currentTimeMillis() + FIVE_MINUTES);
            exchange.getResponseHeaders().put(Headers.EXPIRES, DateUtils.toDateString(expiresDate));
        } else {
            // all other static resources are versioned and can be safely cached forever
            String filename = path.substring(path.lastIndexOf('/') + 1);
            int to = filename.lastIndexOf('.');
            int from = filename.lastIndexOf('.', to - 1);
            String rev = filename.substring(from + 1, to);
            exchange.getResponseHeaders().put(Headers.ETAG, rev);
            Date expiresDate = new Date(System.currentTimeMillis() + TEN_YEARS);
            exchange.getResponseHeaders().put(Headers.EXPIRES, DateUtils.toDateString(expiresDate));

            if (rev.equals(exchange.getRequestHeaders().get(Headers.IF_NONE_MATCH))) {
                exchange.setResponseCode(StatusCodes.NOT_MODIFIED);
                return;
            }
        }
        URL url;
        ClassLoader classLoader = HttpServerHandler.class.getClassLoader();
        if (classLoader == null) {
            url = ClassLoader.getSystemResource(path);
        } else {
            url = classLoader.getResource(path);
        }
        if (url == null) {
            logger.warn("unexpected path: {}", path);
            exchange.setResponseCode(StatusCodes.NOT_FOUND);
            return;
        }
        byte[] staticContent = Resources.toByteArray(url);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mimeType);
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, staticContent.length);
        exchange.getResponseSender().send(ByteBuffer.wrap(staticContent));
    }

    private void handleJsonRequest(Object jsonService, String serviceMethodName, String[] args,
            String requestText, HttpServerExchange exchange) {

        logger.debug("handleJsonRequest(): serviceMethodName={}, args={}, requestText={}",
                serviceMethodName, args, requestText);

        Object responseText;
        try {
            responseText = callMethod(jsonService, serviceMethodName, args, requestText, exchange);
        } catch (ReflectiveTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JsonServiceException) {
                // this is an "expected" exception, no need to log
            	JsonServiceException jsonServiceException = (JsonServiceException) cause;
            	newHttpResponseFromJsonServiceException(exchange,
            			jsonServiceException.getStatus(), jsonServiceException.getMessage());
                return;
            }
            logger.error(e.getMessage(), e);
            if (cause instanceof SQLException
                    && ((SQLException) cause).getErrorCode() == ErrorCode.STATEMENT_WAS_CANCELED) {
                newHttpResponseWithStackTrace(exchange, StatusCodes.REQUEST_TIME_OUT, e,
                        "Query timed out (timeout is configurable under Configuration > Advanced)");
                return;
            }
            newHttpResponseWithStackTrace(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e, null);
            return;
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
            newHttpResponseWithStackTrace(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e, null);
            return;
        }
        if (responseText == null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
                    "application/json; charset=UTF-8");
            exchange.getResponseHeaders().put(HttpString.tryFromString("Glowroot-Layout-Version"),
                    layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(exchange);
            return;
        }
        if (responseText instanceof String) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
                    "application/json; charset=UTF-8");
            exchange.getResponseHeaders().put(HttpString.tryFromString("Glowroot-Layout-Version"),
                    layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(exchange);
            exchange.getResponseSender().send(responseText.toString());
            return;
        }
        if (responseText instanceof byte[]) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
                    "application/json; charset=UTF-8");
            exchange.getResponseHeaders().put(HttpString.tryFromString("Glowroot-Layout-Version"),
                    layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(exchange);
            exchange.getResponseSender().send(ByteBuffer.wrap((byte[]) responseText));
            return;
        }
        logger.warn("unexpected type of json service response: {}",
                responseText.getClass().getName());
        exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR);
    }

    private static void newHttpResponseFromJsonServiceException(HttpServerExchange exchange,
    		int statusCode, @Nullable String message) {
        // this is an "expected" exception, no need to send back stack trace
        StringBuilder sb = new StringBuilder();
        try {
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("message", message);
            jg.writeEndObject();
            jg.close();
            exchange.setResponseCode(statusCode);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
                    "application/json; charset=UTF-8");
            exchange.getResponseSender().send(sb.toString());
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR);
        }
    }

    private static void newHttpResponseWithStackTrace(HttpServerExchange exchange,
            int statusCode, Exception e, @Nullable String simplifiedMessage) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        try {
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            String message;
            if (simplifiedMessage == null) {
                Throwable cause = e;
                Throwable childCause = cause.getCause();
                while (childCause != null) {
                    cause = childCause;
                    childCause = cause.getCause();
                }
                message = cause.getMessage();
            } else {
                message = simplifiedMessage;
            }
            jg.writeStringField("message", message);
            jg.writeStringField("stackTrace", sw.toString());
            jg.writeEndObject();
            jg.close();
            exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
                    "application/json; charset=UTF-8");
            exchange.getResponseSender().send(sb.toString());
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            exchange.setResponseCode(StatusCodes.INTERNAL_SERVER_ERROR);
        }
    }

    @Nullable
    private static Object callMethod(Object object, String methodName, String[] args,
            String requestText, HttpServerExchange exchange) throws ReflectiveException {
        List<Class<?>> parameterTypes = Lists.newArrayList();
        List<Object> parameters = Lists.newArrayList();
        for (int i = 0; i < args.length; i++) {
            parameterTypes.add(String.class);
            parameters.add(args[i]);
        }
        Method method = null;
        try {
            method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                    parameterTypes.toArray(new Class[parameterTypes.size()]));
        } catch (ReflectiveException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            // try again with requestText
            parameterTypes.add(String.class);
            parameters.add(requestText);
            try {
                method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                        parameterTypes.toArray(new Class[parameterTypes.size()]));
            } catch (ReflectiveException f) {
                // log exception at debug level
                logger.debug(f.getMessage(), f);
                // try again with exchange
                parameterTypes.add(HttpServerExchange.class);
                parameters.add(exchange);
                try {
                    method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                            parameterTypes.toArray(new Class[parameterTypes.size()]));
                } catch (ReflectiveException g) {
                    // log exception at debug level
                    logger.debug(g.getMessage(), g);
                    throw new ReflectiveException(new NoSuchMethodException());
                }
            }
        }
        return Reflections.invoke(method, object,
                parameters.toArray(new Object[parameters.size()]));
    }

    private static String getRequestText(HttpServerExchange exchange)
            throws IOException {
        if (exchange.getRequestMethod() == Methods.POST) {
            exchange.startBlocking();
            return CharStreams.toString(new InputStreamReader(exchange.getInputStream(),
                    Charsets.ISO_8859_1));
        } else {
            // create json message out of the query string
            // flatten map values from list to single element where possible
            Map<String, Object> parameters = Maps.newHashMap();
            for (Entry<String, Deque<String>> entry : exchange.getQueryParameters().entrySet()) {
                String key = entry.getKey();
                key = CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, key);
                // special rule for "-mbean" so that it will convert to "...MBean"
                key = key.replace("Mbean", "MBean");
                if (entry.getValue().size() == 1) {
                    parameters.put(key, entry.getValue().getFirst());
                } else {
                    parameters.put(key, entry.getValue());
                }
            }
            return mapper.writeValueAsString(parameters);
        }
    }

    private static class JsonServiceMapping {

        private final HttpMethod httpMethod;
        private final Pattern pattern;
        private final Object service;
        private final String methodName;

        private JsonServiceMapping(HttpMethod httpMethod, String path, Object service,
                String methodName) {
            this.httpMethod = httpMethod;
            this.service = service;
            this.methodName = methodName;
            if (path.contains("(")) {
                pattern = Pattern.compile(path);
            } else {
                pattern = Pattern.compile(Pattern.quote(path));
            }

        }
    }

    static enum HttpMethod {
        GET, POST
    }
}
