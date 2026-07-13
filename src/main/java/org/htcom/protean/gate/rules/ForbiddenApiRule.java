/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate.rules;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Built-in guardrail rule — statically rejects *accidental* dangerous API calls.
 * (Not a security sandbox, but a rail to prevent mistakes by trusted developers. Reflection bypass is out of scope.)
 */
@Component
public class ForbiddenApiRule implements CodeRule {

    /** owner(internal name) -> set of forbidden method names. */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "java/lang/System", List.of("exit"),
            // addShutdownHook: registers JVM-globally → hard reference leak of the module ClassLoader.
            // For background work, use the injectable ProteanTaskExecutor, which is reclaimed automatically on unload.
            "java/lang/Runtime", List.of("halt", "exec", "addShutdownHook"),
            "java/lang/ProcessBuilder", List.of("start")
    );

    @Override
    public String name() {
        return "forbidden-api";
    }

    @Override
    public List<String> check(String className, byte[] bytecode) {
        List<String> violations = new ArrayList<>();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String desc, boolean isInterface) {
                        List<String> banned = FORBIDDEN.get(owner);
                        if (banned != null && banned.contains(name)) {
                            violations.add(className + "#" + methodName
                                    + " forbidden call: " + owner.replace('/', '.') + "." + name);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return violations;
    }
}
