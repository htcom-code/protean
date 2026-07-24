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
import org.junit.jupiter.api.AfterEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB scope auto-provisioning — worker consumption wiring e2e (Testcontainers Postgres, class skipped without Docker).
 * With auto-provision enabled in worker mode, on module deploy: a per-module isolated schema is provisioned, the worker
 * boots with that scope's credentials, and the module performs DB work only within its own schema (search_path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WorkerAutoProvisionDbTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-autoprov-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> "wpscope");
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.wp.WpController";
    static final String SRC = """
            package runtime.wp;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class WpController {
                private final JdbcTemplate jdbc;   // the worker's provisioned scoped DataSource
                public WpController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                @GetMapping("/wp/probe")
                public String probe() {
                    jdbc.execute("create table if not exists wp_t(v varchar(64))");
                    jdbc.update("insert into wp_t values ('scoped')");
                    int rows = jdbc.queryForObject("select count(*) from wp_t", Integer.class);
                    String schema = jdbc.queryForObject("select current_schema()", String.class);
                    return "rows=" + rows + ",schema=" + schema;
                }
            }
            """;

    static final String FQCN2 = "runtime.wp.Wp2Controller";
    static final String SRC2 = """
            package runtime.wp;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Wp2Controller {
                @GetMapping("/wp2/ping")
                public String ping() { return "wp2"; }
            }
            """;

    @AfterEach
    void cleanup() {
        for (String id : new String[]{"wp-mod", "wp-mod2"}) {
            try {
                isolation.undeploy(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void worker_boots_with_provisioned_scope_and_writes_to_its_own_schema() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("wp-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("worker").scope("wpscope")
                .build());

        // the worker boots with the provisioned Postgres scope -> works in its own schema (the sanitized scope name)
        mockMvc.perform(get("/wp/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("rows=1,schema=wpscope"));
    }

    @Test
    void same_scope_modules_pack_into_one_worker() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("wp-mod").version("1.0.0").controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, SRC)).isolationMode("worker").scope("wpscope").build());
        isolation.deploy(ModuleDescriptor.builder()
                .id("wp-mod2").version("1.0.0").controllerFqcn(FQCN2).componentFqcns(List.of(FQCN2))
                .sources(Map.of(FQCN2, SRC2)).isolationMode("worker").scope("wpscope").build());

        // Both modules share scope "wpscope" → pack into a single worker JVM (no longer 1 JVM per module).
        org.junit.jupiter.api.Assertions.assertEquals(1, isolation.workerCount(),
                "same-scope modules must share one worker");
        mockMvc.perform(get("/wp2/ping")).andExpect(status().isOk()).andExpect(content().string("wp2"));
    }

    @Test
    void scope_admin_rest_lists_the_seeded_scope() throws Exception {
        // The scope-admin surface (ScopeAdminController → ScopeAdminService → ScopeManager) is wired under
        // auto-provision + admin.enabled(default) and lists the seeded scope even before any deploy.
        mockMvc.perform(get("/platform/scopes"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("wpscope")));
    }

    @Test
    void deploy_without_scope_is_rejected_under_auto_provision() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                isolation.deploy(ModuleDescriptor.builder()
                        .id("wp-mod").version("1.0.0").controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                        .sources(Map.of(FQCN, SRC)).isolationMode("worker").build()),
                "auto-provision requires an explicit scope");
    }
}
