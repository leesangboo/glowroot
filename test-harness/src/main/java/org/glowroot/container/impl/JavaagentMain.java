/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.container.impl;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.Executors;

import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.Viewer;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.ImmutableGeneralConfig;

import static java.util.concurrent.TimeUnit.SECONDS;

public class JavaagentMain {

    private static final Logger logger = LoggerFactory.getLogger(JavaagentMain.class);

    public static void main(String... args) throws Exception {
        boolean viewerMode = Boolean.getBoolean("glowroot.testHarness.viewerMode");
        if (viewerMode) {
            startViewer();
        } else {
            // traceStoreThresholdMillis=0 is the default for testing
            setTraceStoreThresholdMillisToZero();
        }
        int port = Integer.parseInt(args[0]);
        // socket is never closed since program is still running after main returns
        Socket socket = new Socket((String) null, port);
        ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
        ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
        new Thread(new SocketHeartbeat(objectOut)).start();
        new Thread(new SocketCommandProcessor(objectIn, objectOut)).start();
        if (!viewerMode) {
            // spin a bit to so that caller can capture a trace with <multiple root nodes> if
            // desired
            for (int i = 0; i < 1000; i++) {
                metricMarkerOne();
                metricMarkerTwo();
                Thread.sleep(1);
            }
            // non-daemon threads started above keep jvm alive after main returns
        }
    }

    static void setTraceStoreThresholdMillisToZero() throws Exception {
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        if (glowrootModule == null) {
            // failed to start, e.g. DataSourceLockTest
            return;
        }
        org.glowroot.config.ConfigService configService =
                glowrootModule.getConfigModule().getConfigService();
        GeneralConfig config = configService.getGeneralConfig();
        // conditional check is needed to prevent config file timestamp update when testing
        // ConfigFileLastModifiedTest.shouldNotUpdateFileOnStartupIfNoChanges()
        if (config.traceStoreThresholdMillis() != 0) {
            GeneralConfig updatedConfig =
                    ((ImmutableGeneralConfig) config).withTraceStoreThresholdMillis(0);
            configService.updateGeneralConfig(updatedConfig, config.version());
        }
    }

    private static void startViewer() throws AssertionError {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Viewer.main();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 5) {
            if (MainEntryPoint.getGlowrootModule() != null) {
                break;
            }
        }
        if (MainEntryPoint.getGlowrootModule() == null) {
            throw new AssertionError("Timeout occurred waiting for glowroot to start");
        }
    }

    private static void metricMarkerOne() throws InterruptedException {
        Thread.sleep(1);
    }

    private static void metricMarkerTwo() throws InterruptedException {
        Thread.sleep(1);
    }
}