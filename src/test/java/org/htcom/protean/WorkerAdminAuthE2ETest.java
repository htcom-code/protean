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
 * End-to-end happy path for opt-in worker {@code /__admin/*} authentication (hmac mode). With
 * {@code protean.worker.admin-auth.enabled=true}, the main spawns the worker with the shared secret, signs its
 * control-plane calls (seed + deploy) via {@code WorkerAdminClient}, and the worker's {@code WorkerAdminAuthFilter}
 * verifies them. A module that deploys and serves proves the signed main→worker path works end to end; the filter's
 * rejection branches are covered by {@code WorkerAdminAuthFilterTest}. Spawns a real worker JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerAdminAuthE2ETest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-worker-admin-auth-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.admin-auth.enabled", () -> "true");
        registry.add("protean.worker.admin-auth.mode", () -> "hmac");
        registry.add("protean.worker.admin-auth.secret", () -> "e2e-fixed-admin-secret");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String MODULE = "aa-mod";
    static final String FQCN = "runtime.aa.AaController";
    static final Map<String, String> SOURCES = Map.of(FQCN, """
            package runtime.aa;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class AaController {
                @GetMapping("/aa/ping") public String ping() { return "worker-ok"; }
            }
            """);

    static final Map<String, String> TESTS = Map.of("runtime.aa.AaControllerTest", """
            package runtime.aa;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AaControllerTest { @Test void ok() { assertEquals("worker-ok", new AaController().ping()); } }
            """);

    static ModuleDescriptor module() {
        return ModuleDescriptor.builder().id(MODULE).version("1.0.0")
                .isolationMode("worker")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(SOURCES).tests(TESTS)
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            if (platform.find(MODULE).isPresent()) {
                platform.uninstall(MODULE);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_deploys_and_serves_with_admin_auth_enabled() throws Exception {
        // install → main signs the /__admin/deploy (and seed) calls; the worker's filter verifies them. A 401 anywhere
        // would fail the install, so a served route proves the signed control-plane round-trip.
        platform.install(module());
        mockMvc.perform(get("/aa/ping")).andExpect(status().isOk()).andExpect(content().string("worker-ok"));

        platform.uninstall(MODULE);   // exercises the signed /__admin/undeploy path too
        mockMvc.perform(get("/aa/ping")).andExpect(status().isNotFound());
    }
}
