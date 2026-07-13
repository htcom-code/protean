/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared-module finishing slice: a library-on-library {@code uses} DAG resolves at runtime (a library's runtime
 * ClassLoader is parented on the libraries it itself uses), cyclic {@code uses} is rejected, an {@code exports} that
 * would shadow a reserved package is rejected, and the shared-module bindings surface in module status.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedModuleDagTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String LIB_A = "lib-a";
    static final String LIB_B = "lib-b";
    static final String CONSUMER = "mod-dag-consumer";

    static final Map<String, String> STABLE_TEST = Map.of("pkg.NoopTest", """
            package pkg;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class NoopTest { @Test void ok() { assertTrue(true); } }
            """);

    static ModuleDescriptor libA() {
        return ModuleDescriptor.builder().id(LIB_A).version("1.0.0").kind(ModuleKind.LIBRARY)
                .exports(List.of("a"))
                .sources(Map.of("a.Base", "package a;\npublic class Base { public int base() { return 7; } }\n"))
                .tests(STABLE_TEST)
                .build();
    }

    /** Library B uses A: its class calls a.Base — which must resolve at runtime through B's own parent tier. */
    static ModuleDescriptor libB(List<String> uses) {
        return ModuleDescriptor.builder().id(LIB_B).version("1.0.0").kind(ModuleKind.LIBRARY)
                .exports(List.of("b")).uses(uses)
                .sources(Map.of("b.Mid", "package b;\nimport a.Base;\npublic class Mid { public int mid() { return new Base().base() * 2; } }\n"))
                .tests(STABLE_TEST)
                .build();
    }

    static ModuleDescriptor consumer() {
        return ModuleDescriptor.builder().id(CONSUMER).version("1.0.0").kind(ModuleKind.NORMAL)
                .uses(List.of(LIB_B))
                .controllerFqcn("runtime.dag.DagController")
                .componentFqcns(List.of("runtime.dag.DagController"))
                .sources(Map.of("runtime.dag.DagController", """
                        package runtime.dag;
                        import b.Mid;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class DagController {
                            @GetMapping("/dag/value") public String value() { return String.valueOf(new Mid().mid()); }
                        }
                        """))
                .tests(Map.of("runtime.dag.DagControllerTest", """
                        package runtime.dag;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class DagControllerTest { @Test void ok() { assertTrue(true); } }
                        """))
                .build();
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of(CONSUMER, LIB_B, LIB_A)) {   // dependents first
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** A library→library DAG resolves at runtime: the consumer calls b.Mid, which calls a.Base across two hops. */
    @Test
    void library_on_library_dag_resolves_at_runtime() throws Exception {
        platform.install(libA());
        platform.install(libB(List.of(LIB_A)));
        platform.install(consumer());
        // Mid.mid() = Base.base() * 2 = 7 * 2 = 14. Base is defined by lib-a's CL, reached through lib-b's parent tier.
        mockMvc.perform(get("/dag/value")).andExpect(status().isOk()).andExpect(content().string("14"));
    }

    /** A cyclic uses (A↔B) is rejected — library dependencies must form a DAG. */
    @Test
    void cyclic_uses_is_rejected() {
        platform.install(libA());
        platform.install(libB(List.of(LIB_A)));   // b → a (fine)
        // Updating lib-a to use lib-b would form a → b → a. Rejected before compile.
        ModuleDescriptor cyclicA = libA().toBuilder().version("2.0.0").uses(List.of(LIB_B)).build();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> platform.update(cyclicA));
        assertTrue(ex.getMessage().toLowerCase().contains("cyclic"), "error names the cycle: " + ex.getMessage());
    }

    /** A library exporting a reserved package (would shadow core/framework types for its dependents) is rejected. */
    @Test
    void exporting_reserved_package_is_rejected() {
        ModuleDescriptor bad = ModuleDescriptor.builder().id("lib-evil").version("1.0.0").kind(ModuleKind.LIBRARY)
                .exports(List.of("java.util"))
                .sources(Map.of("a.Base", "package a;\npublic class Base { public int base() { return 1; } }\n"))
                .tests(STABLE_TEST)
                .build();
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> platform.install(bad));
            assertTrue(ex.getMessage().toLowerCase().contains("reserved")
                            || (ex.getCause() != null && ex.getCause().getMessage().toLowerCase().contains("reserved")),
                    "error names the reserved export: " + ex.getMessage());
        } finally {
            if (platform.find("lib-evil").isPresent()) {
                platform.uninstall("lib-evil");
            }
        }
    }

    /** Module status surfaces the shared-module bindings: kind/exports for a library, uses + bound library gens for a dependent. */
    @Test
    void status_exposes_shared_module_bindings() {
        platform.install(libA());
        platform.install(consumer_using_A());

        Long libGen = platform.libraryGeneration(LIB_A);
        assertNotNull(libGen, "library publishes a generation id");
        List<Long> bound = platform.boundLibraryGenerations(CONSUMER);
        assertFalse(bound.isEmpty(), "dependent is bound to a library generation");
        assertTrue(bound.contains(libGen), "dependent is bound to the library's current generation");

        ModuleStatus libStatus = ModuleStatus.from(platform.find(LIB_A).orElseThrow(),
                platform.effectiveMode(platform.find(LIB_A).orElseThrow()), null,
                platform.boundLibraryGenerations(LIB_A), libGen);
        assertEquals(ModuleKind.LIBRARY, libStatus.kind());
        assertEquals(List.of("a"), libStatus.exports());
        assertEquals(libGen, libStatus.libraryGeneration());

        ModuleStatus depStatus = ModuleStatus.from(platform.find(CONSUMER).orElseThrow(),
                platform.effectiveMode(platform.find(CONSUMER).orElseThrow()),
                platform.boundGeneration(CONSUMER), bound, null);
        assertEquals(ModuleKind.NORMAL, depStatus.kind());
        assertEquals(List.of(LIB_A), depStatus.uses());
    }

    /** A normal consumer that uses lib-a directly (for the status test — one hop, no lib-b). */
    static ModuleDescriptor consumer_using_A() {
        return ModuleDescriptor.builder().id(CONSUMER).version("1.0.0").kind(ModuleKind.NORMAL)
                .uses(List.of(LIB_A))
                .controllerFqcn("runtime.dag.AController")
                .componentFqcns(List.of("runtime.dag.AController"))
                .sources(Map.of("runtime.dag.AController", """
                        package runtime.dag;
                        import a.Base;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class AController {
                            @GetMapping("/dag/a") public String a() { return String.valueOf(new Base().base()); }
                        }
                        """))
                .tests(Map.of("runtime.dag.AControllerTest", """
                        package runtime.dag;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class AControllerTest { @Test void ok() { assertTrue(true); } }
                        """))
                .build();
    }
}
