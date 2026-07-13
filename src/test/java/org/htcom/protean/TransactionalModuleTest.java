/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uncovered area #1: @Transactional / DB.
 * Verifies that @Transactional on a module @Service actually works down to rollback, plus leak checks for a tx-proxied module.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionalModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS item(id INT)");
        jdbc.update("DELETE FROM item");
    }

    static final String CONFIG = "runtime.tx.TxConfig";
    static final String SERVICE = "runtime.tx.ItemService";
    static final String CONTROLLER = "runtime.tx.ItemController";

    static final Map<String, String> SOURCES = Map.of(
            CONFIG, """
                    package runtime.tx;
                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.transaction.annotation.EnableTransactionManagement;
                    @Configuration
                    @EnableTransactionManagement
                    public class TxConfig {}
                    """,
            SERVICE, """
                    package runtime.tx;
                    import org.springframework.jdbc.core.JdbcTemplate;
                    import org.springframework.stereotype.Service;
                    import org.springframework.transaction.annotation.Transactional;
                    @Service
                    public class ItemService {
                        private final JdbcTemplate jdbc;
                        public ItemService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
                        @Transactional
                        public void insertThenFail() {
                            jdbc.update("insert into item(id) values (1)");
                            throw new RuntimeException("boom-rollback");
                        }
                        public int count() {
                            return jdbc.queryForObject("select count(*) from item", Integer.class);
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.tx;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class ItemController {
                        private final ItemService service;
                        public ItemController(ItemService service) { this.service = service; }
                        @GetMapping("/tx/test")
                        public String test() {
                            try { service.insertThenFail(); } catch (RuntimeException ignored) {}
                            return "count=" + service.count();
                        }
                    }
                    """
    );

    @Test
    void transactional_rollback_works_in_module() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("tx-mod", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);

        // when insertThenFail throws, @Transactional rolls back -> count must be 0.
        // (if the proxy/tx is not applied, autocommit gives count=1)
        mockMvc.perform(get("/tx/test"))
                .andExpect(status().isOk())
                .andExpect(content().string("count=0"));

        container.undeploy("tx-mod");
        mockMvc.perform(get("/tx/test")).andExpect(status().isNotFound());
    }

    @Test
    void transactional_module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("tx-mod-2", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/tx/test")).andExpect(status().isOk());
        container.undeploy("tx-mod-2");

        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed,
                "a @Transactional module must also have its ClassLoader reclaimed after undeploy "
                        + "(the tx infrastructure is scoped to the child context, so there should be no leak)");
    }

    private static void applyMemoryPressure() {
        try {
            java.util.List<byte[]> ballast = new java.util.ArrayList<>();
            while (true) {
                ballast.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError ignored) {
        }
    }
}
