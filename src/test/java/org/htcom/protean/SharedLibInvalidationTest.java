/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleSharedLibs;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleDescriptor;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Precise invalidation end-to-end. Publishing a new shared-lib generation eagerly
 * rebinds only the ACTIVE modules that use the changed jar (Plan A), leaves unrelated modules untouched, and — when
 * the new jar is incompatible — keeps the module serving on its prior generation (Plan B sticky, zero-downtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedLibInvalidationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("protean-invalidation-test");
        registry.add("protean.module.shared-lib-store-dir", dir::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired SharedLibStore store;
    @Autowired ModulePlatform platform;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleSharedLibs sharedLibs;

    @AfterEach
    void cleanup() {
        for (String id : List.of("inv-user", "inv-bystander")) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }

    /** A jar with {@code ext.inv.Widget} exposing the given method body (used to vary the API across versions). */
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

    /** A module whose route returns {@code new Widget().v()} — recompiled/rebound as the acme jar changes. */
    private static ModuleDescriptor userModule() {
        return ModuleDescriptor.builder()
                .id("inv-user").version("1.0.0")
                .controllerFqcn("runtime.inv.UserController")
                .componentFqcns(List.of("runtime.inv.UserController"))
                .sources(Map.of("runtime.inv.UserController", """
                        package runtime.inv;
                        import ext.inv.Widget;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class UserController {
                            @GetMapping("/inv/val") public int val() { return new Widget().v(); }
                        }
                        """))
                .tests(Map.of("runtime.inv.UserControllerTest", """
                        package runtime.inv;
                        import ext.inv.Widget;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        public class UserControllerTest {
                            @Test void v() { assertTrue(new Widget().v() >= 0); }
                        }
                        """))
                .build();
    }

    @Test
    void compatible_change_eagerly_rebinds_the_using_module_without_redeploy() throws Exception {
        deployWidget("1.0.0", "public int v() { return 1; }");
        platform.install(userModule());
        long genV1 = compiler.boundGeneration("inv-user").orElseThrow();
        mockMvc.perform(get("/inv/val")).andExpect(status().isOk()).andExpect(content().string("1"));

        // Publish a compatible v2 of the same jar → invalidation rebinds inv-user with no explicit redeploy.
        deployWidget("2.0.0", "public int v() { return 2; }");
        mockMvc.perform(get("/inv/val")).andExpect(status().isOk()).andExpect(content().string("2"));
        long genV2 = compiler.boundGeneration("inv-user").orElseThrow();
        assertTrue(genV2 > genV1, "the module moved onto the newer generation");

        // Leak-safe close: the vacated old generation has zero references now, so invalidation unloaded it.
        assertTrue(sharedLibs.generation(genV1).isEmpty(),
                "the old generation is unloaded once no module is bound to it");

        // Observability: get_module exposes the module's bound generation.
        mockMvc.perform(get("/platform/modules/inv-user")).andExpect(status().isOk())
                .andExpect(jsonPath("$.boundGeneration").value((int) genV2));
    }

    @Test
    void a_module_that_does_not_use_the_jar_is_never_touched() throws Exception {
        deployWidget("1.0.0", "public int v() { return 1; }");
        // A bystander module that references no shared lib.
        platform.install(ModuleDescriptor.builder()
                .id("inv-bystander").version("1.0.0")
                .controllerFqcn("runtime.inv.Bystander")
                .componentFqcns(List.of("runtime.inv.Bystander"))
                .sources(Map.of("runtime.inv.Bystander", """
                        package runtime.inv;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class Bystander {
                            @GetMapping("/inv/bystander") public String hi() { return "hi"; }
                        }
                        """))
                .tests(Map.of("runtime.inv.BystanderTest", """
                        package runtime.inv;
                        import org.junit.jupiter.api.Test;
                        public class BystanderTest { @Test void ok() { } }
                        """))
                .build());
        long gen = compiler.boundGeneration("inv-bystander").orElseThrow();

        deployWidget("2.0.0", "public int v() { return 2; }");   // changes acme, which the bystander does not use
        assertEquals(gen, compiler.boundGeneration("inv-bystander").orElseThrow(),
                "a module using none of the changed jars is not rebound");
    }

    @Test
    void incompatible_change_is_sticky_the_module_keeps_serving_the_prior_generation() throws Exception {
        deployWidget("1.0.0", "public int v() { return 1; }");
        platform.install(userModule());
        mockMvc.perform(get("/inv/val")).andExpect(status().isOk()).andExpect(content().string("1"));
        long boundBefore = compiler.boundGeneration("inv-user").orElseThrow();

        // Publish an incompatible jar (no v()) → Plan A recompile fails pre-swap → Plan B sticky.
        deployWidget("3.0.0", "public int w() { return 3; }");

        // Zero-downtime: the module still serves value 1 on its prior generation, still ACTIVE, not rebound.
        mockMvc.perform(get("/inv/val")).andExpect(status().isOk()).andExpect(content().string("1"));
        assertEquals(boundBefore, compiler.boundGeneration("inv-user").orElseThrow(),
                "an incompatible jar leaves the module on its prior generation (sticky)");
        assertTrue(platform.find("inv-user").isPresent(), "the module stays installed (not hard-deactivated)");
    }
}
