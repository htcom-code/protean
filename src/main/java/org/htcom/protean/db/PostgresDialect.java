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
 * PostgreSQL dialect — within the same DB, a dedicated SCHEMA per module + a dedicated ROLE + GRANTs limited to
 * that schema. Since a role has USAGE only on its own schema, it cannot access other modules' schemas (no USAGE).
 */
public class PostgresDialect implements DbDialect {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public int maxNameLength() {
        return 63;  // Postgres identifier limit
    }

    @Override
    public void createScope(JdbcTemplate admin, String name, String password) {
        admin.execute("CREATE SCHEMA IF NOT EXISTS \"" + name + "\"");
        // Create the role only when absent (IF NOT EXISTS unsupported → DO block). name/password are sanitized/generated values.
        admin.execute("DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + name + "') THEN "
                + "CREATE ROLE \"" + name + "\" LOGIN PASSWORD '" + password + "'; END IF; END $$");
        // Reset the password so a re-provision (e.g. after a restart, when the in-memory scope cache is gone
        // and a fresh password is generated) actually takes effect — the DO block above leaves an existing
        // role's password unchanged, which would leave the worker unable to connect.
        admin.execute("ALTER ROLE \"" + name + "\" WITH PASSWORD '" + password + "'");
        admin.execute("GRANT ALL ON SCHEMA \"" + name + "\" TO \"" + name + "\"");
        admin.execute("ALTER ROLE \"" + name + "\" SET search_path = \"" + name + "\"");
    }

    @Override
    public void dropScope(JdbcTemplate admin, String name) {
        admin.execute("DROP SCHEMA IF EXISTS \"" + name + "\" CASCADE");
        admin.execute("DROP ROLE IF EXISTS \"" + name + "\"");
    }

    @Override
    public String scopedUrl(String adminUrl, String name) {
        // Same database, session search_path set to that schema
        int schemeEnd = adminUrl.indexOf("://") + 3;
        int pathStart = adminUrl.indexOf('/', schemeEnd);
        String authority = pathStart < 0 ? adminUrl.substring(schemeEnd) : adminUrl.substring(schemeEnd, pathStart);
        String db = "postgres";
        if (pathStart >= 0) {
            String rest = adminUrl.substring(pathStart + 1);
            int q = rest.indexOf('?');
            db = q < 0 ? rest : rest.substring(0, q);
        }
        return "jdbc:postgresql://" + authority + "/" + db + "?currentSchema=" + name;
    }
}
