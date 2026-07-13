/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Worker isolation PoC: the main process spawns a worker JVM to run modules in isolation and reverse-proxies to it.
 * - deploy: the main route is served by the worker's response
 * - worker crash: 502, main survives (fault isolation)
 * - needsSharedBeans module: rejected (capability boundary)
 *
 * Spawns a real worker JVM, so it can be slow (Spring Boot startup).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerIsolationPoCTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-worker-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");  // enable strong isolation strategy
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.w.WController";
    static final String SRC = """
            package runtime.w;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class WController {
                @GetMapping("/w/ping")
                public String ping() { return "from-worker"; }
            }
            """;

    static ModuleDescriptor descriptor(boolean needsSharedBeans) {
        return ModuleDescriptor.builder()
                .id("w-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(needsSharedBeans)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("w-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void deploy_serves_via_worker_then_crash_isolates() throws Exception {
        // before deploy: 404
        mockMvc.perform(get("/w/ping")).andExpect(status().isNotFound());

        // spawn worker + deploy + register proxy
        isolation.deploy(descriptor(false));

        // the main route is served by the worker's response (proxy round-trip)
        mockMvc.perform(get("/w/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("from-worker"));

        // fault isolation: worker crash -> 502, main stays healthy (other requests fine)
        isolation.simulateCrash("w-mod");
        mockMvc.perform(get("/w/ping")).andExpect(status().is(502));
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isNotFound()); // main still alive
    }

    @Test
    void shared_beans_module_is_rejected() {
        // capability boundary: workers cannot access shared beans -> reject needsSharedBeans modules
        assertFalse(isolation.supports(descriptor(true)),
                "worker mode must not support modules that depend on shared beans (fail-fast)");
        assertTrue(isolation.supports(descriptor(false)));
    }
}
