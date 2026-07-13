/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.isolation.WorkerProcessIsolation.DebugWorkerHandle;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.proxy.ReverseProxy;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A module launched under debug must be seeded with the live parent tier, exactly like a normal pooled worker.
 * {@link WorkerProcessIsolation#launchDebugWorker} spawns a dedicated worker outside the
 * pool, so it does not go through the normal {@code spawnAndReady} + {@code ensureUsesClosure} path; it must therefore
 * seed the parent tier and publish the module's {@code uses} closure itself before deploying the module.
 *
 * <p>Here a worker-mode module {@code uses} an in-process library. Under debug launch, the library must be published
 * into the debug worker first, or the module fails to compile against {@code geo.Point} inside that worker. This is a
 * real-JVM debug launch (no JDI attach — the compile/serve outcome alone proves the seeding), so it is not timing
 * sensitive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DebugLaunchUsesClosureTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-debug-uses-closure-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;
    @Autowired ModulePlatform platform;
    @Autowired ReverseProxy proxy;

    static final String LIB = "dbg-lib-geo";
    static final String CONSUMER = "dbg-uses-consumer";
    static final String PATH = "/dbg-uses/label";

    static final Map<String, String> LIB_SOURCES = Map.of("geo.Point", """
            package geo;
            public class Point { public String label() { return "geo-v1"; } }
            """);

    static final Map<String, String> LIB_TESTS = Map.of("geo.PointTest", """
            package geo;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class PointTest { @Test void ok() { assertNotNull(new Point().label()); } }
            """);

    static ModuleDescriptor library() {
        return ModuleDescriptor.builder().id(LIB).version("1.0.0").kind(ModuleKind.LIBRARY)
                .exports(List.of("geo")).isolationMode("in-process")
                .sources(LIB_SOURCES).tests(LIB_TESTS)
                .build();
    }

    static final Map<String, String> CONSUMER_SOURCES = Map.of("runtime.dbguses.DbgUsesController", """
            package runtime.dbguses;
            import geo.Point;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class DbgUsesController {
                @GetMapping("/dbg-uses/label") public String label() { return "worker:" + new Point().label(); }
            }
            """);

    static ModuleDescriptor consumer() {
        return ModuleDescriptor.builder().id(CONSUMER).version("1.0.0").kind(ModuleKind.NORMAL)
                .uses(List.of(LIB)).isolationMode("worker")
                .controllerFqcn("runtime.dbguses.DbgUsesController")
                .componentFqcns(List.of("runtime.dbguses.DbgUsesController"))
                .sources(CONSUMER_SOURCES)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy(CONSUMER);
        } catch (RuntimeException ignored) {
        }
        try {
            if (platform.find(LIB).isPresent()) {
                platform.uninstall(LIB);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Debug launch of a module that `uses` a library: the debug worker must receive the library's uses closure. */
    @Test
    void debug_launch_seeds_the_uses_closure_so_a_dependent_compiles() throws Exception {
        platform.install(library());   // the library lives in the main's in-process registry + module store

        // Before the fix, launchDebugWorker skipped seedParentTier/ensureUsesClosure, so the debug worker had no
        // `geo` library and the dependent failed to compile. It must now serve, proving the closure was published.
        DebugWorkerHandle handle = isolation.launchDebugWorker(consumer());
        try {
            mockMvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().string("worker:geo-v1"));
        } finally {
            isolation.terminateDebugWorker(handle);
        }
        mockMvc.perform(get(PATH)).andExpect(status().isNotFound());
    }
}
