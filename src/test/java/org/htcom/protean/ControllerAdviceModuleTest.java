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
 * Global @ControllerAdvice — <b>dynamic registration support verification</b>.
 *
 * By default the root ExceptionHandlerExceptionResolver scans only the root context at startup, so
 * advice in a child context is not picked up. ModuleContainer.deploy registers the module's advice
 * directly into the resolver's exceptionHandlerAdviceCache, and evicts it on undeploy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControllerAdviceModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String ADVICE = "runtime.advice.GlobalAdvice";
    static final String CONTROLLER = "runtime.advice.BoomController";

    static final Map<String, String> SOURCES = Map.of(
            ADVICE, """
                    package runtime.advice;
                    import org.springframework.http.HttpStatus;
                    import org.springframework.web.bind.annotation.ControllerAdvice;
                    import org.springframework.web.bind.annotation.ExceptionHandler;
                    import org.springframework.web.bind.annotation.ResponseBody;
                    import org.springframework.web.bind.annotation.ResponseStatus;
                    @ControllerAdvice
                    public class GlobalAdvice {
                        @ExceptionHandler(IllegalStateException.class)
                        @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
                        @ResponseBody
                        public String handle(IllegalStateException e) { return "advised:" + e.getMessage(); }
                    }
                    """,
            CONTROLLER, """
                    package runtime.advice;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class BoomController {
                        @GetMapping("/adv/boom")
                        public String boom() { throw new IllegalStateException("kaboom"); }
                    }
                    """
    );

    @Test
    void global_advice_in_module_is_applied_then_removed() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("adv-mod", loader, List.of(ADVICE, CONTROLLER), CONTROLLER);

        // The module's global advice is registered, catches the exception, and returns 418 + custom body.
        mockMvc.perform(get("/adv/boom"))
                .andExpect(status().isIAmATeapot())
                .andExpect(content().string("advised:kaboom"));

        container.undeploy("adv-mod");
        // The advice was evicted and the mapping itself is gone, so 404.
        mockMvc.perform(get("/adv/boom")).andExpect(status().isNotFound());
    }

    @Test
    void advice_module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("adv-mod-2", loader, List.of(ADVICE, CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/adv/boom")).andExpect(status().isIAmATeapot());
        container.undeploy("adv-mod-2");

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
                "a @ControllerAdvice module's ClassLoader must also be reclaimed after undeploy "
                        + "(exceptionHandlerAdviceCache eviction required)");
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
