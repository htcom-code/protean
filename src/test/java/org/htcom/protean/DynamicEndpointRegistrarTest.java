/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DynamicEndpointRegistrarTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DynamicEndpointRegistrar registrar;

    /**
     * Not a @Controller / @Component — it is never picked up by component scanning.
     * Mimics a purely "register by hand at runtime" scenario.
     */
    static class RuntimeHelloController {
        @GetMapping("/dyn/hello")
        @ResponseBody
        public String hello() {
            return "hi-from-runtime";
        }
    }

    @Test
    void register_then_unregister_at_runtime() throws Exception {
        // 1) Before registration: no mapping, so 404
        mockMvc.perform(get("/dyn/hello")).andExpect(status().isNotFound());

        // 2) Register at runtime -> 1 mapping
        int registered = registrar.register("hello-module", new RuntimeHelloController());
        assertEquals(1, registered, "exactly 1 handler method should be registered");

        // 3) After registration: 200 plus body
        mockMvc.perform(get("/dyn/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("hi-from-runtime"));

        // 4) Unregister -> 1 mapping removed
        int unregistered = registrar.unregister("hello-module");
        assertEquals(1, unregistered, "the 1 registered mapping should be unregistered");

        // 5) After unregistration: 404 again (no zombie mapping)
        mockMvc.perform(get("/dyn/hello")).andExpect(status().isNotFound());
    }
}
