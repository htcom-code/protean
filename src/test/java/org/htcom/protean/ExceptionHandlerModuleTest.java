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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-local @ExceptionHandler coverage.
 * Verifies that exception handling works and that the ClassLoader is reclaimed after unregister
 * (ExceptionHandlerExceptionResolver cache leak).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionHandlerModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired DynamicEndpointRegistrar registrar;

    static final String FQCN = "runtime.err.FailingController";
    static final String SRC = """
            package runtime.err;
            import org.springframework.http.HttpStatus;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.ResponseStatus;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class FailingController {
                @GetMapping("/err/boom")
                public String boom() { throw new IllegalStateException("kaboom"); }

                @ExceptionHandler(IllegalStateException.class)
                @ResponseStatus(HttpStatus.BAD_REQUEST)
                public String handle(IllegalStateException e) { return "handled:" + e.getMessage(); }
            }
            """;

    @Test
    void local_exception_handler_works() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SRC);
        registrar.register("err-mod", module.newInstance());

        // Local @ExceptionHandler catches it: 400 plus a custom body
        mockMvc.perform(get("/err/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("handled:kaboom"));

        registrar.unregister("err-mod");
        mockMvc.perform(get("/err/boom")).andExpect(status().isNotFound());
    }

    @Test
    void module_classloader_reclaimable_after_exception_handling() throws Exception {
        CompiledModule module = compiler.compile(FQCN, SRC);
        registrar.register("err-mod-2", module.newInstance());
        mockMvc.perform(get("/err/boom")).andExpect(status().isBadRequest()); // populate the exception cache
        registrar.unregister("err-mod-2");

        WeakReference<ClassLoader> ref = new WeakReference<>(module.classLoader());
        module = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed,
                "the ClassLoader must be reclaimed even after @ExceptionHandler invocation "
                        + "(ExceptionHandlerExceptionResolver.exceptionHandlerCache must be evicted)");
    }

}
