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

    /** Removes the scope (deprovisioning). */
    void dropScope(JdbcTemplate admin, String name);

    /** Builds the JDBC URL for connecting to that scope from the admin URL. */
    String scopedUrl(String adminUrl, String name);
}
