/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

import org.glowroot.agent.plugin.api.weaving.Shim;

@Value.Immutable
abstract class ShimType {

    private static final Method shimValueMethod;

    static {
        try {
            shimValueMethod = Shim.class.getMethod("value");
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    static ShimType create(Shim shim, Class<?> iface) {
        ImmutableShimType.Builder builder = ImmutableShimType.builder();
        builder.addTargets(getValue(shim));
        builder.iface(Type.getType(iface));
        for (Method method : iface.getMethods()) {
            if (method.isAnnotationPresent(Shim.class)) {
                builder.addShimMethods(method);
            }
        }
        return builder.build();
    }

    abstract Type iface();
    abstract ImmutableList<String> targets();
    abstract ImmutableList<Method> shimMethods();

    // this is needed to support plugins compiled against glowroot-agent-plugin-api versions prior
    // to 0.9.22 (instead of just calling shim.value() directly)
    static String[] getValue(Shim shim) {
        Object value;
        try {
            value = shimValueMethod.invoke(shim);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        if (value instanceof String[]) {
            return (String[]) value;
        } else if (value instanceof String) {
            return new String[] {(String) value};
        } else if (value == null) {
            throw new IllegalStateException("Unexpected @Shim value: null");
        } else {
            throw new IllegalStateException("Unexpected @Shim value class: " + value.getClass());
        }
    }
}
