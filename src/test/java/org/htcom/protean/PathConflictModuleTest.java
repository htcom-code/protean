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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same-path conflict policy.
 * Characterizes the behavior when two modules register the same path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PathConflictModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static String controller(String className, String body) {
        return """
                package runtime.conflict;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class %s {
                    @GetMapping("/conflict/ping")
                    public String ping() { return "%s"; }
                }
                """.formatted(className, body);
    }

    @Test
    void second_module_on_same_path_is_rejected_and_first_stays_intact() throws Exception {
        ModuleClassLoader a = compiler.compileAll(
                Map.of("runtime.conflict.ConflictA", controller("ConflictA", "A")));
        container.deploy("conflict-a", a, List.of("runtime.conflict.ConflictA"), "runtime.conflict.ConflictA");

        mockMvc.perform(get("/conflict/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("A"));

        // deploying a second module on the same path -> must be rejected as an ambiguous mapping.
        ModuleClassLoader b = compiler.compileAll(
                Map.of("runtime.conflict.ConflictB", controller("ConflictB", "B")));
        assertThrows(Exception.class, () ->
                container.deploy("conflict-b", b, List.of("runtime.conflict.ConflictB"), "runtime.conflict.ConflictB"));

        // the first module must remain alive.
        mockMvc.perform(get("/conflict/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("A"));
        // the second module must not be deployed.
        assertFalse(container.isDeployed("conflict-b"));

        container.undeploy("conflict-a");
        mockMvc.perform(get("/conflict/ping")).andExpect(status().isNotFound());
    }
}
