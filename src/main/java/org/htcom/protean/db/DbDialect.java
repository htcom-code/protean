/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-vendor scope provisioning strategy (MySQL/PostgreSQL). Separated because DDL and scoped URL formats differ by vendor.
 *
 * <p>Note: DDL identifiers (DB/schema/user names) cannot be passed as bind parameters and are inlined as strings →
 * callers must pass names sanitized by {@link Identifiers#safeName} before calling (injection prevention).
 */
public interface DbDialect {

    String id();

    /** Maximum identifier length (MySQL user 32, Postgres 63, etc.). */
    int maxNameLength();

    /** Creates an isolated scope: a dedicated DB/schema + a dedicated user/role + GRANTs limited to its own area. */
    void createScope(JdbcTemplate admin, String name, String password);

    /** Removes the scope (deprovisioning). Full drop — DB/schema and its login together (destroy semantics). */
    void dropScope(JdbcTemplate admin, String name);

    /**
     * Detaches a scope: removes only its <b>login</b> (the dedicated USER/ROLE can no longer connect) while leaving the
     * DATABASE/SCHEMA and all data intact. Reversible — a subsequent {@link #createScope} re-enables the login with a
     * fresh password. This is the default, data-safe deprovisioning path.
     *
     * <p>Default throws: a custom dialect that has not implemented detach cannot offer it (never falls through to a
     * data-destroying drop). The built-in MySQL/PostgreSQL dialects override it.
     */
    default void detachScope(JdbcTemplate admin, String name) {
        throw new UnsupportedOperationException("dialect '" + id() + "' does not implement detachScope — "
                + "detach (data-safe deprovision) is unavailable; implement it or use destroy explicitly");
    }

    /**
     * Destroys a scope: irreversibly drops its DATABASE/SCHEMA (CASCADE) and its login — <b>all data is lost</b>.
     * Default delegates to {@link #dropScope} (a legacy dialect's full drop is exactly destroy semantics).
     */
    default void destroyScope(JdbcTemplate admin, String name) {
        dropScope(admin, name);
    }

    /** Builds the JDBC URL for connecting to that scope from the admin URL. */
    String scopedUrl(String adminUrl, String name);
}
