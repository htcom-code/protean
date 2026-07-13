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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AOP-proxied module controller.
 * Verifies that when a controller becomes a CGLIB proxy, the registrar still discovers its mappings
 * correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AopProxiedModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String CONFIG = "runtime.aop.AopConfig";
    static final String ASPECT = "runtime.aop.AuditAspect";
    static final String CONTROLLER = "runtime.aop.AuditedController";

    static final Map<String, String> SOURCES = Map.of(
            CONFIG, """
                    package runtime.aop;
                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.context.annotation.EnableAspectJAutoProxy;
                    @Configuration
                    @EnableAspectJAutoProxy(proxyTargetClass = true)
                    public class AopConfig {}
                    """,
            ASPECT, """
                    package runtime.aop;
                    import org.aspectj.lang.ProceedingJoinPoint;
                    import org.aspectj.lang.annotation.Around;
                    import org.aspectj.lang.annotation.Aspect;
                    import org.springframework.stereotype.Component;
                    @Aspect
                    @Component
                    public class AuditAspect {
                        @Around("execution(* runtime.aop.AuditedController.*(..))")
                        public Object wrap(ProceedingJoinPoint pjp) throws Throwable {
                            return "[aspect]" + pjp.proceed();
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.aop;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class AuditedController {
                        @GetMapping("/aop/ping")
                        public String ping() { return "pong"; }
                    }
                    """
    );

    @Test
    void aop_proxied_controller_registers_and_advice_runs() throws Exception {
        mockMvc.perform(get("/aop/ping")).andExpect(status().isNotFound());

        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("aop-mod", loader, List.of(CONFIG, ASPECT, CONTROLLER), CONTROLLER);

        // Even as a proxy, the mapping should be registered (200) and the advice applied ([aspect] prefix).
        mockMvc.perform(get("/aop/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("[aspect]pong"));

        container.undeploy("aop-mod");
        mockMvc.perform(get("/aop/ping")).andExpect(status().isNotFound());
    }

    @Test
    void aop_proxied_module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("aop-mod-2", loader, List.of(CONFIG, ASPECT, CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/aop/ping")).andExpect(status().isOk());  // the call populates the adapter cache
        container.undeploy("aop-mod-2");

        java.lang.ref.WeakReference<ClassLoader> ref = new java.lang.ref.WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        org.junit.jupiter.api.Assertions.assertTrue(reclaimed,
                "an AOP-proxied module's ClassLoader must also be reclaimed after undeploy "
                        + "(adapter cache eviction must be keyed on the user class, not the proxy class)");
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
