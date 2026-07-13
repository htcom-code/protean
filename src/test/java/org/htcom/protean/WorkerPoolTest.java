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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Worker pool / reuse: modules are packed up to capacity per worker to reduce the number of JVMs.
 * With capacity=2, deploying 3 modules yields 2 workers (not a new JVM per module).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerPoolTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-pool-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.modules-per-worker", () -> "2");  // 2 modules per worker
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static ModuleDescriptor module(int n) {
        String fqcn = "runtime.pool.P" + n + "Controller";
        String src = """
                package runtime.pool;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class P%dController {
                    @GetMapping("/p%d/ping")
                    public String ping() { return "m%d"; }
                }
                """.formatted(n, n, n);
        return ModuleDescriptor.builder()
                .id("pool-m" + n).version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(Map.of(fqcn, src))
                .build();
    }

    @AfterEach
    void cleanup() {
        for (int n = 1; n <= 3; n++) {
            try {
                isolation.undeploy("pool-m" + n);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void modules_pack_into_workers_by_capacity_and_reuse() throws Exception {
        isolation.deploy(module(1));
        isolation.deploy(module(2));
        isolation.deploy(module(3));

        // capacity=2 -> 3 modules packed into 2 workers (not a new JVM per module = reuse)
        assertEquals(2, isolation.workerCount(), "3 modules / capacity 2 should yield 2 workers");

        // all three serve correctly (main proxy routes even across different workers)
        mockMvc.perform(get("/p1/ping")).andExpect(status().isOk()).andExpect(content().string("m1"));
        mockMvc.perform(get("/p2/ping")).andExpect(status().isOk()).andExpect(content().string("m2"));
        mockMvc.perform(get("/p3/ping")).andExpect(status().isOk()).andExpect(content().string("m3"));

        // undeploying m1 leaves m2 alive on the same worker (worker retained)
        isolation.undeploy("pool-m1");
        assertEquals(2, isolation.workerCount(), "worker is retained while m2 remains on it");
        mockMvc.perform(get("/p1/ping")).andExpect(status().isNotFound());
        mockMvc.perform(get("/p2/ping")).andExpect(status().isOk()).andExpect(content().string("m2"));

        // undeploying m2 too leaves the worker empty, so it is cleaned up
        isolation.undeploy("pool-m2");
        assertEquals(1, isolation.workerCount(), "empty workers are cleaned up (min-warm=0)");
        mockMvc.perform(get("/p3/ping")).andExpect(status().isOk()).andExpect(content().string("m3"));
    }
}
