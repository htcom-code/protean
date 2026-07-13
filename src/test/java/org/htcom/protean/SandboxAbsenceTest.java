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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uncovered area: compile-time sandboxing — <b>characterizing its absence (a security risk)</b>.
 *
 * The current runtime compile/execution has no sandbox. Module code runs with full JVM privileges.
 * -> A platform that accepts untrusted sources (MCP/external) must add separate isolation (process
 *   separation, bytecode verification, a security policy, etc.). This test leaves that absence on record.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxAbsenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String FQCN = "runtime.sbx.PrivilegedController";
    static final String SRC = """
            package runtime.sbx;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class PrivilegedController {
                @GetMapping("/sbx/probe")
                public String probe() {
                    // arbitrary privileged action: setting a system property (should be blocked if a sandbox existed)
                    System.setProperty("protean.pwned", "yes");
                    return "user.home=" + System.getProperty("user.home");
                }
            }
            """;

    @AfterEach
    void clearProp() {
        System.clearProperty("protean.pwned");
    }

    @Test
    void module_code_runs_with_full_privileges_no_sandbox() throws Exception {
        System.clearProperty("protean.pwned");

        ModuleClassLoader loader = compiler.compileAll(Map.of(FQCN, SRC));
        container.deploy("sbx-mod", loader, List.of(FQCN), FQCN);

        mockMvc.perform(get("/sbx/probe")).andExpect(status().isOk());

        // module code actually changed a system property = no sandbox.
        assertEquals("yes", System.getProperty("protean.pwned"),
                "no sandbox — module code ran System.setProperty without restriction");

        container.undeploy("sbx-mod");
    }
}
