/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.SharedLibStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the interaction bug where a consumer that uses BOTH a shared-lib JAR and a shared-module
 * (kind=LIBRARY via {@code uses}) must still
 * eagerly rebind on a new JAR generation even after it was previously eager-rebound onto a new shared-module
 * (library) generation via Plan A1. Reproduces the live scenario S3→S4→S5.
 */
@SpringBootTest(properties = "protean.isolation.mode=worker")   // match the live debug profile: global default = worker
@AutoConfigureMockMvc
class SharedLibEagerAfterSharedModuleRebindTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("protean-eager-interaction-test");
        registry.add("protean.module.shared-lib-store-dir", dir::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired SharedLibStore store;
    @Autowired ModulePlatform platform;
    @Autowired RuntimeCompiler compiler;

    static final String LIB = "lib-both";
    static final String CONSUMER = "both-consumer";

    @AfterEach
    void cleanup() {
        for (String id : List.of(CONSUMER, LIB)) {
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
        // reset the shared-lib store so methods don't contaminate each other via the global generation
        try {
            store.remove("acme");
        } catch (RuntimeException ignored) {
        }
    }

    /** A jar with {@code ext.inv.Widget} exposing the given method body. */
    private static byte[] widgetJar(String methodDecl) throws Exception {
        Path base = Files.createTempDirectory("protean-widget");
        Path src = base.resolve("Widget.java");
        Files.writeString(src, "package ext.inv; public class Widget { " + methodDecl + " }");
        Path out = Files.createDirectories(base.resolve("classes"));
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString()) != 0) {
            throw new IllegalStateException("widget fixture compile failed");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            jos.putNextEntry(new JarEntry("ext/inv/Widget.class"));
            jos.write(Files.readAllBytes(out.resolve("ext/inv/Widget.class")));
            jos.closeEntry();
        }
        return bos.toByteArray();
    }

    private void deployWidget(String version, String methodDecl) throws Exception {
        store.deploy(List.of(new SharedLibStore.IncomingLib("acme", version, widgetJar(methodDecl), null, null)));
    }

    private static ModuleDescriptor library(String baseBody, String version) {
        return ModuleDescriptor.builder()
                .id(LIB).version(version).kind(ModuleKind.LIBRARY).exports(List.of("geo"))
                .isolationMode("in-process")
                .sources(Map.of("geo.Calc", "package geo;\npublic class Calc { " + baseBody + " }\n"))
                .tests(Map.of("geo.CalcTest", """
                        package geo;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertNotNull;
                        class CalcTest { @Test void ok() { assertNotNull(new Calc()); } }
                        """))
                .build();
    }

    /** Consumer uses BOTH: geo.Calc (shared-module) and ext.inv.Widget (shared-lib JAR). value = v()*10 + base(). */
    private static ModuleDescriptor consumer(String version) {
        return ModuleDescriptor.builder()
                .id(CONSUMER).version(version).kind(ModuleKind.NORMAL).uses(List.of(LIB))
                .isolationMode("in-process")
                .controllerFqcn("runtime.both.BothController")
                .componentFqcns(List.of("runtime.both.BothController"))
                .sources(Map.of(
                        "runtime.both.BothConsumer", """
                                package runtime.both;
                                import ext.inv.Widget;
                                import geo.Calc;
                                public class BothConsumer {
                                    public int value() { return new Widget().v() * 10 + new Calc().base(); }
                                }
                                """,
                        "runtime.both.BothController", """
                                package runtime.both;
                                import org.springframework.web.bind.annotation.GetMapping;
                                import org.springframework.web.bind.annotation.RestController;
                                @RestController
                                public class BothController {
                                    @GetMapping("/both/val") public String value() { return String.valueOf(new BothConsumer().value()); }
                                }
                                """))
                .tests(Map.of("runtime.both.BothConsumerTest", """
                        package runtime.both;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class BothConsumerTest { @Test void nonneg() { assertTrue(new BothConsumer().value() >= 0); } }
                        """))
                .build();
    }

    /** Same consumer, but a STRICT gate: it hard-codes value()==11 (v=1, base=1), so any shared change
     *  that alters the computed value makes its Plan A rebind fail the gate → Plan B sticky. */
    private static ModuleDescriptor consumerStrict(String version) {
        return ModuleDescriptor.builder()
                .id(CONSUMER).version(version).kind(ModuleKind.NORMAL).uses(List.of(LIB))
                .isolationMode("in-process")
                .controllerFqcn("runtime.both.BothController")
                .componentFqcns(List.of("runtime.both.BothController"))
                .sources(consumer(version).sources())
                .tests(Map.of("runtime.both.BothConsumerTest", """
                        package runtime.both;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        class BothConsumerTest { @Test void exact() { assertEquals(11, new BothConsumer().value()); } }
                        """))
                .build();
    }

    @Test
    void jar_eager_rebind_still_reaches_a_consumer_previously_rebound_by_a_shared_module_swap() throws Exception {
        deployWidget("1.0.0", "public int v() { return 1; }");
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(consumer("1.0.0"));
        // v=1, base=1 -> 1*10+1 = 11
        mockMvc.perform(get("/both/val")).andExpect(status().isOk()).andExpect(content().string("11"));

        long jarGenBefore = compiler.boundGeneration(CONSUMER).orElseThrow();

        // (S4) shared-module live swap: base 1 -> 2, binary-compatible => Plan A1 (retarget, no recompile).
        platform.update(library("public int base() { return 2; }", "2.0.0"));
        mockMvc.perform(get("/both/val")).andExpect(status().isOk()).andExpect(content().string("12"));

        // (S5) shared-lib live swap: v 1 -> 2, binary-compatible => must eagerly rebind the consumer.
        deployWidget("2.0.0", "public int v() { return 2; }");

        // EXPECTED (post-fix): 2*10+2 = 22 and the bound JAR generation advanced. Under the bug it stays "12"/genBefore.
        mockMvc.perform(get("/both/val")).andExpect(status().isOk()).andExpect(content().string("22"));
        assertTrue(compiler.boundGeneration(CONSUMER).orElseThrow() > jarGenBefore,
                "consumer must move onto the newer JAR generation even after a prior shared-module rebind");
    }

    /**
     * The flip side (documents correct zero-downtime behavior, NOT a bug): a consumer whose bundled gate
     * asserts an exact value derived from shared code goes <b>Plan B sticky</b> when a binary-compatible
     * JAR change alters that value — the eager Plan A rebind recompiles and re-runs the gate, which now
     * fails, so the module stays on its prior generation and keeps serving.
     */
    @Test
    void a_strict_gate_consumer_stays_sticky_when_a_shared_lib_change_alters_its_asserted_value() throws Exception {
        deployWidget("1.0.0", "public int v() { return 1; }");
        platform.install(library("public int base() { return 1; }", "1.0.0"));
        platform.install(consumerStrict("1.0.0"));   // gate asserts value()==11 (v=1, base=1)
        mockMvc.perform(get("/both/val")).andExpect(status().isOk()).andExpect(content().string("11"));
        long genBefore = compiler.boundGeneration(CONSUMER).orElseThrow();

        // Binary-compatible JAR change, but it flips the consumer's computed value 11 -> 21; the consumer's
        // own gate asserts 11, so the eager Plan A rebind's gate fails -> Plan B keeps it on the prior gen.
        deployWidget("2.0.0", "public int v() { return 2; }");

        mockMvc.perform(get("/both/val")).andExpect(status().isOk()).andExpect(content().string("11"));   // sticky
        assertEquals(genBefore, compiler.boundGeneration(CONSUMER).orElseThrow(),
                "a strict gate that rejects the new value keeps the module on its prior generation (Plan B)");
        assertTrue(platform.find(CONSUMER).isPresent(), "still installed and serving (zero-downtime)");
    }
}
