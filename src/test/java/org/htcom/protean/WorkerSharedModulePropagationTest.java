/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleDescriptor.ModuleKind;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Library (typed-sharing) propagation to worker JVMs. A worker-mode module that
 * {@code uses} an in-process library compiles and runs against it inside the worker (the main pushes the library's
 * {@code uses} closure — its sources — to the worker before the dependent), and a live library update propagates to
 * the worker dependent in place, no worker restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerSharedModulePropagationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String LIB = "wlib-geo";
    static final String CONSUMER = "wmod-consumer";

    static Map<String, String> librarySources(String label) {
        return Map.of("geo.Point", """
                package geo;
                public class Point { public String label() { return "%s"; } }
                """.formatted(label));
    }

    static final Map<String, String> LIB_TESTS = Map.of("geo.PointTest", """
            package geo;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class PointTest { @Test void ok() { assertNotNull(new Point().label()); } }
            """);

    /** The library is in-process (lives in the main's registry); the main pushes its sources to the worker. */
    static ModuleDescriptor library(String label, String version) {
        return ModuleDescriptor.builder().id(LIB).version(version).kind(ModuleKind.LIBRARY)
                .exports(List.of("geo")).isolationMode("in-process")
                .sources(librarySources(label)).tests(LIB_TESTS)
                .build();
    }

    static final Map<String, String> CONSUMER_SOURCES = Map.of(
            "runtime.wsm.WsmController", """
                    package runtime.wsm;
                    import geo.Point;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class WsmController {
                        @GetMapping("/wsm/label") public String label() { return "worker:" + new Point().label(); }
                    }
                    """);

    static final Map<String, String> CONSUMER_TESTS = Map.of("runtime.wsm.WsmControllerTest", """
            package runtime.wsm;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class WsmControllerTest { @Test void ok() { assertTrue(new WsmController().label().startsWith("worker:")); } }
            """);

    /** The dependent runs in a worker JVM and uses the in-process library. */
    static ModuleDescriptor consumer(String version) {
        return ModuleDescriptor.builder().id(CONSUMER).version(version).kind(ModuleKind.NORMAL)
                .uses(List.of(LIB)).isolationMode("worker")
                .controllerFqcn("runtime.wsm.WsmController")
                .componentFqcns(List.of("runtime.wsm.WsmController"))
                .sources(CONSUMER_SOURCES).tests(CONSUMER_TESTS)
                .build();
    }

    // STRICT gate: asserts the exact label from the library, so a value-changing library impl breaks it.
    static final Map<String, String> STRICT_TESTS = Map.of("runtime.wsm.WsmControllerTest", """
            package runtime.wsm;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class WsmControllerTest { @Test void exact() { assertEquals("worker:v1", new WsmController().label()); } }
            """);

    static ModuleDescriptor strictConsumer(String version) {
        return ModuleDescriptor.builder().id(CONSUMER).version(version).kind(ModuleKind.NORMAL)
                .uses(List.of(LIB)).isolationMode("worker")
                .controllerFqcn("runtime.wsm.WsmController")
                .componentFqcns(List.of("runtime.wsm.WsmController"))
                .sources(CONSUMER_SOURCES).tests(STRICT_TESTS)
                .build();
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of(CONSUMER, LIB)) {
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void worker_dependent_uses_a_library_and_adopts_its_live_update() throws Exception {
        platform.install(library("v1", "1.0.0"));
        // The consumer is deployed to a worker; the main pushes the library's `uses` closure (sources) to that worker
        // first, so the worker compiles and links runtime.wsm against geo.Point.
        platform.install(consumer("1.0.0"));
        mockMvc.perform(get("/wsm/label")).andExpect(status().isOk()).andExpect(content().string("worker:v1"));

        // Update the (in-process) library; the change propagates to the worker: it republishes the library and rebinds
        // the co-located dependent in place, with no worker restart and no manual redeploy of the dependent.
        platform.update(library("v2", "2.0.0"));
        mockMvc.perform(get("/wsm/label")).andExpect(status().isOk()).andExpect(content().string("worker:v2"));

        platform.uninstall(CONSUMER);
        mockMvc.perform(get("/wsm/label")).andExpect(status().isNotFound());
    }

    /**
     * shared-module-a1-gate-drift (worker arm): a binary-compatible library change that breaks a worker
     * dependent's STRICT gate is caught inside the worker's redeploy — the dependent stays sticky (Plan B) on
     * its prior library generation instead of adopting the value-changing impl unverified.
     */
    @Test
    void a_worker_dependent_with_a_strict_gate_stays_sticky_when_a_library_change_breaks_it() throws Exception {
        platform.install(library("v1", "1.0.0"));
        platform.install(strictConsumer("1.0.0"));   // gate asserts exactly "worker:v1"
        mockMvc.perform(get("/wsm/label")).andExpect(status().isOk()).andExpect(content().string("worker:v1"));

        // Binary-compatible library change label v1 -> v2; the worker dependent's strict gate asserts "worker:v1",
        // so its in-worker redeploy gate fails -> Plan B (sticky): it keeps serving the prior library generation.
        platform.update(library("v2", "2.0.0"));
        mockMvc.perform(get("/wsm/label")).andExpect(status().isOk()).andExpect(content().string("worker:v1"));
    }
}
