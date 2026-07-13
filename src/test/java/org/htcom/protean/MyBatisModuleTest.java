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
import org.htcom.protean.module.ModuleResource;
import org.junit.jupiter.api.AfterEach;
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
 * Representative-stack e2e. Integrated proof: a module <b>ships mapper XML through the resource
 * channel</b>, configures a per-module MyBatis SqlSessionFactory in its own child @Configuration to run
 * real queries, and its ClassLoader is reclaimed on unload (zero leaks). MyBatis is bundled on the host
 * by the consumer (test-scope simulation).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyBatisModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;
    @Autowired JdbcTemplate jdbc;

    static final String CONFIG = "runtime.mb.MyBatisConfig";
    static final String SERVICE = "runtime.mb.GreetingService";
    static final String CONTROLLER = "runtime.mb.GreetingController";

    static final Map<String, String> SOURCES = Map.of(
            CONFIG, """
                    package runtime.mb;
                    import org.apache.ibatis.builder.xml.XMLMapperBuilder;
                    import org.apache.ibatis.mapping.Environment;
                    import org.apache.ibatis.session.SqlSessionFactory;
                    import org.apache.ibatis.session.SqlSessionFactoryBuilder;
                    import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
                    import org.springframework.context.annotation.Bean;
                    import org.springframework.context.annotation.Configuration;
                    import javax.sql.DataSource;
                    import java.io.InputStream;
                    @Configuration
                    public class MyBatisConfig {
                        @Bean
                        public SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
                            org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
                            cfg.setEnvironment(new Environment("mod", new JdbcTransactionFactory(), ds));
                            String res = "mapper/GreetingMapper.xml";
                            try (InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
                                new XMLMapperBuilder(in, cfg, res, cfg.getSqlFragments()).parse();
                            }
                            return new SqlSessionFactoryBuilder().build(cfg);
                        }
                    }
                    """,
            SERVICE, """
                    package runtime.mb;
                    import org.apache.ibatis.session.SqlSession;
                    import org.apache.ibatis.session.SqlSessionFactory;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class GreetingService {
                        private final SqlSessionFactory factory;
                        public GreetingService(SqlSessionFactory factory) { this.factory = factory; }
                        public int insertThenCount(int id) {
                            try (SqlSession s = factory.openSession(true)) {
                                s.insert("greeting.ins", id);
                                return s.selectOne("greeting.count");
                            }
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.mb;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class GreetingController {
                        private final GreetingService service;
                        public GreetingController(GreetingService service) { this.service = service; }
                        @GetMapping("/mybatis/count")
                        public String count() { return "count=" + service.insertThenCount(1); }
                    }
                    """
    );

    static final Map<String, ModuleResource> RESOURCES = Map.of(
            "mapper/GreetingMapper.xml", ModuleResource.text("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                    <mapper namespace="greeting">
                        <insert id="ins">INSERT INTO greet(id) VALUES (#{id})</insert>
                        <select id="count" resultType="int">SELECT COUNT(*) FROM greet</select>
                    </mapper>
                    """)
    );

    @BeforeEach
    void resetTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS greet(id INT)");
        jdbc.update("DELETE FROM greet");
    }

    @AfterEach
    void cleanup() {
        if (container.isDeployed("mb-mod")) {
            container.undeploy("mb-mod");
        }
    }

    @Test
    void module_ships_mapper_xml_and_mybatis_reads_it_from_module_classpath() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES,
                ModuleResource.decodeAll(RESOURCES), "mb-mod");
        container.deploy("mb-mod", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);

        // the module's MyBatis parses the mapper XML from the resource channel and runs a real INSERT+SELECT.
        mockMvc.perform(get("/mybatis/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("count=1"));

        container.undeploy("mb-mod");
        mockMvc.perform(get("/mybatis/count")).andExpect(status().isNotFound());
    }

    @Test
    void mybatis_module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES,
                ModuleResource.decodeAll(RESOURCES), "mb-mod");
        container.deploy("mb-mod", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/mybatis/count")).andExpect(status().isOk());
        container.undeploy("mb-mod");

        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed,
                "a MyBatis module (per-module SqlSessionFactory) must also have its ClassLoader reclaimed after undeploy");
    }

}
