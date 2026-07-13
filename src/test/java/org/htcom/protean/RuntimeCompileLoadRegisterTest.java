/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.CompiledModule;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Check #2: compile a source string at runtime -> load it via a dedicated ClassLoader -> register it on the live server.
 * Creates a controller that does not already exist on the classpath.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuntimeCompileLoadRegisterTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DynamicEndpointRegistrar registrar;

    @Autowired
    RuntimeCompiler compiler;

    static final String FQCN = "runtime.gen.GenController";

    static final String SOURCE = """
            package runtime.gen;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.ResponseBody;

            public class GenController {

                @GetMapping("/gen/ping")
                @ResponseBody
                public String ping() {
                    return "pong-compiled-at-runtime";
                }
            }
            """;

    @Test
    void compile_load_register_then_unregister() throws Exception {
        // before registration: 404
        mockMvc.perform(get("/gen/ping")).andExpect(status().isNotFound());

        // runtime compile + load
        CompiledModule module = compiler.compile(FQCN, SOURCE);

        // loaded via a dedicated ClassLoader — must differ from the app ClassLoader
        assertNotSame(getClass().getClassLoader(), module.classLoader(),
                "the module class must be loaded via a dedicated ClassLoader");
        assertSame(module.classLoader(), module.mainClass().getClassLoader());

        // create an instance, then register
        Object handler = module.newInstance();
        int registered = registrar.register("gen-module", handler);
        assertEquals(1, registered);

        // after registration: 200 + body
        mockMvc.perform(get("/gen/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong-compiled-at-runtime"));

        // after unregistration: 404
        registrar.unregister("gen-module");
        mockMvc.perform(get("/gen/ping")).andExpect(status().isNotFound());
    }

    /**
     * Leak canary: register then unregister a module, drop all strong references, and check the ClassLoader is reclaimed.
     *
     * The test JVM runs with -XX:SoftRefLRUPolicyMSPerMB=0 (see root build.gradle), so System.gc() clears the
     * SoftReferences in Spring's internal caches that would otherwise pin the ClassLoader. Not being reclaimed after
     * repeated GC therefore indicates a real hard leak — a Metaspace leak (a lingering thread/static/ThreadLocal).
     */
    @Test
    void module_classloader_is_reclaimable_after_unload() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SOURCE);
        Object handler = module.newInstance();
        registrar.register("leak-probe", handler);
        // actually invoke it to also fill the handler adapter cache before unregistering — the harshest condition
        mockMvc.perform(get("/gen/ping")).andExpect(status().isOk());
        registrar.unregister("leak-probe");

        WeakReference<ClassLoader> ref = new WeakReference<>(module.classLoader());

        // remove all strong references
        handler = null;
        module = null;

        // SoftRefLRUPolicyMSPerMB=0 makes System.gc() clear Spring's soft-cached refs, so plain GC reclaims it.
        boolean cleared = gcUntilCleared(ref, 10);

        System.out.println("[leak-canary] cleared=" + cleared);

        assertTrue(cleared,
                "the module ClassLoader is not reclaimed after repeated GC -> a real hard leak "
                        + "(suspect a lingering thread/static/ThreadLocal)");
    }

    /** Repeats System.gc() until the ref is cleared or rounds are exhausted. */
    private static boolean gcUntilCleared(WeakReference<?> ref, int rounds) throws InterruptedException {
        for (int i = 0; i < rounds; i++) {
            if (ref.get() == null) {
                return true;
            }
            System.gc();
            Thread.sleep(30);
        }
        return ref.get() == null;
    }
}
