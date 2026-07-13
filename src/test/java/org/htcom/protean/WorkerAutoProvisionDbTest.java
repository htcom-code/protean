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
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
        registry.add("protean.worker.db.deprovision-on-undeploy", () -> "true");
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

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("wp-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_boots_with_provisioned_scope_and_writes_to_its_own_schema() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("wp-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("worker")
                .build());

        // the worker boots with the provisioned Postgres scope -> works in its own schema (wp_mod, the sanitized result)
        mockMvc.perform(get("/wp/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("rows=1,schema=wp_mod"));
    }
}
