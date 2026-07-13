/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.db.DbDialect;
import org.htcom.protean.db.DbProvisioningConfig;
import org.htcom.protean.db.DbScopeProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DbDialect extension-point (SPI) verification — no Docker required (no real connection is made,
 * only the dialect-selection logic is checked).
 *
 * <p>The library ships built-in mysql/postgresql, but a consumer that registers a {@link DbDialect}
 * bean must be able to add an arbitrary vendor or override a built-in one. This test pins that
 * contract.
 */
class DbDialectSpiTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class, DbProvisioningConfig.class)
            .withPropertyValues(
                    "protean.worker.db.auto-provision=true",
                    "protean.worker.db.admin-url=jdbc:example://localhost/",
                    "protean.worker.db.admin-username=admin",
                    "protean.worker.db.admin-password=pw");

    @Test
    void builtin_postgres_alias_still_resolves() {
        runner.withPropertyValues("protean.worker.db.dialect=postgres")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .getBean(DbScopeProvisioner.class)
                        .extracting(p -> p.dialect().id())
                        .isEqualTo("postgresql"));
    }

    @Test
    void consumer_dialect_bean_adds_new_vendor() {
        runner.withUserConfiguration(CustomDialectConfig.class)
                .withPropertyValues("protean.worker.db.dialect=oracle")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .getBean(DbScopeProvisioner.class)
                        .extracting(p -> p.dialect().id())
                        .isEqualTo("oracle"));
    }

    @Test
    void consumer_dialect_bean_overrides_builtin() {
        runner.withUserConfiguration(MysqlOverrideConfig.class)
                .withPropertyValues("protean.worker.db.dialect=mysql")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .getBean(DbScopeProvisioner.class)
                        .extracting(p -> p.dialect().maxNameLength())
                        .isEqualTo(999));  // marker of the overriding impl
    }

    @Test
    void unknown_dialect_without_custom_fails_context() {
        runner.withPropertyValues("protean.worker.db.dialect=oracle")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @EnableConfigurationProperties(ProteanProperties.class)
    static class PropsConfig {
    }

    @Configuration
    static class CustomDialectConfig {
        @Bean
        DbDialect oracleDialect() {
            return new StubDialect("oracle", 30);
        }
    }

    @Configuration
    static class MysqlOverrideConfig {
        @Bean
        DbDialect mysqlOverride() {
            return new StubDialect("mysql", 999);
        }
    }

    /** Marker-only dialect that never actually runs (only selection logic is verified, so DDL is never called). */
    private record StubDialect(String id, int maxNameLength) implements DbDialect {
        @Override
        public void createScope(JdbcTemplate admin, String name, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dropScope(JdbcTemplate admin, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String scopedUrl(String adminUrl, String name) {
            return adminUrl + name;
        }
    }
}
