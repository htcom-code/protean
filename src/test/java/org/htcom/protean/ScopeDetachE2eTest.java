/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.db.ScopeAdminService;
import org.htcom.protean.db.ScopeManager;
import org.htcom.protean.db.ScopeRecord;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of {@link ScopeAdminService#detach} orchestration (Testcontainers Postgres): a module deployed
 * to a scope through the platform is, on detach, undeployed (its route goes 404), its worker reclaimed, its DB login
 * dropped, and the scope marked DETACHED — the takeDownModules + forgetScope + provisioner.detach + registry path that
 * unit tests cannot reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ScopeDetachE2eTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-scope-detach-e2e");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> "detachscope");
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;
    @Autowired ScopeAdminService scopes;
    @Autowired ScopeManager scopeManager;

    static final String FQCN = "runtime.det.DetController";
    static ModuleDescriptor module() {
        return ModuleDescriptor.builder()
                .id("det-mod").version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).scope("detachscope").isolationMode("worker")
                .sources(Map.of(FQCN, """
                        package runtime.det;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class DetController {
                            @GetMapping("/det/ping") public String ping() { return "ok"; }
                        }
                        """))
                .tests(Map.of("runtime.det.DetControllerTest", """
                        package runtime.det;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        class DetControllerTest { @Test void ok() { assertEquals("ok", new DetController().ping()); } }
                        """))
                .build();
    }

    @Test
    void detach_undeploys_the_scope_modules_and_marks_the_scope_detached() throws Exception {
        platform.install(module());
        mockMvc.perform(get("/det/ping")).andExpect(status().isOk()).andExpect(content().string("ok"));

        scopes.detach("detachscope");

        // module gone from the store + its route (undeployed by the detach orchestration)
        assertTrue(platform.find("det-mod").isEmpty(), "detach must undeploy the scope's modules");
        mockMvc.perform(get("/det/ping")).andExpect(status().isNotFound());
        // scope registry marked DETACHED (not deployable, still known)
        assertEquals(ScopeRecord.State.DETACHED, scopeManager.stateOf("detachscope").orElseThrow());
        assertTrue(scopeManager.isKnown("detachscope") && !scopeManager.isDeployable("detachscope"));
    }
}
