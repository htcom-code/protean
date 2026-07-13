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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Worker dedicated DB check: a worker module uses the worker's own DataSource (a separate JVM = isolated H2).
 * -> It cannot see the main DB tables (isolation), only its own data. The worker cannot touch the main DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerDedicatedDbTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-wdb-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        // provision a worker-dedicated DataSource (scoped DB). In production, use a restricted user/schema.
        registry.add("protean.worker.datasource.url", () -> "jdbc:h2:mem:workerdb;DB_CLOSE_DELAY=-1");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;
    @Autowired JdbcTemplate mainJdbc;   // the main DB

    static final String FQCN = "runtime.db.DbController";
    static final String SRC = """
            package runtime.db;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class DbController {
                private final JdbcTemplate jdbc;   // the worker's dedicated DataSource
                public DbController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                @GetMapping("/db/probe")
                public String probe() {
                    jdbc.execute("create table if not exists t_worker(v varchar(64))");
                    jdbc.update("insert into t_worker values ('worker-data')");
                    int rows = jdbc.queryForObject("select count(*) from t_worker", Integer.class);
                    boolean seesMain;
                    try { jdbc.queryForObject("select count(*) from t_main", Integer.class); seesMain = true; }
                    catch (Exception e) { seesMain = false; }
                    return "worker_rows=" + rows + ",sees_main=" + seesMain;
                }
            }
            """;

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("wdb-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_uses_its_own_isolated_datasource() throws Exception {
        // table + data in the main DB
        mainJdbc.execute("create table if not exists t_main(v varchar(64))");
        mainJdbc.update("delete from t_main");
        mainJdbc.update("insert into t_main values ('main-data')");

        // deploy the worker module (JdbcTemplate = worker-dedicated, needsSharedBeans=false)
        isolation.deploy(ModuleDescriptor.builder()
                .id("wdb-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .isolationMode("worker")
                .build());

        // the worker writes only to its own DB and cannot see the main table (isolation)
        mockMvc.perform(get("/db/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("worker_rows=1,sees_main=false"));

        // the worker cannot touch the main DB — still 1 row
        assertEquals(1, mainJdbc.queryForObject("select count(*) from t_main", Integer.class));
    }
}
