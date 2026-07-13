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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage-by-stage leak diagnosis: which operation pins the ClassLoader.
 * Does not fail; only prints results, comparing three scenarios side by side.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeakDiagnosisTest {

    @Autowired MockMvc mockMvc;
    @Autowired DynamicEndpointRegistrar registrar;
    @Autowired RuntimeCompiler compiler;

    static final String FQCN = "runtime.gen.GenController";
    static final String SOURCE = """
            package runtime.gen;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.ResponseBody;
            public class GenController {
                @GetMapping("/gen/ping")
                @ResponseBody
                public String ping() { return "pong"; }
            }
            """;

    @Test
    void probe_all_three_scenarios() throws Exception {
        System.out.println("[diag] A(load-only)         collected=" + scenarioLoadOnly());
        System.out.println("[diag] B(register+unreg)    collected=" + scenarioRegisterUnregister());
        System.out.println("[diag] C(register+call+unreg) collected=" + scenarioRegisterInvokeUnregister());
    }

    private boolean scenarioLoadOnly() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SOURCE);
        WeakReference<ClassLoader> ref = new WeakReference<>(module.classLoader());
        module = null;
        return reclaimed(ref);
    }

    private boolean scenarioRegisterUnregister() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SOURCE);
        Object handler = module.newInstance();
        registrar.register("diag-b", handler);
        registrar.unregister("diag-b");
        WeakReference<ClassLoader> ref = new WeakReference<>(module.classLoader());
        handler = null;
        module = null;
        return reclaimed(ref);
    }

    private boolean scenarioRegisterInvokeUnregister() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SOURCE);
        Object handler = module.newInstance();
        registrar.register("diag-c", handler);
        mockMvc.perform(get("/gen/ping")).andExpect(status().isOk());
        registrar.unregister("diag-c");
        WeakReference<ClassLoader> ref = new WeakReference<>(module.classLoader());
        handler = null;
        module = null;
        return reclaimed(ref);
    }

    private static boolean reclaimed(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 8 && ref.get() != null; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
        }
        return ref.get() == null;
    }

    private static void applyMemoryPressure() {
        try {
            java.util.List<byte[]> ballast = new java.util.ArrayList<>();
            while (true) {
                ballast.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError ignored) {
        }
    }
}
