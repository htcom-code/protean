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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves e2e that {@code classpath*:} pattern scanning enumerates an in-memory module's mapper XML
 * (resources) and {@code *.class} (classes) — the module wires mappers into a MyBatis
 * {@code SqlSessionFactoryBean} via {@code mapper-locations=classpath*:mbsmapper/*.xml} to run real
 * queries, and also checks the class pattern-scan count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyBatisScanModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;
    @Autowired JdbcTemplate jdbc;

    static final String CONFIG = "runtime.mbs.MbsConfig";
    static final String SERVICE = "runtime.mbs.MbsService";
    static final String CONTROLLER = "runtime.mbs.MbsController";

    static final Map<String, String> SOURCES = Map.of(
            CONFIG, """
                    package runtime.mbs;
                    import org.mybatis.spring.SqlSessionFactoryBean;
                    import org.apache.ibatis.session.SqlSessionFactory;
                    import org.springframework.context.ApplicationContext;
                    import org.springframework.context.annotation.Bean;
                    import org.springframework.context.annotation.Configuration;
                    import javax.sql.DataSource;
                    @Configuration
                    public class MbsConfig {
                        @Bean
                        public SqlSessionFactory sqlSessionFactory(DataSource ds, ApplicationContext ctx) throws Exception {
                            SqlSessionFactoryBean f = new SqlSessionFactoryBean();
                            f.setDataSource(ds);
                            // classpath*: pattern -> enumerate mapper XML from the module CL index (ProteanResourcePatternResolver)
                            f.setMapperLocations(ctx.getResources("classpath*:mbsmapper/*.xml"));
                            f.afterPropertiesSet();
                            return f.getObject();
                        }
                    }
                    """,
            SERVICE, """
                    package runtime.mbs;
                    import org.apache.ibatis.session.SqlSession;
                    import org.apache.ibatis.session.SqlSessionFactory;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class MbsService {
                        private final SqlSessionFactory factory;
                        public MbsService(SqlSessionFactory factory) { this.factory = factory; }
                        public int insertThenCount(int id) {
                            try (SqlSession s = factory.openSession(true)) {
                                s.insert("mbs.ins", id);
                                return s.selectOne("mbs.count");
                            }
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.mbs;
                    import org.springframework.context.ApplicationContext;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class MbsController {
                        private final MbsService service;
                        private final ApplicationContext ctx;
                        public MbsController(MbsService service, ApplicationContext ctx) {
                            this.service = service; this.ctx = ctx;
                        }
                        @GetMapping("/mbs/count")
                        public String count() { return "count=" + service.insertThenCount(7); }
                        @GetMapping("/mbs/scan")
                        public String scan() throws Exception {
                            return "classes=" + ctx.getResources("classpath*:runtime/mbs/**/*.class").length;
                        }
                    }
                    """
    );

    static final Map<String, ModuleResource> RESOURCES = Map.of(
            "mbsmapper/MbsMapper.xml", ModuleResource.text("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                    <mapper namespace="mbs">
                        <insert id="ins">INSERT INTO mbs_t(id) VALUES (#{id})</insert>
                        <select id="count" resultType="int">SELECT COUNT(*) FROM mbs_t</select>
                    </mapper>
                    """)
    );

    @BeforeEach
    void resetTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS mbs_t(id INT)");
        jdbc.update("DELETE FROM mbs_t");
    }

    @AfterEach
    void cleanup() {
        if (container.isDeployed("mbs-mod")) {
            container.undeploy("mbs-mod");
        }
    }

    @Test
    void classpath_star_scan_finds_module_mapper_xml_and_classes() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES,
                ModuleResource.decodeAll(RESOURCES), "mbs-mod");
        container.deploy("mbs-mod", loader, List.of(CONFIG, SERVICE, CONTROLLER), CONTROLLER);

        // find mapper XML via classpath*: to configure the SqlSessionFactory -> real INSERT/SELECT.
        mockMvc.perform(get("/mbs/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("count=1"));

        // classpath*: class pattern scan enumerates the module's 3 classes (config, service, controller).
        String body = mockMvc.perform(get("/mbs/scan"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int classes = Integer.parseInt(body.substring("classes=".length()));
        assertTrue(classes >= 3, "classpath*: class scan must enumerate the module classes: " + body);

        container.undeploy("mbs-mod");
        mockMvc.perform(get("/mbs/count")).andExpect(status().isNotFound());
    }
}
