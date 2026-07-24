/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.db.DbScope;
import org.htcom.protean.db.DbScopeProvisioner;
import org.htcom.protean.db.MySqlDialect;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DB scope auto-provisioning — high-fidelity MySQL verification (Testcontainers, Docker-gated).
 * Confirms against a real engine that a dedicated DATABASE+USER+GRANT is created per module, and that
 * one module's user cannot access another module's DB.
 */
class MySqlScopeProvisioningTest {

    private static JdbcTemplate jdbc(String url, String user, String pw) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, pw));
    }

    @Test
    void provisions_isolated_database_and_enforces_grant() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip MySQL provisioning test");
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            String adminUrl = mysql.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new MySqlDialect(), adminUrl, "root", mysql.getPassword());

            DbScope a = prov.provision("mod-a");
            DbScope b = prov.provision("mod-b");

            // A freely writes to and reads from its own DB
            JdbcTemplate ja = jdbc(a.url(), a.username(), a.password());
            ja.execute("CREATE TABLE t (x INT)");
            ja.update("INSERT INTO t VALUES (1)");
            assertEquals(1, (int) ja.queryForObject("SELECT COUNT(*) FROM t", Integer.class));

            // A cannot access B's DATABASE (its GRANT is limited to its own DB)
            assertThrows(Exception.class,
                    () -> ja.execute("CREATE TABLE `" + b.username() + "`.x (i INT)"),
                    "module A must not access module B's DB");

            // deprovision -> remove A's DATABASE
            prov.destroy("mod-a");
            JdbcTemplate admin = jdbc(adminUrl, "root", mysql.getPassword());
            Integer schemas = admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, a.username());
            assertEquals(0, schemas, "the DB must be gone after deprovisioning");
        }
    }

    @Test
    void detach_drops_the_user_but_retains_the_database_and_data() {
        // Detach = data-safe deprovision: the dedicated USER is dropped but its DATABASE and data remain. A later
        // re-provision recreates the user (fresh password) and the persisted data is still there.
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip MySQL detach test");
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            String adminUrl = mysql.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new MySqlDialect(), adminUrl, "root", mysql.getPassword());

            DbScope first = prov.provision("tenant-x");
            jdbc(first.url(), first.username(), first.password()).execute("CREATE TABLE t (x INT)");
            jdbc(first.url(), first.username(), first.password()).update("INSERT INTO t VALUES (42)");

            prov.detach("tenant-x");

            JdbcTemplate admin = jdbc(adminUrl, "root", mysql.getPassword());
            assertEquals(1, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, first.username()), "detach must retain the database");
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM mysql.user WHERE user = ?", Integer.class, first.username()),
                    "detach must drop the user");

            DbScope second = prov.provision("tenant-x");
            JdbcTemplate j2 = jdbc(second.url(), second.username(), second.password());
            assertEquals(42, (int) j2.queryForObject("SELECT x FROM t", Integer.class),
                    "data must survive detach + re-provision");
        }
    }

    @Test
    void destroy_drops_the_database_and_user_irreversibly() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip MySQL destroy test");
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            String adminUrl = mysql.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new MySqlDialect(), adminUrl, "root", mysql.getPassword());

            DbScope s = prov.provision("tenant-y");
            jdbc(s.url(), s.username(), s.password()).execute("CREATE TABLE t (x INT)");

            prov.destroy("tenant-y");

            JdbcTemplate admin = jdbc(adminUrl, "root", mysql.getPassword());
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, s.username()), "destroy must drop the database");
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM mysql.user WHERE user = ?", Integer.class, s.username()),
                    "destroy must drop the user");
        }
    }

    @Test
    void reprovision_resets_password_so_the_new_credentials_connect() {
        // Restart scenario: the in-memory scope cache is gone, so provision() runs again for an existing
        // user and generates a *fresh* password. The user must end up with that new password (otherwise the
        // worker cannot connect after a restart). Data in the DB must survive.
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip MySQL reprovision test");
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")) {
            mysql.start();
            String adminUrl = mysql.getJdbcUrl();
            // no teardown between provisions → the user/DB persist across "restarts"
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new MySqlDialect(), adminUrl, "root", mysql.getPassword());

            DbScope first = prov.provision("mod-a");
            JdbcTemplate j1 = jdbc(first.url(), first.username(), first.password());
            j1.execute("CREATE TABLE t (x INT)");
            j1.update("INSERT INTO t VALUES (1)");

            // "restart": provision again → new password for the same user
            DbScope second = prov.provision("mod-a");
            org.junit.jupiter.api.Assertions.assertNotEquals(first.password(), second.password(),
                    "a fresh password is generated on re-provision");

            // the new credentials must connect and see the persisted data
            JdbcTemplate j2 = jdbc(second.url(), second.username(), second.password());
            assertEquals(1, (int) j2.queryForObject("SELECT COUNT(*) FROM t", Integer.class));
        }
    }
}
