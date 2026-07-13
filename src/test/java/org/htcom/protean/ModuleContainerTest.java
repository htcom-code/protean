/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Child ApplicationContext integration: brings up a module with dependencies in a child context to verify
 * DI works, and that unload cleanly closes the context and lets the ClassLoader be garbage-collected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModuleContainerTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String SERVICE_FQCN = "runtime.mod.GreetingService";
    static final String CONTROLLER_FQCN = "runtime.mod.GreetingController";

    static final String SERVICE_SRC = """
            package runtime.mod;
            import org.springframework.stereotype.Service;
            @Service
            public class GreetingService {
                public String greet() { return "hello-from-injected-service"; }
            }
            """;

    static final String CONTROLLER_SRC = """
            package runtime.mod;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class GreetingController {
                private final GreetingService service;
                public GreetingController(GreetingService service) { this.service = service; }
                @GetMapping("/mod/greet")
                public String greet() { return service.greet(); }
            }
            """;

    @Test
    void deploy_module_with_di_then_undeploy() throws Exception {
        // Before deploy: 404
        mockMvc.perform(get("/mod/greet")).andExpect(status().isNotFound());

        ModuleClassLoader loader = compiler.compileAll(Map.of(
                SERVICE_FQCN, SERVICE_SRC,
                CONTROLLER_FQCN, CONTROLLER_SRC));

        container.deploy("greet-mod", loader,
                List.of(SERVICE_FQCN, CONTROLLER_FQCN), CONTROLLER_FQCN);

        // After deploy: 200 + the injected service's result must appear (DI proof)
        mockMvc.perform(get("/mod/greet"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello-from-injected-service"));

        // Unload: unregister mappings + close the child context
        container.undeploy("greet-mod");
        mockMvc.perform(get("/mod/greet")).andExpect(status().isNotFound());
    }

    @Test
    void module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(Map.of(
                SERVICE_FQCN, SERVICE_SRC,
                CONTROLLER_FQCN, CONTROLLER_SRC));
        container.deploy("greet-mod-2", loader,
                List.of(SERVICE_FQCN, CONTROLLER_FQCN), CONTROLLER_FQCN);
        mockMvc.perform(get("/mod/greet")).andExpect(status().isOk());
        container.undeploy("greet-mod-2");

        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed,
                "the ModuleClassLoader must be reclaimed after closing the child context and unregistering mappings");
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
