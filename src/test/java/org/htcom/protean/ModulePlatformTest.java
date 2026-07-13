/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.InProcessIsolation;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ModulePlatform verification: durable storage + startup reconcile + isolation SPI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModulePlatformTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-p0-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;
    @Autowired InProcessIsolation isolation;

    static final String FQCN = "runtime.p0.P0Controller";
    static final String TEST_FQCN = "runtime.p0.P0ControllerTest";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.p0;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class P0Controller {
                    @GetMapping("/p0/ping")
                    public String ping() { return "p0"; }
                }
                """;
        String test = """
                package runtime.p0;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class P0ControllerTest {
                    @Test void ping_returns_p0() { assertEquals("p0", new P0Controller().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id("p0-mod").version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of(TEST_FQCN, test))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("p0-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void install_persists_and_serves_then_reconcile_restores_after_restart() throws Exception {
        // before install: 404
        mockMvc.perform(get("/p0/ping")).andExpect(status().isNotFound());

        // install -> write-ahead persist + deploy
        platform.install(descriptor());

        mockMvc.perform(get("/p0/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("p0"));

        // verify durable storage
        assertTrue(store.load("p0-mod").isPresent(), "the descriptor must be persisted to the store");
        assertEquals(ModuleDescriptor.DesiredState.ACTIVE, store.load("p0-mod").get().desiredState());

        // simulate a forced reboot: in-memory deployment lost (mapping gone), store retained
        isolation.undeploy("p0-mod");
        mockMvc.perform(get("/p0/ping")).andExpect(status().isNotFound());

        // startup reconcile: recompile and redeploy from stored sources
        int restored = platform.reconcile();
        assertTrue(restored >= 1, "reconcile must restore ACTIVE modules from the store");

        mockMvc.perform(get("/p0/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("p0"));
    }

    /**
     * Regression: when install() is called again for an already-serving module (e.g. startup reconcile
     * restores it first, then a consumer bootstrap runner installs it again) — the conflict must be
     * rejected but <b>must not tear down the existing healthy deployment and store record</b>. Previously
     * install()'s deploy-failure rollback ran undeploy + store.remove even on an "already deployed"
     * conflict, causing a 404 route + loss of the store record.
     */
    @Test
    void install_on_already_deployed_module_rejects_without_clobbering() throws Exception {
        // first install -> serving + stored
        platform.install(descriptor());
        mockMvc.perform(get("/p0/ping")).andExpect(status().isOk());

        // duplicate install (simulated race): a conflict, so it must be rejected
        assertThrows(IllegalStateException.class, () -> platform.install(descriptor()));

        // key point: even when rejected, the existing deployment and store record must stay alive
        mockMvc.perform(get("/p0/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("p0"));
        assertTrue(store.load("p0-mod").isPresent(), "a conflict rejection must not delete the store record");
        assertEquals(ModuleDescriptor.DesiredState.ACTIVE, store.load("p0-mod").get().desiredState());
    }
}
