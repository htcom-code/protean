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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graceful main shutdown must terminate the worker JVMs, or they survive as orphan processes (holding their random
 * ports and heap): {@code ProcessBuilder} children are not killed when the parent JVM exits. The
 * {@code @PreDestroy shutdown()} hook is the process-track counterpart to the container track's teardown (PR #34).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerShutdownTeardownTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-shutdown-teardown-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.modules-per-worker", () -> "1");  // dedicated JVM per module → 2 workers to tear down
    }

    @Autowired WorkerProcessIsolation isolation;

    static ModuleDescriptor module(int n) {
        String fqcn = "runtime.teardown.T" + n + "Controller";
        String src = """
                package runtime.teardown;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class T%dController {
                    @GetMapping("/t%d/ping")
                    public String ping() { return "m%d"; }
                }
                """.formatted(n, n, n);
        return ModuleDescriptor.builder()
                .id("teardown-m" + n).version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(Map.of(fqcn, src))
                .build();
    }

    @Test
    void shutdown_terminates_all_worker_jvms() {
        isolation.deploy(module(1));
        isolation.deploy(module(2));

        List<Process> workers = isolation.workerProcesses();   // capture handles before teardown
        assertEquals(2, workers.size(), "capacity=1 → one dedicated worker JVM per module");
        assertTrue(workers.stream().allMatch(Process::isAlive), "workers are alive before shutdown");

        isolation.shutdown();   // what @PreDestroy fires on graceful main shutdown

        assertEquals(0, isolation.workerCount(), "pool is cleared on shutdown");
        assertTrue(workers.stream().noneMatch(Process::isAlive), "no worker JVM survives as an orphan after shutdown");

        // idempotent — a second invocation (e.g. Spring context close after a manual call) is a harmless no-op
        isolation.shutdown();
        assertFalse(workers.stream().anyMatch(Process::isAlive), "workers stay dead after a repeat shutdown");
    }
}
