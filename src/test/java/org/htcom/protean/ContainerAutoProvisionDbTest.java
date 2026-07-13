/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Container-track DB scope auto-provisioning consumer-wiring e2e (Testcontainers Postgres + Docker,
 * bootJar required). On auto-provision, the container worker is provisioned with a per-module
 * isolated schema; verifies that from inside the container it connects to the host's Postgres via
 * host.docker.internal and works only within its own schema (search_path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ContainerAutoProvisionDbTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
        registry.add("protean.worker.db.deprovision-on-undeploy", () -> "true");
        // db-host defaults to host.docker.internal (container -> host published port)
    }

    @Autowired MockMvc mockMvc;
    @Autowired ContainerWorkerIsolation isolation;

    static final String FQCN = "runtime.cp2.Cp2Controller";
    static final String SRC = """
            package runtime.cp2;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class Cp2Controller {
                private final JdbcTemplate jdbc;   // the container worker's provisioned scoped DataSource
                public Cp2Controller(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                @GetMapping("/cp2/probe")
                public String probe() {
                    jdbc.execute("create table if not exists cp2_t(v varchar(64))");
                    jdbc.update("insert into cp2_t values ('scoped')");
                    int rows = jdbc.queryForObject("select count(*) from cp2_t", Integer.class);
                    String schema = jdbc.queryForObject("select current_schema()", String.class);
                    return "rows=" + rows + ",schema=" + schema;
                }
            }
            """;

    @BeforeEach
    void preconditions() {
        assumeTrue(OsIsolationTest.bootJarExists(), "no bootJar ('gradle bootJar') — skip");
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("cp2-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void container_worker_boots_with_provisioned_scope() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("cp2-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("container")
                .build());

        // The container worker connects via host.docker.internal to its own schema (cp2_mod) in the host's Postgres and works there
        mockMvc.perform(get("/cp2/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("rows=1,schema=cp2_mod"));
    }
}
