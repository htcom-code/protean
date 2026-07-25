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
import org.htcom.protean.isolation.RuntimeInfo;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Autowired org.htcom.protean.module.ModulePlatform platform;

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

    /**
     * {@link #module(int)} plus the bundled test the promotion gate requires, for the cases that install through
     * {@link org.htcom.protean.module.ModulePlatform} (which runs the gates) rather than straight onto the strategy.
     */
    static ModuleDescriptor gatedModule(int n) {
        String fqcn = "runtime.pool.P" + n + "Controller";
        ModuleDescriptor base = module(n);
        return ModuleDescriptor.builder()
                .id(base.id()).version(base.version()).trustTier(base.trustTier())
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(base.sources())
                .tests(Map.of(fqcn + "Test", """
                        package runtime.pool;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class P%dControllerTest {
                            @Test void ping() { assertTrue(new P%dController().ping().startsWith("m")); }
                        }
                        """.formatted(n, n)))
                .build();
    }

    /** Same module id as {@link #module(int)} but a new version/body (for a hot-swap). */
    static ModuleDescriptor moduleV2(int n) {
        String fqcn = "runtime.pool.P" + n + "Controller";
        String src = """
                package runtime.pool;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class P%dController {
                    @GetMapping("/p%d/ping")
                    public String ping() { return "m%d-v2"; }
                }
                """.formatted(n, n, n);
        return ModuleDescriptor.builder()
                .id("pool-m" + n).version("2.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(Map.of(fqcn, src))
                .build();
    }

    @AfterEach
    void cleanup() {
        for (int n = 1; n <= 3; n++) {
            try {
                if (platform.find("pool-m" + n).isPresent()) {
                    platform.uninstall("pool-m" + n);
                }
            } catch (RuntimeException ignored) {
                // fall through to the direct strategy teardown below
            }
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

    /**
     * A runtime hosting nothing is invisible to any module-centric view, and an operator cannot add the surface that
     * reveals it — so the platform reports it. Uses the pool's own packing: with capacity 2 and three modules there are
     * two workers, and emptying one of them (min-warm keeps it) must still show up as a runtime with no modules.
     */
    @Test
    void runtimes_report_a_worker_that_hosts_no_modules() throws Exception {
        // Installed through the platform (not straight onto the strategy) so the modules exist in the store — that is
        // where membership is grouped from, which is what keeps this surface consistent with module status.
        platform.install(gatedModule(1));
        platform.install(gatedModule(2));
        platform.install(gatedModule(3));

        List<RuntimeInfo> runtimes = platform.runtimes();
        List<RuntimeInfo> workers = runtimes.stream().filter(r -> "worker".equals(r.mode())).toList();
        assertEquals(2, workers.size(), "two workers are in the pool, so both must be reported: " + runtimes);
        assertTrue(runtimes.stream().anyMatch(r -> "main".equals(r.runtimeId())),
                "the main JVM is always a runtime: " + runtimes);
        assertTrue(workers.stream().allMatch(r -> r.state() == RuntimeInfo.State.LIVE), "pool workers are LIVE");
        assertTrue(workers.stream().allMatch(r -> r.sinceEpochMs() > 0), "each runtime reports when it started");

        // Membership comes from grouping modules on runtimeId, so it cannot disagree with module status.
        RuntimeInfo hostOfM3 = workers.stream()
                .filter(r -> r.runtimeId().equals(platform.runtimeId("pool-m3")))
                .findFirst().orElseThrow(() -> new AssertionError("no runtime claims pool-m3: " + runtimes));
        assertTrue(hostOfM3.moduleIds().contains("pool-m3"), "hosted modules must be listed: " + hostOfM3);

        // Empty the second worker (m3 was the only module on it) → the worker is gone, and the surface says so.
        int before = workers.size();
        platform.uninstall("pool-m3");
        List<RuntimeInfo> after = platform.runtimes().stream().filter(r -> "worker".equals(r.mode())).toList();
        assertEquals(before - 1, after.size(), "a retired worker must disappear from the runtime list: " + after);
        assertTrue(after.stream().allMatch(r -> !r.moduleIds().isEmpty()),
                "the remaining worker still hosts its modules: " + after);
    }

    @Test
    void hot_swap_removes_the_emptied_old_worker_synchronously() throws Exception {
        // Regression for the drain race: after a hot-swap moves the module to a fresh worker, the emptied old worker
        // must be pulled from the pool IMMEDIATELY (marked retiring + removed under the lock), not only after the grace
        // delay. Otherwise a concurrent deploy could reuse it and then be killed by the deferred cleanup
        // (→ worker /__admin/deploy 500). We assert the pool reflects the removal synchronously (count stays 1, not 2).
        isolation.deploy(module(1));
        assertEquals(1, isolation.workerCount());

        isolation.hotSwap(moduleV2(1));   // v2 lands on a new worker; the old one is now empty

        assertEquals(1, isolation.workerCount(),
                "the emptied old worker must be removed from the pool synchronously on hot-swap (no reuse window)");
        mockMvc.perform(get("/p1/ping")).andExpect(status().isOk()).andExpect(content().string("m1-v2"));
    }
}
