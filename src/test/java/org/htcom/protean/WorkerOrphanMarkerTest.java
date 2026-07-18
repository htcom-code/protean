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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring for the unclean-exit reaper: a spawned worker JVM carries the {@code -Dprotean.worker.id} marker on
 * its command line and a durable marker file under {@code <module-store>/workers}; a graceful {@code shutdown()} removes
 * the markers (so the next startup reaper has nothing to do). The reap-on-startup logic itself is unit-tested in
 * {@code OrphanWorkerReaperTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerOrphanMarkerTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-orphan-marker-test");
    static final Path MARKER_DIR = STORE_DIR.resolve("workers");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.modules-per-worker", () -> "1");
    }

    @Autowired WorkerProcessIsolation isolation;

    static ModuleDescriptor module() {
        String fqcn = "runtime.marker.MController";
        String src = """
                package runtime.marker;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class MController {
                    @GetMapping("/mk/ping")
                    public String ping() { return "mk"; }
                }
                """;
        return ModuleDescriptor.builder()
                .id("marker-m").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn)).sources(Map.of(fqcn, src))
                .build();
    }

    private static long markerFileCount() throws IOException {
        if (!Files.isDirectory(MARKER_DIR)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(MARKER_DIR)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void worker_carries_marker_and_records_a_marker_file_cleared_on_shutdown() throws Exception {
        isolation.deploy(module());

        // a durable marker file exists for the live worker
        assertEquals(1, markerFileCount(), "one marker file per live worker");

        // the worker JVM actually carries the -Dprotean.worker.id marker on its command line
        String cmdLine = isolation.workerProcesses().get(0).toHandle().info().commandLine().orElse("");
        assertTrue(cmdLine.contains("-Dprotean.worker.id="),
                "worker command line carries the identity marker (was: " + cmdLine + ")");

        isolation.shutdown();   // graceful teardown removes the markers

        assertEquals(0, markerFileCount(), "markers cleared on graceful shutdown");
    }
}
