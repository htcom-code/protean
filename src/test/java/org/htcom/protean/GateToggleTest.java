/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion-gate toggle: turning both gates off with {@code protean.gate.tests-enabled=false} and
 * {@code protean.gate.review-enabled=false} installs modules that would normally be rejected (no tests /
 * containing forbidden APIs).
 *
 * <p>The rejection behavior with defaults (all on) is already verified by {@link PromotionGateTest} and
 * {@link ReviewGateTest}. This test demonstrates that a library consumer can relax the gates (the off path).
 */
@SpringBootTest(properties = {
        "protean.gate.tests-enabled=false",
        "protean.gate.review-enabled=false"
})
@AutoConfigureMockMvc
class GateToggleTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-gate-toggle-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    @AfterEach
    void cleanup() {
        for (String id : List.of("toggle-notest", "toggle-forbidden")) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void tests_gate_off_allows_module_without_tests() throws Exception {
        String ctrl = "runtime.toggle.NoTestController";
        String src = """
                package runtime.toggle;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class NoTestController {
                    @GetMapping("/toggle/notest") public String ping() { return "notest"; }
                }
                """;
        // The tests map is empty (normally rejected by the tests gate), but tests-enabled=false so it must pass and install.
        platform.install(ModuleDescriptor.builder()
                .id("toggle-notest").version("1.0.0")
                .controllerFqcn(ctrl).componentFqcns(List.of(ctrl)).sources(Map.of(ctrl, src))
                .build());

        mockMvc.perform(get("/toggle/notest")).andExpect(status().isOk());
        assertTrue(store.load("toggle-notest").isPresent(), "a module that passed with the gate off should be stored");
    }

    @Test
    void review_gate_off_allows_module_with_forbidden_api() throws Exception {
        String ctrl = "runtime.toggle.ForbiddenController";
        // System.exit is normally rejected by the review gate (ForbiddenApiRule), but review-enabled=false so it passes.
        // danger() is never called (calling it would terminate the JVM) — merely having the forbidden call in the bytecode makes it a gate target.
        String src = """
                package runtime.toggle;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ForbiddenController {
                    @GetMapping("/toggle/safe") public String safe() { return "safe"; }
                    @GetMapping("/toggle/danger") public String danger() { System.exit(1); return "never"; }
                }
                """;
        platform.install(ModuleDescriptor.builder()
                .id("toggle-forbidden").version("1.0.0")
                .controllerFqcn(ctrl).componentFqcns(List.of(ctrl)).sources(Map.of(ctrl, src))
                .build());

        mockMvc.perform(get("/toggle/safe")).andExpect(status().isOk());   // danger is not called
        assertTrue(store.load("toggle-forbidden").isPresent(),
                "with review off, a forbidden-API module should also install and be stored");
    }
}
