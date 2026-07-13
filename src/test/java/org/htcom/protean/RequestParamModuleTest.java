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
 * Regression check for the {@code -parameters} flag in RuntimeCompiler.
 *
 * <p>A module controller must work even when it <b>omits</b> the name on {@code @RequestParam}
 * (just like an ordinary Spring controller). Without {@code -parameters}, the runtime throws
 * {@code IllegalArgumentException: Name for argument ... not specified} and returns 500.
 * This test catches that regression.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestParamModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String CONTROLLER = "runtime.rp.EchoController";

    static final Map<String, String> SOURCES = Map.of(
            CONTROLLER, """
                    package runtime.rp;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RequestParam;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class EchoController {
                        // Unnamed @RequestParam - binding to the parameter name 'msg' requires -parameters.
                        @GetMapping("/rp/echo")
                        public String echo(@RequestParam String msg) { return "echo:" + msg; }
                    }
                    """);

    @Test
    void unnamed_request_param_binds_via_parameters_flag() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("rp-mod", loader, List.of(CONTROLLER), CONTROLLER);

        mockMvc.perform(get("/rp/echo").param("msg", "hi"))
                .andExpect(status().isOk())
                .andExpect(content().string("echo:hi"));

        container.undeploy("rp-mod");
        mockMvc.perform(get("/rp/echo").param("msg", "hi")).andExpect(status().isNotFound());
    }
}
