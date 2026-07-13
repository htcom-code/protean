/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.gate.PromotionPipeline;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion gate #1 — mandatory unit-test enforcement and execution.
 * Green tests are required to install (promote); a failure or absence rejects the module (neither stored nor deployed).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionGateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-gate-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.gate.GateController";
    static final String TEST = "runtime.gate.GateControllerTest";

    static final String CTRL_SRC = """
            package runtime.gate;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class GateController {
                @GetMapping("/gate/ping")
                public String ping() { return "gate"; }
            }
            """;

    static ModuleDescriptor descriptor(Map<String, String> tests) {
        return ModuleDescriptor.builder()
                .id("gate-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL)).sources(Map.of(CTRL, CTRL_SRC)).tests(tests)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("gate-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void green_tests_pass_gate_and_install() throws Exception {
        String passingTest = """
                package runtime.gate;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class GateControllerTest {
                    @Test void ping_ok() { assertEquals("gate", new GateController().ping()); }
                }
                """;
        platform.install(descriptor(Map.of(TEST, passingTest)));
        mockMvc.perform(get("/gate/ping")).andExpect(status().isOk());
    }

    @Test
    void failing_tests_block_install() throws Exception {
        String failingTest = """
                package runtime.gate;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class GateControllerTest {
                    @Test void ping_wrong() { assertEquals("NOPE", new GateController().ping()); }
                }
                """;
        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.install(descriptor(Map.of(TEST, failingTest))));

        // Gate failure -> neither deployed nor stored
        mockMvc.perform(get("/gate/ping")).andExpect(status().isNotFound());
        assertFalse(store.load("gate-mod").isPresent(), "a module that fails the gate must not be stored");
    }

    @Test
    void missing_tests_block_install() throws Exception {
        assertThrows(PromotionPipeline.GateFailedException.class,
                () -> platform.install(descriptor(Map.of())));  // no tests = enforcement violation

        mockMvc.perform(get("/gate/ping")).andExpect(status().isNotFound());
        assertFalse(store.load("gate-mod").isPresent());
    }
}
