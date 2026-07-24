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
 * MySQL dialect — in MySQL, schema = database. A dedicated DATABASE per module + a dedicated USER +
 * GRANTs limited to that DB give strong isolation (a user cannot SHOW/access other DBs).
 */
public class MySqlDialect implements DbDialect {

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public int maxNameLength() {
        return 32;  // MySQL username limit (DB is 64; use the smaller one)
    }

    @Override
    public void createScope(JdbcTemplate admin, String name, String password) {
        admin.execute("CREATE DATABASE IF NOT EXISTS `" + name + "`");
        admin.execute("CREATE USER IF NOT EXISTS '" + name + "'@'%' IDENTIFIED BY '" + password + "'");
        // Reset the password so a re-provision (e.g. after a restart, when the in-memory scope cache is gone
        // and a fresh password is generated) actually takes effect — CREATE USER IF NOT EXISTS alone leaves an
        // existing user's password unchanged, which would leave the worker unable to connect.
        admin.execute("ALTER USER '" + name + "'@'%' IDENTIFIED BY '" + password + "'");
        admin.execute("GRANT ALL PRIVILEGES ON `" + name + "`.* TO '" + name + "'@'%'");
    }

    @Override
    public void dropScope(JdbcTemplate admin, String name) {
        admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        admin.execute("DROP USER IF EXISTS '" + name + "'@'%'");
    }

    @Override
    public void detachScope(JdbcTemplate admin, String name) {
        // Drop only the login; the DATABASE (schema=database in MySQL) and its data are retained. A later
        // createScope re-creates the user (CREATE USER IF NOT EXISTS + ALTER … PASSWORD) → reversible.
        admin.execute("DROP USER IF EXISTS '" + name + "'@'%'");
    }

    @Override
    public void destroyScope(JdbcTemplate admin, String name) {
        dropScope(admin, name);   // DROP DATABASE + DROP USER — irreversible
    }

    @Override
    public String scopedUrl(String adminUrl, String name) {
        return "jdbc:mysql://" + authority(adminUrl) + "/" + name;
    }

    /** "jdbc:mysql://host:port/db?params" → "host:port" */
    private static String authority(String url) {
        int schemeEnd = url.indexOf("://") + 3;
        int pathStart = url.indexOf('/', schemeEnd);
        return pathStart < 0 ? url.substring(schemeEnd) : url.substring(schemeEnd, pathStart);
    }
}
