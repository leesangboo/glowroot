/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import java.io.PrintWriter;
import java.security.CodeSource;
import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.WeavingClassVisitor.PointcutClassFoundException;
import org.glowroot.weaving.WeavingClassVisitor.ShortCircuitException;
import org.glowroot.weaving.WeavingTimerService.WeavingTimer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // this is an internal property sometimes useful for debugging errors in the weaver,
    // especially exceptions of type java.lang.VerifyError
    private static final boolean verifyWeaving =
            Boolean.getBoolean("glowroot.internal.weaving.verify");

    private final Supplier<ImmutableList<Advice>> advisors;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final WeavingTimerService weavingTimerService;
    private final boolean metricWrapperMethods;

    Weaver(Supplier<ImmutableList<Advice>> advisors, List<MixinType> mixinTypes,
            AnalyzedWorld analyzedWorld, WeavingTimerService weavingTimerService,
            boolean metricWrapperMethods) {
        this.advisors = advisors;
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.analyzedWorld = analyzedWorld;
        this.weavingTimerService = weavingTimerService;
        this.metricWrapperMethods = metricWrapperMethods;
    }

    byte/*@Nullable*/[] weave(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        if (metricWrapperMethods) {
            return weave$glowroot$metric$glowroot$weaving$0(classBytes, className, codeSource,
                    loader);
        } else {
            return weaveInternal(classBytes, className, codeSource, loader);
        }
    }

    // weird method name is following "metric marker" method naming
    private byte/*@Nullable*/[] weave$glowroot$metric$glowroot$weaving$0(byte[] classBytes,
            String className, @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        return weaveInternal(classBytes, className, codeSource, loader);
    }

    private byte/*@Nullable*/[] weaveInternal(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        WeavingTimer weavingTimer = weavingTimerService.start();
        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            WeavingClassVisitor cv = new WeavingClassVisitor(cw, advisors.get(), mixinTypes,
                    loader, analyzedWorld, codeSource, metricWrapperMethods);
            ClassReader cr = new ClassReader(classBytes);
            boolean shortCircuitException = false;
            boolean pointcutClassFoundException = false;
            try {
                cr.accept(cv, ClassReader.SKIP_FRAMES);
            } catch (ShortCircuitException e) {
                shortCircuitException = true;
            } catch (PointcutClassFoundException e) {
                pointcutClassFoundException = true;
            } catch (ClassCircularityError e) {
                logger.error(e.getMessage(), e);
                return null;
            }
            if (shortCircuitException || cv.isInterfaceSoNothingToWeave()) {
                return null;
            } else if (pointcutClassFoundException) {
                ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                PointcutClassVisitor cv2 = new PointcutClassVisitor(cw2);
                ClassReader cr2 = new ClassReader(classBytes);
                cr2.accept(cv2, ClassReader.SKIP_FRAMES);
                return cw2.toByteArray();
            } else {
                byte[] wovenBytes = cw.toByteArray();
                if (verifyWeaving) {
                    verifyBytecode(classBytes, className, false);
                    verifyBytecode(wovenBytes, className, true);
                }
                return wovenBytes;
            }
        } finally {
            weavingTimer.stop();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("advisors", advisors)
                .add("mixinTypes", mixinTypes)
                .add("analyzedWorld", analyzedWorld)
                .add("weavingTimerService", weavingTimerService)
                .add("metricWrapperMethods", metricWrapperMethods)
                .toString();
    }

    private static void verifyBytecode(byte[] bytes, String className, boolean woven) {
        ClassReader verifyClassReader = new ClassReader(bytes);
        try {
            CheckClassAdapter.verify(verifyClassReader, false, new PrintWriter(System.err));
        } catch (Exception e) {
            if (woven) {
                logger.warn("error verifying class {} (after weaving): {}", className,
                        e.getMessage(), e);
            } else {
                logger.warn("error verifying class {} (before weaving): {}", className,
                        e.getMessage(), e);
            }
        }
    }
}
