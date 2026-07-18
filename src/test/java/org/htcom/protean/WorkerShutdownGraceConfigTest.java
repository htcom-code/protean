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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code protean.worker.shutdown-grace-ms=0} opts out of the graceful wait: workers are force-killed immediately
 * (the {@code graceMs <= 0} branch of the teardown) rather than given a SIGTERM grace period. The observable outcome
 * is the same — no orphan survives — but this exercises the config-honoring path distinct from the default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerShutdownGraceConfigTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-shutdown-grace-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.modules-per-worker", () -> "1");
        registry.add("protean.worker.shutdown-grace-ms", () -> "0");  // opt out of the graceful wait → immediate force-kill
    }

    @Autowired WorkerProcessIsolation isolation;

    static ModuleDescriptor module() {
        String fqcn = "runtime.grace.GController";
        String src = """
                package runtime.grace;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class GController {
                    @GetMapping("/g/ping")
                    public String ping() { return "g"; }
                }
                """;
        return ModuleDescriptor.builder()
                .id("grace-m").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(Map.of(fqcn, src))
                .build();
    }

    @Test
    void grace_zero_force_kills_workers_immediately() {
        isolation.deploy(module());

        List<Process> workers = isolation.workerProcesses();
        assertEquals(1, workers.size());
        assertTrue(workers.stream().allMatch(Process::isAlive), "worker is alive before shutdown");

        isolation.shutdown();   // grace=0 → destroyForcibly path

        assertEquals(0, isolation.workerCount(), "pool is cleared on shutdown");
        assertTrue(workers.stream().noneMatch(Process::isAlive), "no worker survives with grace=0");
    }
}
