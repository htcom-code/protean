/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotion gate (approval, human authorization): when {@code protean.gate.approval.required=true}, install
 * passes only the automatic gates and stores the module as PENDING_APPROVAL (not serving); only approve
 * promotes it to ACTIVE. The signing/test/review gates are turned off for isolation.
 */
@SpringBootTest(properties = {
        "protean.gate.tests-enabled=false",
        "protean.gate.review-enabled=false",
        "protean.gate.approval.required=true"
})
@AutoConfigureMockMvc
class ModuleApprovalGateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-approval-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ModuleStore store;

    static final String CTRL = "runtime.appr.ApprController";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.appr;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ApprController {
                    @GetMapping("/appr/ping") public String ping() { return "ok"; }
                }
                """;
        return ModuleDescriptor.builder()
                .id("appr-mod").version("1.0.0")
                .controllerFqcn(CTRL).componentFqcns(List.of(CTRL)).sources(Map.of(CTRL, src))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall("appr-mod");
        } catch (RuntimeException ignored) {
        }
        try {
            store.remove("appr-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void install_with_approval_required_parks_as_pending_and_does_not_serve() throws Exception {
        platform.install(descriptor());

        // Stored as PENDING but absent from serving and the (ACTIVE) list.
        assertEquals(ModuleDescriptor.DesiredState.PENDING_APPROVAL,
                store.load("appr-mod").orElseThrow().desiredState());
        assertTrue(platform.list().stream().noneMatch(d -> d.id().equals("appr-mod")), "PENDING must not appear in list(ACTIVE)");
        mockMvc.perform(get("/appr/ping")).andExpect(status().isNotFound());
    }

    @Test
    void approve_promotes_pending_to_active_and_serves() throws Exception {
        platform.install(descriptor());
        platform.approve("appr-mod", "alice");

        assertEquals(ModuleDescriptor.DesiredState.ACTIVE, store.load("appr-mod").orElseThrow().desiredState());
        mockMvc.perform(get("/appr/ping")).andExpect(status().isOk());
    }

    @Test
    void reject_removes_pending_module() throws Exception {
        platform.install(descriptor());
        platform.reject("appr-mod", "alice");

        assertFalse(store.load("appr-mod").isPresent(), "a rejected module must be removed from the store");
        mockMvc.perform(get("/appr/ping")).andExpect(status().isNotFound());
    }

    @Test
    void reconcile_does_not_restore_pending_module() throws Exception {
        platform.install(descriptor());
        platform.reconcile();   // simulate restart recovery: only ACTIVE serves, unapproved bypass blocked
        mockMvc.perform(get("/appr/ping")).andExpect(status().isNotFound());
    }
}
