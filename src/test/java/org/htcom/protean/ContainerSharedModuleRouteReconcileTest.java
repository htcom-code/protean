/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.htcom.protean.isolation.InProcessIsolation;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shared-module-consuming route works when the consumer is <b>container</b>-isolated and survives a main
 * restart (reconcile) — the exact matrix cell that first surfaced the container reconcile name-collision bug.
 * The library stays in-process. Requires a Docker daemon and a bootJar; skipped otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContainerSharedModuleRouteReconcileTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-sm-route-container");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired InProcessIsolation inProcess;                 // library
    @Autowired ContainerWorkerIsolation containerIsolation;  // consumer

    @BeforeEach
    void preconditions() {
        assumeTrue(dockerAvailable(), "no Docker daemon — skip");
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar' required) — skip");
    }

    @AfterEach
    void cleanup() {
        for (String id : new String[]{SharedModuleRouteScenario.CONSUMER_ID, SharedModuleRouteScenario.LIB_ID}) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void container_route_consumes_shared_module_and_survives_reconcile() throws Exception {
        platform.install(SharedModuleRouteScenario.library());
        platform.install(SharedModuleRouteScenario.consumer("container"));

        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE).param("x", "1").param("y", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string("at=point(11,12)"));

        // Forced reboot: drop the container deployment + the library, keep the store.
        containerIsolation.undeploy(SharedModuleRouteScenario.CONSUMER_ID);
        inProcess.undeploy(SharedModuleRouteScenario.LIB_ID);
        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE)).andExpect(status().isNotFound());

        int restored = platform.reconcile();
        assertTrue(restored >= 1, "reconcile must restore ACTIVE modules from the store");

        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE).param("x", "1").param("y", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string("at=point(11,12)"));
    }

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean bootJarExists() {
        Path libs = Path.of("build", "libs");
        if (!Files.isDirectory(libs)) {
            return false;
        }
        try (Stream<Path> s = Files.list(libs)) {
            return s.anyMatch(p -> p.toString().endsWith("-boot.jar"));
        } catch (Exception e) {
            return false;
        }
    }
}
