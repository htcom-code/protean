/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.zaxxer.hikari.HikariDataSource;
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Representative-stack e2e. Multiple DataSources: a module defines two DataSources (+ @Qualifier) and
 * a JdbcTemplate for each in its own child @Configuration, writes to two independent DBs (isolation),
 * and both pools are closed on unload (platform = mechanism: the module defines the pools, the platform
 * handles lifecycle cleanup). Pool and routing policy are the consumer's responsibility.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class MultiDataSourceModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    /** The module publishes its own DataSources here (visible via the parent ClassLoader). */
    public static volatile DataSource capturedA;
    public static volatile DataSource capturedB;

    static final String CONFIG = "runtime.mds.MultiConfig";
    static final String SERVICE = "runtime.mds.MultiService";
    static final String CONTROLLER = "runtime.mds.MultiController";

    static final Map<String, String> SOURCES = Map.of(
            CONFIG, """
                    package runtime.mds;
                    import com.zaxxer.hikari.HikariDataSource;
                    import org.springframework.beans.factory.annotation.Qualifier;
                    import org.springframework.context.annotation.Bean;
                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.jdbc.core.JdbcTemplate;
                    import javax.sql.DataSource;
                    @Configuration
                    public class MultiConfig {
                        private static DataSource h2(String name) {
                            HikariDataSource ds = new HikariDataSource();
                            ds.setJdbcUrl("jdbc:h2:mem:" + name);
                            ds.setUsername("sa");
                            return ds;
                        }
                        @Bean("dsA") public DataSource dsA() { return h2("mds-a"); }
                        @Bean("dsB") public DataSource dsB() { return h2("mds-b"); }
                        @Bean("jtA") public JdbcTemplate jtA(@Qualifier("dsA") DataSource ds) { return new JdbcTemplate(ds); }
                        @Bean("jtB") public JdbcTemplate jtB(@Qualifier("dsB") DataSource ds) { return new JdbcTemplate(ds); }
                    }
                    """,
            SERVICE, """
                    package runtime.mds;
                    import org.htcom.protean.MultiDataSourceModuleTest;
                    import org.springframework.beans.factory.annotation.Qualifier;
                    import org.springframework.jdbc.core.JdbcTemplate;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class MultiService {
                        private final JdbcTemplate a, b;
                        public MultiService(@Qualifier("jtA") JdbcTemplate a, @Qualifier("jtB") JdbcTemplate b) {
                            this.a = a; this.b = b;
                        }
                        public String run() {
                            a.execute("create table if not exists t(id int)");
                            b.execute("create table if not exists t(id int)");
                            a.update("delete from t"); b.update("delete from t");
                            a.update("insert into t values (1)");
                            a.update("insert into t values (2)");
                            b.update("insert into t values (9)");   // A's inserts must not be visible to B (isolation)
                            MultiDataSourceModuleTest.capturedA = a.getDataSource();
                            MultiDataSourceModuleTest.capturedB = b.getDataSource();
                            int ca = a.queryForObject("select count(*) from t", Integer.class);
                            int cb = b.queryForObject("select count(*) from t", Integer.class);
                            return "a=" + ca + ",b=" + cb;
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.mds;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class MultiController {
                        private final MultiService service;
                        public MultiController(MultiService service) { this.service = service; }
                        @GetMapping("/multids/run")
                        public String run() { return service.run(); }
                    }
                    """
    );

    @BeforeEach
    void reset() {
        capturedA = null;
        capturedB = null;
    }

    @AfterEach
    void cleanup() {
        if (container.isDeployed("mds-mod")) {
            container.undeploy("mds-mod");
        }
    }

    @Test
    void module_defines_two_isolated_datasources_and_pools_close_on_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("mds-mod", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);

        // writes to two independent DBs via two DataSources -> A=2, B=1 (isolation: A's inserts not visible to B).
        mockMvc.perform(get("/multids/run"))
                .andExpect(status().isOk())
                .andExpect(content().string("a=2,b=1"));

        DataSource a = capturedA;
        DataSource b = capturedB;
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "the module must have two distinct DataSources");
        assertFalse(((HikariDataSource) a).isClosed());
        assertFalse(((HikariDataSource) b).isClosed());

        // unload -> both pools defined by the module must be closed (child.close() cleans up AutoCloseables).
        container.undeploy("mds-mod");
        assertTrue(((HikariDataSource) a).isClosed(), "DataSource A pool must be closed on unload");
        assertTrue(((HikariDataSource) b).isClosed(), "DataSource B pool must be closed on unload");
        mockMvc.perform(get("/multids/run")).andExpect(status().isNotFound());
    }
}
