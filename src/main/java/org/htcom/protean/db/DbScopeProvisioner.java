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
import java.util.function.Supplier;

/**
 * Automatically provisions an isolated DB scope per module. Using an admin (privileged) connection, it creates a
 * dedicated DB/schema + user + GRANTs for each module and returns a {@link DbScope} (url/user/pw) for connecting to
 * that scope.
 *
 * <p>Vendor differences are absorbed by {@link DbDialect}. Can be constructed without a Spring context (easy to test).
 *
 * <p><b>Live admin creds (POC)</b>: the admin coordinates are read through a {@link Supplier} at operation time, and
 * the admin {@link JdbcTemplate} is rebuilt only when they change, so an admin-credential rotation
 * ({@code protean.worker.db.admin-url/username/password}) applies to the next provision/deprovision without a restart.
 * This mirrors how {@code deprovisionOnUndeploy} is already re-read per undeploy. POC scope: no pre-swap validation
 * connection and no in-flight draining — bad new creds simply surface as the next provision's failure.
 */
public class DbScopeProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PW_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Admin (privileged) connection coordinates. A value change triggers an admin JdbcTemplate rebuild. */
    public record AdminCreds(String url, String username, String password) {
    }

    private final DbDialect dialect;
    /** Read live so a runtime protean.worker.db.admin-* change rebuilds the admin connection on the next op. */
    private final Supplier<AdminCreds> credsSupplier;
    /** Read live (Tier 1) so a runtime protean.worker.db.deprovision-on-undeploy change applies to the next undeploy. */
    private final BooleanSupplier deprovisionOnUndeploy;

    // Cached admin JdbcTemplate + the creds snapshot it was built from; rebuilt lazily when the live creds change.
    private AdminCreds currentCreds;
    private JdbcTemplate admin;

    /** Fixed-creds constructor (tests / non-Spring use). */
    public DbScopeProvisioner(DbDialect dialect, String adminUrl, String adminUser, String adminPassword,
                              boolean deprovisionOnUndeploy) {
        this(dialect, () -> new AdminCreds(adminUrl, adminUser, adminPassword), () -> deprovisionOnUndeploy);
    }

    /** Live constructor: admin creds and the deprovision flag are re-read at operation time (Spring bean path). */
    public DbScopeProvisioner(DbDialect dialect, Supplier<AdminCreds> credsSupplier,
                              BooleanSupplier deprovisionOnUndeploy) {
        this.dialect = dialect;
        this.credsSupplier = credsSupplier;
        this.deprovisionOnUndeploy = deprovisionOnUndeploy;
    }

    /**
     * Returns the admin JdbcTemplate for the given creds snapshot, rebuilding it iff those creds differ from the ones
     * the cached template was built from. Callers pass a single {@code credsSupplier.get()} snapshot so the template
     * and the scoped URL stay consistent under concurrent rotation.
     */
    private synchronized JdbcTemplate adminFor(AdminCreds creds) {
        if (admin == null || !creds.equals(currentCreds)) {
            this.admin = new JdbcTemplate(new DriverManagerDataSource(creds.url(), creds.username(), creds.password()));
            this.currentCreds = creds;
        }
        return admin;
    }

    /** Creates an isolated scope for the moduleId and returns its connection info. */
    public DbScope provision(String moduleId) {
        AdminCreds creds = credsSupplier.get();
        JdbcTemplate a = adminFor(creds);
        String name = Identifiers.safeName(moduleId, dialect.maxNameLength());
        String password = randomPassword();
        dialect.createScope(a, name, password);
        return new DbScope(dialect.scopedUrl(creds.url(), name), name, password);
    }

    /** Removes the scope only when deprovision-on-undeploy is enabled (retained by default). */
    public void deprovision(String moduleId) {
        if (!deprovisionOnUndeploy.getAsBoolean()) {
            return;
        }
        dialect.dropScope(adminFor(credsSupplier.get()), Identifiers.safeName(moduleId, dialect.maxNameLength()));
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
