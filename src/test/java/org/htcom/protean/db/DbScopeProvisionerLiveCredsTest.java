/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POC: admin credentials are read live, so a runtime rotation rebuilds the admin connection on the next
 * provision/deprovision without a restart. A capturing dialect records which admin URL the (possibly rebuilt)
 * JdbcTemplate carries, so no real database is needed — the datasource is never asked for a connection.
 */
class DbScopeProvisionerLiveCredsTest {

    /** Records the admin JdbcTemplate's underlying URL each call; never opens a real connection. */
    private static final class CapturingDialect implements DbDialect {
        volatile String lastAdminUrl;

        @Override
        public String id() {
            return "capture";
        }

        @Override
        public int maxNameLength() {
            return 64;
        }

        @Override
        public void createScope(JdbcTemplate admin, String name, String password) {
            lastAdminUrl = ((DriverManagerDataSource) admin.getDataSource()).getUrl();
        }

        @Override
        public void dropScope(JdbcTemplate admin, String name) {
            lastAdminUrl = ((DriverManagerDataSource) admin.getDataSource()).getUrl();
        }

        @Override
        public String scopedUrl(String adminUrl, String name) {
            return adminUrl + "/" + name;
        }
    }

    @Test
    void adminConnectionRebuiltWhenCredsRotateLive() {
        AtomicReference<DbScopeProvisioner.AdminCreds> creds = new AtomicReference<>(
                new DbScopeProvisioner.AdminCreds("jdbc:h2:mem:admin1", "sa", "old-pw"));
        CapturingDialect dialect = new CapturingDialect();
        DbScopeProvisioner prov = new DbScopeProvisioner(dialect, creds::get);

        DbScope s1 = prov.provision("modA");
        assertEquals("jdbc:h2:mem:admin1", dialect.lastAdminUrl, "first provision uses the initial admin url");
        assertTrue(s1.url().startsWith("jdbc:h2:mem:admin1/"), "scoped url derives from the live admin url");

        // Rotate the admin creds at runtime — same provisioner instance, no restart.
        creds.set(new DbScopeProvisioner.AdminCreds("jdbc:h2:mem:admin2", "sa", "rotated-pw"));

        DbScope s2 = prov.provision("modB");
        assertEquals("jdbc:h2:mem:admin2", dialect.lastAdminUrl, "rotated creds rebuild the admin connection");
        assertTrue(s2.url().startsWith("jdbc:h2:mem:admin2/"));

        // Deprovision also uses the rebuilt (rotated) admin connection.
        prov.destroy("modB");
        assertEquals("jdbc:h2:mem:admin2", dialect.lastAdminUrl, "deprovision uses the current admin connection");
    }

    @Test
    void badRotationIsRejectedAndPreviousConnectionRetained() {
        AtomicReference<DbScopeProvisioner.AdminCreds> creds = new AtomicReference<>(
                new DbScopeProvisioner.AdminCreds("jdbc:h2:mem:good", "sa", "pw"));
        CapturingDialect dialect = new CapturingDialect();
        DbScopeProvisioner prov = new DbScopeProvisioner(dialect, creds::get);

        prov.provision("m1");
        assertEquals("jdbc:h2:mem:good", dialect.lastAdminUrl);

        // Rotate to creds that cannot connect (no driver for this url) → validation rejects it before adoption.
        creds.set(new DbScopeProvisioner.AdminCreds("jdbc:nosuchdb://nope", "x", "y"));
        assertThrows(IllegalStateException.class, () -> prov.provision("m2"));
        assertEquals("jdbc:h2:mem:good", dialect.lastAdminUrl, "failed rotation must not touch the live admin connection");

        // Revert to good creds → recovers on the retained working connection.
        creds.set(new DbScopeProvisioner.AdminCreds("jdbc:h2:mem:good", "sa", "pw"));
        prov.provision("m3");
        assertEquals("jdbc:h2:mem:good", dialect.lastAdminUrl);
    }

    @Test
    void adminConnectionReusedWhenCredsUnchanged() {
        DbScopeProvisioner.AdminCreds fixed = new DbScopeProvisioner.AdminCreds("jdbc:h2:mem:stable", "sa", "pw");
        CapturingDialect dialect = new CapturingDialect();
        // Supplier always returns an equal value → the admin connection is built once and reused (no rebuild churn).
        DbScopeProvisioner prov = new DbScopeProvisioner(dialect, () -> fixed);

        prov.provision("m1");
        String afterFirst = dialect.lastAdminUrl;
        prov.provision("m2");
        assertEquals(afterFirst, dialect.lastAdminUrl, "unchanged creds keep the same admin url");
        assertEquals("jdbc:h2:mem:stable", afterFirst);
    }
}
