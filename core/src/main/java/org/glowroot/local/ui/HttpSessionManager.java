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

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.Singleton;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class HttpSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionManager.class);

    private final ConfigService configService;
    private final Clock clock;
    private final LayoutJsonService layoutJsonService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessionExpirations = Maps.newConcurrentMap();

    HttpSessionManager(ConfigService configService, Clock clock,
            LayoutJsonService layoutJsonService) {
        this.configService = configService;
        this.clock = clock;
        this.layoutJsonService = layoutJsonService;
    }

    void login(HttpServerExchange exchange) throws IOException {
        boolean success;
        exchange.startBlocking();
        String password = CharStreams.toString(new InputStreamReader(exchange.getInputStream(),
                Charsets.ISO_8859_1));
        try {
            success = configService.getUserInterfaceConfig().validatePassword(password);
        } catch (GeneralSecurityException e) {
            logger.error(e.getMessage(), e);
            exchange.setResponseCode(500);
            return;
        }
        if (success) {
            createSession(exchange);
            exchange.getResponseSender().send(layoutJsonService.getLayout());
        } else {
            exchange.getResponseSender().send("{\"incorrectPassword\":true}");
        }
    }

    boolean needsAuthentication(HttpServerExchange exchange) {
        if (!configService.getUserInterfaceConfig().isPasswordEnabled()) {
            return false;
        }
        String sessionId = getSessionId(exchange);
        if (sessionId == null) {
            return true;
        }
        Long expires = sessionExpirations.get(sessionId);
        if (expires == null) {
            return true;
        }
        if (expires < clock.currentTimeMillis()) {
            return true;
        } else {
            // session is valid and not expired, update expiration
            updateExpiration(sessionId);
            return false;
        }
    }

    void signOut(HttpServerExchange exchange) {
        String sessionId = getSessionId(exchange);
        if (sessionId != null) {
            sessionExpirations.remove(sessionId);
        }
        deleteSession(exchange);
    }

    void createSession(HttpServerExchange exchange) {
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        updateExpiration(sessionId);
        Cookie cookie = new CookieImpl("GLOWROOT_SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        exchange.setResponseCookie(cookie);
        // TODO clean up expired sessions
    }

    void deleteSession(HttpServerExchange exchange) {
        Cookie cookie = new CookieImpl("GLOWROOT_SESSION_ID", "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        exchange.setResponseCookie(cookie);
    }

    @Nullable
    String getSessionId(HttpServerExchange exchange) {
        Cookie cookie = exchange.getRequestCookies().get("GLOWROOT_SESSION_ID");
        if (cookie == null) {
            return null;
        }
        return cookie.getValue();
    }

    private void updateExpiration(String sessionId) {
        int timeoutMinutes = configService.getUserInterfaceConfig().getSessionTimeoutMinutes();
        if (timeoutMinutes == 0) {
            sessionExpirations.put(sessionId, Long.MAX_VALUE);
        } else {
            sessionExpirations.put(sessionId,
                    clock.currentTimeMillis() + MINUTES.toMillis(timeoutMinutes));
        }
    }
}
