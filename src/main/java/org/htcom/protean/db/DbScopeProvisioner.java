/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.SecureRandom;
import java.util.function.BooleanSupplier;

/**
 * Automatically provisions an isolated DB scope per module. Using an admin (privileged) connection, it creates a
 * dedicated DB/schema + user + GRANTs for each module and returns a {@link DbScope} (url/user/pw) for connecting to
 * that scope.
 *
 * <p>Vendor differences are absorbed by {@link DbDialect}. Can be constructed without a Spring context (easy to test).
 */
public class DbScopeProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PW_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final DbDialect dialect;
    private final String adminUrl;
    /** Read live (Tier 1) so a runtime protean.worker.db.deprovision-on-undeploy change applies to the next undeploy. */
    private final BooleanSupplier deprovisionOnUndeploy;
    private final JdbcTemplate admin;

    /** Fixed-flag constructor (tests / non-Spring use). */
    public DbScopeProvisioner(DbDialect dialect, String adminUrl, String adminUser, String adminPassword,
                              boolean deprovisionOnUndeploy) {
        this(dialect, adminUrl, adminUser, adminPassword, () -> deprovisionOnUndeploy);
    }

    /** Live-flag constructor: {@code deprovisionOnUndeploy} is evaluated per undeploy (Spring bean path). */
    public DbScopeProvisioner(DbDialect dialect, String adminUrl, String adminUser, String adminPassword,
                              BooleanSupplier deprovisionOnUndeploy) {
        this.dialect = dialect;
        this.adminUrl = adminUrl;
        this.deprovisionOnUndeploy = deprovisionOnUndeploy;
        DriverManagerDataSource ds = new DriverManagerDataSource(adminUrl, adminUser, adminPassword);
        this.admin = new JdbcTemplate(ds);
    }

    /** Creates an isolated scope for the moduleId and returns its connection info. */
    public DbScope provision(String moduleId) {
        String name = Identifiers.safeName(moduleId, dialect.maxNameLength());
        String password = randomPassword();
        dialect.createScope(admin, name, password);
        return new DbScope(dialect.scopedUrl(adminUrl, name), name, password);
    }

    /** Removes the scope only when deprovision-on-undeploy is enabled (retained by default). */
    public void deprovision(String moduleId) {
        if (!deprovisionOnUndeploy.getAsBoolean()) {
            return;
        }
        dialect.dropScope(admin, Identifiers.safeName(moduleId, dialect.maxNameLength()));
    }

    public boolean deprovisionOnUndeploy() {
        return deprovisionOnUndeploy.getAsBoolean();
    }

    /** The dialect this provisioner selected (for status responses/validation). */
    public DbDialect dialect() {
        return dialect;
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(PW_CHARS.charAt(RANDOM.nextInt(PW_CHARS.length())));
        }
        return sb.toString();
    }
}
