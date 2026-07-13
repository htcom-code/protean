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
import org.htcom.protean.mcp.debug.DebugCore;
import org.htcom.protean.mcp.debug.DebugSession;
import org.htcom.protean.module.ModuleDescriptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auto-spawn of a debug-launch worker: verifies that {@link WorkerProcessIsolation#launchDebugWorker}
 * spawns a dedicated JDWP-enabled worker, deploys and routes the module to it, and that the worker can be
 * attached over JDI. Also verifies that {@link WorkerProcessIsolation#terminateDebugWorker} restores the
 * route (after a live takeover) or releases it (for a fresh deploy).
 *
 * <p>Spawns a real worker JVM with JDWP, so it can be slow (Spring Boot startup). The MCP surface
 * (mcp.enabled) is not needed; only the isolation machinery plus a local {@link DebugCore} are driven directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DebugLaunchWorkerTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-debug-launch-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;
    @Autowired ReverseProxy proxy;

    static final String FQCN = "runtime.d.DController";
    static final String PATH = "/dbg/ping";
    static final String SRC = """
            package runtime.d;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class DController {
                @GetMapping("/dbg/ping")
                public String ping() { return "from-debug-worker"; }
            }
            """;

    static ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder()
                .id("dbg-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("dbg-mod");
        } catch (RuntimeException ignored) {
        }
    }

    /** Fresh (no prior deploy): launch -> the dedicated worker registers a new route and is JDWP-attachable; terminate -> route released. */
    @Test
    void launch_spawns_jdwp_worker_routes_and_attaches_then_terminate_unregisters() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isNotFound());  // 404 before deploy

        DebugWorkerHandle handle = isolation.launchDebugWorker(descriptor());
        DebugCore core = new DebugCore();
        try {
            assertTrue(handle.jdwpPort() > 0, "must parse the JDWP listening port");
            assertTrue(handle.workerPort() > 0, "must parse the worker port");
            assertTrue(handle.paths().contains(PATH), "the module path must be registered");
            assertTrue(handle.priorPorts().isEmpty(), "no restore port since there was no prior deploy");

            // The route points to the debug worker and a real request is served by that worker's response
            assertEquals(handle.workerPort(), proxy.portOf(PATH));
            mockMvc.perform(get(PATH)).andExpect(status().isOk());

            // The auto-spawned worker is attached over JDI (no manual host:port needed)
            DebugSession session = core.attach("127.0.0.1", handle.jdwpPort());
            assertNotNull(session.vmName(), "query VM name after attach");
            assertFalse(session.threadNames().isEmpty(), "debug worker threads should be visible");
            core.terminate(session.id());
        } finally {
            core.terminateAll();
            isolation.terminateDebugWorker(handle);
        }

        // After terminate: it was a fresh route, so the route is released (404)
        assertNull(proxy.portOf(PATH), "a fresh route must be released on termination");
        mockMvc.perform(get(PATH)).andExpect(status().isNotFound());
    }

    /** Live takeover: launch atomically takes over a normally deployed module, and terminate restores the original worker port. */
    @Test
    void launch_takes_over_live_route_and_restores_on_terminate() throws Exception {
        isolation.deploy(descriptor());                       // normal deploy
        Integer origPort = proxy.portOf(PATH);
        assertNotNull(origPort, "route exists after normal deploy");
        mockMvc.perform(get(PATH)).andExpect(status().isOk());

        DebugWorkerHandle handle = isolation.launchDebugWorker(descriptor());
        try {
            // Atomic takeover: route moves to the debug worker, original port recorded for restore
            assertEquals(handle.workerPort(), proxy.portOf(PATH), "route handed over to the debug worker");
            assertEquals(origPort, handle.priorPorts().get(PATH), "original port recorded before takeover");
            mockMvc.perform(get(PATH)).andExpect(status().isOk());  // no downtime
        } finally {
            isolation.terminateDebugWorker(handle);
        }

        // Restore: back to the original worker port, the normal deploy keeps serving
        assertEquals(origPort, proxy.portOf(PATH), "restored to the original worker on termination");
        mockMvc.perform(get(PATH)).andExpect(status().isOk());
    }
}
