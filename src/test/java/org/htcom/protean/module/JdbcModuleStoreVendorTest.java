/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JdbcModuleStore against real MySQL and PostgreSQL engines (Testcontainers, Docker-gated) — the cross-vendor coverage
 * that was missing (the store was only ever tested on the embedded H2). Proves the vendor-adaptive DDL boots
 * (dialect detected from the product name), the startup self-check passes, and a large descriptor round-trips through
 * the {@code LONGTEXT}/{@code TEXT} column with a working auto-increment {@code seq}.
 */
class JdbcModuleStoreVendorTest {

    /** A source string comfortably larger than any bounded VARCHAR (e.g. MySQL's 65,535 limit). */
    private static final String BIG_SOURCE = "class Big { /* " + "x".repeat(200_000) + " */ }";

    private static ModuleDescriptor bigDescriptor(String id, String version) {
        return ModuleDescriptor.builder()
                .id(id).version(version)
                .controllerFqcn("x.Y").componentFqcns(List.of("x.Y"))
                .sources(Map.of("x.Y", BIG_SOURCE)).tests(Map.of("x.YTest", "t"))
                .build();
    }

    private static void roundTrip(JdbcTemplate jdbc) {
        ProteanProperties props = new ProteanProperties();  // dialect blank -> auto-detect from the container
        // Mirror the Spring Boot ObjectMapper (lenient on unknown props + classpath modules registered).
        ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .findAndAddModules()
                .build();
        JdbcModuleStore store = new JdbcModuleStore(jdbc, mapper, props, List.of());
        store.initSchema();  // vendor-adaptive DDL + startup self-check (128 KB probe + seq)

        store.save(bigDescriptor("v-mod", "1.0.0"));
        assertTrue(store.load("v-mod").isPresent(), "descriptor persisted");
        assertEquals("1.0.0", store.load("v-mod").get().version());
        assertEquals(BIG_SOURCE, store.load("v-mod").get().sources().get("x.Y"), "large source not truncated");
        assertTrue(store.listActive().stream().anyMatch(d -> d.id().equals("v-mod")));

        store.save(bigDescriptor("v-mod", "2.0.0"));
        assertEquals(2, store.history("v-mod").size(), "append-only history via auto-increment seq");
        assertEquals("2.0.0", store.history("v-mod").get(0).version(), "newest first");
        assertTrue(store.loadVersion("v-mod", "1.0.0").isPresent());

        store.remove("v-mod");
        assertTrue(store.load("v-mod").isEmpty());
        assertTrue(store.history("v-mod").isEmpty());
    }

    @Test
    void mysql_backend_boots_and_round_trips() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no Docker — skip MySQL store test");
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            roundTrip(new JdbcTemplate(new DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())));
        }
    }

    @Test
    void postgres_backend_boots_and_round_trips() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no Docker — skip Postgres store test");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            roundTrip(new JdbcTemplate(new DriverManagerDataSource(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())));
        }
    }
}
