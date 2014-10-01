/*
 * Copyright 2014 the original author or authors.
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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// this is needed as hacky workaround for now, see https://github.com/xnio/xnio/pull/68
class XnioMonkeyPatch {

    static byte[] transformXnioClass(byte[] bytes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new XnioClassVisitor(cw), ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static class XnioClassVisitor extends ClassVisitor {

        private XnioClassVisitor(ClassWriter cw) {
            super(ASM5, cw);
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("<clinit>")) {
                return new XnioClinitMethodVisitor(mv);
            } else if (name.equals("register")) {
                mv.visitCode();
                mv.visitMethodInsn(INVOKESTATIC, "org/xnio/IoUtils", "nullCloseable",
                        "()Ljava/io/Closeable;", false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            return mv;
        }
    }

    private static class XnioClinitMethodVisitor extends MethodVisitor {

        private XnioClinitMethodVisitor(MethodVisitor mv) {
            super(ASM5, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            if (name.equals("doPrivileged")) {
                visitInsn(POP);
                visitInsn(ACONST_NULL);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }
}
