/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.InProcessIsolation;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shared-module-consuming route works in-process and survives a main restart (reconcile from the durable
 * store). Forced-reboot pattern: drop the in-memory deployment (keep the store) → route 404 → reconcile →
 * route back. The library is in-process; the consumer is in-process here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InProcessSharedModuleRouteReconcileTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-sm-route-inproc");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired InProcessIsolation isolation;

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
    void route_consumes_shared_module_and_survives_reconcile() throws Exception {
        platform.install(SharedModuleRouteScenario.library());
        platform.install(SharedModuleRouteScenario.consumer("in-process"));

        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE).param("x", "1").param("y", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string("at=point(11,12)"));

        // Forced reboot: in-memory deployment lost, durable store retained.
        isolation.undeploy(SharedModuleRouteScenario.CONSUMER_ID);
        isolation.undeploy(SharedModuleRouteScenario.LIB_ID);
        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE)).andExpect(status().isNotFound());

        int restored = platform.reconcile();
        assertTrue(restored >= 1, "reconcile must restore ACTIVE modules from the store");

        mockMvc.perform(get(SharedModuleRouteScenario.ROUTE).param("x", "1").param("y", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string("at=point(11,12)"));
    }
}
