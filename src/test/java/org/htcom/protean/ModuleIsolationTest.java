/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module dependency — <b>isolation characterization</b>.
 *
 * Each module is compiled only against the application classpath (the in-memory bytecode of other
 * modules is not visible). Therefore, if module B imports a class from module A, compilation itself fails.
 * -> Sharing between modules must go through a shared SPI on the application classpath (the parent),
 * enforcing the SPI-boundary rule.
 */
@SpringBootTest
class ModuleIsolationTest {

    @Autowired RuntimeCompiler compiler;

    @Test
    void module_cannot_compile_against_another_modules_class() {
        // Module A's class — not on the application classpath
        String moduleA = """
                package runtime.iso.a;
                public class OnlyInModuleA {
                    public String hello() { return "A"; }
                }
                """;
        compiler.compileAll(Map.of("runtime.iso.a.OnlyInModuleA", moduleA)); // A compiles fine

        // Module B imports A's class -> separate compilation cannot see A's bytecode -> failure
        String moduleB = """
                package runtime.iso.b;
                import runtime.iso.a.OnlyInModuleA;
                public class UsesA {
                    private final OnlyInModuleA a = new OnlyInModuleA();
                    public String use() { return a.hello(); }
                }
                """;
        RuntimeCompiler.CompilationException ex = assertThrows(
                RuntimeCompiler.CompilationException.class,
                () -> compiler.compileAll(Map.of("runtime.iso.b.UsesA", moduleB)));

        assertTrue(ex.getMessage().contains("OnlyInModuleA") || ex.getMessage().contains("runtime.iso.a"),
                "Module B compilation must fail because A's class is absent. Actual: " + ex.getMessage());

        // Stable error code + structured diagnostics extension (file, line, message) for agent self-correction.
        assertEquals(ErrorCode.COMPILATION_FAILED, ex.code());
        List<?> diagnostics = assertInstanceOf(List.class, ex.extensions().get("diagnostics"));
        assertFalse(diagnostics.isEmpty(), "compilation diagnostics must be attached in structured form");
        assertInstanceOf(Map.class, diagnostics.get(0));
        assertTrue(((Map<?, ?>) diagnostics.get(0)).containsKey("line"), "diagnostic entry contains line");
    }
}
