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
import org.htcom.protean.db.PostgresDialect;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DB scope auto-provisioning — high-fidelity PostgreSQL verification (Testcontainers, Docker-gated).
 * Confirms that a dedicated SCHEMA+ROLE+GRANT is created per module, and that one module's role cannot
 * access another module's schema (no USAGE granted).
 */
class PostgresScopeProvisioningTest {

    private static JdbcTemplate jdbc(String url, String user, String pw) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, pw));
    }

    @Test
    void provisions_isolated_schema_and_enforces_grant() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip Postgres provisioning test");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            String adminUrl = pg.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new PostgresDialect(), adminUrl, pg.getUsername(), pg.getPassword(), true);

            DbScope a = prov.provision("mod-a");
            DbScope b = prov.provision("mod-b");

            // A freely writes to and reads from its own schema (search_path)
            JdbcTemplate ja = jdbc(a.url(), a.username(), a.password());
            ja.execute("CREATE TABLE t (x INT)");
            ja.update("INSERT INTO t VALUES (1)");
            assertEquals(1, (int) ja.queryForObject("SELECT COUNT(*) FROM t", Integer.class));

            // A cannot access B's schema (no USAGE granted)
            assertThrows(Exception.class,
                    () -> ja.execute("CREATE TABLE \"" + b.username() + "\".x (i INT)"),
                    "module A must not access module B's schema");

            // deprovision -> remove A's schema
            prov.deprovision("mod-a");
            JdbcTemplate admin = jdbc(adminUrl, pg.getUsername(), pg.getPassword());
            Integer schemas = admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, a.username());
            assertEquals(0, schemas, "the schema must be gone after deprovisioning");
        }
    }

    @Test
    void detach_drops_login_but_retains_the_schema_and_data_and_reprovision_restores_access() {
        // Detach = data-safe deprovision: the role can no longer log in (NOLOGIN) but its SCHEMA and data remain.
        // Re-provisioning (create) restores LOGIN with a fresh password and the persisted data is still there.
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip Postgres detach test");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            String adminUrl = pg.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new PostgresDialect(), adminUrl, pg.getUsername(), pg.getPassword(), false);

            DbScope first = prov.provision("tenant-x");
            JdbcTemplate j1 = jdbc(first.url(), first.username(), first.password());
            j1.execute("CREATE TABLE t (x INT)");
            j1.update("INSERT INTO t VALUES (42)");

            prov.detach("tenant-x");

            JdbcTemplate admin = jdbc(adminUrl, pg.getUsername(), pg.getPassword());
            // schema (and its data) retained
            assertEquals(1, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, first.username()), "detach must retain the schema");
            // the role still exists but cannot log in
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM pg_roles WHERE rolname = ? AND rolcanlogin", Integer.class, first.username()),
                    "detach must revoke the role's LOGIN");
            assertThrows(Exception.class, () -> jdbc(first.url(), first.username(), first.password())
                    .queryForObject("SELECT 1", Integer.class), "a detached role must not be able to connect");

            // re-provision restores login (fresh password) and the data survives
            DbScope second = prov.provision("tenant-x");
            JdbcTemplate j2 = jdbc(second.url(), second.username(), second.password());
            assertEquals(42, (int) j2.queryForObject("SELECT x FROM t", Integer.class),
                    "data must survive detach + re-provision");
        }
    }

    @Test
    void destroy_drops_the_schema_and_role_irreversibly() {
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip Postgres destroy test");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            String adminUrl = pg.getJdbcUrl();
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new PostgresDialect(), adminUrl, pg.getUsername(), pg.getPassword(), false);

            DbScope s = prov.provision("tenant-y");
            jdbc(s.url(), s.username(), s.password()).execute("CREATE TABLE t (x INT)");

            prov.destroy("tenant-y");

            JdbcTemplate admin = jdbc(adminUrl, pg.getUsername(), pg.getPassword());
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, s.username()), "destroy must drop the schema (CASCADE)");
            assertEquals(0, (int) admin.queryForObject(
                    "SELECT COUNT(*) FROM pg_roles WHERE rolname = ?", Integer.class, s.username()),
                    "destroy must drop the role");
        }
    }

    @Test
    void reprovision_resets_password_so_the_new_credentials_connect() {
        // Restart scenario: the in-memory scope cache is gone, so provision() runs again for an existing
        // role and generates a *fresh* password. The role must end up with that new password (otherwise the
        // worker cannot connect after a restart). Data in the schema must survive.
        assumeTrue(OsIsolationTest.dockerAvailable(), "no Docker — skip Postgres reprovision test");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")) {
            pg.start();
            String adminUrl = pg.getJdbcUrl();
            // deprovision-on-undeploy = false → the role/schema persist across "restarts"
            DbScopeProvisioner prov = new DbScopeProvisioner(
                    new PostgresDialect(), adminUrl, pg.getUsername(), pg.getPassword(), false);

            DbScope first = prov.provision("mod-a");
            JdbcTemplate j1 = jdbc(first.url(), first.username(), first.password());
            j1.execute("CREATE TABLE t (x INT)");
            j1.update("INSERT INTO t VALUES (1)");

            // "restart": provision again → new password for the same role
            DbScope second = prov.provision("mod-a");
            org.junit.jupiter.api.Assertions.assertNotEquals(first.password(), second.password(),
                    "a fresh password is generated on re-provision");

            // the new credentials must connect and see the persisted data
            JdbcTemplate j2 = jdbc(second.url(), second.username(), second.password());
            assertEquals(1, (int) j2.queryForObject("SELECT COUNT(*) FROM t", Integer.class));
        }
    }
}
